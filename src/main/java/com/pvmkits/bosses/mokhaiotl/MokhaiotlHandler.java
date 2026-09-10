package com.pvmkits.bosses.mokhaiotl;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.core.BossHandler;
import com.pvmkits.core.SoundPlayer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Projectile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ProjectileMoved;

import javax.inject.Inject;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handler for the Doom of Mokhaiotl (delve boss). This is a discovery-first
 * release: NPC, spotanim and graphic IDs that could be confirmed from the pinned
 * RuneLite gameval jar are hardcoded, while the standard-attack / rock-throw /
 * shard projectile IDs (which are not named in the jar and vary in the wild) are
 * left as provisional sets that are filled from the verbose Phase 0 logging.
 *
 * The handler tracks a small state machine (delve/depth level plus the current
 * fight phase) and feeds three overlays:
 * <ul>
 * <li>a dedicated prayer-book outline system driven by a queue of scheduled
 * prayer events (one per incoming projectile);</li>
 * <li>a scene overlay for larvae true tiles, boulder landing tiles, the shield /
 * melee-punish 5x5 boss tile and the car-phase dash path / safe tiles;</li>
 * <li>a menu-entry priority for attacking larvae stacked under the boss.</li>
 * </ul>
 */
@Slf4j
public class MokhaiotlHandler implements BossHandler {

    @Inject
    private Client client;

    @Inject
    private PvmKitsConfig config;

    // ---- Confirmed NPC IDs (gameval prefix DOM_, from runelite-api 1.12.35) ----
    static final int BOSS_NORMAL = 14707; // DOM_BOSS
    static final int BOSS_SHIELDED = 14708; // DOM_BOSS_SHIELDED (shield phase)
    static final int BOSS_BURROWED = 14709; // DOM_BOSS_BURROWED (car / dash phase)
    static final Set<Integer> BOSS_IDS = Set.of(BOSS_NORMAL, BOSS_SHIELDED, BOSS_BURROWED);

    // Larvae. The gameval name encodes a fixed weakness style for most ids (RANGE id
    // -> attack with ranged, etc); the neutral id instead varies its overhead
    // protection prayers, so its weakness is read from those (see larvaStyle).
    static final int LARVA_NEUTRAL = 14710; // DOM_DEMONIC_ENERGY (delve 1-3, no fixed style)
    static final int LARVA_RANGE = 14711; // DOM_DEMONIC_ENERGY_RANGE
    static final int LARVA_MAGE = 14712; // DOM_DEMONIC_ENERGY_MAGE
    static final int LARVA_MELEE = 14713; // DOM_DEMONIC_ENERGY_MELEE (shield phase only)
    static final int LARVA_GIANT_RANGE = 14788; // DOM_DEMONIC_ENERGY_GIANT_RANGE (delve 8+)
    static final int LARVA_GIANT_MAGE = 14789; // DOM_DEMONIC_ENERGY_GIANT_MAGE (delve 8+)
    static final Set<Integer> LARVA_IDS = Set.of(LARVA_NEUTRAL, LARVA_RANGE, LARVA_MAGE, LARVA_MELEE,
            LARVA_GIANT_RANGE, LARVA_GIANT_MAGE);

    // Shockwave ("statue & orb") phase. Volatile earth = the attackable "statues";
    // the earthen shield = the 3x3 "orb" that travels between the two you destroy.
    static final int STATUE_ID = 14714; // DOM_SHOCKWAVE_PATH_NODE (volatile earth)
    static final int ORB_ID = 14715; // DOM_SHOCKWAVE_SHIELD (earthen shield / orb)

    // ---- Confirmed spotanim / graphic IDs (VFX_DOOM_*) ----
    // Car phase "eye" telegraph: the yellow eye is spawned a few tiles past the
    // player, and the boss then dashes to it. These graphics mark that eye tile.
    static final int GFX_BURROW_MOVE_TELEGRAPH = 3415; // VFX_DOOM_BOSS_BURROWED_MOVEMENT_TELEGRAPH
    static final int GFX_BURROW_TELEGRAPH_SPAWN = 3416; // VFX_DOOM_BOSS_BURROWED_TELEGRAPH_SPAWN
    static final Set<Integer> CAR_EYE_GRAPHIC_IDS = Set.of(GFX_BURROW_MOVE_TELEGRAPH, GFX_BURROW_TELEGRAPH_SPAWN);

    // Special Beam Cannon charge-up graphics. The melee-punish phase is the boss
    // standing still charging this beam, so the charge-up graphic on the boss is
    // the primary melee-punish signal (confirm/adjust from Phase 0 logs).
    static final int GFX_BEAM_CHARGE_UP = 3412; // VFX_BEAM_CHARGE_UP_01
    static final int GFX_BEAM_CHARGE_UP_BURROW = 3414; // VFX_BEAM_CHARGE_UP_BURROW_01
    static final Set<Integer> BEAM_CHARGE_GRAPHIC_IDS = Set.of(GFX_BEAM_CHARGE_UP, GFX_BEAM_CHARGE_UP_BURROW);

    // Bundled alert played when the melee-punish charge starts (src/main/resources).
    private static final String MELEE_PUNISH_SOUND = "/com/pvmkits/sounds/melee_punish.wav";

