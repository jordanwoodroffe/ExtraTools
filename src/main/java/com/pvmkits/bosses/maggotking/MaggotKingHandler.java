package com.pvmkits.bosses.maggotking;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.core.BossHandler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Prayer;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.ProjectileMoved;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
public class MaggotKingHandler implements BossHandler {

    @Inject
    private Client client;

    @Inject
    private PvmKitsConfig config;

    private static final int ATTACK_COOLDOWN_TICKS = 4;
    private static final int ACID_POOL_LINGER_TICKS = 2;
    private static final int SPLIT_SPLASH_PERSIST_TICKS = 6;
    private static final int SCREECH_WARNING_TICKS = 4;
    private static final int LOCAL_HAZARD_MAX_DISTANCE = 14;

    // Client game cycles per game tick (600ms / 20ms), used to convert a
    // projectile's remaining cycles into the game tick it will land on.
    private static final int CYCLES_PER_GAME_TICK = 30;

    // Melee slam telegraph graphics painted on every struck tile: 3998 = charge-up
    // (early warning), 2953 = explosion (the damage frame). Each tile showing one of
    // these is part of the slam square and must be excluded from the safe tiles. The
    // charge-up leads the explosion by two ticks (confirmed from live logs:
    // startCycle 6497 -> 6557 = 60 cycles) and the explosion deals damage on the tick
    // it appears, so a struck tile is safe the tick after the explosion.
    private static final int MELEE_SLAM_CHARGEUP_GRAPHIC_ID = 3998;
    private static final int MELEE_SLAM_EXPLOSION_GRAPHIC_ID = 2953;
    private static final Set<Integer> MELEE_SLAM_GRAPHIC_IDS =
            Set.of(MELEE_SLAM_CHARGEUP_GRAPHIC_ID, MELEE_SLAM_EXPLOSION_GRAPHIC_ID);

    // How long a slam tile stays dangerous, anchored to each graphic's own start
    // cycle rather than to how long the graphic lingers on the ground. The charge-up
    // must stay dangerous through the explosion two ticks later plus its damage tick
    // (3 ticks); the explosion deals damage on its start tick and is safe the
    // following tick (1 tick). Both therefore free the tile the tick after the damage
    // lands, instead of the 2-3 ticks later the lingering explosion graphic used to
    // keep it flagged.
    private static final int SLAM_CHARGEUP_DANGER_TICKS = 3;
    private static final int SLAM_EXPLOSION_DANGER_TICKS = 1;

    // Confirmed boss NPC id (Maggot King). Matching by id keeps larvae (which share
    // the "maggot" naming) from being treated as the boss.
    private static final Set<Integer> KNOWN_BOSS_IDS = Set.of(15742);

    // Add NPCs that share the boss's "maggot" naming but must never be treated as
    // the boss (e.g. "Ur-maggot larvae", id 15743) or they'd be recoloured with the
    // boss attack-style tile.
    private static final Set<Integer> LARVA_NPC_IDS = Set.of(15743);

    private static final List<String> BOSS_NAME_KEYWORDS = Arrays.asList(
            "maggot king",
            "progenitor");

        // Player-confirmed: the boss uses one shared attack animation (see
        // ATTACK_ANIMATION_ID) for both mage and range, so the projectile is the
        // only reliable way to know which overhead prayer to use.
        //   1555 = ranged (green spit), 3445 = magic.
        private static final Map<Integer, AttackStyle> KNOWN_PROJECTILE_STYLES = Map.of(
            1555, AttackStyle.RANGE,
            3445, AttackStyle.MAGE);

        // Carrion special projectiles: 3796 = the main pile that comes down first,
        // 3797 = the acid blobs it splits into. Neither leaves a lingering damaging
        // pool — the main pile's landing tile stays bare and the blob tiles dry into
        // safe carrion — so these tiles are dangerous only until they land and are
        // safe the tick after (see onProjectileMoved).
        private static final Set<Integer> CARRION_PROJECTILE_IDS = Set.of(3796, 3797);

