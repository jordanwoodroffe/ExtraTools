package com.pvmkits.bosses.yama;

import com.pvmkits.core.BossHandler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.ProjectileMoved;

import javax.inject.Inject;
import java.awt.Color;
import java.util.*;

@Slf4j
public class YamaHandler implements BossHandler {

    @Inject
    private Client client;

    // Track current Yama phases by NPC index
    private Map<Integer, YamaPhase> yamaPhases = new HashMap<>();

    // Yama animation IDs
    private static final int ANIMATION_MELEE = 12146;
    private static final int ANIMATION_ORB_ATTACK = 12146; // Same as melee
    private static final int ANIMATION_MAGE = 12144;

    // Yama graphic IDs
    private static final int GRAPHIC_MAGE = 3246;
    private static final int GRAPHIC_RANGE = 3243;
    private static final int GRAPHIC_GLYPH_ATTACK = 3253;
    // Fire special variant (seen near the end of the fight) - treated as a fire
    // special attack: highlights Yama's fire-special colour and the fire glyphs.
    private static final int GRAPHIC_FIRE_SPECIAL_VARIANT = 3270;
    // Shadow special variant - the shadow mirror of the fire special variant:
    // highlights Yama's shadow-special colour and the shadow glyphs. Confirmed
    // from client logs (2026-07-19 enrage): graphic 3259 fires with the same
    // special animation (12148) as the fire special (3270) and its own projectile
    // (3260), interleaved with the fire special during the enrage.
    private static final int GRAPHIC_SHADOW_SPECIAL_VARIANT = 3259;

    // Yama NPC ID
    private static final int YAMA_ID = 14176;

    // Attack cycle constants
    private static final int ATTACK_CYCLE_TICKS = 8;
    private static final int ENRAGE_ATTACK_CYCLE_TICKS = 7;

    // Graphic for phase transition
    private static final int GRAPHIC_PHASE_TRANSITION = 3276;

    // Track last logged animation for each Yama to prevent duplicate logging
    private Map<Integer, Integer> lastLoggedAnimations = new HashMap<>();

    // Track attack timers for each Yama (NPC index -> ticks until next attack)
    private Map<Integer, Integer> yamaAttackTimers = new HashMap<>();

    // Track phase transition graphic occurrences for each Yama (NPC index -> count)
    private Map<Integer, Integer> phaseTransitionCounts = new HashMap<>();

    // Track which timers were just initialized this tick to prevent immediate
    // countdown
    private Set<Integer> newlyInitializedTimers = new HashSet<>();

    // Track attack cooldowns to prevent multiple timer resets from duplicate
    // animations
    // Maps NPC index to the tick when the cooldown expires
    private Map<Integer, Integer> attackCooldowns = new HashMap<>();

    // Cooldown duration in ticks after detecting an attack
    private static final int ATTACK_COOLDOWN_TICKS = 6;

    // Glyph GameObject IDs and the elemental attacks that activate them.
    // Detection is primarily via the Yama NPC's attack graphic (fires earlier),
    // with the projectile kept as a fallback in case the graphic is missed.
    private static final int GLYPH_FIRE_ID = 56336;
    private static final int GLYPH_SHADOW_ID = 56335;
    private static final int GRAPHIC_FIRE_ATTACK = 3253;
    private static final int GRAPHIC_SHADOW_ATTACK = 3256;
    private static final int PROJECTILE_FIRE_ATTACK = 3254;
    private static final int PROJECTILE_SHADOW_ATTACK = 3257;
    // Shadow special variant projectile - fallback for graphic 3259, mirroring the
    // projectile fallbacks kept for the regular fire/shadow attacks.
    private static final int PROJECTILE_SHADOW_SPECIAL_VARIANT = 3260;

    // ---- 3-fireball line special attack (final enrage phase) ----
    // Each cast drops three fireballs in a straight line: horizontal (E-W),
    // vertical (N-S), or NW-SE diagonal. The centre fireball lands on the player;
    // the two safe tiles are the tiles immediately perpendicular to the line
    // through that centre (horizontal -> N/S, vertical -> E/W, NW-SE -> NE/SW).
    //
    // Confirmed from client logs (2026-07-19 enrage): the fireball is
    // GraphicsObject id 3262, and the three fireballs are NOT adjacent - straight
    // lines are spaced 3 tiles apart and diagonals 2 tiles apart. So the line is
    // detected by geometry rather than adjacency: for any two fireballs whose
    // integer midpoint also carries a fireball (i.e. a true 3-in-a-line), that
    // midpoint is the centre and the safe tiles are centre +/- the perpendicular
    // unit step (see recomputeFireballSafeTiles). No projectile id was observed
    // for this attack; the projectile set is kept for the fallback path only.
    private static final Set<Integer> FIREBALL_GRAPHIC_IDS = new HashSet<>(Arrays.asList(3262));
    private static final Set<Integer> FIREBALL_PROJECTILE_IDS = new HashSet<>();

