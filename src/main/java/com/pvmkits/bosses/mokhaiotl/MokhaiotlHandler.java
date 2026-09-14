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
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
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
 * melee-punish 5x5 boss tile, the car-phase dash path and the post-dash slam
 * danger area;</li>
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
    // The burrowed boss only plays this while it is dashing ("car zoom").
    static final int ANIM_BURROWED_MOVEMENT = 12417; // DOM_BURROWED_MOVEMENT
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

    // Client game cycles per game tick (600ms / 20ms).
    static final int CYCLES_PER_GAME_TICK = 30;

    // How long a boulder landing tile stays highlighted after the projectile lands.
    private static final int BOULDER_TILE_HOLD_TICKS = 2;
    // How long the car eye / dash path is held after the last telegraph graphic.
    private static final int CAR_TELEGRAPH_HOLD_TICKS = 1;
    // Ticks the boss must be absent before a reappearance counts as a new delve.
    private static final int DELVE_GAP_TICKS = 3;

    // ---- Post-dash slam ("shockwave") area ----
    // The slam's hit area is a Euclidean disc centred on the boss's 5x5 centre: a
    // tile is hit iff dx*dx + dy*dy <= 250, i.e. a radius of sqrt(250) ~= 15.81
    // tiles. Measured off every unclipped slam footprint in client.log (the AoE
    // ground graphics spawn one object per covered tile): the observed max |dx| per
    // |dy| matches this threshold on all 16 rows, with no tile ever outside it.
    // Any cutoff from 250 to 255 yields the identical tile set, so 250 is used.
    static final int SLAM_RADIUS_SQ = 250;
    // Ticks from the slam windup animation to the hit landing. Measured at a
    // consistent 6 across every slam in the logs (anim 10898 -> tiles 10904, etc.).
    private static final int SLAM_IMPACT_DELAY_TICKS = 6;
    // How long to keep the area up on the boss after a dash finishes while waiting
    // for the windup animation to arrive with the impact tick. The windup follows the
    // last dash tick by 1-2, so this only has to cover the handover; it exists so a
    // windup that never arrives cannot leave the area on screen indefinitely.
    private static final int SLAM_WINDUP_WAIT_TICKS = 5;
    // How long the area stays up after the hit lands, so the impact is still
    // readable for the tick or two the game's own graphics linger.
    private static final int SLAM_AREA_HOLD_TICKS = 2;

    // VarPlayers holding the delve depth. DOM_CURRENT_LEVEL_TEMP is the live level
    // inside a delve; DOM_LAST_DELVE_LEVEL is the fallback for when it reads 0.
    private static final int VARP_DOM_CURRENT_LEVEL = 4828; // DOM_CURRENT_LEVEL_TEMP
    private static final int VARP_DOM_LAST_DELVE_LEVEL = 4798; // DOM_LAST_DELVE_LEVEL
    // Upper bound used to reject junk varp reads (delves are unbounded in practice).
    private static final int MAX_PLAUSIBLE_DELVE = 100;

    // Ticks after the volatile earth ("statues") appear until the shockwave lands.
    private static final int SHOCKWAVE_TIMER_TICKS = 20;
    // The earthen shield ("orb") footprint size.
    static final int ORB_SIZE = 3;
    // Shortest orb path worth walking: the two statues of a pair must be at least
    // this many tiles apart (inclusive) for the pair to be highlighted.
    private static final int MIN_STATUE_PAIR_DISTANCE = 14;
    // Fallback distance used when no pair is MIN_STATUE_PAIR_DISTANCE apart. A
    // shorter orb path is still a usable path, and is far better than drawing
    // nothing at all (which also used to take the shockwave countdown with it).
    private static final int FALLBACK_STATUE_PAIR_DISTANCE = 8;
    // Progressive relaxations tried in order until a pair is found:
    // {maximum off-axis offset in tiles, minimum separation in tiles}. The first
    // tier is the strict "in line, 14+ apart" rule; later tiers allow a shorter
    // walk and then a one-tile dogleg, which some layouts (seen after a car phase
    // on deeper delves) only ever satisfy.
    private static final int[][] STATUE_PAIR_TIERS = {
            { 0, MIN_STATUE_PAIR_DISTANCE },
            { 0, FALLBACK_STATUE_PAIR_DISTANCE },
            { 1, FALLBACK_STATUE_PAIR_DISTANCE },
    };
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

    // Post-dash slam: the disc of tiles the next slam will hit, frozen at the tick
    // its windup started, and the tick the hit lands on. Frozen rather than tracked
    // live because the slam comes from where the dash left the boss, and deliberately
    // kept out of the car-phase state below: the boss unburrows partway through the
    // countdown, which ends the car phase while the warning still has to be up.
    private final Set<WorldPoint> slamAreaTiles = new HashSet<>();
    // Set only by the windup animation, which carries the exact figure, so a value
    // here always means the countdown is trustworthy. -1 while the area is up on the
    // strength of a telegraph or a just-finished dash alone.
    private int slamImpactTick = -1;
    // Tick the area expires at while no impact tick is known: the window between the
    // dash finishing and the windup arriving. Keeps the area up across that handover
    // without inventing an impact tick nothing could rely on.
    private int slamAreaUntilTick = -1;
    private WorldPoint slamCentre;
    // Whether the current dash has already had its slam window armed, so the area is
    // built once when the dash path clears rather than rebuilt every tick after it.
    private boolean slamArmedThisDash;

    // The centre slamAreaTiles was last built around, so the disc is only rebuilt
    // when it actually moves rather than every tick the area is up.
    private WorldPoint slamAreaBuiltFor;

    // Car-phase dash: the eye tile the boss dashes to and the corridor it tramples.
    private WorldPoint carEyeTile;
    private int carTelegraphUntilTick = -1;
    private final Set<WorldPoint> dashPathTiles = new HashSet<>();
    private boolean carPhaseActive;
    // Whether a dash has happened at all this car phase, so no slam window is armed
    // before the phase's first dash (there is no slam coming yet).
    private boolean carZoomSeen;
    // Boss tile as of the previous tick, so a dash is still detected on the ticks its
    // animation is missed (the boss only moves while dashing).
    private WorldPoint lastBossTile;

    // Melee-punish charge state. The audio cue loops for as long as the charge is
    // up (the same window the red 5x5 tile is drawn) and stops the tick the player
    // interrupts it, so it is tracked rather than fired once on the rising edge.
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
    private int shockwaveImpactTick = -1;
    private boolean statuesPresentLastTick = false;
    // One diagnostic dump per shockwave phase when no pair can be picked at all.
    private boolean loggedNoStatuePairThisPhase = false;

    // One-shot logging guards.
    private final Set<Integer> loggedProjectileIds = new HashSet<>();
    private final Set<Integer> loggedNpcIds = new HashSet<>();
    private final Set<Integer> loggedObjectIds = new HashSet<>();
    private final Set<Integer> loggedGraphicsObjectIds = new HashSet<>();
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
        // finish between game ticks; the dash-end arming in updateCarPhase is what
        // keeps a missed one from costing the warning entirely.
        if (npc.getId() == BOSS_BURROWED && anim == ANIM_BURROWED_EXPLOSION) {
            armSlam(npc, client.getTickCount() + SLAM_IMPACT_DELAY_TICKS);
            // Also mark the dash handled, so that if this animation arrives on the
            // same tick the dash path clears, the estimate in updateCarPhase cannot
            // run afterwards and overwrite this exact impact tick with its own.
            slamArmedThisDash = true;
        }

        if (!config.mokhaiotlVerboseLogging()) {
            return;
        }
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
        if (config.mokhaiotlVerboseLogging() && isInBossArea(client) && loggedObjectIds.add(id)) {
            log.info("Mokhaiotl area object spawned: id {} at {}", id, obj.getWorldLocation());
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
        updateCarPhase(boss, currentTick);
        updateStatuePhase(currentTick);

        if (config.mokhaiotlVerboseLogging() && boss != null) {
            logChargeState(boss, currentTick);
        }
        if (config.mokhaiotlVerboseLogging()) {
            logLarvaOverheads(currentTick);
            logGraphicsObjects(currentTick);
            logShockwaveFootprint(boss, currentTick);
        }
    }

    // Full footprint of the post-dash shockwave, dumped every tick any of it is on
    // the floor. Unlike logGraphicsObjects this deliberately does not dedupe by id:
    // the shockwave spawns one graphics object per covered tile, so the whole set is
    // needed to read its radius back and to tell whether it is a square or a disc.
    // Offsets are from the boss's 5x5 centre, with the Chebyshev (square) and
    // Euclidean (circular) extents alongside so the two shapes can be told apart.
    private void logShockwaveFootprint(NPC boss, int currentTick) {
        if (!isInBossArea(client)) {
            return;
        }

        List<WorldPoint> tiles = new ArrayList<>();
        for (GraphicsObject go : client.getGraphicsObjects()) {
            if (go == null || go.finished() || !SHOCKWAVE_AOE_GRAPHIC_IDS.contains(go.getId())) {
                continue;
            }
            LocalPoint lp = go.getLocation();
            if (lp != null) {
                tiles.add(WorldPoint.fromLocal(client, lp));
            }
        }
        if (tiles.isEmpty()) {
            return;
        }

        WorldPoint centre = bossCentre(boss);
        Player player = client.getLocalPlayer();
        WorldPoint playerTile = player == null ? null : player.getWorldLocation();

        int maxCheb = 0;
        double maxEuclid = 0;
        StringBuilder offsets = new StringBuilder();
        for (WorldPoint tile : tiles) {
            if (offsets.length() > 0) {
                offsets.append(' ');
            }
            if (centre == null) {
                offsets.append('(').append(tile.getX()).append(',').append(tile.getY()).append(')');
                continue;
            }
            int dx = tile.getX() - centre.getX();
            int dy = tile.getY() - centre.getY();
            maxCheb = Math.max(maxCheb, Math.max(Math.abs(dx), Math.abs(dy)));
            maxEuclid = Math.max(maxEuclid, Math.sqrt((double) dx * dx + (double) dy * dy));
            offsets.append('(').append(dx).append(',').append(dy).append(')');
        }

        int playerCheb = centre == null || playerTile == null ? -1
                : Math.max(Math.abs(playerTile.getX() - centre.getX()),
                        Math.abs(playerTile.getY() - centre.getY()));
        log.info("Mokhaiotl SHOCKWAVE footprint tick {} phase {} tiles {} bossCentre {} player {} "
                        + "playerCheb {} maxCheb {} maxEuclid {} offsets {}",
                currentTick, phase, tiles.size(), centre, playerTile, playerCheb, maxCheb,
                String.format("%.2f", maxEuclid), offsets);
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

    // Ground graphics objects seen in the arena, logged once per id, so what the
    // safe-tile clear check is actually excluding can be read back from a fight.
    private void logGraphicsObjects(int currentTick) {
        if (!isInBossArea(client)) {
            return;
        }
        for (GraphicsObject go : client.getGraphicsObjects()) {
            if (go == null || !loggedGraphicsObjectIds.add(go.getId())) {
                continue;
            }
            LocalPoint lp = go.getLocation();
            log.info("Mokhaiotl graphics object id {} at {} at tick {}", go.getId(),
                    lp == null ? null : WorldPoint.fromLocal(client, lp), currentTick);
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
        // The varp is the absolute delve number, which the count-the-respawns
        // heuristic below cannot be (entering at delve 5 would still read as 1), so
        // prefer it whenever it gives a plausible value.
        int varpLevel = readDelveLevelVarp();
        if (varpLevel > 0) {
            if (varpLevel != depthLevel) {
                log.info("Mokhaiotl: delve/depth level {} (varp)", varpLevel);
            }
            depthLevel = varpLevel;
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
        if (level <= 0) {
            level = client.getVarpValue(VARP_DOM_LAST_DELVE_LEVEL);
        }
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
        destroyedStatueTiles.entrySet().removeIf(en -> en.getValue() < currentTick);

        // Drop the slam area a couple of ticks after the hit has landed, or when a
        // dash finished and the windup never turned up to say when that would be.
        // Each dash sets this up again, so a later slam simply replaces it.
        if (slamImpactTick >= 0) {
            if (currentTick > slamImpactTick + SLAM_AREA_HOLD_TICKS) {
                clearSlamArea();
            }
        } else if (slamAreaUntilTick >= 0 && currentTick > slamAreaUntilTick) {
            clearSlamArea();
        }
    }

    // ------------------------------------------------------------------
    // Post-dash slam area
    // ------------------------------------------------------------------

    /**
     * Re-centre the area on the boss now its dash has finished, and hold it there
     * while the windup animation is awaited. No countdown starts here: the impact
     * tick is not knowable yet, and the overlay only shows the number for the last
     * few ticks, which are always inside the window the windup has already defined.
     *
     * <p>Without this the area would blink out for the tick or two between the
     * telegraph clearing and the windup arriving.
     */
    private void holdSlamAreaForWindup(NPC boss, int currentTick) {
        WorldPoint centre = bossCentre(boss);
        if (centre == null) {
            return;
        }
        slamAreaUntilTick = currentTick + SLAM_WINDUP_WAIT_TICKS;
        setSlamArea(centre);
    }

    // Start the countdown from the windup animation, the one source of an exact
    // impact tick, re-centring on the boss in case the dash-end hold was missed.
    private void armSlam(NPC boss, int impactTick) {
        WorldPoint centre = bossCentre(boss);
        if (centre == null) {
            return;
        }
        if (config.mokhaiotlVerboseLogging()) {
            log.info("Mokhaiotl slam windup at tick {}, impact {} ({} ticks), area was {}",
                    client.getTickCount(), impactTick, impactTick - client.getTickCount(),
                    slamAreaTiles.isEmpty() ? "not up" : "already up on " + slamAreaBuiltFor);
        }
        slamImpactTick = impactTick;
        slamAreaUntilTick = -1;
        setSlamArea(centre);
    }

    /**
     * Keep the area up for a dash that is still telegraphed, centred on the eye tile
     * the boss is about to dash to. The eye always ends up stacked under the boss's
     * centre tile, so it is exactly where the slam will come from - not an estimate
     * of it - which is why the area can be drawn in full this early and does not
     * shift when {@link #holdSlamAreaForWindup} re-centres on the landed boss.
     *
     * <p>This is what gets the area on screen as early as the dash is known about,
     * rather than only once it has landed. It is the same area drawn the same way;
     * only the countdown waits for the windup.
     */
    private void updateSlamForecast() {
        // Once a dash has finished, the area belongs to the boss's landed position -
        // whether it is counting down yet or still waiting on the windup - so never
        // move it back onto an eye tile.
        if (slamImpactTick >= 0 || slamAreaUntilTick >= 0) {
            return;
        }
        if (carEyeTile == null) {
            clearSlamArea();
        } else if (!carEyeTile.equals(slamAreaBuiltFor)) {
            setSlamArea(carEyeTile);
        }
    }

    private void setSlamArea(WorldPoint centre) {
        slamCentre = centre;
        slamAreaBuiltFor = centre;
        slamAreaTiles.clear();
        slamAreaTiles.addAll(clippedSlamDisc(centre));
    }

    private void clearSlamArea() {
        slamAreaTiles.clear();
        slamCentre = null;
        slamAreaBuiltFor = null;
        slamImpactTick = -1;
        slamAreaUntilTick = -1;
    }

    // The slam disc around a centre, clipped to floor that actually exists. The disc
    // reaches well past the arena from most positions, and without the clip the
    // highlight spills over the void around it and stops reading as the arena's shape.
    private Set<WorldPoint> clippedSlamDisc(WorldPoint centre) {
        Set<WorldPoint> tiles = new HashSet<>();
        for (WorldPoint tile : slamDisc(centre)) {
            if (isWalkable(tile)) {
                tiles.add(tile);
            }
        }
        return tiles;
    }

    /**
     * Whether this is the tick to put the slam area up, given what the car phase is
     * doing. True exactly when the dash path highlight stops being drawn after a
     * dash: {@code rebuildDashPath} produces tiles only while a telegraph is up, so
     * handing over on {@code !telegraphUp} is what makes the two highlights meet with
     * no gap and no overlap.
     *
     * @param zooming      a dash is in progress, so the boss is not yet where the
     *                     slam will come from
     * @param telegraphUp  the eye telegraph is up, i.e. the dash path is on screen
     * @param dashSeen     this car phase has dashed at least once, so a slam is
     *                     actually coming
     * @param alreadyArmed this dash's slam window has already been armed, by an
     *                     earlier tick or by the windup animation
     */
    static boolean shouldArmSlamWindow(boolean zooming, boolean telegraphUp, boolean dashSeen,
            boolean alreadyArmed) {
        return !zooming && !telegraphUp && dashSeen && !alreadyArmed;
    }

    /**
     * Every tile the slam hits, unclipped: the disc {@code dx*dx + dy*dy <=}
     * {@link #SLAM_RADIUS_SQ} around {@code centre}.
     *
     * <p>A disc rather than a square, which is the whole reason this is worth
     * drawing: it reaches 15 tiles along the axes but only 11 diagonally, so the
     * corners of the arena can be out of range while its edges are not.
     */
    static Set<WorldPoint> slamDisc(WorldPoint centre) {
        Set<WorldPoint> tiles = new HashSet<>();
        int r = (int) Math.floor(Math.sqrt(SLAM_RADIUS_SQ));
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                if (dx * dx + dy * dy <= SLAM_RADIUS_SQ) {
                    tiles.add(new WorldPoint(centre.getX() + dx, centre.getY() + dy,
                            centre.getPlane()));
                }
            }
        }
        return tiles;
    }

    // In the loaded scene and not fully blocked. Used only to clip the slam disc to
    // real floor, so a tile with acid or anything else on it still counts - it is
    // part of the hit area whether or not it is somewhere worth standing.
    private boolean isWalkable(WorldPoint tile) {
        LocalPoint lp = LocalPoint.fromWorld(client, tile);
        if (lp == null || !lp.isInScene()) {
            return false;
        }
        CollisionData[] maps = client.getTopLevelWorldView().getCollisionMaps();
        if (maps == null || tile.getPlane() < 0 || tile.getPlane() >= maps.length
                || maps[tile.getPlane()] == null) {
            // No collision data to judge by, so keep the tile rather than punching a
            // hole in the area.
            return true;
        }
        int[][] flags = maps[tile.getPlane()].getFlags();
        return (flags[lp.getSceneX()][lp.getSceneY()] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
    }

    // ------------------------------------------------------------------
    // Car (dash) phase
    // ------------------------------------------------------------------

    private void updateCarPhase(NPC boss, int currentTick) {
        if (phase != Phase.CAR || boss == null) {
            resetCarPhaseState();
            return;
        }

        WorldPoint bossTile = boss.getWorldLocation();
        if (!carPhaseActive) {
            // First tick of the phase. Seeding lastBossTile here keeps the boss
            // simply being in a new spot from reading as a dash that just finished.
            carPhaseActive = true;
            carZoomSeen = false;
            slamArmedThisDash = false;
            lastBossTile = bossTile;
        }

        // Locate the eye tile from its telegraph graphic each tick it is present.
        WorldPoint eye = findCarEyeTile();
        if (eye != null) {
            carEyeTile = eye;
            carTelegraphUntilTick = currentTick + CAR_TELEGRAPH_HOLD_TICKS;
        } else if (currentTick > carTelegraphUntilTick) {
            carEyeTile = null;
        }

        // The dash animation is short enough that a tick of it can fall between game
        // ticks, so the boss having moved counts as a dash too - it only moves while
        // dashing.
        boolean moved = bossTile != null && lastBossTile != null && !bossTile.equals(lastBossTile);
        boolean zooming = boss.getAnimation() == ANIM_BURROWED_MOVEMENT || moved;
        lastBossTile = bossTile;
        if (zooming) {
            carZoomSeen = true;
            // A fresh dash is its own attack, so the next slam window arms again.
            slamArmedThisDash = false;
        }

        // Arm the slam area the moment the dash path highlight comes down, so the two
        // hand over with no gap. Gating on exactly what the dash path is drawn from -
        // a telegraph being up, or a dash in progress - is what guarantees that: the
        // tick carEyeTile goes null is the tick rebuildDashPath stops producing tiles.
        // The windup animation arrives a tick or two later and re-arms with the exact
        // impact tick, so this only has to get the area up and the countdown started.
        if (shouldArmSlamWindow(zooming, carEyeTile != null, carZoomSeen, slamArmedThisDash)) {
            slamArmedThisDash = true;
            holdSlamAreaForWindup(boss, currentTick);
        }

        updateSlamForecast();
        rebuildDashPath(boss);
    }

    private void resetCarPhaseState() {
        carEyeTile = null;
        carTelegraphUntilTick = -1;
        dashPathTiles.clear();
        carPhaseActive = false;
        carZoomSeen = false;
        slamArmedThisDash = false;
        lastBossTile = null;
        // A slam counting down, or a dash waiting on its windup, outlives the car
        // phase: the boss unburrows partway through. Only an area held for a
        // telegraph that has gone away with the phase goes here.
        if (slamImpactTick < 0 && slamAreaUntilTick < 0) {
            clearSlamArea();
        }
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
            shockwaveImpactTick = currentTick + SHOCKWAVE_TIMER_TICKS;
            attackedStatueIndices.clear();
            attackedStatueTiles.clear();
            loggedNoStatuePairThisPhase = false;
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
        int bestNear = Integer.MAX_VALUE;
        int bestFar = Integer.MAX_VALUE;
        int usedTier = -1;

        // Try the strict rule first and only relax it when nothing matches, so a
        // normal layout still picks exactly the pair it always did.
        for (int tier = 0; tier < STATUE_PAIR_TIERS.length && farStatueIndex < 0; tier++) {
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
                        usedTier = tier;
                    }
                }
            }
        }

        if (farStatueIndex >= 0) {
            highlightedStatueIndices.add(farStatueIndex);
            highlightedStatueIndices.add(nearStatueIndex);
        }

        if (!config.mokhaiotlVerboseLogging()) {
            return;
        }
        if (!previous.equals(highlightedStatueIndices) && farStatueIndex >= 0) {
            log.info("Mokhaiotl: statue pair locked in (tier {}) - far {} ({} tiles), near {} ({} tiles), of {} statues",
                    usedTier, farStatueIndex, bestFar, nearStatueIndex, bestNear, statues.size());
        } else if (farStatueIndex < 0 && !loggedNoStatuePairThisPhase) {
            // Previously this case logged nothing at all, which is why the failure was
            // invisible in client.log. Dump the layout so it can be diagnosed.
            loggedNoStatuePairThisPhase = true;
            List<String> tiles = new ArrayList<>();
            for (NPC statue : statues) {
                WorldPoint tile = statue.getWorldLocation();
                tiles.add(statue.getIndex() + "@" + (tile == null ? "null"
                        : tile.getX() + "," + tile.getY() + "," + tile.getPlane()));
            }
            log.info("Mokhaiotl: NO statue pair found among {} statues (player {}): {}",
                    statues.size(), playerLoc, tiles);
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

    // The disc the pending post-dash slam will hit, or empty when none is pending.
    public Set<WorldPoint> getSlamAreaTiles() {
        return new HashSet<>(slamAreaTiles);
    }

    // Ticks until the pending slam lands, or -1 when none is pending. Goes to 0 on
    // the tick the hit lands and then negative through the area's hold ticks, so the
    // countdown stops at 0 while the area itself stays up a moment longer.
    public int getSlamTimer() {
        return slamImpactTick < 0 ? -1 : slamImpactTick - client.getTickCount();
    }

    // Centre of the pending slam's disc, used to anchor its countdown text.
    public WorldPoint getSlamCentre() {
        return slamCentre;
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
        // Never leave the looping alert sounding once the fight state is torn down.
        SoundPlayer.stopLoop(MELEE_PUNISH_SOUND);
        prayerEvents.clear();
        scheduledProjectiles.clear();
        boulderTiles.clear();
        resetCarPhaseState();

        highlightedStatueIndices.clear();
        attackedStatueIndices.clear();
        attackedStatueTiles.clear();
        destroyedStatueTiles.clear();
        farStatueIndex = -1;
        nearStatueIndex = -1;
        shockwaveImpactTick = -1;
        statuesPresentLastTick = false;
        loggedNoStatuePairThisPhase = false;
        loggedProjectileIds.clear();
        loggedNpcIds.clear();
        loggedObjectIds.clear();
        loggedGraphicsObjectIds.clear();
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
