package com.pvmkits.bosses.mokhaiotl;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.core.BossHandler;
import com.pvmkits.core.SoundPlayer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Projectile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
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
 * left as provisional sets.
 *
 * The handler tracks a small state machine (delve/depth level plus the current
 * fight phase) and feeds three overlays:
 * <ul>
 * <li>a dedicated prayer-book outline system driven by a queue of scheduled
 * prayer events (one per incoming projectile);</li>
 * <li>a scene overlay for larvae true tiles, boulder landing tiles, the shield /
 * melee-punish 5x5 boss tile, the car-phase dash path and its boulder safe
 * tiles, and the countdown to the slam that follows a dash;</li>
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

    // ---- Confirmed animation IDs (DOM_*) ----
    // Windup of the slam ("shockwave") that follows each dash, and the earliest the
    // attack can be seen: the hit lands a fixed 6 ticks later. Each dash of a car
    // phase gets its own slam, so this fires once per dash rather than once a phase.
    static final int ANIM_BURROWED_EXPLOSION = 12419; // DOM_BURROWED_EXPLOSION

    // Ground graphics the post-dash shockwave paints its hit area with. 3374 is the
    // burrowed explosion's own AoE marker; VFX_AREA_SLAM_01-03 are the three tile
    // variants the slam tiles the area out with. One object is spawned per covered
    // tile, so the whole set present on a tick is the shockwave's footprint.
    static final Set<Integer> SHOCKWAVE_AOE_GRAPHIC_IDS = Set.of(
            3374, // VFX_DOM_BURROWED_EXPLOSION_AOE
            3405, // VFX_AREA_SLAM_01
            3406, // VFX_AREA_SLAM_02
            3407); // VFX_AREA_SLAM_03

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

    // Client game cycles per game tick (600ms / 20ms).
    static final int CYCLES_PER_GAME_TICK = 30;

    // How long a boulder landing tile stays highlighted after the projectile lands.
    private static final int BOULDER_TILE_HOLD_TICKS = 2;
    // How long the car eye / dash path is held after the last telegraph graphic.
    private static final int CAR_TELEGRAPH_HOLD_TICKS = 1;
    // Ticks the boss must be absent before a reappearance counts as a new delve.
    private static final int DELVE_GAP_TICKS = 3;
    // Radius (in tiles) around the player searched for car-phase safe tiles.
    private static final int CAR_SAFE_SEARCH_RADIUS = 5;

    // Landed-rock ground object (stays after a rock throw) used for car-phase
    // line-of-sight safe tiles. 57286 alone, which is the set this has always used
    // and is confirmed twice over: its spawn tiles in client.log land on the
    // rock-throw projectiles' own target tiles (57286 at 13409,3744 and 13413,3749
    // against targets 3367@13409,3744 and 3394@13413,3749). The arena's other
    // objects in this id range are not rocks - 57285 sits on the exact arena centre
    // every delve, under the boss - so adding them would only invent safe tiles.
    static final Set<Integer> BOULDER_OBJECT_IDS = Set.of(57286);

    // Acid pool game object. It lands from the boss's acid attack and then sits on
    // the floor for far longer than its impact graphic plays, which is why the
    // car-phase safe tiles track the object and not the graphic.
    static final Set<Integer> ACID_OBJECT_IDS = Set.of(57283);

    // ---- Post-dash slam ("shockwave") countdown ----
    // The boss is 5x5, so its footprint reaches this far from its centre tile.
    static final int BOSS_HALF_SIZE = 2;
    // Ticks from the slam windup animation to the hit landing. Measured at a
    // consistent 6 across every slam in the logs (anim 10898 -> tiles 10904, etc.).
    private static final int SLAM_IMPACT_DELAY_TICKS = 6;
    // How long the countdown stays up after the hit lands, so the 0 is still
    // readable for the tick or two the game's own graphics linger.
    private static final int SLAM_TIMER_HOLD_TICKS = 2;

    // ---- First-dash eye candidates ----
    // Tiles from the boss's spawn centre to the tile its first car-phase dash eye
    // is marked on. Measured off the dashes in client.log: the boss always moves
    // along one of the eight compass rays (4 tiles per tick, then a remainder) and
    // lands exactly on the eye. 10 is the most common measured length - 7 of the 12
    // dashes, with 8, 9, 11 and 12 making up the rest - so the mark sits at that
    // modal length.
    static final int CAR_EYE_DASH_DISTANCE = 10;

    // VarPlayers holding the delve depth. DOM_CURRENT_LEVEL_TEMP is the live level
    // inside a delve; DOM_LAST_DELVE_LEVEL is the fallback for when it reads 0.
    private static final int VARP_DOM_CURRENT_LEVEL = 4828; // DOM_CURRENT_LEVEL_TEMP
    private static final int VARP_DOM_LAST_DELVE_LEVEL = 4798; // DOM_LAST_DELVE_LEVEL
    // Upper bound used to reject junk varp reads (delves are unbounded in practice).
    private static final int MAX_PLAUSIBLE_DELVE = 100;


    // The earthen shield ("orb") footprint size.
    static final int ORB_SIZE = 3;
    // Shortest orb path worth walking: the two statues of a pair must be at least
    // this many tiles apart (inclusive) for the pair to be highlighted.
    private static final int MIN_STATUE_PAIR_DISTANCE = 13;
    // Fallback distance used when no pair is MIN_STATUE_PAIR_DISTANCE apart. A
    // shorter orb path is still a usable path, and is far better than drawing
    // nothing at all (which also used to take the shockwave countdown with it).
    private static final int FALLBACK_STATUE_PAIR_DISTANCE = 8;
    // Progressive relaxations tried in order until a pair is found:
    // {maximum off-axis offset in tiles, minimum separation in tiles}. The first
    // tier is the strict "in line, 13+ apart" rule; later tiers allow a shorter
    // walk and then a one-tile dogleg, which some layouts (seen after a car phase
    // on deeper delves) only ever satisfy.
    private static final int[][] STATUE_PAIR_TIERS = {
            { 0, MIN_STATUE_PAIR_DISTANCE },
            { 0, FALLBACK_STATUE_PAIR_DISTANCE },
            { 1, FALLBACK_STATUE_PAIR_DISTANCE },
    };
    // Shortest separation the last-resort pick will accept. Below this the orb
    // barely travels, so it is not a shield the player can get behind.
    private static final int LAST_RESORT_PAIR_DISTANCE = 4;
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
    // Rock-throw shards already tested against the player's tile, so each shard is
    // tested once when it spawns rather than on every one of its move events.
    private final Set<Projectile> testedShardProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());

    // Boulder shatter / rock-throw landing tiles -> expiry game tick.
    private final Map<WorldPoint, Integer> boulderTiles = new HashMap<>();
    // The subset of those tiles that were under the player at the moment the boulder
    // shattered. One shard of every shatter is aimed at wherever the player is
    // standing then, so that tile is the one the player is forced off.
    private final Map<WorldPoint, Integer> playerLockedBoulderTiles = new HashMap<>();

    // Post-dash slam: the tick its hit lands on and where it is centred, both set
    // only by the windup animation, which carries the exact figure. Deliberately
    // kept out of the car-phase state below: the boss unburrows partway through the
    // countdown, which ends the car phase while the warning still has to be up.
    private int slamImpactTick = -1;
    private WorldPoint slamCentre;

    // Car-phase dash: the eye tile the boss dashes to and the corridor it tramples.
    private WorldPoint carEyeTile;
    private int carTelegraphUntilTick = -1;
    private final Set<WorldPoint> dashPathTiles = new HashSet<>();
    // Tiles a boulder shields from the boss, so the dash cannot reach the player
    // standing on them.
    private final Set<WorldPoint> carSafeTiles = new HashSet<>();
    // Tracked boulder ground objects (hash -> tile) for the LoS safe-tile check.
    private final Map<Long, WorldPoint> boulderObjects = new HashMap<>();
    // Tracked acid pool objects (hash -> tile), so the car-phase safe tiles can
    // exclude the tiles acid is currently sitting on.
    private final Map<Long, WorldPoint> acidObjects = new HashMap<>();

    // Candidate tiles for the first car-phase dash's eye, marked once when the boss
    // spawns for a delve and then left alone until the next one. The delve they were
    // locked in for is what makes that "once": a new delve re-marks them, every tick
    // in between finds them already up.
    private final Set<WorldPoint> eyeSpawnTiles = new HashSet<>();
    private int eyeSpawnLockedDelve = -1;
    private int eyeSpawnTick = -1;

    // Bundled alert looped while the melee-punish charge is up (src/main/resources).
    private static final String MELEE_PUNISH_SOUND = "/com/pvmkits/sounds/melee_punish.wav";

    // Melee-punish charge state, tracked for as long as the charge is up (the same
    // window the red 5x5 tile is drawn) and cleared the tick the player interrupts
    // it, so the overlay follows the charge rather than a single rising edge.
    private boolean meleePunishActive;

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
    private int statueShockwaveExpiryTick = -1;
    // Last direction tiles worked out while both statues of the pair were in the
    // scene. Kept so the 3 marks outlive the statue NPCs and stay up for exactly as
    // long as the shockwave countdown does, instead of blinking out early when a
    // statue is destroyed or despawns.
    private Set<WorldPoint> lastStatueDirectionTiles = Collections.emptySet();

    private boolean statuesPresentLastTick = false;

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
        Actor actor = event.getActor();
        if (!(actor instanceof NPC)) {
            return;
        }
        NPC npc = (NPC) actor;
        if (!BOSS_IDS.contains(npc.getId()) && !LARVA_IDS.contains(npc.getId())) {
            return;
        }
        int anim = npc.getAnimation();

        // The post-dash slam windup, which carries the exact impact tick. Taken from
        // the animation rather than polled on the tick because it can start and
        // finish between game ticks.
        if (npc.getId() == BOSS_BURROWED && anim == ANIM_BURROWED_EXPLOSION) {
            armSlam(npc, client.getTickCount() + SLAM_IMPACT_DELAY_TICKS);
        }
    }

    @Override
    public void onGraphicChanged(GraphicChanged event) {
        // Boss spotanims are read on the tick instead (see updatePhase).
    }

    @Override
    public void onProjectileMoved(ProjectileMoved event) {
        Projectile projectile = event.getProjectile();
        if (projectile == null) {
            return;
        }
        int id = projectile.getId();

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
                int expiry = impactTick + BOULDER_TILE_HOLD_TICKS;
                boulderTiles.merge(landing, expiry, Math::max);

                // Flag the one shard of the shatter that is aimed at the tile the
                // player is standing on right now. Tested once, on the shard's first
                // move event, so a player who later walks onto another shard's tile
                // does not turn that one into the forced-move tile too.
                if (local != null && testedShardProjectiles.add(projectile)
                        && landing.equals(local.getWorldLocation())) {
                    playerLockedBoulderTiles.merge(landing, expiry, Math::max);
                }
            }
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
                boulderObjects.put(obj.getHash(), WorldPoint.fromLocal(client, lp));
            }
        }
        if (ACID_OBJECT_IDS.contains(id)) {
            LocalPoint lp = obj.getLocalLocation();
            if (lp != null) {
                acidObjects.put(obj.getHash(), WorldPoint.fromLocal(client, lp));
            }
        }
    }

    public void onGameObjectDespawned(GameObjectDespawned event) {
        GameObject obj = event.getGameObject();
        if (obj == null) {
            return;
        }
        int id = obj.getId();
        if (BOULDER_OBJECT_IDS.contains(id)) {
            boulderObjects.remove(obj.getHash());
        }
        if (ACID_OBJECT_IDS.contains(id)) {
            acidObjects.remove(obj.getHash());
        }
    }

    @Override
    public void onGameTick(GameTick event) {
        if (client.getGameState() != GameState.LOGGED_IN) {
            // Nothing below runs to clear it, so make sure a charge that was up when
            // the player logged out / hopped does not keep looping its alert.
            setMeleePunishActive(false);
            return;
        }

        int currentTick = client.getTickCount();
        NPC boss = findBoss();

        updateDepthTracking(boss, currentTick);
        updatePhase(boss);
        pruneExpiredState(currentTick);
        // After updatePhase, so a burrowed boss can be recognised and skipped, and
        // after updateDepthTracking, so the delve the marks are locked for is current.
        updateEyeSpawnCandidates(boss, currentTick);
        updateCarPhase(boss, currentTick);
        updateStatuePhase(currentTick);
    }

    // Centre tile of the boss's 5x5 footprint (its WorldLocation is the SW corner).
    private WorldPoint bossCentre(NPC boss) {
        if (boss == null) {
            return null;
        }
        net.runelite.api.coords.WorldArea area = boss.getWorldArea();
        if (area == null) {
            return boss.getWorldLocation();
        }
        return new WorldPoint(area.getX() + area.getWidth() / 2,
                area.getY() + area.getHeight() / 2, area.getPlane());
    }

    // ------------------------------------------------------------------
    // State machine
    // ------------------------------------------------------------------

    private void updateDepthTracking(NPC boss, int currentTick) {
        // The varp is the absolute delve number, which the count-the-respawns
        // heuristic below cannot be (entering at delve 5 would still read as 1), so
        // prefer it whenever it gives a plausible value.
        // NOTE: The varp tracks completed delves (0 on delve 1), so add 1 for the actual level.
        int varpLevel = readDelveLevelVarp();
        if (varpLevel > 0) {
            int actualDelve = varpLevel + 1;
            if (actualDelve != depthLevel) {
                log.info("Mokhaiotl: delve/depth level {} (varp read {})", actualDelve, varpLevel);
            }
            depthLevel = actualDelve;
            bossSeenEver = bossSeenEver || boss != null;
            if (boss != null) {
                lastBossSeenTick = currentTick;
            }
            return;
        }

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

    // The delve depth from the game's own varps, or -1 when neither reads sanely.
    private int readDelveLevelVarp() {
        int level = client.getVarpValue(VARP_DOM_CURRENT_LEVEL);
        // Do not fallback to LAST_DELVE_LEVEL when level is 0, because 0 means delve 1.
        // A fallback here would incorrectly pull the previous run's depth into a new run.
        return level > 0 && level <= MAX_PLAUSIBLE_DELVE ? level : -1;
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

    // Tracks the melee-punish charge and keeps the audio cue looping for the whole
    // of it: started on the rising edge and stopped as soon as the charge ends (the
    // player's melee hit interrupts it), so the alert lasts exactly as long as the
    // red boss tile is shown. Calling loop() every tick is a no-op while it plays.
    private void setMeleePunishActive(boolean active) {
        meleePunishActive = active;
        if (!active || !config.mokhaiotlMeleePunishSound()) {
            SoundPlayer.stopLoop(MELEE_PUNISH_SOUND);
            return;
        }
        SoundPlayer.loop(MELEE_PUNISH_SOUND, config.mokhaiotlMeleePunishSoundVolume());
    }

    private void pruneExpiredState(int currentTick) {
        // Actual damage lands ~2 ticks after the calculated impactTick, so keep events
        // alive until currentTick > impactTick + 2 to match the overlay's +1 condition
        // and correctly cycle through back-to-back prayers on consecutive ticks.
        prayerEvents.removeIf(e -> e.impactTick + 2 < currentTick);
        boulderTiles.entrySet().removeIf(en -> en.getValue() < currentTick);
        playerLockedBoulderTiles.entrySet().removeIf(en -> en.getValue() < currentTick);
        destroyedStatueTiles.entrySet().removeIf(en -> en.getValue() < currentTick);

        if (statueShockwaveExpiryTick >= 0 && currentTick > statueShockwaveExpiryTick) {
            statueShockwaveExpiryTick = -1;
        }

        // Drop the slam countdown a couple of ticks after the hit has landed. Each
        // dash arms it again, so a later slam simply replaces it.
        if (slamImpactTick >= 0 && currentTick > slamImpactTick + SLAM_TIMER_HOLD_TICKS) {
            clearSlam();
        }
    }

    // ------------------------------------------------------------------
    // Post-dash slam countdown
    // ------------------------------------------------------------------

    // Start the countdown from the windup animation, the one source of an exact
    // impact tick, anchored on the boss's centre tile now the dash has landed it
    // there. The hit itself covers the whole arena, so only the timing is drawn.
    private void armSlam(NPC boss, int impactTick) {
        WorldPoint centre = bossCentre(boss);
        if (centre == null) {
            return;
        }
        slamImpactTick = impactTick;
        slamCentre = centre;
    }

    private void clearSlam() {
        slamCentre = null;
        slamImpactTick = -1;
    }

    // ------------------------------------------------------------------
    // Car (dash) phase
    // ------------------------------------------------------------------

    private void updateCarPhase(NPC boss, int currentTick) {
        if (phase != Phase.CAR || boss == null) {
            resetCarPhaseState();
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
        // Only ever one of the two on screen. While a dash is telegraphed the path
        // is the whole answer - it says exactly which tiles are about to be hit - so
        // the safe tiles would only clutter it. They come back the moment it clears,
        // which is the window they are actually acted on: picking where to stand
        // before the next dash is telegraphed.
        if (dashPathTiles.isEmpty()) {
            rebuildCarSafeTiles(boss);
        } else {
            carSafeTiles.clear();
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
        Set<WorldPoint> occupied = occupiedTiles(boulderTileSet);

        for (int dx = -CAR_SAFE_SEARCH_RADIUS; dx <= CAR_SAFE_SEARCH_RADIUS; dx++) {
            for (int dy = -CAR_SAFE_SEARCH_RADIUS; dy <= CAR_SAFE_SEARCH_RADIUS; dy++) {
                WorldPoint candidate = new WorldPoint(playerLoc.getX() + dx, playerLoc.getY() + dy,
                        playerLoc.getPlane());
                if (boulderTileSet.contains(candidate)) {
                    continue;
                }
                // Acid (and any other floor effect) makes the tile unstandable
                // even where a boulder shields it from the dash.
                if (occupied.contains(candidate)) {
                    continue;
                }
                // The search box is a square around the player, so near the edge of
                // the arena it runs into the walls and the void beyond them. Those
                // tiles are shielded from the boss like any other, so without this
                // they would be marked safe to stand on.
                if (!isWalkable(candidate)) {
                    continue;
                }
                if (lineBlockedByBoulder(bossCentre, candidate, boulderTileSet)) {
                    carSafeTiles.add(candidate);
                }
            }
        }
    }

    // True if the player could actually stand on this tile. Read off the collision
    // map, so it rules out the arena walls and the void outside them in one check
    // without having to hardcode the arena's shape: BLOCK_MOVEMENT_FLOOR is the
    // void (no floor at all), and the object / floor-decoration flags are the walls
    // and the scenery standing on a tile. A tile outside the loaded scene is not
    // somewhere the player can be either, so that counts as unwalkable too.
    private boolean isWalkable(WorldPoint tile) {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) {
            return false;
        }
        CollisionData[] maps = wv.getCollisionMaps();
        int plane = wv.getPlane();
        if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null) {
            // No collision data to judge by - leave the tile in rather than blanking
            // the overlay, since the rest of the checks have already vetted it.
            return true;
        }
        // Null here already means the tile is outside the loaded scene.
        LocalPoint lp = LocalPoint.fromWorld(wv, tile);
        if (lp == null) {
            return false;
        }
        int[][] flags = maps[plane].getFlags();
        int sceneX = lp.getSceneX();
        int sceneY = lp.getSceneY();
        if (flags == null || sceneX < 0 || sceneX >= flags.length
                || sceneY < 0 || sceneY >= flags[sceneX].length) {
            return false;
        }
        int blocked = CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
                | CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
                | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION;
        return (flags[sceneX][sceneY] & blocked) == 0;
    }

    // Tiles that are not clean to stand on: the acid pools sitting on the floor,
    // plus whatever ground animation is currently playing. The acid is tracked as
    // a game object because it outlasts its own impact graphic by many ticks - the
    // graphic alone would clear the tile while the pool is still there.
    // The graphics pass stays id-blind: anything playing on a tile disqualifies it.
    // Only live graphics objects are read, never the scene's own objects - the
    // arena floor is scenery, so that would exclude every tile in the room.
    // The boulders are passed in and kept out of the set: they are what makes the
    // tiles behind them safe, and the caller already excludes standing on one.
    private Set<WorldPoint> occupiedTiles(Set<WorldPoint> boulderTileSet) {
        Set<WorldPoint> occupied = new HashSet<>(acidObjects.values());
        for (GraphicsObject go : client.getGraphicsObjects()) {
            if (go == null || go.finished()) {
                continue;
            }
            LocalPoint lp = go.getLocation();
            if (lp == null) {
                continue;
            }
            WorldPoint loc = WorldPoint.fromLocal(client, lp);
            if (!boulderTileSet.contains(loc)) {
                occupied.add(loc);
            }
        }
        return occupied;
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
    // First-dash eye candidates
    // ------------------------------------------------------------------

    /**
     * Mark where the delve's first car-phase dash could send its eye, once, on the
     * tick the boss first appears for that delve. The tiles stay put for the rest of
     * the delve and are re-marked for the next one.
     *
     * <p>Only the four cardinal rays are marked, at
     * {@link #CAR_EYE_DASH_DISTANCE} tiles. The dash does also go diagonally - see
     * {@link #cardinalEyeCandidates} - so these are four of the eight tiles it
     * picks from.
     */
    private void updateEyeSpawnCandidates(NPC boss, int currentTick) {
        if (boss == null || depthLevel == eyeSpawnLockedDelve) {
            return;
        }
        // Never lock in off a burrowed boss: that one is mid-car-phase and so has
        // already left the centre it spawned on.
        if (boss.getId() == BOSS_BURROWED) {
            return;
        }
        WorldPoint centre = bossCentre(boss);
        if (centre == null) {
            return;
        }
        eyeSpawnLockedDelve = depthLevel;
        eyeSpawnTick = currentTick;
        eyeSpawnTiles.clear();
        eyeSpawnTiles.addAll(cardinalEyeCandidates(centre));
    }

    /**
     * The four tiles {@link #CAR_EYE_DASH_DISTANCE} north, east, south and west of
     * {@code centre}.
     *
     * <p>Measured off client.log rather than assumed: the boss dashes along one of
     * the eight compass rays and lands exactly on its eye tile, so the eye is always
     * on a ray. Of the 12 dashes measured, 6 were cardinal and 6 were pure diagonal,
     * and the length was 10 for 7 of them (the rest 8, 9, 11 and 12). So these four
     * tiles are the cardinal half of the picture, marked at that modal length.
     */
    static Set<WorldPoint> cardinalEyeCandidates(WorldPoint centre) {
        Set<WorldPoint> tiles = new HashSet<>();
        int d = CAR_EYE_DASH_DISTANCE;
        tiles.add(new WorldPoint(centre.getX(), centre.getY() + d, centre.getPlane()));
        tiles.add(new WorldPoint(centre.getX(), centre.getY() - d, centre.getPlane()));
        tiles.add(new WorldPoint(centre.getX() + d, centre.getY(), centre.getPlane()));
        tiles.add(new WorldPoint(centre.getX() - d, centre.getY(), centre.getPlane()));
        return tiles;
    }

    private void resetCarPhaseState() {
        carEyeTile = null;
        carTelegraphUntilTick = -1;
        dashPathTiles.clear();
        carSafeTiles.clear();
        // The slam countdown deliberately survives: the boss unburrows partway
        // through it, which ends the car phase while the warning still has to be up.
        // pruneExpiredState is what takes it down, a couple of ticks after the hit.
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

        dashPathTiles.addAll(sweptFootprint(new WorldPoint(cx, cy, plane), carEyeTile, half));
    }

    /**
     * Union of the boss's footprint at every step of a dash from {@code from} to
     * {@code to}, which is the area the dash tramples.
     *
     * <p>Sweeping the whole footprint rather than a line thickened perpendicular to
     * travel matters for the diagonal dashes: a diagonal perpendicular steps
     * diagonally too, so it lays down a row of disconnected diagonal lines with the
     * tiles between them missing, where the swept footprint comes out as one solid
     * jagged band. It also carries the area the {@code half} tiles past the eye that
     * the footprint reaches once the boss's centre lands on it - 2 for the 5x5 boss.
     */
    static Set<WorldPoint> sweptFootprint(WorldPoint from, WorldPoint to, int half) {
        Set<WorldPoint> tiles = new HashSet<>();
        int dx = Integer.signum(to.getX() - from.getX());
        int dy = Integer.signum(to.getY() - from.getY());
        if (dx == 0 && dy == 0) {
            return tiles;
        }
        int plane = from.getPlane();
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getY() - from.getY()));
        for (int s = 0; s <= steps; s++) {
            int bx = from.getX() + dx * s;
            int by = from.getY() + dy * s;
            for (int ox = -half; ox <= half; ox++) {
                for (int oy = -half; oy <= half; oy++) {
                    tiles.add(new WorldPoint(bx + ox, by + oy, plane));
                }
            }
        }
        return tiles;
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
            attackedStatueIndices.clear();
            attackedStatueTiles.clear();
            statueShockwaveExpiryTick = currentTick + 16;
        }

        trackDestroyedStatues(statues, currentTick);

        if (!present) {
            highlightedStatueIndices.clear();
            attackedStatueIndices.clear();
            farStatueIndex = -1;
            nearStatueIndex = -1;
            statueShockwaveExpiryTick = -1;
        } else if (highlightedStatueIndices.isEmpty()) {
            // Only until a pair is found: the choice is then locked in, so it never
            // re-picks as the player walks towards the statues. The boss cannot move
            // during this phase, so a pair clear of it at pick time stays clear.
            computeClosestStraightPair(statues, findBoss());
        }

        statuesPresentLastTick = present;
    }

    // Keep the tile of every statue the player has hit, and when one of them leaves
    // the scene leave its red marker behind for a few ticks so the player can still
    // see where it stood.
    //
    // The starting statue is deliberately excluded. It reads green while it is the
    // live target and then goes away for good, so a red "attacked" marker left at its
    // tile a few ticks later would wrongly point the player back at an endpoint they
    // are already done with. Only the destination statue leaves a red marker behind.
    private void trackDestroyedStatues(List<NPC> statues, int currentTick) {
        Set<Integer> presentIndices = new HashSet<>();
        for (NPC statue : statues) {
            presentIndices.add(statue.getIndex());
            if (attackedStatueIndices.contains(statue.getIndex())
                    && !isStartingStatue(statue)) {
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
    //
    // Lower (tighter) tiers are slightly preferred: a higher-tier pair must have its
    // near-statue at least TIER_PROXIMITY_ADVANTAGE tiles closer to the player per
    // tier step to beat a lower-tier pair. This keeps statue separation as the
    // primary factor while giving nearby but slightly relaxed pairs a chance.
    private static final int TIER_PROXIMITY_ADVANTAGE = 3;

    private void computeClosestStraightPair(List<NPC> statues, NPC boss) {
        highlightedStatueIndices.clear();

        Player local = client.getLocalPlayer();
        WorldPoint playerLoc = local == null ? null : local.getWorldLocation();

        // Throw away the statues standing on the boss before any pairing is tried, so
        // no relaxation tier can fall back onto one. Such an endpoint is unusable:
        // reaching it means standing in the 5x5 melee-punish footprint.
        WorldPoint bossCentre = bossCentre(boss);
        // Kept so the last-resort pick can reach back past this filter if it removed
        // so many statues that nothing at all is left to pair.
        List<NPC> allStatues = statues;
        if (bossCentre != null) {
            List<NPC> usable = new ArrayList<>(statues.size());
            for (NPC statue : statues) {
                if (!overlapsBossFootprint(statue, bossCentre)) {
                    usable.add(statue);
                }
            }
            statues = usable;
        }

        // Collect the best candidate from each tier so they can be compared across
        // tiers with a proximity advantage bias toward lower (tighter) tiers.
        int[] tierBestNear = new int[STATUE_PAIR_TIERS.length];
        int[] tierBestFar = new int[STATUE_PAIR_TIERS.length];
        int[] tierNearIdx = new int[STATUE_PAIR_TIERS.length];
        int[] tierFarIdx = new int[STATUE_PAIR_TIERS.length];
        for (int t = 0; t < STATUE_PAIR_TIERS.length; t++) {
            tierBestNear[t] = Integer.MAX_VALUE;
            tierBestFar[t] = Integer.MAX_VALUE;
            tierNearIdx[t] = -1;
            tierFarIdx[t] = -1;
        }

        for (int tier = 0; tier < STATUE_PAIR_TIERS.length; tier++) {
            int maxOffAxis = STATUE_PAIR_TIERS[tier][0];
            int minDistance = STATUE_PAIR_TIERS[tier][1];
            // Rank by the nearer statue of the pair, then by the further one so a tie
            // on the near end picks the shorter walk to the far end.
            for (int i = 0; i < statues.size(); i++) {
                WorldPoint a = statues.get(i).getWorldLocation();
                if (a == null) {
                    continue;
                }
                for (int j = i + 1; j < statues.size(); j++) {
                    WorldPoint b = statues.get(j).getWorldLocation();
                    if (b == null || b.getPlane() != a.getPlane()
                            || !isStraightPair(a, b, maxOffAxis, minDistance)) {
                        continue;
                    }
                    if (bossCentre != null && pathOverlapsBossFootprint(a, b, bossCentre)) {
                        continue;
                    }
                    int distA = playerLoc == null ? 0 : playerLoc.distanceTo(a);
                    int distB = playerLoc == null ? 0 : playerLoc.distanceTo(b);
                    int near = Math.min(distA, distB);
                    int far = Math.max(distA, distB);
                    if (near < tierBestNear[tier] || (near == tierBestNear[tier] && far < tierBestFar[tier])) {
                        tierBestNear[tier] = near;
                        tierBestFar[tier] = far;
                        boolean aIsNearer = distA <= distB;
                        tierNearIdx[tier] = statues.get(aIsNearer ? i : j).getIndex();
                        tierFarIdx[tier] = statues.get(aIsNearer ? j : i).getIndex();
                    }
                }
            }
        }

        // Pick across tiers: start with the lowest (tightest) tier that found a pair,
        // then let a higher tier beat it only if its near-statue is meaningfully closer
        // to the player (TIER_PROXIMITY_ADVANTAGE tiles per tier step).
        farStatueIndex = -1;
        nearStatueIndex = -1;
        int bestNear = Integer.MAX_VALUE;
        int bestFar = Integer.MAX_VALUE;
        int usedTier = -1;

        for (int tier = 0; tier < STATUE_PAIR_TIERS.length; tier++) {
            if (tierFarIdx[tier] < 0) {
                continue;
            }
            if (usedTier < 0) {
                // First tier with a pair: accept unconditionally.
                usedTier = tier;
                bestNear = tierBestNear[tier];
                bestFar = tierBestFar[tier];
                nearStatueIndex = tierNearIdx[tier];
                farStatueIndex = tierFarIdx[tier];
            } else {
                // A higher tier must save at least TIER_PROXIMITY_ADVANTAGE tiles per
                // tier step on the near-statue distance to justify the relaxation.
                int tierGap = tier - usedTier;
                int requiredAdvantage = tierGap * TIER_PROXIMITY_ADVANTAGE;
                if (tierBestNear[tier] + requiredAdvantage < bestNear
                        || (tierBestNear[tier] + requiredAdvantage == bestNear
                            && tierBestFar[tier] < bestFar)) {
                    usedTier = tier;
                    bestNear = tierBestNear[tier];
                    bestFar = tierBestFar[tier];
                    nearStatueIndex = tierNearIdx[tier];
                    farStatueIndex = tierFarIdx[tier];
                }
            }
        }

        if (farStatueIndex < 0) {
            // Last resort: no tier matched, so stop insisting on alignment entirely
            // and take the closest thing to a straight pair the layout offers.
            // An awkward orb path still beats drawing nothing, which used to leave
            // the player with no shield and no shockwave countdown at all.
            pickBestAvailablePair(statues, allStatues, bossCentre, playerLoc);
        }

        if (farStatueIndex >= 0) {
            highlightedStatueIndices.add(farStatueIndex);
            highlightedStatueIndices.add(nearStatueIndex);
        }
    }

    // Score for the last-resort pick, lower being better. Straightness dominates:
    // one tile off-axis costs more than any separation or proximity gain can win
    // back, so the pair drawn is always the most nearly axis-aligned one available,
    // then the longest, then the one starting nearest the player.
    static int lastResortPairScore(WorldPoint a, WorldPoint b, WorldPoint playerLoc) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int offAxis = Math.min(dx, dy);
        int separation = Math.max(dx, dy);
        int near = playerLoc == null ? 0 : Math.min(playerLoc.distanceTo(a), playerLoc.distanceTo(b));
        return offAxis * 1000 - separation * 10 + near;
    }

    // Pick the least-bad pair with no alignment or separation tier to satisfy.
    // Statues on the boss and orb paths running through it are still skipped while
    // any alternative remains, since those endpoints get the player killed; only if
    // that leaves nothing does the search widen to every statue in the scene.
    private void pickBestAvailablePair(List<NPC> usableStatues, List<NPC> allStatues,
            WorldPoint bossCentre, WorldPoint playerLoc) {
        // Pass 1: statues clear of the boss, avoiding paths through it.
        // Pass 2: the same statues, allowing a path through the boss.
        // Pass 3: every statue, including those on the boss.
        List<List<NPC>> passes = Arrays.asList(usableStatues, usableStatues, allStatues);
        boolean[] avoidBossPath = { true, false, false };

        for (int pass = 0; pass < passes.size(); pass++) {
            List<NPC> statues = passes.get(pass);
            boolean avoidPath = avoidBossPath[pass];
            int bestScore = Integer.MAX_VALUE;
            int bestNearIdx = -1;
            int bestFarIdx = -1;

            for (int i = 0; i < statues.size(); i++) {
                WorldPoint a = statues.get(i).getWorldLocation();
                if (a == null) {
                    continue;
                }
                for (int j = i + 1; j < statues.size(); j++) {
                    WorldPoint b = statues.get(j).getWorldLocation();
                    if (b == null || b.getPlane() != a.getPlane()) {
                        continue;
                    }
                    int separation = Math.max(Math.abs(a.getX() - b.getX()),
                            Math.abs(a.getY() - b.getY()));
                    if (separation < LAST_RESORT_PAIR_DISTANCE) {
                        continue;
                    }
                    if (avoidPath && bossCentre != null && pathOverlapsBossFootprint(a, b, bossCentre)) {
                        continue;
                    }
                    int score = lastResortPairScore(a, b, playerLoc);
                    if (score < bestScore) {
                        bestScore = score;
                        boolean aIsNearer = playerLoc == null
                                || playerLoc.distanceTo(a) <= playerLoc.distanceTo(b);
                        bestNearIdx = statues.get(aIsNearer ? i : j).getIndex();
                        bestFarIdx = statues.get(aIsNearer ? j : i).getIndex();
                    }
                }
            }

            if (bestFarIdx >= 0) {
                nearStatueIndex = bestNearIdx;
                farStatueIndex = bestFarIdx;
                return;
            }
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

    public boolean isStartingStatue(NPC npc) {
        return npc != null && npc.getIndex() == nearStatueIndex;
    }

    // Tiles of statues the player destroyed, held briefly after they despawn.
    public Set<WorldPoint> getDestroyedStatueTiles() {
        return new HashSet<>(destroyedStatueTiles.keySet());
    }

    // True when a statue's 3x3 footprint overlaps the boss's 5x5 plus a 1-tile buffer ring,
    // which makes it unusable as an orb endpoint. This prevents selecting a pair where
    // attacking the statue puts the player in melee range of the boss.
    private static boolean overlapsBossFootprint(NPC statue, WorldPoint bossCentre) {
        if (statue == null || bossCentre == null) {
            return false;
        }
        net.runelite.api.coords.WorldArea area = statue.getWorldArea();
        if (area != null) {
            return overlapsBossFootprint(new WorldPoint(area.getX(), area.getY(), area.getPlane()),
                    Math.max(area.getWidth(), area.getHeight()), bossCentre);
        }
        return overlapsBossFootprint(statue.getWorldLocation(), 1, bossCentre);
    }

    // Footprint overlap in tiles: the statue effectively acts as a 3x3 area, and the
    // boss has a 5x5 area. Compared centre to centre, with a 2-tile buffer added to
    // the boss's half-size so that attacking the statue doesn't put the player in
    // melee range. That ring is wider than the 5x5 itself: a statue merely flush
    // against the boss is still excluded, which is deliberate.
    static boolean overlapsBossFootprint(WorldPoint statueSouthWest, int size, WorldPoint bossCentre) {
        if (statueSouthWest == null || bossCentre == null
                || statueSouthWest.getPlane() != bossCentre.getPlane()) {
            return false;
        }
        
        int statueCx = statueSouthWest.getX() + size / 2;
        int statueCy = statueSouthWest.getY() + size / 2;

        int dx = Math.abs(statueCx - bossCentre.getX());
        int dy = Math.abs(statueCy - bossCentre.getY());

        return dx <= BOSS_HALF_SIZE + 2 && dy <= BOSS_HALF_SIZE + 2;
    }

    // True when the 3x3 orb path between two statues overlaps the boss's 5x5 footprint plus a 1-tile buffer ring.
    // This prevents pairing two statues if moving between them would force the player into melee range of the boss.
    static boolean pathOverlapsBossFootprint(WorldPoint a, WorldPoint b, WorldPoint bossCentre) {
        if (a == null || b == null || bossCentre == null || a.getPlane() != bossCentre.getPlane()) {
            return false;
        }

        // A statue footprint is 3x3 with 'a' and 'b' being the south-west tiles.
        // The path bounding box covers from minX to maxX + 2, and minY to maxY + 2.
        int minX = Math.min(a.getX(), b.getX());
        int maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int maxY = Math.max(a.getY(), b.getY());

        // The boss footprint is a 5x5 area, but we enforce a 1-tile buffer (7x7 total) to avoid melee range.
        // It covers X from bossX - (BOSS_HALF_SIZE + 1) to bossX + (BOSS_HALF_SIZE + 1), and similarly for Y.
        int bufferedBossHalfSize = BOSS_HALF_SIZE + 1;
        boolean overlapX = (maxX + 2 >= bossCentre.getX() - bufferedBossHalfSize) && (minX <= bossCentre.getX() + bufferedBossHalfSize);
        boolean overlapY = (maxY + 2 >= bossCentre.getY() - bufferedBossHalfSize) && (minY <= bossCentre.getY() + bufferedBossHalfSize);

        return overlapX && overlapY;
    }

    // Axis-aligned (never diagonal) and far enough apart to be worth the walk.
    static boolean isStraightPair(WorldPoint a, WorldPoint b) {
        return isStraightPair(a, b, 0, MIN_STATUE_PAIR_DISTANCE);
    }

    // As above, but with the tolerances of one relaxation tier: maxOffAxis is how
    // many tiles the shorter leg may be off a pure row/column, minDistance the
    // separation along the longer leg.
    static boolean isStraightPair(WorldPoint a, WorldPoint b, int maxOffAxis, int minDistance) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        if (Math.min(dx, dy) > maxOffAxis) {
            return false;
        }
        return Math.max(dx, dy) >= minDistance;
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

    @SuppressWarnings("deprecation") // getGraphic() is the stable spotanim read in this pinned version
    private int spotAnimId(NPC npc) {
        return npc.getGraphic();
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

    public int getEyeSpawnTick() {
        return eyeSpawnTick;
    }

    // Where the delve's first car-phase dash could send its eye, or empty before the
    // boss has spawned for a delve.
    public Set<WorldPoint> getEyeSpawnTiles() {
        return new HashSet<>(eyeSpawnTiles);
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

    // The shatter tiles that were under the player when the boulder broke apart, i.e.
    // the tiles the player is being forced to step off.
    public Set<WorldPoint> getPlayerLockedBoulderTiles() {
        return new HashSet<>(playerLockedBoulderTiles.keySet());
    }

    // Tiles currently occupied by a boulder ground object (used by the overlay to
    // keep eye-spawn marks alive only where a boulder is sitting during shield phase).
    public Set<WorldPoint> getBoulderObjectTiles() {
        return new HashSet<>(boulderObjects.values());
    }

    public WorldPoint getCarEyeTile() {
        return carEyeTile;
    }

    public Set<WorldPoint> getDashPathTiles() {
        return new HashSet<>(dashPathTiles);
    }

    // Tiles a boulder shields from the boss during a car phase.
    public Set<WorldPoint> getCarSafeTiles() {
        return new HashSet<>(carSafeTiles);
    }

    // Ticks until the pending slam lands, or -1 when none is pending. Goes to 0 on
    // the tick the hit lands and then negative through the hold ticks, so the
    // countdown stops at 0 while the number itself stays up a moment longer.
    public int getSlamTimer() {
        return slamImpactTick < 0 ? -1 : slamImpactTick - client.getTickCount();
    }

    // Boss centre tile as of the windup, used to anchor the countdown text.
    public WorldPoint getSlamCentre() {
        return slamCentre;
    }

    public int getStatueShockwaveTimer() {
        return statueShockwaveExpiryTick < 0 ? -1 : statueShockwaveExpiryTick - client.getTickCount();
    }

    // Where the slam is predicted to land while its dash is still telegraphed, or
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

    // Fallback anchor for the shockwave countdown: the volatile earth statue nearest
    // the player. The countdown is drawn on the highlighted pair normally, so without
    // this a failed pair pick would silently hide the timer as well.
    public NPC getNearestStatue() {
        Player local = client.getLocalPlayer();
        WorldPoint playerLoc = local == null ? null : local.getWorldLocation();
        NPC nearest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null || npc.getId() != STATUE_ID) {
                continue;
            }
            WorldPoint tile = npc.getWorldLocation();
            int distance = (playerLoc == null || tile == null) ? 0 : playerLoc.distanceTo(tile);
            if (nearest == null || distance < bestDistance) {
                nearest = npc;
                bestDistance = distance;
            }
        }
        return nearest;
    }

    // A volatile earth statue that is not part of the highlighted pair. Only true
    // once a pair has actually been locked in: with no pair picked every statue is
    // still a candidate, so none of them may be taken out of the menu.
    public boolean isUnhighlightedStatue(NPC npc) {
        return npc != null && npc.getId() == STATUE_ID
                && !highlightedStatueIndices.isEmpty()
                && !highlightedStatueIndices.contains(npc.getIndex());
    }

    // True while a statue pair is locked in, i.e. for exactly as long as the
    // shockwave phase is demoting the unpaired statues. Larvae are demoted over the
    // same window, so a misclick during the walk between the pair cannot pull you
    // onto a larva, and both rules stop together when the statues go away.
    public boolean isStatuePhaseActive() {
        return !highlightedStatueIndices.isEmpty();
    }

    public boolean isStatueAttacked(NPC npc) {
        return npc != null && attackedStatueIndices.contains(npc.getIndex());
    }



    // The 3 tiles off the near statue pointing along the pair, which show the way the
    // earthen shield will travel. Tied to the shockwave countdown rather than to the
    // statue NPCs: once worked out they are held for as long as the countdown runs,
    // so they and the timer go away on the same tick.
    public Set<WorldPoint> getStatueDirectionTiles() {
        // Gated on the countdown the overlay actually shows, not just on the expiry
        // tick being set: the field outlives the last drawn number by a tick, and
        // reusing getStatueShockwaveTimer() here is what makes the two end together.
        if (getStatueShockwaveTimer() < 0) {
            lastStatueDirectionTiles = Collections.emptySet();
            return Collections.emptySet();
        }
        if (nearStatueIndex < 0 || farStatueIndex < 0) {
            return lastStatueDirectionTiles;
        }
        NPC nearStatue = null;
        NPC farStatue = null;
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == STATUE_ID) {
                if (npc.getIndex() == nearStatueIndex) nearStatue = npc;
                if (npc.getIndex() == farStatueIndex) farStatue = npc;
            }
        }
        if (nearStatue == null || farStatue == null) {
            return lastStatueDirectionTiles;
        }
        WorldPoint nearLoc = nearStatue.getWorldLocation();
        WorldPoint farLoc = farStatue.getWorldLocation();
        if (nearLoc == null || farLoc == null) {
            return lastStatueDirectionTiles;
        }

        int dx = farLoc.getX() - nearLoc.getX();
        int dy = farLoc.getY() - nearLoc.getY();
        
        // Find the center of the 3x3 starting statue
        WorldPoint center = nearLoc;
        net.runelite.api.coords.WorldArea area = nearStatue.getWorldArea();
        if (area != null) {
            center = new WorldPoint(area.getX() + area.getWidth() / 2,
                    area.getY() + area.getHeight() / 2, area.getPlane());
        } else {
            // Fallback assuming 3x3
            center = nearLoc.dx(1).dy(1);
        }

        Set<WorldPoint> tiles = new HashSet<>();
        
        if (Math.abs(dx) > Math.abs(dy)) {
            if (dx > 0) {
                // East
                tiles.add(center.dx(2).dy(-1));
                tiles.add(center.dx(2).dy(0));
                tiles.add(center.dx(2).dy(1));
            } else {
                // West
                tiles.add(center.dx(-2).dy(-1));
                tiles.add(center.dx(-2).dy(0));
                tiles.add(center.dx(-2).dy(1));
            }
        } else if (dy != 0) {
            if (dy > 0) {
                // North
                tiles.add(center.dx(-1).dy(2));
                tiles.add(center.dx(0).dy(2));
                tiles.add(center.dx(1).dy(2));
            } else {
                // South
                tiles.add(center.dx(-1).dy(-2));
                tiles.add(center.dx(0).dy(-2));
                tiles.add(center.dx(1).dy(-2));
            }
        }

        // A degenerate pair (no cardinal offset at all) yields nothing; keep whatever
        // was last worked out rather than dropping the marks mid-countdown.
        if (tiles.isEmpty()) {
            return lastStatueDirectionTiles;
        }

        lastStatueDirectionTiles = tiles;
        return tiles;
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
        // Never leave the looping alert sounding once the fight state is torn down.
        SoundPlayer.stopLoop(MELEE_PUNISH_SOUND);
        prayerEvents.clear();
        scheduledProjectiles.clear();
        testedShardProjectiles.clear();
        boulderTiles.clear();
        playerLockedBoulderTiles.clear();
        resetCarPhaseState();
        clearSlam();
        boulderObjects.clear();
        acidObjects.clear();
        eyeSpawnTiles.clear();
        eyeSpawnLockedDelve = -1;
        eyeSpawnTick = -1;

        highlightedStatueIndices.clear();
        attackedStatueIndices.clear();
        attackedStatueTiles.clear();
        destroyedStatueTiles.clear();
        farStatueIndex = -1;
        nearStatueIndex = -1;
        statueShockwaveExpiryTick = -1;
        lastStatueDirectionTiles = Collections.emptySet();
        statuesPresentLastTick = false;
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