    // ---- Attack projectile IDs (confirmed from client.log + gameval names) ----
    // VFX_STANDARD_PROJECTILE_* are the boss's standard orbs aimed at the player;
    // VFX_ROCK_PROJECTILE_SPLIT_* are the shatter shards fired at the player after a
    // rock throw. Both feed the prayer outline via the style they encode.
    static final Set<Integer> MAGIC_PROJECTILE_IDS = Set.of(3379, 3387); // STANDARD_MAGIC, ROCK_SPLIT_MAGIC
    static final Set<Integer> RANGE_PROJECTILE_IDS = Set.of(3380, 3386); // STANDARD_RANGE, ROCK_SPLIT_RANGE
    static final Set<Integer> MELEE_PROJECTILE_IDS = Set.of(3378); // STANDARD_MELEE (delve 2+)

    // Rock-throw falling rocks (tile-targeted) that mark the shatter/landing tiles
    // to dodge: VFX_ROCK_PROJECTILE_PROJECTILE01-08 + ALT. The launched boulder
    // 3384/3385 is intentionally excluded so its single centre tile is not drawn.
    static final Set<Integer> ROCK_THROW_PROJECTILE_IDS = Set.of(
            3388, 3389, 3390, 3391, 3392, 3393, 3394, 3395,
            3396, 3397, 3398, 3399, 3400, 3401, 3402, 3403);

    // Landed-rock ground object (stays after a rock throw) used for car-phase
    // line-of-sight safe tiles. Confirmed 57286 from client.log; more may exist.
    static final Set<Integer> BOULDER_OBJECT_IDS = Set.of(57286);

    // Client game cycles per game tick (600ms / 20ms).
    static final int CYCLES_PER_GAME_TICK = 30;

    // How long a boulder landing tile stays highlighted after the projectile lands.
    private static final int BOULDER_TILE_HOLD_TICKS = 2;
    // How long the car eye / dash path is held after the last telegraph graphic.
    private static final int CAR_TELEGRAPH_HOLD_TICKS = 1;
    // Radius (in tiles) around the player searched for car-phase safe tiles.
    private static final int CAR_SAFE_SEARCH_RADIUS = 5;
    // Ticks the boss must be absent before a reappearance counts as a new delve.
    private static final int DELVE_GAP_TICKS = 3;

    // Ticks after the volatile earth ("statues") appear until the shockwave lands.
    private static final int SHOCKWAVE_TIMER_TICKS = 20;
    // The earthen shield ("orb") footprint size.
    static final int ORB_SIZE = 3;
    // Shortest orb path worth walking: the two statues of a pair must be at least
    // this many tiles apart (inclusive) for the pair to be highlighted.
    private static final int MIN_STATUE_PAIR_DISTANCE = 14;
    // How long the red marker of a destroyed statue stays up after it despawns.
    private static final int DESTROYED_STATUE_HOLD_TICKS = 10;

    // Combat style the player must protect against. The sprite id locates the
    // matching protection prayer icon in the prayer book for the outline overlay.
    public enum Style {
        MAGIC(127, Prayer.PROTECT_FROM_MAGIC),
        RANGE(128, Prayer.PROTECT_FROM_MISSILES),
        MELEE(129, Prayer.PROTECT_FROM_MELEE);

        private final int spriteId;
        private final Prayer prayer;

        Style(int spriteId, Prayer prayer) {
            this.spriteId = spriteId;
            this.prayer = prayer;
        }

        public int getSpriteId() {
            return spriteId;
        }

        public Prayer getPrayer() {
            return prayer;
        }
    }

    // Fight phases derived from the boss NPC id plus charge detection.
    public enum Phase {
        STANDARD, SHIELD, CAR, MELEE_PUNISH
    }

    // A single incoming attack the player must pray against, scheduled from a
    // projectile. impactTick is the game tick the projectile lands (damage tick).
    public static final class PrayerEvent {
        public final Style style;
        public final int impactTick;

        PrayerEvent(Style style, int impactTick) {
            this.style = style;
            this.impactTick = impactTick;
        }
    }

    // ---- State ----
    private Phase phase = Phase.STANDARD;
    private int depthLevel = 1;
    private boolean bossSeenEver = false;
    private int lastBossSeenTick = -1;