        // Player-confirmed: generic attack animation shared by mage and range. It
        // does NOT differentiate the style (the projectile does), so it never sets
        // a prayer colour on its own.
        private static final int ATTACK_ANIMATION_ID = 13933;

        // Player-confirmed: screech (prayer-off) animation. The boss is recoloured
        // while this animation is playing.
        //
        // This animation is the ONLY screech cue. Sound effects must never trigger
        // one: the boss plays ambient/attack sounds (11027, 11821, 11822, 11835-11837,
        // ...) every 2-3 ticks for the whole fight, so a "any sound near the boss"
        // cue re-armed the warning window faster than it could expire and pinned the
        // overlay to the screech colour, hiding the range/mage prayer colour entirely
        // (live logs: 70 of 72 screech cues were ambient sounds, 2 were real). The
        // sounds also lag the animation by a tick, so they added no early warning.
        private static final int SCREECH_ANIMATION_ID = 13922;

        // Fresh acid pools that deal damage. 33423 = range acid (user-confirmed);
        // 33424 = mage acid (inferred — it dries into the safe 33425 pool in logs).
        private static final Set<Integer> DANGEROUS_ACID_OBJECT_IDS = Set.of(33423, 33424);

        // Dried acid: user-confirmed SAFE to stand on (do not treat as hazard).
        private static final Set<Integer> DRIED_ACID_OBJECT_IDS = Set.of(33425);

    private final Set<Integer> discoveredBossIds = new HashSet<>();
    private final Set<Integer> trackedBossIndices = new HashSet<>();

    private final Map<Integer, AttackStyle> bossStyles = new HashMap<>();

    // Display style for the overlay — toggled on each screech animation based on the
    // fixed range→mage→range→... cycle. Never influenced by projectiles.
    private final Map<Integer, AttackStyle> displayAttackStyles = new HashMap<>();

    // Pre-screech display style saved on each screech so it can be restored when
    // slams follow the screech (melee-phase fake screech).
    private final Map<Integer, AttackStyle> preScreechStyles = new HashMap<>();

    private final Map<Integer, Integer> attackCooldowns = new HashMap<>();
    private final Map<Integer, Integer> lastLoggedAnimations = new HashMap<>();

    // Active acid pool objects keyed by object hash -> tile, so danger tracks the
    // object's true on-ground lifetime rather than a fixed guess.
    private final Map<Long, WorldPoint> activeAcidPools = new HashMap<>();

    // Active dried acid pools (id 33425) keyed by object hash -> tile. Dried acid is
    // SAFE to stand on, so these tiles are force-cleared of danger every tick (a
    // split splash flags its whole landing grid as danger a tick before the piles
    // form) and become eligible safe tiles the instant the pile dries.
    private final Map<Long, WorldPoint> activeDriedAcid = new HashMap<>();

    // Tiles an acid orb is currently inbound to, keyed by tile -> the game tick the
    // orb lands on. An orb deals damage the tick it lands, so an inbound tile stays
    // dangerous while the orb is airborne AND on its landing tick even when a dried
    // (safe) pile currently sits under it; the tick after landing the pile dries and
    // the tile is free to become safe again.
    private final Map<WorldPoint, Integer> incomingImpactTiles = new HashMap<>();

    // Hazard tile store with expiry and priority. Higher priority means more dangerous.
    private final Map<WorldPoint, HazardTileState> dangerTiles = new HashMap<>();

    private int screechWarningUntilTick = -1;
    private int screechStartTick = -1;

    @Override
    public String getBossName() {
        return "Maggot King";
    }

    @Override
    public boolean isInBossArea(Client client) {
        boolean foundBoss = false;

        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null) {
                continue;
            }