    // Client game cycles per game tick (600ms / 20ms). Converts a projectile's
    // remaining cycles into the game tick it will land on.
    private static final int CYCLES_PER_GAME_TICK = 30;

    // How long an impact fireball graphic keeps its tile flagged after it is seen.
    // Kept short so an old line's tiles never linger into the next line's solve.
    private static final int FIREBALL_GRAPHIC_LINGER_TICKS = 2;

    // Largest distance (in tiles) an outer fireball sits from the centre. Observed
    // radii are 3 (straight) and 2 (diagonal); the cap rejects pairing unrelated
    // fireballs from different casts that briefly overlap.
    private static final int FIREBALL_MAX_ARM_RADIUS = 4;

    // Known glyph object IDs spawned during the Yama fight. If this set is made
    // empty, discovery mode activates: every floor GameObject that spawns is
    // tracked and highlighted (and logged at INFO) so new IDs can be identified.
    private static final Set<Integer> GLYPH_OBJECT_IDS = new HashSet<>(Arrays.asList(
            56335, 56336, 56337, 56338));

    // Track active glyph GameObjects (keyed by TileObject hash)
    private final Map<Long, GameObject> glyphObjects = new HashMap<>();

    // Which glyph type is currently active, driven by the elemental attack
    // projectile that was last seen (fire -> fire glyphs, shadow -> shadow glyphs)
    private GlyphType activeGlyphType = GlyphType.NONE;

    // How long (in ticks) the glyph highlight stays lit after the last elemental
    // attack detection. Refreshed on every fire/shadow detection so multi-wave
    // attacks keep the highlight alive, then it clears once the attack is over.
    private static final int GLYPH_HIGHLIGHT_DURATION_TICKS = 5;

    // Tick at which the active glyph highlight expires and is cleared
    private int activeGlyphExpiryTick = -1;

    // Fireball special: telegraphed landing tiles -> the tick the telegraph
    // expires. Fed by projectiles (while airborne, early warning) and/or impact
    // graphics, then solved into safe tiles every tick.
    private final Map<WorldPoint, Integer> fireballTiles = new HashMap<>();

    // The safe tiles derived from the active fireball line(s), recomputed each
    // tick. Normally two per line; a tile that itself has a fireball on it is
    // never included.
    private final Set<WorldPoint> fireballSafeTiles = new HashSet<>();

    public enum GlyphType {
        NONE, FIRE, SHADOW
    }

    @Override
    public String getBossName() {
        return "Yama";
    }