    private final List<PrayerEvent> prayerEvents = new ArrayList<>();
    // Dedup projectile-to-event scheduling by object identity so a projectile's
    // per-client-tick move events only schedule one prayer event. Identity-based (not
    // id+startCycle) so two simultaneous projectiles of the same type (e.g. two MAGIC
    // shards from a wave-2 boulder shatter) are each tracked as distinct events.
    private final Set<Projectile> scheduledProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());

    // Boulder shatter / rock-throw landing tiles -> expiry game tick.
    private final Map<WorldPoint, Integer> boulderTiles = new HashMap<>();

    // Car-phase dash: the eye tile the boss dashes to, the corridor it tramples,
    // and the line-of-sight safe tiles behind arena boulders.
    private WorldPoint carEyeTile;
    private int carTelegraphUntilTick = -1;
    private final Set<WorldPoint> dashPathTiles = new HashSet<>();
    private final Set<WorldPoint> carSafeTiles = new HashSet<>();

    // Tracked boulder ground objects (hash -> tile) for the LoS safe-tile check.
    private final Map<Long, WorldPoint> boulderObjects = new HashMap<>();

    // Melee-punish charge state. The "was active" flag makes the audio cue fire once
    // on the rising edge of the charge rather than every tick the graphic is up.
    private boolean meleePunishActive;
    private boolean meleePunishSoundPlayed;

    // Shockwave ("statue & orb") phase state: the two statues of the highlighted
    // pair, which of them the player has hit, and the shockwave impact tick.
    private final List<Integer> highlightedStatueIndices = new ArrayList<>();
    private final Set<Integer> attackedStatueIndices = new HashSet<>();
    // The pair split into the statue further from / nearer to the player, measured
    // once when the pair was picked. The far one is attacked first, so the near one
    // stays hidden until the far one has been hit.
    private int farStatueIndex = -1;
    private int nearStatueIndex = -1;
    // Tile of each statue the player has hit, kept while it is alive so its marker
    // can outlive it, and the tiles of destroyed ones -> the tick they stop showing.
    private final Map<Integer, WorldPoint> attackedStatueTiles = new HashMap<>();
    private final Map<WorldPoint, Integer> destroyedStatueTiles = new HashMap<>();
    private int shockwaveImpactTick = -1;
    private boolean statuesPresentLastTick = false;

    // One-shot logging guards.
    private final Set<Integer> loggedProjectileIds = new HashSet<>();
    private final Set<Integer> loggedNpcIds = new HashSet<>();
    private final Set<Integer> loggedObjectIds = new HashSet<>();
    private final Map<Integer, Integer> lastLoggedAnimation = new HashMap<>();
    // Larva index -> last logged overhead sprite ids (verbose logging only).
    private final Map<Integer, String> lastLoggedOverheads = new HashMap<>();

    @Override
    public String getBossName() {
        return "Doom of Mokhaiotl";
    }

    @Override
    public boolean isInBossArea(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null) {
                continue;
            }
            if (BOSS_IDS.contains(npc.getId())) {
                return true;
            }
            String name = npc.getName();
            if (name != null && name.toLowerCase().contains("mokhaiotl")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Actor getBossActor(Client client) {
        return findBoss();
    }

    // ------------------------------------------------------------------
    // Event handlers
    // ------------------------------------------------------------------

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        if (!config.mokhaiotlVerboseLogging()) {
            return;
        }
        Actor actor = event.getActor();
        if (!(actor instanceof NPC)) {
            return;
        }
        NPC npc = (NPC) actor;
        if (!BOSS_IDS.contains(npc.getId()) && !LARVA_IDS.contains(npc.getId())) {
            return;
        }
        int anim = npc.getAnimation();
        Integer prev = lastLoggedAnimation.get(npc.getIndex());
        if (prev == null || prev != anim) {
            lastLoggedAnimation.put(npc.getIndex(), anim);
            log.info("Mokhaiotl NPC {} (id {}) animation -> {} at tick {}",
                    npc.getIndex(), npc.getId(), anim, client.getTickCount());
        }
    }

    @Override
    public void onGraphicChanged(GraphicChanged event) {
        if (!config.mokhaiotlVerboseLogging()) {
            return;
        }
        Actor actor = event.getActor();
        if (!(actor instanceof NPC)) {
            return;
        }
        NPC npc = (NPC) actor;
        if (!BOSS_IDS.contains(npc.getId())) {
            return;
        }
        log.info("Mokhaiotl boss (id {}) graphic -> {} at tick {}", npc.getId(), spotAnimId(npc),
                client.getTickCount());
    }

    @Override
    public void onProjectileMoved(ProjectileMoved event) {
        Projectile projectile = event.getProjectile();
        if (projectile == null) {
            return;
        }
        int id = projectile.getId();

        logProjectileOnce(projectile);

        Player local = client.getLocalPlayer();
        Actor target = projectile.getTargetActor();

        // Player-targeted orb / shard -> schedule a prayer event for its impact.
        Style style = styleForProjectile(id);
        if (style != null && local != null && target == local) {
            schedulePrayerEvent(projectile, style);
            return;
        }

        // Tile-targeted rock-throw boulder -> highlight its shatter/landing tile.
        if (ROCK_THROW_PROJECTILE_IDS.contains(id)) {
            WorldPoint landing = projectile.getTargetPoint();
            if (landing != null) {
                int impactTick = client.getTickCount()
                        + Math.round(projectile.getRemainingCycles() / (float) CYCLES_PER_GAME_TICK);
                boulderTiles.merge(landing, impactTick + BOULDER_TILE_HOLD_TICKS, Math::max);
            }
        }
    }

    @Override
    public void onNpcSpawned(NpcSpawned event) {
        NPC npc = event.getNpc();
        if (npc == null) {
            return;
        }
        if (config.mokhaiotlVerboseLogging() && isInBossArea(client) && loggedNpcIds.add(npc.getId())) {
            log.info("Mokhaiotl NPC spawned: id {} name '{}' index {}", npc.getId(), npc.getName(), npc.getIndex());
        }
    }

    @Override
    public void onNpcDespawned(NpcDespawned event) {
        // Depth tracking is handled in onGameTick from boss presence gaps.
    }

    @Override
    public void onHitsplatApplied(HitsplatApplied event) {
        Actor actor = event.getActor();
        // A hit the player deals to a volatile earth statue turns it blue (the
        // player has committed that statue as a shield endpoint).
        if (actor instanceof NPC && ((NPC) actor).getId() == STATUE_ID && event.getHitsplat().isMine()) {
            attackedStatueIndices.add(((NPC) actor).getIndex());
        }
        if (config.mokhaiotlVerboseLogging() && actor == client.getLocalPlayer() && isInBossArea(client)) {
            log.info("Mokhaiotl player hitsplat type {} amount {} at tick {} pending {}",
                    event.getHitsplat().getHitsplatType(), event.getHitsplat().getAmount(),
                    client.getTickCount(), describePrayerEvents());
        }
    }

    // Pending prayer events as "STYLE@impactTick" so the log shows the offset between
    // a scheduled impact tick and the tick its damage actually splats.
    private String describePrayerEvents() {
        StringBuilder sb = new StringBuilder("[");
        for (PrayerEvent event : prayerEvents) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(event.style).append('@').append(event.impactTick);
        }
        return sb.append(']').toString();
    }

    public void onGameObjectSpawned(GameObjectSpawned event) {
        GameObject obj = event.getGameObject();
        if (obj == null) {
            return;
        }
        int id = obj.getId();
        if (BOULDER_OBJECT_IDS.contains(id)) {
            LocalPoint lp = obj.getLocalLocation();
            if (lp != null) {
                boulderObjects.put(objectKey(obj), WorldPoint.fromLocal(client, lp));
            }
        }
        if (config.mokhaiotlVerboseLogging() && isInBossArea(client) && loggedObjectIds.add(id)) {
            log.info("Mokhaiotl area object spawned: id {} at {}", id, obj.getWorldLocation());
        }
    }

    public void onGameObjectDespawned(GameObjectDespawned event) {
        GameObject obj = event.getGameObject();
        if (obj == null) {
            return;
        }
        if (BOULDER_OBJECT_IDS.contains(obj.getId())) {
            boulderObjects.remove(objectKey(obj));
        }
    }

    @Override
    public void onGameTick(GameTick event) {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        int currentTick = client.getTickCount();
        NPC boss = findBoss();

        updateDepthTracking(boss, currentTick);
        updatePhase(boss);
        pruneExpiredState(currentTick);
        updateCarPhase(boss, currentTick);
        updateStatuePhase(currentTick);

        if (config.mokhaiotlVerboseLogging() && boss != null) {
            logChargeState(boss, currentTick);
        }
        if (config.mokhaiotlVerboseLogging()) {
            logLarvaOverheads(currentTick);
        }
    }

    // Larva overhead protection icons, logged whenever they change, so the sprite
    // ids behind OVERHEAD_PROTECTED_STYLES can be confirmed against the game.
    private void logLarvaOverheads(int currentTick) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (!isLarva(npc)) {
                continue;
            }
            short[] spriteIds = npc.getOverheadSpriteIds();
            String overheads = spriteIds == null ? "none" : Arrays.toString(spriteIds);
            String prev = lastLoggedOverheads.get(npc.getIndex());
            if (!overheads.equals(prev)) {
                lastLoggedOverheads.put(npc.getIndex(), overheads);
                log.info("Mokhaiotl larva {} (id {}) overheads {} -> weakness {} at tick {}",
                        npc.getIndex(), npc.getId(), overheads, larvaStyle(npc), currentTick);
            }
        }
    }

    // ------------------------------------------------------------------
    // State machine
    // ------------------------------------------------------------------

    private void updateDepthTracking(NPC boss, int currentTick) {
        if (boss != null) {
            if (!bossSeenEver) {
                bossSeenEver = true;
            } else if (lastBossSeenTick >= 0 && currentTick - lastBossSeenTick > DELVE_GAP_TICKS) {
                // Boss reappeared after a real absence (kill + next delve), not a
                // 1-tick phase-swap flicker.
                depthLevel++;
                log.info("Mokhaiotl: advanced to delve/depth level {}", depthLevel);
            }
            lastBossSeenTick = currentTick;
        }
    }

    private void updatePhase(NPC boss) {
        if (boss == null) {
            phase = Phase.STANDARD;
            setMeleePunishActive(false);
            return;
        }

        int id = boss.getId();
        if (id == BOSS_SHIELDED) {
            phase = Phase.SHIELD;
            setMeleePunishActive(false);
            return;
        }
        if (id == BOSS_BURROWED) {
            phase = Phase.CAR;
            setMeleePunishActive(false);
            return;
        }

        // Normal boss: a beam charge-up graphic while it is otherwise idle marks the
        // melee-punish charge, which the player must interrupt with a melee hit.
        if (BEAM_CHARGE_GRAPHIC_IDS.contains(spotAnimId(boss))) {
            phase = Phase.MELEE_PUNISH;
            setMeleePunishActive(true);
        } else {
            phase = Phase.STANDARD;
            setMeleePunishActive(false);
        }
    }

    // Tracks the melee-punish charge and fires the audio cue once as it starts, so a
    // charge that lasts several ticks does not retrigger the alert every tick.
    private void setMeleePunishActive(boolean active) {
        meleePunishActive = active;
        if (!active) {
            meleePunishSoundPlayed = false;
            return;
        }
        if (!meleePunishSoundPlayed && config.mokhaiotlMeleePunishSound()) {
            SoundPlayer.play(MELEE_PUNISH_SOUND, config.mokhaiotlMeleePunishSoundVolume());
        }
        meleePunishSoundPlayed = true;
    }

    private void pruneExpiredState(int currentTick) {
        // Actual damage lands ~2 ticks after the calculated impactTick, so keep events
        // alive until currentTick > impactTick + 2 to match the overlay's +1 condition
        // and correctly cycle through back-to-back prayers on consecutive ticks.
        prayerEvents.removeIf(e -> e.impactTick + 2 < currentTick);
        boulderTiles.entrySet().removeIf(en -> en.getValue() < currentTick);
        destroyedStatueTiles.entrySet().removeIf(en -> en.getValue() < currentTick);
    }

    // ------------------------------------------------------------------
    // Car (dash) phase
    // ------------------------------------------------------------------

    private void updateCarPhase(NPC boss, int currentTick) {
        if (phase != Phase.CAR || boss == null) {
            carEyeTile = null;
            dashPathTiles.clear();
            carSafeTiles.clear();
            return;
        }

        // Locate the eye tile from its telegraph graphic each tick it is present.
        WorldPoint eye = findCarEyeTile();
        if (eye != null) {
            carEyeTile = eye;
            carTelegraphUntilTick = currentTick + CAR_TELEGRAPH_HOLD_TICKS;
        } else if (currentTick > carTelegraphUntilTick) {
            carEyeTile = null;
        }

        rebuildDashPath(boss);
        rebuildCarSafeTiles(boss);
    }

    private WorldPoint findCarEyeTile() {
        for (GraphicsObject go : client.getGraphicsObjects()) {
            if (go == null || !CAR_EYE_GRAPHIC_IDS.contains(go.getId())) {
                continue;
            }
            LocalPoint lp = go.getLocation();
            if (lp != null) {
                return WorldPoint.fromLocal(client, lp);
            }
        }
        return null;
    }

    // Build the trample corridor from the boss's current position to the eye tile,
    // widened to the boss's footprint (like the Phosani surge path).
    private void rebuildDashPath(NPC boss) {
        dashPathTiles.clear();
        if (carEyeTile == null) {
            return;
        }
        WorldPoint bossBase = boss.getWorldLocation();
        net.runelite.api.coords.WorldArea area = boss.getWorldArea();
        if (bossBase == null || area == null) {
            return;
        }
        int plane = bossBase.getPlane();
        int w = area.getWidth();
        int h = area.getHeight();
        // Centre of the boss footprint.
        int cx = area.getX() + w / 2;
        int cy = area.getY() + h / 2;
        int half = Math.max(w, h) / 2;

        int dx = Integer.signum(carEyeTile.getX() - cx);
        int dy = Integer.signum(carEyeTile.getY() - cy);
        if (dx == 0 && dy == 0) {
            return;
        }

        // Unit vector perpendicular to the travel direction, used to thicken the
        // corridor symmetrically for both cardinal and diagonal dashes.
        int px = -dy;
        int py = dx;

        int steps = Math.max(Math.abs(carEyeTile.getX() - cx), Math.abs(carEyeTile.getY() - cy));
        for (int s = 0; s <= steps; s++) {
            int bx = cx + dx * s;
            int by = cy + dy * s;
            for (int o = -half; o <= half; o++) {
                dashPathTiles.add(new WorldPoint(bx + px * o, by + py * o, plane));
            }
        }
    }

    // Tiles within CAR_SAFE_SEARCH_RADIUS of the player that a boulder shields from
    // the boss: the straight line from the boss centre to the tile is blocked by a
    // tracked boulder, so the dash cannot reach the player there.
    private void rebuildCarSafeTiles(NPC boss) {
        carSafeTiles.clear();
        if (boulderObjects.isEmpty()) {
            return;
        }
        Player local = client.getLocalPlayer();
        if (local == null) {
            return;
        }
        WorldPoint playerLoc = local.getWorldLocation();
        net.runelite.api.coords.WorldArea area = boss.getWorldArea();
        if (playerLoc == null || area == null) {
            return;
        }
        WorldPoint bossCentre = new WorldPoint(area.getX() + area.getWidth() / 2,
                area.getY() + area.getHeight() / 2, playerLoc.getPlane());
        Set<WorldPoint> boulderTileSet = new HashSet<>(boulderObjects.values());

        for (int dx = -CAR_SAFE_SEARCH_RADIUS; dx <= CAR_SAFE_SEARCH_RADIUS; dx++) {
            for (int dy = -CAR_SAFE_SEARCH_RADIUS; dy <= CAR_SAFE_SEARCH_RADIUS; dy++) {
                WorldPoint candidate = new WorldPoint(playerLoc.getX() + dx, playerLoc.getY() + dy,
                        playerLoc.getPlane());
                if (boulderTileSet.contains(candidate)) {
                    continue;
                }
                if (lineBlockedByBoulder(bossCentre, candidate, boulderTileSet)) {
                    carSafeTiles.add(candidate);
                }
            }
        }
    }

    // Bresenham line walk from -> to; true if any intermediate tile carries a
    // boulder (i.e. the boulder sits between the boss and the candidate tile).
    private boolean lineBlockedByBoulder(WorldPoint from, WorldPoint to, Set<WorldPoint> boulders) {
        int x0 = from.getX();
        int y0 = from.getY();
        int x1 = to.getX();
        int y1 = to.getY();
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0;
        int y = y0;
        while (x != x1 || y != y1) {
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
            if (x == x1 && y == y1) {
                break;
            }
            if (boulders.contains(new WorldPoint(x, y, from.getPlane()))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Statue & orb (shockwave) phase
    // ------------------------------------------------------------------

    // Volatile earth ("statues") appear in a batch before the shockwave. Highlight
    // the single usable pair (axis-aligned, far enough apart) nearest the player and
    // arm the shockwave countdown. The pair is picked once, from where the player
    // stood when it was worked out, and then left alone until the statues go away.
    private void updateStatuePhase(int currentTick) {
        List<NPC> statues = new ArrayList<>();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == STATUE_ID) {
                statues.add(npc);
            }
        }

        boolean present = !statues.isEmpty();
        if (present && !statuesPresentLastTick) {
            shockwaveImpactTick = currentTick + SHOCKWAVE_TIMER_TICKS;
            attackedStatueIndices.clear();
            attackedStatueTiles.clear();
            if (config.mokhaiotlVerboseLogging()) {
                log.info("Mokhaiotl: {} volatile earth statues spawned, shockwave in ~{} ticks",
                        statues.size(), SHOCKWAVE_TIMER_TICKS);
            }
        }

        trackDestroyedStatues(statues, currentTick);

        if (!present) {
            highlightedStatueIndices.clear();
            attackedStatueIndices.clear();
            farStatueIndex = -1;
            nearStatueIndex = -1;
            shockwaveImpactTick = -1;
        } else if (highlightedStatueIndices.isEmpty()) {
            // Only until a pair is found: the choice is then locked in, so it never
            // re-picks as the player walks towards the statues.
            computeClosestStraightPair(statues);
        }

        statuesPresentLastTick = present;
    }

    // Keep the tile of every statue the player has hit, and when one of them leaves
    // the scene leave its red marker behind for a few ticks so the player can still
    // see where it stood.
    private void trackDestroyedStatues(List<NPC> statues, int currentTick) {
        Set<Integer> presentIndices = new HashSet<>();
        for (NPC statue : statues) {
            presentIndices.add(statue.getIndex());
            if (attackedStatueIndices.contains(statue.getIndex())) {
                WorldPoint tile = statue.getWorldLocation();
                if (tile != null) {
                    attackedStatueTiles.put(statue.getIndex(), tile);
                }
            }
        }

        attackedStatueTiles.entrySet().removeIf(entry -> {
            if (presentIndices.contains(entry.getKey())) {
                return false;
            }
            destroyedStatueTiles.merge(entry.getValue(),
                    currentTick + DESTROYED_STATUE_HOLD_TICKS, Math::max);
            return true;
        });
    }

    // A usable pair is two statues sharing a row or a column (so the earthen shield
    // travels in one cardinal direction with no diagonal step) at least
    // MIN_STATUE_PAIR_DISTANCE tiles apart. Only one pair is ever highlighted: the
    // one with a statue nearest the player, so the walk starts from where you stand.
    private void computeClosestStraightPair(List<NPC> statues) {
        List<Integer> previous = new ArrayList<>(highlightedStatueIndices);
        highlightedStatueIndices.clear();

        Player local = client.getLocalPlayer();
        WorldPoint playerLoc = local == null ? null : local.getWorldLocation();

        farStatueIndex = -1;
        nearStatueIndex = -1;
        // Rank by the nearer statue of the pair, then by the further one so a tie on
        // the near end picks the shorter walk to the far end.
        int bestNear = Integer.MAX_VALUE;
        int bestFar = Integer.MAX_VALUE;
        for (int i = 0; i < statues.size(); i++) {
            WorldPoint a = statues.get(i).getWorldLocation();
            if (a == null) {
                continue;
            }
            for (int j = i + 1; j < statues.size(); j++) {
                WorldPoint b = statues.get(j).getWorldLocation();
                if (b == null || b.getPlane() != a.getPlane() || !isStraightPair(a, b)) {
                    continue;
                }
                int distA = playerLoc == null ? 0 : playerLoc.distanceTo(a);
                int distB = playerLoc == null ? 0 : playerLoc.distanceTo(b);
                int near = Math.min(distA, distB);
                int far = Math.max(distA, distB);
                if (near < bestNear || (near == bestNear && far < bestFar)) {
                    bestNear = near;
                    bestFar = far;
                    boolean aIsNearer = distA <= distB;
                    nearStatueIndex = statues.get(aIsNearer ? i : j).getIndex();
                    farStatueIndex = statues.get(aIsNearer ? j : i).getIndex();
                }
            }
        }

        if (farStatueIndex >= 0) {
            highlightedStatueIndices.add(farStatueIndex);
            highlightedStatueIndices.add(nearStatueIndex);
        }

        if (config.mokhaiotlVerboseLogging() && !previous.equals(highlightedStatueIndices)) {
            log.info("Mokhaiotl: statue pair locked in - far {} ({} tiles), near {} ({} tiles), of {} statues",
                    farStatueIndex, bestFar == Integer.MAX_VALUE ? -1 : bestFar,
                    nearStatueIndex, bestNear == Integer.MAX_VALUE ? -1 : bestNear, statues.size());
        }
    }

    // The near statue is dormant until the far one has been hit: it is drawn faintly
    // so the pair can be read early, but only the far one looks like a live target.
    public boolean isStatueDormant(NPC npc) {
        if (npc == null || npc.getIndex() != nearStatueIndex || farStatueIndex < 0) {
            return false;
        }
        return !attackedStatueIndices.contains(farStatueIndex);
    }

    // Tiles of statues the player destroyed, held briefly after they despawn.
    public Set<WorldPoint> getDestroyedStatueTiles() {
        return new HashSet<>(destroyedStatueTiles.keySet());
    }

    // Axis-aligned (never diagonal) and far enough apart to be worth the walk.
    static boolean isStraightPair(WorldPoint a, WorldPoint b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        if (dx != 0 && dy != 0) {
            return false;
        }
        return Math.max(dx, dy) >= MIN_STATUE_PAIR_DISTANCE;
    }

    // ------------------------------------------------------------------
    // Prayer event scheduling
    // ------------------------------------------------------------------

    private void schedulePrayerEvent(Projectile projectile, Style style) {
        if (!scheduledProjectiles.add(projectile)) {
            return;
        }
        // A projectile lands on the tick its remaining cycles run out, so the landing
        // tick is a ceiling: 160 cycles is 5.33 ticks of flight and it splats on the
        // 6th. Rounding here schedules a tick early whenever the fraction is under .5.
        int impactTick = client.getTickCount()
                + (int) Math.ceil(projectile.getRemainingCycles() / (double) CYCLES_PER_GAME_TICK);
        prayerEvents.add(new PrayerEvent(style, impactTick));
        if (config.mokhaiotlVerboseLogging()) {
            log.info("Mokhaiotl prayer event scheduled: {} impact tick {} (projectile {})",
                    style, impactTick, projectile.getId());
        }
    }

    private Style styleForProjectile(int id) {
        if (MAGIC_PROJECTILE_IDS.contains(id)) {
            return Style.MAGIC;
        }
        if (RANGE_PROJECTILE_IDS.contains(id)) {
            return Style.RANGE;
        }
        if (MELEE_PROJECTILE_IDS.contains(id)) {
            return Style.MELEE;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Logging helpers (Phase 0 diagnostics)
    // ------------------------------------------------------------------

    private void logProjectileOnce(Projectile projectile) {
        if (!config.mokhaiotlVerboseLogging() || !isInBossArea(client)) {
            return;
        }
        int id = projectile.getId();
        if (!loggedProjectileIds.add(id)) {
            return;
        }
        Player local = client.getLocalPlayer();
        boolean atPlayer = local != null && projectile.getTargetActor() == local;
        int flightTicks = Math.round(projectile.getRemainingCycles() / (float) CYCLES_PER_GAME_TICK);
        log.info("Mokhaiotl projectile FIRST-SEEN id {} flightTicks ~{} targetsPlayer {} targetTile {}",
                id, flightTicks, atPlayer, projectile.getTargetPoint());
    }

    private void logChargeState(NPC boss, int currentTick) {
        int graphic = spotAnimId(boss);
        if (BEAM_CHARGE_GRAPHIC_IDS.contains(graphic)) {
            log.info("Mokhaiotl boss charge graphic {} (phase {}) at tick {}", graphic, phase, currentTick);
        }
    }

    @SuppressWarnings("deprecation") // getGraphic() is the stable spotanim read in this pinned version
    private int spotAnimId(NPC npc) {
        return npc.getGraphic();
    }

    private static long objectKey(GameObject obj) {
        return obj.getHash();
    }

    private NPC findBoss() {
        NPC fallback = null;
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null) {
                continue;
            }
            if (BOSS_IDS.contains(npc.getId())) {
                return npc;
            }
            String name = npc.getName();
            if (name != null && name.toLowerCase().contains("mokhaiotl")) {
                fallback = npc;
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------
    // Accessors for the overlays / plugin
    // ------------------------------------------------------------------

    public Phase getPhase() {
        return phase;
    }

    public int getDepthLevel() {
        return depthLevel;
    }

    public boolean isShieldPhase() {
        return phase == Phase.SHIELD;
    }

    public boolean isCarPhase() {
        return phase == Phase.CAR;
    }

    public boolean isMeleePunishActive() {
        return meleePunishActive;
    }

    public NPC getBoss() {
        return findBoss();
    }

    public List<PrayerEvent> getPrayerEvents() {
        return new ArrayList<>(prayerEvents);
    }

    public Set<WorldPoint> getBoulderTiles() {
        return new HashSet<>(boulderTiles.keySet());
    }

    public WorldPoint getCarEyeTile() {
        return carEyeTile;
    }

    public Set<WorldPoint> getDashPathTiles() {
        return new HashSet<>(dashPathTiles);
    }

    public Set<WorldPoint> getCarSafeTiles() {
        return new HashSet<>(carSafeTiles);
    }

    // The one highlighted pair of volatile earth statues for the shield path.
    public List<NPC> getHighlightedStatues() {
        List<NPC> result = new ArrayList<>();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == STATUE_ID && highlightedStatueIndices.contains(npc.getIndex())) {
                result.add(npc);
            }
        }
        return result;
    }

    public boolean isStatueAttacked(NPC npc) {
        return npc != null && attackedStatueIndices.contains(npc.getIndex());
    }

    // Ticks until the shockwave lands, or -1 when no statues are present.
    public int getShockwaveTimer() {
        return shockwaveImpactTick < 0 ? -1 : shockwaveImpactTick - client.getTickCount();
    }

    // The earthen shield ("orb") NPC, or null when it is not active.
    public NPC getOrb() {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == ORB_ID) {
                return npc;
            }
        }
        return null;
    }

    public static boolean isEarthenShield(NPC npc) {
        return npc != null && npc.getId() == ORB_ID;
    }

    public static boolean isLarva(NPC npc) {
        return npc != null && LARVA_IDS.contains(npc.getId());
    }

    // Weakness style of a larva (the style you must attack it with). The id encodes
    // it when the larva has a single fixed weakness; otherwise the larva's overhead
    // protection prayers are the source of truth (see larvaStyleFromOverheads).
    public static Style larvaStyle(NPC npc) {
        if (npc == null) {
            return null;
        }
        switch (npc.getId()) {
            case LARVA_RANGE:
            case LARVA_GIANT_RANGE:
                return Style.RANGE;
            case LARVA_MAGE:
            case LARVA_GIANT_MAGE:
                return Style.MAGIC;
            case LARVA_MELEE:
                return Style.MELEE;
            default:
                // Neutral id (delve 1-3): the overheads still narrow the styles down.
                return larvaStyleFromOverheads(npc);
        }
    }

    /**
     * Weakness style derived from a larva's overhead protection prayers. Each
     * overhead marks a style the larva is immune to, so whatever is left over is a
     * style it can be attacked with:
     * <ul>
     * <li>no overheads -> every style works, so no colour (null);</li>
     * <li>one overhead -> two styles work; either is correct, so one is picked via
     * {@link #WEAKNESS_PREFERENCE} instead of leaving the larva uncoloured;</li>
     * <li>two overheads -> the single remaining style.</li>
     * </ul>
     */
    private static Style larvaStyleFromOverheads(NPC npc) {
        short[] spriteIds = npc.getOverheadSpriteIds();
        if (spriteIds == null || spriteIds.length == 0) {
            return null;
        }
        Set<Style> protectedStyles = EnumSet.noneOf(Style.class);
        for (short spriteId : spriteIds) {
            Set<Style> styles = OVERHEAD_PROTECTED_STYLES.get((int) spriteId);
            if (styles != null) {
                protectedStyles.addAll(styles);
            }
        }
        if (protectedStyles.isEmpty()) {
            return null;
        }
        for (Style style : WEAKNESS_PREFERENCE) {
            if (!protectedStyles.contains(style)) {
                return style;
            }
        }
        return null; // protected against everything - nothing useful to highlight
    }

    // Overhead icon -> the styles that icon protects against. The sprite ids are
    // indexes into the default prayer headicon sheet, i.e. HeadIcon ordinals. The
    // combined icons are included so a single overhead covering two styles is read
    // correctly.
    private static final Map<Integer, Set<Style>> OVERHEAD_PROTECTED_STYLES = Map.of(
            0, EnumSet.of(Style.MELEE), // HeadIcon.MELEE
            1, EnumSet.of(Style.RANGE), // HeadIcon.RANGED
            2, EnumSet.of(Style.MAGIC), // HeadIcon.MAGIC
            6, EnumSet.of(Style.RANGE, Style.MAGIC), // HeadIcon.RANGE_MAGE
            7, EnumSet.of(Style.RANGE, Style.MELEE), // HeadIcon.RANGE_MELEE
            8, EnumSet.of(Style.MAGIC, Style.MELEE), // HeadIcon.MAGE_MELEE
            9, EnumSet.of(Style.RANGE, Style.MAGIC, Style.MELEE)); // HeadIcon.RANGE_MAGE_MELEE

    // Order used to pick one weakness when a larva still has two. Either is correct,
    // so the ranged/magic options come first: larvae stack under the boss and don't
    // have to be reached to be hit with those.
    private static final List<Style> WEAKNESS_PREFERENCE = List.of(Style.RANGE, Style.MAGIC, Style.MELEE);

    @Override
    public void reset() {
        phase = Phase.STANDARD;
        depthLevel = 1;
        bossSeenEver = false;
        lastBossSeenTick = -1;
        meleePunishActive = false;
        meleePunishSoundPlayed = false;
        prayerEvents.clear();
        scheduledProjectiles.clear();
        boulderTiles.clear();
        carEyeTile = null;
        carTelegraphUntilTick = -1;
        dashPathTiles.clear();
        carSafeTiles.clear();
        boulderObjects.clear();
        highlightedStatueIndices.clear();
        attackedStatueIndices.clear();
        attackedStatueTiles.clear();
        destroyedStatueTiles.clear();
        farStatueIndex = -1;
        nearStatueIndex = -1;
        shockwaveImpactTick = -1;
        statuesPresentLastTick = false;
        loggedProjectileIds.clear();
        loggedNpcIds.clear();
        loggedObjectIds.clear();
        lastLoggedAnimation.clear();
        lastLoggedOverheads.clear();
    }

    // Hardcoded larva highlight colours (the only use of the style colours).
    private static final Color MAGIC_COLOR = new Color(100, 149, 237); // Soft blue
    private static final Color RANGE_COLOR = new Color(144, 238, 144); // Soft green
    private static final Color MELEE_COLOR = new Color(240, 100, 100); // Soft red

    // Colour helpers so overlays share one style->colour mapping.
    public Color styleColor(Style style) {
        if (style == null) {
            return Color.GRAY;
        }
        switch (style) {
            case MAGIC:
                return MAGIC_COLOR;
            case RANGE:
                return RANGE_COLOR;
            case MELEE:
                return MELEE_COLOR;
            default:
                return Color.GRAY;
        }
    }
}