            if (isBossNpcCandidate(npc)) {
                foundBoss = true;
                rememberDiscoveredBoss(npc);
            }
        }

        return foundBoss;
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        // Animation handling is centralized in onGameTick for consistency.
    }

    @Override
    public void onGraphicChanged(GraphicChanged event) {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        Actor actor = event.getActor();
        if (!(actor instanceof NPC)) {
            return;
        }

        NPC npc = (NPC) actor;
        if (!isBossNpcCandidate(npc)) {
            return;
        }

        // Graphics only feed boss discovery. Attack style is driven by confirmed
        // animation/projectile ids so the overlay never flickers a wrong colour
        // between attacks.
        rememberDiscoveredBoss(npc);
    }

    @Override
    public void onProjectileMoved(ProjectileMoved event) {
        if (client.getGameState() != GameState.LOGGED_IN || !isCombatCaptureActive()) {
            return;
        }

        Projectile projectile = event.getProjectile();
        if (projectile == null) {
            return;
        }

        // The acid splat lands on the projectile's target tile, so mark that
        // (not the in-flight position) as danger. This gives the few ticks of
        // warning while the splat is airborne, before the pool object forms.
        WorldPoint landingTile = projectile.getTargetPoint();
        if (landingTile == null) {
            LocalPoint projectilePoint = event.getPosition();
            landingTile = projectilePoint != null ? WorldPoint.fromLocal(client, projectilePoint) : null;
        }

        if (!isTileRelevantToFight(landingTile)) {
            return;
        }

        if (landingTile != null) {
            // The tick the orb will land on, recomputed each move from its remaining
            // cycles (accurate: 120 cycles = 4 ticks, 60 = 2). An orb deals damage on
            // the tick it lands and the tile is safe the tick after.
            int ticksToLand = Math.max(0,
                    (int) Math.ceil(projectile.getRemainingCycles() / (double) CYCLES_PER_GAME_TICK));
            int landingTick = client.getTickCount() + ticksToLand;

            if (CARRION_PROJECTILE_IDS.contains(projectile.getId())) {
                // Carrion special (main pile + split blobs): the landing tile leaves
                // no lingering damaging pool, so it must be dangerous only up to and
                // including the landing tick, then safe immediately after. Marking it
                // through the landing tick (expiry = landingTick + 1) instead of for a
                // fixed window stops it lingering 2-3 ticks after the acid has landed —
                // this covers both the bare main-pile landing tile and the blob tiles
                // that dry into safe carrion. The projectile re-marks this each move,
                // so even its final landing-tick event only holds the tile through the
                // damage tick.
                markDangerTile(landingTile, ticksToLand + 1, HazardPriority.HIGH);
            } else {
                // Standard attack: the splat forms a fresh acid pool on landing that
                // is tracked separately (activeAcidPools) and keeps the tile dangerous
                // for its true lifetime, so a short pre-landing warning suffices here.
                markDangerTile(landingTile, SPLIT_SPLASH_PERSIST_TICKS, HazardPriority.HIGH);
            }

            // Record the landing tick so a tile stays dangerous while an orb is
            // airborne and on its landing (damage) tick, even if a dried, otherwise-
            // safe pile currently sits under it; it clears to safe the tick after the
            // orb lands.
            incomingImpactTiles.merge(landingTile, landingTick, Math::max);
        }

        NPC primaryBoss = getPrimaryBossNpc();
        if (primaryBoss != null) {
            int index = primaryBoss.getIndex();
            int projectileId = projectile.getId();
            AttackStyle style = KNOWN_PROJECTILE_STYLES.get(projectileId);
            if (style != null) {
                bossStyles.put(index, style);
            }
            registerBossAttack(index, "projectile", projectileId,
                    style != null ? style : AttackStyle.UNKNOWN, landingTile);
        }
    }

    @Override
    public void onGameTick(GameTick event) {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        trackedBossIndices.clear();

        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (isBossNpcCandidate(npc)) {
                processBossNpc(npc);
            }
        }

        refreshAcidPools();
        refreshSlamDangerTiles();
        refreshDriedAcidTiles();

        cleanupExpiredState();
    }

    @Override
    public Actor getBossActor(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && isBossNpcCandidate(npc)) {
                return npc;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        discoveredBossIds.clear();
        trackedBossIndices.clear();
        bossStyles.clear();
        displayAttackStyles.clear();
        attackCooldowns.clear();
        lastLoggedAnimations.clear();
        activeAcidPools.clear();
        activeDriedAcid.clear();
        incomingImpactTiles.clear();
        dangerTiles.clear();
        screechWarningUntilTick = -1;
        preScreechStyles.clear();
        screechStartTick = -1;
    }

    public void onGameObjectSpawned(GameObjectSpawned event) {
        GameObject object = event.getGameObject();
        if (object == null || !isCombatCaptureActive()) {
            return;
        }

        WorldPoint location = object.getWorldLocation();
        if (location != null && isTileRelevantToFight(location)) {
            int objectId = object.getId();
            if (DANGEROUS_ACID_OBJECT_IDS.contains(objectId)) {
                // A new attack can land on a previously dried tile, re-wetting it.
                // The orb has now landed, so drop the in-flight prediction (the pool
                // itself keeps this tile dangerous).
                activeDriedAcid.values().removeIf(location::equals);
                incomingImpactTiles.remove(location);
                activeAcidPools.put(object.getHash(), location);
                markDangerTile(location, ACID_POOL_LINGER_TICKS, HazardPriority.HIGH);
            } else if (DRIED_ACID_OBJECT_IDS.contains(objectId)) {
                // Dried acid is safe to stand on. Track it and drop any lingering
                // danger here (e.g. a split splash pre-landing mark, or the fresh
                // pool that just dried). The dried pile appearing is ground truth
                // that the orb has landed AND dried, so clear the in-flight
                // prediction too — otherwise a slightly-overestimated landing tick
                // would keep this now-safe tile flagged for a tick or two. This
                // makes the freshly dried tile count as safe on the same tick it
                // appears and keeps counting while the pile persists.
                activeDriedAcid.put(object.getHash(), location);
                incomingImpactTiles.remove(location);
                dangerTiles.remove(location);

                if (config.hideDriedAcid()) {
                    client.getScene().removeGameObject(object);
                }
            }
        }
    }

    public void onGameObjectDespawned(GameObjectDespawned event) {
        GameObject object = event.getGameObject();
        if (object == null || !isCombatCaptureActive()) {
            return;
        }

        if (DANGEROUS_ACID_OBJECT_IDS.contains(object.getId())) {
            // The fresh acid pool is gone. If it dried, a dried (33425) object
            // spawns on this tile and the dried handling keeps it safe. If it split
            // (the main carrion pile's centre tile does NOT dry — only the spread
            // piles do), nothing replaces it, so its danger would otherwise linger
            // for ACID_POOL_LINGER_TICKS after despawn. Clear it now so the tile is
            // safe the tick the pile leaves, unless another hazard still covers it.
            WorldPoint vacatedTile = activeAcidPools.remove(object.getHash());
            clearVanishedAcidDanger(vacatedTile);
        } else if (DRIED_ACID_OBJECT_IDS.contains(object.getId())) {
            activeDriedAcid.remove(object.getHash());
        }
    }

    public AttackStyle getBossStyle(int npcIndex) {
        return bossStyles.getOrDefault(npcIndex, AttackStyle.UNKNOWN);
    }

    public AttackStyle getDisplayAttackStyle(int npcIndex) {
        return displayAttackStyles.getOrDefault(npcIndex, AttackStyle.UNKNOWN);
    }

    public boolean isScreechWarningActive() {
        // Show the screech overlay color while the boss is actually screeching.
        // This persists for the full animation duration, not just a fixed tick window.
        if (isAnyBossScreeching()) {
            return true;
        }
        // Brief extra window after the animation ends so the transition is smooth
        return client.getTickCount() <= screechWarningUntilTick;
    }

    /**
     * True while the boss is actively playing the screech (prayer-off) animation.
     * Clears the instant the animation ends.
     */
    public boolean isScreeching(NPC npc) {
        return npc != null && npc.getAnimation() == SCREECH_ANIMATION_ID;
    }

    /**
     * True if any tracked boss is currently screeching. Used to know when the
     * player must have all overhead prayers switched off.
     */
    public boolean isAnyBossScreeching() {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (isBossNpcCandidate(npc) && isScreeching(npc)) {
                return true;
            }
        }
        return false;
    }

    public boolean isOverheadPrayerEnabled() {
        return client.isPrayerActive(Prayer.PROTECT_FROM_MELEE)
                || client.isPrayerActive(Prayer.PROTECT_FROM_MISSILES)
                || client.isPrayerActive(Prayer.PROTECT_FROM_MAGIC);
    }

    public boolean isKnownBossNpc(NPC npc) {
        return npc != null && isBossNpcCandidate(npc);
    }

    private void processBossNpc(NPC npc) {
        rememberDiscoveredBoss(npc);

        int index = npc.getIndex();
        trackedBossIndices.add(index);

        // The fight always opens with a ranged attack, so default a freshly seen
        // boss to the ranged style. This surfaces the configured range colour
        // immediately (before any projectile lands) so the player can pray against
        // the first attack. Real projectiles and screech predictions still update it.
        bossStyles.putIfAbsent(index, AttackStyle.RANGE);
        displayAttackStyles.putIfAbsent(index, AttackStyle.RANGE);

        int animationId = npc.getAnimation();
        if (animationId != -1) {
            Integer previousAnimation = lastLoggedAnimations.get(index);
            boolean changed = previousAnimation == null || previousAnimation != animationId;
            if (changed) {
                lastLoggedAnimations.put(index, animationId);

                if (animationId == SCREECH_ANIMATION_ID) {
                    triggerScreechWarning("screech animation", animationId);
                    screechStartTick = client.getTickCount();
                    predictPostScreechStyle(index);
                    log.info("Maggot King (index {}) animation {} -> SCREECH (turn overhead prayers off)",
                            index, animationId);
                } else {
                    AttackStyle style = resolveAnimationStyle(animationId);
                    if (style != AttackStyle.UNKNOWN) {
                        bossStyles.put(index, style);
                        registerBossAttack(index, "animation", animationId, style, npc.getWorldLocation());
                    }

                    log.info("Maggot King (index {}) animation {} -> style={} ({})",
                            index,
                            animationId,
                            style,
                            style == AttackStyle.UNKNOWN ? "unmapped, style unchanged" : "confirmed");
                }
            }
        }
    }

    private void registerBossAttack(int npcIndex, String source, int signalId, AttackStyle style, WorldPoint signalTile) {
        int currentTick = client.getTickCount();
        Integer cooldownUntil = attackCooldowns.get(npcIndex);

        if (cooldownUntil != null && currentTick < cooldownUntil) {
            return;
        }

        attackCooldowns.put(npcIndex, currentTick + ATTACK_COOLDOWN_TICKS);

        log.info("Maggot King (index {}) {} signal id={} style={} tile={}",
                npcIndex,
                source,
                signalId,
                style,
                signalTile);

        if (style == AttackStyle.SPECIAL) {
            triggerScreechWarning("special attack signal", signalId);
        }
    }

    private void cleanupExpiredState() {
        int currentTick = client.getTickCount();

        Set<Integer> knownIndices = new HashSet<>();
        knownIndices.addAll(bossStyles.keySet());
        knownIndices.addAll(attackCooldowns.keySet());
        knownIndices.addAll(lastLoggedAnimations.keySet());

        for (int index : knownIndices) {
            if (!trackedBossIndices.contains(index)) {
                bossStyles.remove(index);
                attackCooldowns.remove(index);
                lastLoggedAnimations.remove(index);
            }
        }

        dangerTiles.entrySet().removeIf(e -> currentTick >= e.getValue().expiryTick);
        incomingImpactTiles.entrySet().removeIf(e -> currentTick > e.getValue());

        if (trackedBossIndices.isEmpty()) {
            dangerTiles.clear();
            incomingImpactTiles.clear();
        }
    }

    private void rememberDiscoveredBoss(NPC npc) {
        if (npc == null) {
            return;
        }

        int id = npc.getId();
        if (discoveredBossIds.add(id)) {
            String name = npc.getName() == null ? "unknown" : npc.getName();
            log.info("Maggot King discovery: boss candidate npc id={} name={} index={} location={}",
                    id,
                    name,
                    npc.getIndex(),
                    npc.getWorldLocation());
        }
    }

    private AttackStyle resolveAnimationStyle(int animationId) {
        // No animation identifies a prayer for this boss: the generic attack
        // animation (ATTACK_ANIMATION_ID) is shared by mage and range. The style is
        // set only by projectiles, the screech flip, and the melee slam telegraph
        // (see refreshSlamDangerTiles) — never from the attack animation itself.
        if (animationId == ATTACK_ANIMATION_ID) {
            return AttackStyle.UNKNOWN;
        }

        return AttackStyle.UNKNOWN;
    }

    private void markDangerTile(WorldPoint tile, int persistTicks, HazardPriority priority) {
        if (tile == null) {
            return;
        }

        int expiry = client.getTickCount() + Math.max(1, persistTicks);
        HazardTileState state = dangerTiles.get(tile);
        if (state == null) {
            dangerTiles.put(tile, new HazardTileState(expiry, priority));
            return;
        }

        state.expiryTick = Math.max(state.expiryTick, expiry);
        if (priority.weight > state.priority.weight) {
            state.priority = priority;
        }
    }

    private void refreshAcidPools() {
        if (activeAcidPools.isEmpty()) {
            return;
        }

        // Re-mark each active acid pool every tick so the hazard tracks the
        // object's true lifetime on the ground, clearing shortly after despawn.
        for (WorldPoint tile : activeAcidPools.values()) {
            markDangerTile(tile, ACID_POOL_LINGER_TICKS, HazardPriority.HIGH);
        }
    }

    /**
     * Clears the danger on a tile whose fresh acid pool has just despawned so it
     * becomes safe the same tick the pool leaves, instead of lingering for
     * ACID_POOL_LINGER_TICKS. This matters for the main carrion pile's centre tile,
     * which splits away without drying (no 33425 spawns there to clear it), whereas
     * the spread piles dry in place and are cleared by their dried object.
     *
     * The decision is purely id/state driven, never positional: a neighbouring
     * split-off can drop a dried (33425) pile on this same centre tile, and a fresh
     * attack can re-wet it. So the tile is kept dangerous only when another active
     * fresh acid pool (33423/33424) still covers it, an acid orb is inbound to it
     * (it deals damage on landing), or a melee slam is striking it (CRITICAL). A
     * dried pile landing here is safe and is tracked separately, so it must not keep
     * the tile flagged.
     */
    private void clearVanishedAcidDanger(WorldPoint tile) {
        if (tile == null
                || activeAcidPools.containsValue(tile)
                || incomingImpactTiles.containsKey(tile)) {
            return;
        }

        HazardTileState state = dangerTiles.get(tile);
        if (state != null && state.priority.weight <= HazardPriority.HIGH.weight) {
            dangerTiles.remove(tile);
        }
    }

    /**
     * Dried acid (id 33425) is safe to stand on. A split splash marks its whole
     * landing grid as danger a tick before the piles form, and most of that grid
     * immediately dries into safe carrion, so clearing danger only once when the
     * dried object spawns can leave a genuinely safe tile flagged (the split
     * projectile re-marks it, or the fresh pool's linger outlasts the drying).
     * Re-clearing every tick keeps dried tiles safe the instant they land and for
     * as long as the pile persists. A tile is left dangerous when: an acid orb is
     * still inbound to it (it deals damage on landing), a new attack has re-wet it
     * (an active fresh acid pool), or a melee slam is striking it (CRITICAL
     * priority).
     */
    private void refreshDriedAcidTiles() {
        if (activeDriedAcid.isEmpty()) {
            return;
        }

        int currentTick = client.getTickCount();
        Set<WorldPoint> freshTiles = new HashSet<>(activeAcidPools.values());
        for (WorldPoint tile : activeDriedAcid.values()) {
            if (freshTiles.contains(tile)) {
                continue;
            }

            // An orb is airborne toward this tile or lands on it this tick: keep it
            // dangerous even though a dried pile is currently under it.
            Integer landingTick = incomingImpactTiles.get(tile);
            if (landingTick != null && currentTick <= landingTick) {
                continue;
            }

            HazardTileState state = dangerTiles.get(tile);
            if (state != null && state.priority.weight <= HazardPriority.HIGH.weight) {
                dangerTiles.remove(tile);
            }
        }
    }

    /**
     * The melee-phase slam paints each struck tile with a charge-up graphic (3998)
     * and then, two ticks later, an explosion graphic (2953) that deals the damage.
    * Marking those tiles as danger routes the safe-tile search out of the slam
    * square.
     *
     * The danger for each tile is anchored to its graphic's start cycle rather than
     * refreshed every tick the graphic lingers on the ground. The explosion graphic
     * stays visible for a few ticks after the damage frame, so the old "present +
     * linger" marking kept a tile flagged 2-3 ticks after it was already safe;
     * anchoring frees the tile the tick after the damage lands (mirrors the
     * shadow-hand fix).
     *
     * A slam explosion also confirms a preceding screech was a melee-phase fake, so
     * the display style is reverted to the range/mage colour shown before it.
     */
    private void refreshSlamDangerTiles() {
        int currentTick = client.getTickCount();
        int gameCycle = client.getGameCycle();
        boolean slamExplosionActive = false;

        for (GraphicsObject graphicsObject : client.getGraphicsObjects()) {
            if (graphicsObject == null || !MELEE_SLAM_GRAPHIC_IDS.contains(graphicsObject.getId())) {
                continue;
            }

            LocalPoint local = graphicsObject.getLocation();
            WorldPoint tile = local != null ? WorldPoint.fromLocal(client, local) : null;
            if (!isTileRelevantToFight(tile)) {
                continue;
            }

            // Recover the tick the graphic actually started on from its start cycle,
            // so re-seeing the same lingering explosion each tick keeps the same
            // expiry instead of pushing it back a tick at a time.
            int ticksSinceStart = Math.max(0,
                    (gameCycle - graphicsObject.getStartCycle()) / CYCLES_PER_GAME_TICK);
            int startTick = currentTick - ticksSinceStart;
            int dangerTicks = graphicsObject.getId() == MELEE_SLAM_EXPLOSION_GRAPHIC_ID
                    ? SLAM_EXPLOSION_DANGER_TICKS
                    : SLAM_CHARGEUP_DANGER_TICKS;
            int expiryTick = startTick + dangerTicks;

            if (expiryTick > currentTick) {
                if (graphicsObject.getId() == MELEE_SLAM_EXPLOSION_GRAPHIC_ID) {
                    slamExplosionActive = true;
                }
                markDangerTile(tile, expiryTick - currentTick, HazardPriority.CRITICAL);
            }
        }

        // If we see a slam EXPLOSION (2953) after a screech, it's definitely a fake screech.
        // We only check for the explosion because the chargeup (3998) is shared with carrison piles.
        if (screechStartTick != -1 && slamExplosionActive) {
            for (Map.Entry<Integer, AttackStyle> entry : preScreechStyles.entrySet()) {
                int idx = entry.getKey();
                displayAttackStyles.put(idx, entry.getValue());
                log.info("Maggot King (index {}) slamming (explosion {}) after screech -> reverted display style to {}",
                        idx, MELEE_SLAM_EXPLOSION_GRAPHIC_ID, entry.getValue());
            }
            preScreechStyles.clear();
            screechStartTick = -1; // Reset it so we don't keep reverting
        }
    }

    /**
     * The boss alternates its ranged and magic cycles with a screech between each
     * one, so the instant the screech animation starts we flip the display style
     * to the opposite of the style currently shown. This makes the attack-style
     * overlay correctly predict the next cycle's overhead prayer without needing
     * projectiles or animation ids.
     */
    private void predictPostScreechStyle(int npcIndex) {
        AttackStyle currentDisplay = displayAttackStyles.getOrDefault(npcIndex, AttackStyle.RANGE);
        // Save the pre-screech style so we can revert if slams follow this screech
        preScreechStyles.put(npcIndex, currentDisplay);
        AttackStyle next = flipRangeMage(currentDisplay);

        displayAttackStyles.put(npcIndex, next);
        log.info("Maggot King (index {}) screech -> switched display style to {} for next cycle (was {})",
                npcIndex, next, currentDisplay);
    }

    private AttackStyle flipRangeMage(AttackStyle style) {
        switch (style) {
            case RANGE:
                return AttackStyle.MAGE;
            case MAGE:
                return AttackStyle.RANGE;
            default:
                return AttackStyle.UNKNOWN;
        }
    }

    private void triggerScreechWarning(String source, int signalId) {
        int untilTick = client.getTickCount() + SCREECH_WARNING_TICKS;
        if (untilTick > screechWarningUntilTick) {
            screechWarningUntilTick = untilTick;
        }

        log.info("Maggot King screech cue: source={} id={} warningUntilTick={} (turn overhead prayers off)",
                source,
                signalId,
                screechWarningUntilTick);
    }

    private NPC getPrimaryBossNpc() {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && isBossNpcCandidate(npc)) {
                return npc;
            }
        }
        return null;
    }

    private boolean isTileRelevantToFight(WorldPoint tile) {
        if (tile == null) {
            return false;
        }

        NPC boss = getPrimaryBossNpc();
        if (boss != null && boss.getWorldLocation() != null) {
            WorldPoint bossTile = boss.getWorldLocation();
            if (tile.getPlane() == bossTile.getPlane()
                    && chebyshevDistance(tile, bossTile) <= LOCAL_HAZARD_MAX_DISTANCE) {
                return true;
            }
        }

        if (client.getLocalPlayer() != null && client.getLocalPlayer().getWorldLocation() != null) {
            WorldPoint playerTile = client.getLocalPlayer().getWorldLocation();
            return tile.getPlane() == playerTile.getPlane()
                    && chebyshevDistance(tile, playerTile) <= LOCAL_HAZARD_MAX_DISTANCE;
        }

        return false;
    }

    private int chebyshevDistance(WorldPoint a, WorldPoint b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private boolean hasTrackedBoss() {
        return !trackedBossIndices.isEmpty();
    }

    private boolean isCombatCaptureActive() {
        return hasTrackedBoss() || !discoveredBossIds.isEmpty();
    }

    private boolean isBossNpcCandidate(NPC npc) {
        if (npc == null) {
            return false;
        }

        int id = npc.getId();
        // Larvae ("Ur-maggot larvae", id 15743) share the boss's naming but are
        // adds — never treat them as the boss.
        if (LARVA_NPC_IDS.contains(id)) {
            return false;
        }

        if (KNOWN_BOSS_IDS.contains(id) || discoveredBossIds.contains(id)) {
            return true;
        }

        String loweredName = normalizedNpcName(npc);
        if (loweredName.isEmpty()) {
            return false;
        }

        for (String keyword : BOSS_NAME_KEYWORDS) {
            if (loweredName.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    private String normalizedNpcName(NPC npc) {
        String name = npc.getName();
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ENGLISH);
    }

    private static class HazardTileState {
        private int expiryTick;
        private HazardPriority priority;

        private HazardTileState(int expiryTick, HazardPriority priority) {
            this.expiryTick = expiryTick;
            this.priority = priority;
        }
    }

    private enum HazardPriority {
        LOW(1),
        MEDIUM(2),
        HIGH(3),
        CRITICAL(4);

        private final int weight;

        HazardPriority(int weight) {
            this.weight = weight;
        }
    }

    public enum AttackStyle {
        RANGE,
        MAGE,
        MELEE,
        SPECIAL,
        UNKNOWN
    }
}