    @Override
    public boolean isInBossArea(Client client) {
        // Check if any Yama NPCs are present
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == YAMA_ID) {
                log.info(
                        "YamaHandler.isInBossArea: Found Yama NPC with ID " + YAMA_ID + " and index " + npc.getIndex());
                return true;
            }
        }
        log.debug("YamaHandler.isInBossArea: No Yama NPCs found, checking all NPCs...");

        // Debug: Log all NPC IDs to help identify if Yama ID is wrong
        int npcCount = 0;
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null) {
                npcCount++;
                if (npcCount <= 5) { // Only log first 5 NPCs to avoid spam
                    log.debug("YamaHandler.isInBossArea: Found NPC ID " + npc.getId() + " at index " + npc.getIndex());
                }
            }
        }
        log.debug("YamaHandler.isInBossArea: Total NPCs found: " + npcCount);

        return false;
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        // Animation detection moved to onGameTick to match working example
    }

    @Override
    public void onProjectileMoved(ProjectileMoved event) {
        Projectile projectile = event.getProjectile();
        if (projectile == null) {
            return;
        }
        int projectileId = projectile.getId();

        // Fallback only: the graphic change in onGraphicChanged fires earlier and is
        // the preferred trigger. This catches the case where the graphic is missed.
        if (projectileId == PROJECTILE_FIRE_ATTACK) {
            setActiveGlyphType(GlyphType.FIRE, "projectile " + projectileId + " (fallback)");
        } else if (projectileId == PROJECTILE_SHADOW_ATTACK || projectileId == PROJECTILE_SHADOW_SPECIAL_VARIANT) {
            setActiveGlyphType(GlyphType.SHADOW, "projectile " + projectileId + " (fallback)");
        }

        // 3-fireball line special: record the landing tile while the fireball is
        // still airborne so the safe tiles can be shown before it lands. The tile
        // stays flagged through its landing tick, then clears.
        if (FIREBALL_PROJECTILE_IDS.contains(projectileId)) {
            WorldPoint target = fireballTargetTile(event, projectile);
            if (target != null) {
                int ticksToLand = Math.max(0,
                        (int) Math.ceil(projectile.getRemainingCycles() / (double) CYCLES_PER_GAME_TICK));
                int expiry = client.getTickCount() + ticksToLand + 1;
                fireballTiles.merge(target, expiry, Math::max);
            }
        }
    }

    private WorldPoint fireballTargetTile(ProjectileMoved event, Projectile projectile) {
        WorldPoint target = projectile.getTargetPoint();
        if (target == null) {
            LocalPoint pos = event.getPosition();
            target = pos != null ? WorldPoint.fromLocal(client, pos) : null;
        }
        return target;
    }

    private void setActiveGlyphType(GlyphType type, String source) {
        // Only (re)start the highlight window when the attack type actually
        // changes. A single elemental attack fires the graphic once and then the
        // projectile repeatedly while it travels; extending the window on each of
        // those events made the highlight linger far too long, and letting the
        // graphic-triggered window expire before the projectile re-triggered it
        // caused a one-tick mid-attack flicker. Anchoring the window to the first
        // detection of the attack fixes both.
        if (activeGlyphType == type) {
            return;
        }
        activeGlyphType = type;
        activeGlyphExpiryTick = client.getTickCount() + GLYPH_HIGHLIGHT_DURATION_TICKS;
        log.info("Yama " + type + " elemental attack detected via " + source
                + " - highlighting " + type + " glyphs");
    }

    @Override
    @SuppressWarnings("deprecation") // getGraphic() is deprecated but still functional
    public void onGraphicChanged(GraphicChanged event) {
        Actor actor = event.getActor();

        if (!(actor instanceof NPC)) {
            return;
        }

        NPC npc = (NPC) actor;
        if (npc.getId() != YAMA_ID) {
            return;
        }

        int index = npc.getIndex();
        int graphicId = npc.getGraphic();

        // Log every graphic change event, including when graphics are cleared
        log.info("Yama (index " + index + ") attack graphic: graphicId=" + graphicId);

        // Track phase transition graphics (attack graphic 3276)
        if (graphicId == GRAPHIC_PHASE_TRANSITION) {
            int currentCount = phaseTransitionCounts.getOrDefault(index, 0);
            phaseTransitionCounts.put(index, currentCount + 1);
            log.info("Yama (index " + index + ") phase transition detected. Count: " + (currentCount + 1) +
                    (currentCount + 1 >= 2 ? " - ENRAGE PHASE ACTIVATED" : ""));
        }

        // Reset timer when graphic-based attacks are detected, but only if not in
        // cooldown
        if (isAttackGraphic(graphicId)) {
            int currentTick = client.getTickCount();
            Integer cooldownExpiry = attackCooldowns.get(index);

            // Only reset timer if we're not in cooldown or cooldown has expired
            if (cooldownExpiry == null || currentTick >= cooldownExpiry) {
                int attackTicks = getAttackCycleTicks(index);
                yamaAttackTimers.put(index, attackTicks);
                newlyInitializedTimers.add(index);
                // Set cooldown to expire in 6 ticks
                attackCooldowns.put(index, currentTick + ATTACK_COOLDOWN_TICKS);
                log.info("Yama (index " + index + ") graphic attack detected, timer reset to " + attackTicks +
                        " (cooldown until tick " + (currentTick + ATTACK_COOLDOWN_TICKS) + ")" +
                        (isYamaInEnragePhase(index) ? " [ENRAGE PHASE]" : ""));
            } else {
                log.info(
                        "Yama (index " + index + ") graphic attack ignored - in cooldown until tick " + cooldownExpiry);
            }
        }

        if (graphicId == GRAPHIC_MAGE) {
            yamaPhases.put(index, YamaPhase.MAGE);
        } else if (graphicId == GRAPHIC_RANGE) {
            yamaPhases.put(index, YamaPhase.RANGE);
        } else if (graphicId == GRAPHIC_GLYPH_ATTACK || graphicId == GRAPHIC_FIRE_SPECIAL_VARIANT) {
            yamaPhases.put(index, YamaPhase.FIRE_SPECIAL); // Glyph attack / fire special variant
        } else if (graphicId == GRAPHIC_SHADOW_SPECIAL_VARIANT) {
            yamaPhases.put(index, YamaPhase.SHADOW_SPECIAL); // Shadow special variant
        }

        // Determine which glyphs to highlight based on the elemental attack graphic.
        // This fires earlier than the projectile, giving more reaction time.
        if (graphicId == GRAPHIC_FIRE_ATTACK || graphicId == GRAPHIC_FIRE_SPECIAL_VARIANT) {
            setActiveGlyphType(GlyphType.FIRE, "graphic " + graphicId);
        } else if (graphicId == GRAPHIC_SHADOW_ATTACK || graphicId == GRAPHIC_SHADOW_SPECIAL_VARIANT) {
            setActiveGlyphType(GlyphType.SHADOW, "graphic " + graphicId);
        }
    }

    @Override
    public void onGameTick(GameTick event) {
        if (client.getGameState().getState() < 30) {
            return;
        }

        log.debug("YamaHandler.onGameTick: Called, GameState=" + client.getGameState());

        // Clear the glyph highlight once the elemental attack window has elapsed
        if (activeGlyphType != GlyphType.NONE && client.getTickCount() >= activeGlyphExpiryTick) {
            log.info("Yama glyph highlight expired - clearing " + activeGlyphType + " glyphs");
            activeGlyphType = GlyphType.NONE;
        }

        boolean yamaPresent = false;
        // Track all visible Yamas in the scene
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == YAMA_ID) {
                yamaPresent = true;
                int index = npc.getIndex();
                log.debug("YamaHandler.onGameTick: Processing Yama with index " + index);

                // Log animation IDs for Yama only when they change
                int animationId = npc.getAnimation();
                if (animationId != -1) {
                    Integer lastLogged = lastLoggedAnimations.get(index);
                    if (lastLogged == null || lastLogged != animationId) {
                        log.info("Yama (index " + index + ") animation: animationId=" + animationId);
                        lastLoggedAnimations.put(index, animationId);
                    }

                    // Reset timer to appropriate ticks when Yama attacks, but only if not in
                    // cooldown
                    // Check for attack animation regardless of whether it changed
                    if (isAttackAnimation(animationId)) {
                        int currentTick = client.getTickCount();
                        Integer cooldownExpiry = attackCooldowns.get(index);

                        // Only reset timer if we're not in cooldown or cooldown has expired
                        if (cooldownExpiry == null || currentTick >= cooldownExpiry) {
                            int attackTicks = getAttackCycleTicks(index);
                            yamaAttackTimers.put(index, attackTicks);
                            newlyInitializedTimers.add(index);
                            // Set cooldown to expire in 6 ticks
                            attackCooldowns.put(index, currentTick + ATTACK_COOLDOWN_TICKS);
                            log.info("Yama (index " + index + ") attack detected, timer reset to " + attackTicks +
                                    " (cooldown until tick " + (currentTick + ATTACK_COOLDOWN_TICKS) + ")" +
                                    (isYamaInEnragePhase(index) ? " [ENRAGE PHASE]" : ""));
                        } else {
                            log.debug("Yama (index " + index + ") attack ignored - in cooldown until tick "
                                    + cooldownExpiry);
                        }
                    }

                    // Update phase based on animation if available (handle melee attacks here)
                    if (animationId == ANIMATION_MELEE) {
                        yamaPhases.put(index, YamaPhase.MELEE);
                    }
                }

                // Initialize with UNKNOWN if we haven't seen this Yama before
                if (!yamaPhases.containsKey(index)) {
                    yamaPhases.put(index, YamaPhase.UNKNOWN);
                    log.info("YamaHandler.onGameTick: Initialized phase to UNKNOWN for Yama index " + index);
                }

                // Initialize timer if not present (start with appropriate timing)
                if (!yamaAttackTimers.containsKey(index)) {
                    int attackTicks = getAttackCycleTicks(index);
                    yamaAttackTimers.put(index, attackTicks);
                    newlyInitializedTimers.add(index);
                    log.info("Yama (index " + index + ") timer initialized to " + attackTicks +
                            (isYamaInEnragePhase(index) ? " [ENRAGE PHASE]" : ""));
                } else {
                    // Debug: Log current timer state every 10 ticks to avoid spam
                    if (client.getTickCount() % 10 == 0) {
                        int currentTimer = yamaAttackTimers.get(index);
                        log.debug("Yama (index " + index + ") current timer value: " + currentTimer);
                    }
                }
            }
        }

        // If no Yama exists, clear all highlights and phases
        if (!yamaPresent) {
            if (!yamaPhases.isEmpty() || !yamaAttackTimers.isEmpty()) {
                log.info("YamaHandler.onGameTick: No Yama present, clearing all data");
            }
            yamaPhases.clear();
            yamaAttackTimers.clear();
            phaseTransitionCounts.clear();
            attackCooldowns.clear();
            glyphObjects.clear();
            activeGlyphType = GlyphType.NONE;
            activeGlyphExpiryTick = -1;
            fireballTiles.clear();
            fireballSafeTiles.clear();
            return;
        }

        // Solve the 3-fireball line special into safe tiles for this tick.
        refreshFireballSpecial();

        // Update attack timers for all Yamas
        log.debug("YamaHandler.onGameTick: Updating timers for " + yamaAttackTimers.size() + " Yamas");
        for (Map.Entry<Integer, Integer> entry : yamaAttackTimers.entrySet()) {
            int yamaIndex = entry.getKey();
            int currentTicks = entry.getValue();

            // Skip countdown for newly initialized timers this tick
            if (newlyInitializedTimers.contains(yamaIndex)) {
                log.debug("Yama (index " + yamaIndex + ") timer skip countdown (newly initialized): " + currentTicks);
                continue;
            }

            // Only decrement if the timer is greater than 1
            if (currentTicks > 1) {
                // Countdown the timer
                int newTicks = currentTicks - 1;
                yamaAttackTimers.put(yamaIndex, newTicks);
                log.debug("Yama (index " + yamaIndex + ") timer countdown: " + currentTicks + " -> " + newTicks);

            } else if (currentTicks == 1) {
                // Timer at 1, next tick should be an attack
                log.debug("Yama (index " + yamaIndex + ") timer at 1, waiting for attack");
                // Keep timer at 1 until attack is detected
            } else if (currentTicks <= 0) {
                // Timer went below 1, reset it
                int attackTicks = getAttackCycleTicks(yamaIndex);
                yamaAttackTimers.put(yamaIndex, attackTicks);
                log.info("Yama (index " + yamaIndex + ") timer reset from " + currentTicks + " to " + attackTicks);
            }
        }

        // Clear the newly initialized timers set for next tick
        if (!newlyInitializedTimers.isEmpty()) {
            log.debug("YamaHandler.onGameTick: Clearing newly initialized timers: " + newlyInitializedTimers);
        }
        newlyInitializedTimers.clear();
    }

    @Override
    public Actor getBossActor(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == YAMA_ID) {
                return npc;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        yamaPhases.clear();
        yamaAttackTimers.clear();
        phaseTransitionCounts.clear();
        attackCooldowns.clear();
        lastLoggedAnimations.clear();
        newlyInitializedTimers.clear();
        glyphObjects.clear();
        activeGlyphType = GlyphType.NONE;
        activeGlyphExpiryTick = -1;
        fireballTiles.clear();
        fireballSafeTiles.clear();
    }

    // Track glyph floor objects that spawn during the Yama fight
    public void onGameObjectSpawned(GameObjectSpawned event) {
        GameObject obj = event.getGameObject();
        if (obj == null || !isYamaPresent()) {
            return;
        }

        int id = obj.getId();

        // In discovery mode (empty ID set) track everything; otherwise only glyphs
        if (GLYPH_OBJECT_IDS.isEmpty() || GLYPH_OBJECT_IDS.contains(id)) {
            glyphObjects.put(obj.getHash(), obj);
            log.info("Yama glyph spawned: id=" + id + " at " + obj.getWorldLocation());
        } else {
            log.debug("Yama GameObject spawned (not a glyph): id=" + id + " at " + obj.getWorldLocation());
        }
    }

    public void onGameObjectDespawned(GameObjectDespawned event) {
        GameObject obj = event.getGameObject();
        if (obj == null) {
            return;
        }
        glyphObjects.remove(obj.getHash());
    }

    private boolean isYamaPresent() {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == YAMA_ID) {
                return true;
            }
        }
        return false;
    }

    public Collection<GameObject> getGlyphObjects() {
        return glyphObjects.values();
    }

    public GlyphType getActiveGlyphType() {
        return activeGlyphType;
    }

    // Glyphs to highlight right now: only those matching the active elemental
    // attack. Returns empty until a fire or shadow attack projectile is seen.
    public Collection<GameObject> getActiveGlyphObjects() {
        if (activeGlyphType == GlyphType.NONE) {
            return Collections.emptyList();
        }

        int targetId = activeGlyphType == GlyphType.FIRE ? GLYPH_FIRE_ID : GLYPH_SHADOW_ID;
        List<GameObject> active = new ArrayList<>();
        for (GameObject obj : glyphObjects.values()) {
            if (obj != null && obj.getId() == targetId) {
                active.add(obj);
            }
        }
        return active;
    }

    // The safe tiles for the currently-active 3-fireball line special attack(s).
    // Empty when no fireball line is active. Recomputed every tick.
    public Set<WorldPoint> getFireballSafeTiles() {
        return fireballSafeTiles;
    }

    /**
     * Collects fireball landing tiles from graphics objects (impacts) each tick,
     * prunes expired telegraphs, and re-solves the safe tiles. Projectile-based
     * telegraphs are added earlier in onProjectileMoved.
     */
    @SuppressWarnings("deprecation")
    private void refreshFireballSpecial() {
        int now = client.getTickCount();

        for (GraphicsObject go : client.getGraphicsObjects()) {
            if (go == null) {
                continue;
            }

            int id = go.getId();
            LocalPoint lp = go.getLocation();
            WorldPoint wp = lp != null ? WorldPoint.fromLocal(client, lp) : null;

            if (wp != null && FIREBALL_GRAPHIC_IDS.contains(id)) {
                fireballTiles.merge(wp, now + FIREBALL_GRAPHIC_LINGER_TICKS, Math::max);
            }
        }

        // Drop telegraphs that have expired (an orb clears the tick after it lands).
        fireballTiles.entrySet().removeIf(e -> now >= e.getValue());

        recomputeFireballSafeTiles();
    }

    /**
     * Derives the safe tiles from the current fireball tiles using pure geometry,
     * independent of how far apart the three fireballs are spaced.
     *
     * For every pair of fireballs whose integer midpoint also carries a fireball,
     * that midpoint is the centre of a genuine 3-in-a-line cast. The line must be
     * axis-aligned or a perfect diagonal, and the arms within a sane radius, so two
     * unrelated fireballs never pair up. With the wide spacing seen in the logs the
     * gap tiles are technically safe too, but only the nearest two matter, so we
     * emit just the two tiles immediately either side of the centre, perpendicular
     * to the line: horizontal -> N/S, vertical -> E/W, NW-SE diagonal -> NE/SW
     * (perp of a unit step (ux, uy) is (-uy, ux)).
     *
     * If more than one line is momentarily active (a previous cast's fireballs
     * lingering into the next), only the pair for the cast whose centre is nearest
     * the player is shown, since the centre fireball always lands on the player.
     * The player position is used solely to pick the relevant line, never to
     * compute the safe tiles themselves.
     */
    private void recomputeFireballSafeTiles() {
        fireballSafeTiles.clear();
        if (fireballTiles.size() < 3) {
            return;
        }

        List<WorldPoint> centres = new ArrayList<>();
        List<WorldPoint[]> safePairs = new ArrayList<>();

        List<WorldPoint> tiles = new ArrayList<>(fireballTiles.keySet());
        int count = tiles.size();
        for (int i = 0; i < count; i++) {
            WorldPoint a = tiles.get(i);
            for (int j = i + 1; j < count; j++) {
                WorldPoint b = tiles.get(j);
                if (a.getPlane() != b.getPlane()) {
                    continue;
                }

                int dx = b.getX() - a.getX();
                int dy = b.getY() - a.getY();

                // The two arms must be an even distance apart so their midpoint (the
                // centre fireball) lands on a tile, and form a straight or perfect
                // diagonal line.
                if ((dx & 1) != 0 || (dy & 1) != 0) {
                    continue;
                }
                boolean straight = (dx == 0) ^ (dy == 0);
                boolean diagonal = dx != 0 && Math.abs(dx) == Math.abs(dy);
                if (!straight && !diagonal) {
                    continue;
                }

                int halfX = dx / 2;
                int halfY = dy / 2;
                int radius = Math.max(Math.abs(halfX), Math.abs(halfY));
                if (radius < 1 || radius > FIREBALL_MAX_ARM_RADIUS) {
                    continue;
                }

                WorldPoint centre = new WorldPoint(a.getX() + halfX, a.getY() + halfY, a.getPlane());
                if (!fireballTiles.containsKey(centre)) {
                    // A real cast always drops a centre fireball on the player; its
                    // absence means these two are from different casts.
                    continue;
                }

                // The nearest two safe tiles: one unit step either side of the
                // centre, perpendicular to the line direction.
                int px = -Integer.signum(halfY);
                int py = Integer.signum(halfX);
                WorldPoint safeA = new WorldPoint(centre.getX() + px, centre.getY() + py, centre.getPlane());
                WorldPoint safeB = new WorldPoint(centre.getX() - px, centre.getY() - py, centre.getPlane());
                centres.add(centre);
                safePairs.add(new WorldPoint[] { safeA, safeB });
            }
        }

        if (centres.isEmpty()) {
            return;
        }

        // Keep only the line whose centre is closest to the player.
        int chosen = 0;
        WorldPoint playerTile = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getWorldLocation()
                : null;
        if (playerTile != null) {
            int best = Integer.MAX_VALUE;
            for (int k = 0; k < centres.size(); k++) {
                WorldPoint c = centres.get(k);
                int dist = c.getPlane() == playerTile.getPlane()
                        ? Math.max(Math.abs(c.getX() - playerTile.getX()), Math.abs(c.getY() - playerTile.getY()))
                        : Integer.MAX_VALUE;
                if (dist < best) {
                    best = dist;
                    chosen = k;
                }
            }
        }

        for (WorldPoint safe : safePairs.get(chosen)) {
            if (!fireballTiles.containsKey(safe)) {
                fireballSafeTiles.add(safe);
            }
        }
    }

    // Helper methods
    private boolean isAttackAnimation(int animationId) {
        return animationId == ANIMATION_MELEE ||
                animationId == ANIMATION_ORB_ATTACK ||
                animationId == ANIMATION_MAGE;
    }

    private boolean isAttackGraphic(int graphicId) {
        return graphicId == GRAPHIC_MAGE ||
                graphicId == GRAPHIC_RANGE ||
                graphicId == GRAPHIC_GLYPH_ATTACK;
    }

    public boolean isYamaInEnragePhase(int npcIndex) {
        Integer transitionCount = phaseTransitionCounts.get(npcIndex);
        return transitionCount != null && transitionCount >= 2;
    }

    private int getAttackCycleTicks(int npcIndex) {
        return isYamaInEnragePhase(npcIndex) ? ENRAGE_ATTACK_CYCLE_TICKS : ATTACK_CYCLE_TICKS;
    }

    public YamaPhase getYamaPhase(int npcIndex) {
        return yamaPhases.getOrDefault(npcIndex, YamaPhase.UNKNOWN);
    }

    public int getYamaAttackTimer(int npcIndex) {
        return yamaAttackTimers.getOrDefault(npcIndex, ATTACK_CYCLE_TICKS);
    }

    public List<NPC> getYamaNpcs() {
        List<NPC> yamas = new ArrayList<>();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == YAMA_ID) {
                yamas.add(npc);
            }
        }
        return yamas;
    }

    // Yama combat phases
    public enum YamaPhase {
        MAGE(new Color(100, 149, 237)), // Soft blue
        RANGE(new Color(144, 238, 144)), // Soft green
        FIRE_SPECIAL(new Color(255, 200, 100, 50)), // Lighter soft orange, more transparent
        SHADOW_SPECIAL(new Color(180, 150, 240, 50)), // Lighter soft purple, more transparent
        MELEE(new Color(240, 100, 100, 120)), // Soft red
        UNKNOWN(Color.GRAY);

        private final Color color;

        YamaPhase(Color color) {
            this.color = color;
        }

        public Color getColor() {
            return color;
        }
    }
}
