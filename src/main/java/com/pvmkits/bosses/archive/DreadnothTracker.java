package com.pvmkits.bosses.archive;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Prayer;
import net.runelite.api.Projectile;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Dreadnoth the Jellied (boss 3, the alternative to Moia). The logging went in
 * ahead of the first fight on 2026-11-11, and the first highlights on
 * 2026-11-12, still before any fight had been logged. So only the two NPC ids
 * are known; everything else is recognised live (a projectile he fires, an
 * object that spawns on his floor) and logged, so the next log can pin it down.
 *
 * What the wiki had on 2026-11-12. The boss page gives stats and ids only. The
 * mechanics come from The Fractured Archive/Strategies, which says it is
 * mid-rewrite, so none of these are confirmed:
 * - Dreadnoth 16665: 5x5, 2,100 HP solo, 4-tick attacks, max hit 45, "Melee,
 *   Ranged". He walks about a tile every two ticks, and his auto is a melee on
 *   everything adjacent.
 * - At 85%, 65% and 45% HP: an arena-wide slam (Protect from Melee) and three
 *   ranged attacks (Protect from Missiles). Each phase adds one slam, up to
 *   three, and brings a wave of jellies.
 * - At 25%: "*glub glub*", then he jumps towards and slams on a random player,
 *   healing ~30-40 "if the player is underneath Dreadnoth", "only ... up to a
 *   maximum 5 tiles from his centre tile". A ranged shot and a jelly splatter
 *   follow at that player. He absorbs live jellies he lands on and stops his
 *   melee autos.
 * - Jelly 16666: 1x1, 61 HP, a 5-tick crush that drains prayer like a Tz-Kih.
 *   "When jellies die, and periodically through the fight, jelly will spawn on
 *   tiles", hitting for 6-8 a tick. Seven jellies spawn when he dies.
 * A raid emulator's notes (SamSjosten/OSRSPS #549, from packet captures, so
 * second-hand) add: first jump 5 ticks after the line, then every 8; a 5x5
 * shadow where he will land; landing 3 ticks on, centred on the player if
 * within 5 tiles, else 5 tiles the nearest of eight ways towards them; the jump
 * is an NPC exact move.
 *
 * Prayer timing (read by ArchivePrayerOverlay), logged in three fights
 * (2026-11-12 to 11-14: 18 slams, 54 projectiles). Each slam is anim 14777 on
 * tick S, and its hit lands on S+1: Protect from Melee blocked 15 of 15, and
 * all 3 taken with Missiles still on hit for 36-38. His three 4211s show on
 * S+2 with 3, 4 and 5-tick flights and hit on S+4, S+5 and S+6 (54 of 54 on
 * first sight + flight - 1, as with Moia's axe). The next slam of the phase
 * comes on S+6, so its hit lands the tick after the third projectile's. Only
 * the prayer on a hit's own tick counts: Melee on for S+6 took the third
 * projectile 3 times out of 4 (the fourth was a 0 roll). So the prayer is
 * Missiles on exactly the ticks his projectiles at you hit, and Melee on every
 * other tick, which also blocks his melee and the jellies' crush.
 *
 * Highlights (read by FracturedArchiveOverlay):
 * - Ranged: from the first sight of a projectile he fires at a player until it
 *   lands. Any projectile of his counts, except the 1-tick effect every boss in
 *   the raid sends back to you (2727/2729). The user's first fight (2026-11-12)
 *   showed only 4211, three at a time after each 85/65/45% crossing.
 * - No jump landing: one was predicted here until 2026-11-12, when the user had
 *   it removed. His grey true tiles (drawn by the overlay from his server tile)
 *   already show where he is coming down while his model is in the air.
 * - Floor: every object or ground object that spawns in his room after it
 *   loaded, and every ground graphic, on a walkable tile, minus the raid's
 *   known blockers and any graphic covering the whole arena.
 *
 * Log lines, all through the handler's emit so its logging toggle gates them:
 * - DREAD: his tile and target whenever either changes, with your distance to
 *   him; his HP from the HUD bar and his health bar on every change, calling out
 *   the 85/65/45/25% crossings and any heal; any hitsplat on him that is not a
 *   player's damage (heals); every projectile shown as ranged; and a JUMPED
 *   note on any server-tile move of more than a tile.
 * - JELLY: each jelly's tile, anim, target and distance to him on change; each
 *   wave of new jellies as offsets from his centre; and where each jelly was
 *   when it went (0 from him = under him, so absorbed?).
 * - PRAY: your prayer dropping 2 or more points in one tick (a jelly drain).
 * - SHAPE: each new ground graphic group and spawned object group drawn as a
 *   picture, with him, the jellies and you on it (slams and splatters).
 * - FLOOR: the ids the floor highlight is drawing, whenever that set changes.
 * - MAP: his arena, drawn once on the first tick he is seen.
 */
public class DreadnothTracker {

    static final int DREADNOTH_ID = 16665;
    static final int JELLY_ID = 16666;

    // The wiki's phase thresholds, checked against the logged crossings.
    static final int[] HP_THRESHOLDS = { 85, 65, 45, 25 };

    // His slam (see the class notes). Its three projectiles hit from 4 ticks
    // after it (18 of 18 logged slams), and the ranged countdown runs to that
    // tick from the tick after the slam: 4-3-2-1, the 1 on the first Missiles
    // tick. Within a phase that is the three Melee ticks between two volleys.
    static final int SLAM_ANIMATION_ID = 14777;
    static final int SLAM_TO_FIRST_RANGED_HIT_TICKS = 4;
    static final int RANGED_COUNTDOWN_START = 4;

    // Projectiles from a boss that are not its attacks: a 1-tick effect from
    // whatever you are hitting back to you (height 124 -> 151), logged from the
    // Guardian and Moia in every raid, so presumably your own gear.
    static final Set<Integer> NOT_HIS_PROJECTILE_IDS = Set.of(2727, 2729);
    // Objects the raid spawns in every room that are not floor hazards: 26209
    // and 29733 sit under bosses and walls (29733 x25 under the Guardian's 5x5),
    // 31588 replaced 26209 at the Bork room's start.
    static final Set<Integer> NOT_FLOOR_OBJECT_IDS = Set.of(26209, 29733, 31588);
    // A ground graphic id on more tiles than this at once covers the arena (his
    // slam), which cannot be dodged by moving, so it is not drawn.
    static final int MAX_FLOOR_GRAPHIC_TILES = 25;

    // His arena is unmeasured. The slam is said to be arena-wide, so the flood
    // reaches further than Ezekiel's 9x9.
    static final int ARENA_SEARCH_RADIUS = 20;
    // A SHAPE picture wider or taller than this is skipped rather than flooding the log.
    private static final int MAX_PICTURE_SIZE = 48;
    // A jelly at this distance or closer counts as next to you on PRAY and HIT lines.
    private static final int JELLY_REACH = 1;

    private final EzekielTracker.Emitter log;

    // True from the first tick he is seen until the raid state is reset, so
    // the jellies that outlive him are still followed.
    private boolean inRoom;
    private boolean mapLogged;
    private String lastBossState;
    private WorldPoint lastBossTile;
    private int lastHudCurrent = -1;
    private int lastHudMax = -1;
    // The health bar reads -1 whenever it is hidden, so only shown values count.
    private int lastRatio = -1;
    private int lastScale = -1;
    private double lastPercent = -1;
    private int lastPrayer = -1;
    // Jelly NPC index -> its last logged state, and the tick it was first seen.
    private final Map<Integer, String> jellyStates = new HashMap<>();
    private final Map<Integer, Integer> jellySpawnTicks = new HashMap<>();
    // The last SHAPE picture and its tick, so a group repeated on one tick is drawn once.
    private Set<WorldPoint> lastPicture = Set.of();
    private int lastPictureTick = -1;

    // ---- Highlight state, tracked whether or not logging is on ----

    // The template region he was first seen in: his room. The floor highlight
    // only runs there, since the raid goes on to other rooms after him.
    private int roomRegion = -1;
    // Client game cycle his latest projectile lands on; he shows as ranging until then.
    private int rangedUntilCycle = -1;
    // Ticks a projectile of his at you deals its damage on: Protect from
    // Missiles has to be on for exactly these, Melee for every other tick.
    private final TreeSet<Integer> missilesTicks = new TreeSet<>();
    // The first ranged hit tick each slam promises, known 2 ticks before its
    // projectiles show, so the countdown can start the tick after the slam.
    private final TreeSet<Integer> slamVolleyTicks = new TreeSet<>();
    // Objects spawned on his floor since the room loaded, by hash. Game and
    // ground objects are kept apart because their hashes are separate spaces.
    private final Map<Long, FloorObject> floorGameObjects = new HashMap<>();
    private final Map<Long, FloorObject> floorGroundObjects = new HashMap<>();
    // Ids that have spawned on his floor, so a scene reload, which re-spawns
    // everything still lying there among the room's scenery, keeps them.
    private final Set<Integer> floorObjectIds = new HashSet<>();
    // Every walkable tile with something on it, recomputed each tick.
    private Set<WorldPoint> floorTiles = Set.of();
    private String lastFloorSummary = "";

    private static final class FloorObject {
        final int id;
        final Set<WorldPoint> tiles;

        FloorObject(int id, Set<WorldPoint> tiles) {
            this.id = id;
            this.tiles = tiles;
        }
    }

    DreadnothTracker(EzekielTracker.Emitter log) {
        this.log = log;
    }

    void reset() {
        inRoom = false;
        mapLogged = false;
        lastBossState = null;
        lastBossTile = null;
        lastHudCurrent = -1;
        lastHudMax = -1;
        lastRatio = -1;
        lastScale = -1;
        lastPercent = -1;
        lastPrayer = -1;
        jellyStates.clear();
        jellySpawnTicks.clear();
        lastPicture = Set.of();
        lastPictureTick = -1;
        roomRegion = -1;
        rangedUntilCycle = -1;
        missilesTicks.clear();
        slamVolleyTicks.clear();
        floorGameObjects.clear();
        floorGroundObjects.clear();
        floorObjectIds.clear();
        floorTiles = Set.of();
        lastFloorSummary = "";
    }

    boolean isInRoom() {
        return inRoom;
    }

    // ------------------------------------------------------------------
    // Read by the overlay
    // ------------------------------------------------------------------

    // Whether one of his projectiles is in the air at this client game cycle.
    boolean isRanging(int gameCycle) {
        return gameCycle < rangedUntilCycle;
    }

    Set<WorldPoint> getFloorTiles() {
        return floorTiles;
    }

    // The protection prayer to be on right now (see the class notes), or null
    // with nothing of his up. Read at render time, so "now" is the tick a click
    // lands on. The jellies count too, so it carries on after he dies while his
    // jellies are still up.
    Prayer getPrayer(Client client) {
        NPC boss = findDreadnoth(client);
        boolean up = boss != null && !boss.isDead();
        if (!up && !(isInHisRoom(client) && anyLiveJelly(client))) {
            return null;
        }
        return prayerAt(missilesTicks, client.getTickCount());
    }

    // Missiles on a tick one of his projectiles at you hits, Melee on any other.
    static Prayer prayerAt(Set<Integer> missilesTicks, int renderTick) {
        return missilesTicks.contains(renderTick) ? Prayer.PROTECT_FROM_MISSILES : Prayer.PROTECT_FROM_MELEE;
    }

    // The number to show over Protect from Missiles right now, or 0 for none.
    // Read at render time like the prayer, so the 1 is on the tick to be on Missiles.
    int getRangedCountdown(Client client) {
        return rangedCountdownAt(missilesTicks, slamVolleyTicks, client.getTickCount());
    }

    // The countdown on render tick renderTick to the next tick Missiles goes on:
    // a tick a slam promised its projectiles would start hitting on, or the
    // first of a run of ranged hit ticks (so a lone projectile, as the 25% jump
    // is said to bring, counts down too). 4-3-2-1 with the 1 on that tick; 0
    // before that, and through the rest of a volley's Missiles ticks.
    static int rangedCountdownAt(Set<Integer> missilesTicks, Set<Integer> slamVolleyTicks, int renderTick) {
        int next = Integer.MAX_VALUE;
        for (int tick : slamVolleyTicks) {
            if (tick >= renderTick) {
                next = Math.min(next, tick);
            }
        }
        for (int tick : missilesTicks) {
            if (tick >= renderTick && !missilesTicks.contains(tick - 1)) {
                next = Math.min(next, tick);
            }
        }
        if (next == Integer.MAX_VALUE) {
            return 0;
        }
        int left = next - renderTick + 1;
        return left >= 1 && left <= RANGED_COUNTDOWN_START ? left : 0;
    }

    // The tick a projectile of his, first seen on firstSeenTick with the given
    // flight (FracturedArchiveHandler.flightTicks), deals its damage: 54 of 54
    // logged 4211s, the same rule as Moia's axe.
    static int rangedHitTick(int firstSeenTick, int flightTicks) {
        return firstSeenTick + flightTicks - 1;
    }

    private static boolean anyLiveJelly(Client client) {
        for (NPC jelly : findJellies(client)) {
            if (!jelly.isDead()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Per-tick tracking and logging
    // ------------------------------------------------------------------

    void onGameTick(Client client) {
        NPC boss = findDreadnoth(client);
        List<NPC> jellies = findJellies(client);
        if (boss != null && !inRoom) {
            inRoom = true;
            log.emit("DREAD", "Dreadnoth seen: {} size {} lvl {}", label(client, boss), size(boss),
                    boss.getCombatLevel());
        }
        if (!inRoom) {
            return;
        }
        missilesTicks.headSet(client.getTickCount()).clear();
        slamVolleyTicks.headSet(client.getTickCount()).clear();
        if (boss != null && roomRegion < 0) {
            roomRegion = FracturedArchiveHandler.templateRegion(client);
            log.emit("DREAD", "his room is template region {}", roomRegion);
        }
        if (boss != null && !mapLogged) {
            mapLogged = true;
            logArenaMap(client, boss, jellies);
        }
        logBoss(client, boss);
        if (boss != null) {
            logHp(client, boss);
        }
        logJellies(client, boss, jellies);
        logPrayer(client, jellies);
        floorTiles = computeFloorTiles(client);
    }

    private void logBoss(Client client, NPC boss) {
        if (boss == null) {
            if (lastBossState != null) {
                log.emit("DREAD", "no longer in the scene");
                lastBossState = null;
                lastBossTile = null;
            }
            return;
        }
        WorldPoint sw = boss.getWorldLocation();
        String state = "id " + boss.getId() + " @" + FracturedArchiveHandler.tile(sw)
                + " centre " + xy(centre(boss)) + " target " + label(client, boss.getInteracting());
        if (state.equals(lastBossState)) {
            return;
        }
        // A step of more than one tile in a tick is a jump (the 25% slam).
        String moved = "";
        if (lastBossTile != null && sw != null && !sw.equals(lastBossTile)) {
            int step = step(lastBossTile, sw);
            moved = step > 1 ? " JUMPED " + step + " tiles" : " moved " + step;
        }
        log.emit("DREAD", "{}{}, you {} from him, anim {}", state, moved, youDistance(client, boss), boss.getAnimation());
        lastBossState = state;
        lastBossTile = sw;
    }

    // Logs both health sources until the log shows which one tracks him: the
    // Guardian's HUD varbits (6099/6100) and his own health bar.
    private void logHp(Client client, NPC boss) {
        int current = client.getVarbitValue(FracturedArchiveHandler.HUD_BAR_CURRENT_VARBIT);
        int max = client.getVarbitValue(FracturedArchiveHandler.HUD_BAR_MAX_VARBIT);
        int ratio = boss.getHealthRatio();
        int scale = boss.getHealthScale();
        boolean barShown = ratio >= 0 && scale > 0;
        boolean hudChanged = current != lastHudCurrent || max != lastHudMax;
        boolean barChanged = barShown && (ratio != lastRatio || scale != lastScale);
        if (!hudChanged && !barChanged) {
            return;
        }

        double percent = max > 1 ? 100.0 * current / max
                : barShown ? 100.0 * ratio / scale : -1;
        StringBuilder notes = new StringBuilder();
        if (max > 1 && max == lastHudMax && lastHudCurrent >= 0 && current > lastHudCurrent) {
            notes.append(" HEAL +").append(current - lastHudCurrent);
        } else if (max <= 1 && barShown && scale == lastScale && lastRatio >= 0 && ratio > lastRatio) {
            notes.append(" HEAL (bar ").append(lastRatio).append(" -> ").append(ratio).append(')');
        }
        if (percent >= 0 && lastPercent >= 0) {
            for (int threshold : HP_THRESHOLDS) {
                if (lastPercent > threshold && percent <= threshold) {
                    notes.append(" CROSSED ").append(threshold).append('%');
                }
            }
        }
        log.emit("DREAD", "hp hud {}/{} bar {}/{} = {}%{}, anim {}, {} jellies",
                current, max, ratio, scale,
                percent < 0 ? "?" : String.format(Locale.ROOT, "%.1f", percent), notes,
                boss.getAnimation(), jellyStates.size());

        lastHudCurrent = current;
        lastHudMax = max;
        if (barShown) {
            lastRatio = ratio;
            lastScale = scale;
        }
        if (percent >= 0) {
            lastPercent = percent;
        }
    }

    private void logJellies(Client client, NPC boss, List<NPC> jellies) {
        int tick = client.getTickCount();
        WorldPoint bossCentre = boss != null ? centre(boss) : null;
        List<String> newOffsets = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (NPC jelly : jellies) {
            int index = jelly.getIndex();
            WorldPoint at = jelly.getWorldLocation();
            String state = "@" + xy(at) + " anim " + jelly.getAnimation()
                    + " target " + label(client, jelly.getInteracting())
                    + " hp " + jelly.getHealthRatio() + "/" + jelly.getHealthScale()
                    + " " + distanceTo(at, boss) + " from Dreadnoth";
            if (!jellyStates.containsKey(index)) {
                jellySpawnTicks.put(index, tick);
                newOffsets.add("#" + index + " " + offset(at, bossCentre));
            }
            if (!state.equals(jellyStates.put(index, state))) {
                changed.add("#" + index + " " + state);
            }
        }
        if (!newOffsets.isEmpty()) {
            Player player = client.getLocalPlayer();
            WorldPoint you = player != null ? player.getWorldLocation() : null;
            log.emit("JELLY", "{} new ({} live), offsets from Dreadnoth's centre {}: {}; you at {}",
                    newOffsets.size(), jellies.size(), xy(bossCentre), newOffsets, offset(you, bossCentre));
        }
        for (String line : changed) {
            log.emit("JELLY", "{}", line);
        }
    }

    // A drop of 2+ in one tick is more than any prayer drains on its own.
    private void logPrayer(Client client, List<NPC> jellies) {
        int prayer = client.getBoostedSkillLevel(Skill.PRAYER);
        if (lastPrayer >= 0 && lastPrayer - prayer >= 2) {
            log.emit("PRAY", "your prayer {} -> {} (-{}), jellies next to you {}",
                    lastPrayer, prayer, lastPrayer - prayer, jelliesNextTo(client, jellies));
        }
        lastPrayer = prayer;
    }

    // ------------------------------------------------------------------
    // Floor
    // ------------------------------------------------------------------

    void onGameObjectSpawned(Client client, GameObject object, boolean sceneLoading) {
        int id = object.getId();
        if (!isFloorCandidate(client, id, sceneLoading)) {
            return;
        }
        Set<WorldPoint> tiles = objectTiles(client, object);
        // An object filling his footprint exactly sits under him, not on the floor.
        if (tiles.size() > 1 && tiles.equals(EzekielTracker.footprint(findDreadnoth(client)))) {
            log.emit("FLOOR", "object {} covers exactly his footprint: taken as a blocker under him, not drawn", id);
            return;
        }
        floorGameObjects.put(object.getHash(), new FloorObject(id, tiles));
        floorObjectIds.add(id);
    }

    void onGameObjectDespawned(GameObject object) {
        floorGameObjects.remove(object.getHash());
    }

    void onGroundObjectSpawned(Client client, GroundObject object, boolean sceneLoading) {
        int id = object.getId();
        WorldPoint at = object.getWorldLocation();
        if (at == null || !isFloorCandidate(client, id, sceneLoading)) {
            return;
        }
        floorGroundObjects.put(object.getHash(), new FloorObject(id, Set.of(at)));
        floorObjectIds.add(id);
    }

    void onGroundObjectDespawned(GroundObject object) {
        floorGroundObjects.remove(object.getHash());
    }

    // Anything that spawns in his room after it has loaded, except the raid's
    // blockers. During a load only ids already seen on his floor count: the
    // rest is the room's scenery.
    private boolean isFloorCandidate(Client client, int id, boolean sceneLoading) {
        if (!isInHisRoom(client) || NOT_FLOOR_OBJECT_IDS.contains(id)) {
            return false;
        }
        return !sceneLoading || floorObjectIds.contains(id);
    }

    // The rebuilt scene re-spawns whatever is still lying on the floor.
    void onSceneLoading() {
        floorGameObjects.clear();
        floorGroundObjects.clear();
    }

    private boolean isInHisRoom(Client client) {
        return roomRegion >= 0 && FracturedArchiveHandler.templateRegion(client) == roomRegion;
    }

    private Set<WorldPoint> computeFloorTiles(Client client) {
        if (!isInHisRoom(client)) {
            return Set.of();
        }
        WorldView wv = client.getTopLevelWorldView();
        Set<WorldPoint> tiles = new HashSet<>();
        Set<Integer> objectIds = new TreeSet<>();
        Set<Integer> groundIds = new TreeSet<>();
        for (FloorObject object : floorGameObjects.values()) {
            tiles.addAll(object.tiles);
            objectIds.add(object.id);
        }
        for (FloorObject object : floorGroundObjects.values()) {
            tiles.addAll(object.tiles);
            groundIds.add(object.id);
        }

        Map<Integer, List<WorldPoint>> graphics = new TreeMap<>();
        for (GraphicsObject go : wv.getGraphicsObjects()) {
            LocalPoint lp = go.getLocation();
            if (go.finished() || lp == null || go.getLevel() != wv.getPlane()) {
                continue;
            }
            graphics.computeIfAbsent(go.getId(), k -> new ArrayList<>()).add(WorldPoint.fromLocal(client, lp));
        }
        Set<Integer> graphicIds = new TreeSet<>();
        Set<Integer> arenaWideIds = new TreeSet<>();
        for (Map.Entry<Integer, List<WorldPoint>> e : graphics.entrySet()) {
            if (e.getValue().size() > MAX_FLOOR_GRAPHIC_TILES) {
                arenaWideIds.add(e.getKey());
            } else {
                tiles.addAll(e.getValue());
                graphicIds.add(e.getKey());
            }
        }

        // Only tiles you could stand on: an object that blocks its tile is a
        // wall or barrier, not something to step around.
        int[][] flags = ArchiveArenaSimplifier.collisionFlags(wv);
        Set<WorldPoint> walkable = new HashSet<>();
        for (WorldPoint tile : tiles) {
            LocalPoint lp = LocalPoint.fromWorld(wv, tile);
            if (lp != null && (flags == null
                    || ArchiveArenaSimplifier.isWalkable(flags, lp.getSceneX(), lp.getSceneY()))) {
                walkable.add(tile);
            }
        }

        String summary = "objects " + objectIds + ", ground objects " + groundIds + ", graphics " + graphicIds
                + (arenaWideIds.isEmpty() ? "" : ", arena-wide graphics not drawn " + arenaWideIds);
        if (!summary.equals(lastFloorSummary)) {
            lastFloorSummary = summary;
            log.emit("FLOOR", "drawing {} tiles ({} blocked left out): {}", walkable.size(),
                    tiles.size() - walkable.size(), summary);
        }
        return walkable;
    }

    // Every tile a (possibly multi-tile) object covers.
    private static Set<WorldPoint> objectTiles(Client client, GameObject object) {
        Set<WorldPoint> tiles = new HashSet<>();
        WorldView wv = client.getTopLevelWorldView();
        Point min = object.getSceneMinLocation();
        Point max = object.getSceneMaxLocation();
        if (min == null || max == null) {
            WorldPoint at = object.getWorldLocation();
            if (at != null) {
                tiles.add(at);
            }
            return tiles;
        }
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                tiles.add(WorldPoint.fromScene(wv, x, y, object.getPlane()));
            }
        }
        return tiles;
    }

    // ------------------------------------------------------------------
    // Event hooks from the handler
    // ------------------------------------------------------------------

    // "Challenge started" opens every attempt, so nothing of his is still in the air.
    void onChallengeStarted() {
        rangedUntilCycle = -1;
        missilesTicks.clear();
        slamVolleyTicks.clear();
    }

    // His slam starts the ranged countdown: its projectiles hit from S+4.
    void onAnimation(NPC npc, int anim, int tick) {
        if (anim != SLAM_ANIMATION_ID || !isDreadnoth(npc)) {
            return;
        }
        int first = tick + SLAM_TO_FIRST_RANGED_HIT_TICKS;
        slamVolleyTicks.add(first);
        log.emit("DREAD", "slam t{}: its projectiles should hit from t{} (countdown {} from t{})",
                tick, first, RANGED_COUNTDOWN_START, first - RANGED_COUNTDOWN_START + 1);
    }

    // His projectiles at a player are his ranged attack (see the class notes).
    // Each one at you also puts Missiles on the tick it hits.
    void onProjectile(Client client, Projectile p, int tick, int flightTicks) {
        Actor source = p.getSourceActor();
        if (!(source instanceof NPC) || !isDreadnoth((NPC) source)
                || NOT_HIS_PROJECTILE_IDS.contains(p.getId()) || p.getTargetActor() instanceof NPC) {
            return;
        }
        rangedUntilCycle = Math.max(rangedUntilCycle, p.getEndCycle());
        String missiles = "";
        if (p.getTargetActor() != null && p.getTargetActor() == client.getLocalPlayer()) {
            int hit = rangedHitTick(tick, flightTicks);
            missilesTicks.add(hit);
            missiles = ", Missiles tick t" + hit;
        }
        log.emit("DREAD", "projectile {} at {}: shown as ranged for its {}t flight (lands t{}){}",
                p.getId(), label(client, p.getTargetActor()), flightTicks, tick + flightTicks, missiles);
    }

    void onNpcDespawned(Client client, NPC npc) {
        if (isDreadnoth(npc)) {
            rangedUntilCycle = -1;
            missilesTicks.clear();
            slamVolleyTicks.clear();
            return;
        }
        int index = npc.getIndex();
        if (!jellyStates.containsKey(index) || !isJelly(npc)) {
            return;
        }
        Integer spawned = jellySpawnTicks.remove(index);
        jellyStates.remove(index);
        WorldPoint at = npc.getWorldLocation();
        NPC boss = findDreadnoth(client);
        log.emit("JELLY", "#{} gone after {}t @{}, {} from Dreadnoth, last hp {}/{}, {} left",
                index, spawned != null ? client.getTickCount() - spawned : "?", xy(at), distanceTo(at, boss),
                npc.getHealthRatio(), npc.getHealthScale(), jellyStates.size());
    }

    // Heals and other hitsplats no player dealt, on him or a jelly. Player damage
    // already shows on the hp lines.
    void onHitsplatApplied(Client client, Actor actor, Hitsplat hitsplat) {
        if (!inRoom || !(actor instanceof NPC) || hitsplat.isMine() || hitsplat.isOthers()) {
            return;
        }
        NPC npc = (NPC) actor;
        if (isDreadnoth(npc) || isJelly(npc)) {
            log.emit(isDreadnoth(npc) ? "DREAD" : "JELLY", "{} took a non-player hitsplat: type {} amount {}",
                    label(client, npc), hitsplat.getHitsplatType(), hitsplat.getAmount());
        }
    }

    // Appended to the handler's HIT line: where you stood relative to him, the
    // jellies and the highlighted floor.
    String describeYou(Client client) {
        if (!inRoom) {
            return "";
        }
        NPC boss = findDreadnoth(client);
        Player player = client.getLocalPlayer();
        WorldPoint you = player != null ? player.getWorldLocation() : null;
        return ", Dreadnoth " + youDistance(client, boss)
                + ", jellies next to you " + jelliesNextTo(client, findJellies(client))
                + (you != null && floorTiles.contains(you) ? ", ON A HIGHLIGHTED FLOOR TILE" : "");
    }

    /**
     * Draws one ground graphic or spawned object group as a picture, north at the
     * top, with Dreadnoth, the jellies and you on it, so a telegraph's or a
     * splatter's exact shape can be read straight off the log.
     */
    void logShape(Client client, String kind, int id, List<WorldPoint> tiles) {
        if (!inRoom) {
            return;
        }
        Set<WorldPoint> shape = new HashSet<>();
        for (WorldPoint tile : tiles) {
            if (tile != null) {
                shape.add(tile);
            }
        }
        int tick = client.getTickCount();
        if (shape.isEmpty() || (tick == lastPictureTick && shape.equals(lastPicture))) {
            return;
        }
        lastPicture = shape;
        lastPictureTick = tick;

        Set<WorldPoint> boss = EzekielTracker.footprint(findDreadnoth(client));
        Set<WorldPoint> jellies = new HashSet<>();
        for (NPC jelly : findJellies(client)) {
            jellies.add(jelly.getWorldLocation());
        }
        Player player = client.getLocalPlayer();
        WorldPoint you = player != null ? player.getWorldLocation() : null;

        // Framed on the shape, him and you; jellies only where they fall inside it.
        List<WorldPoint> framed = new ArrayList<>(shape);
        framed.addAll(boss);
        if (you != null) {
            framed.add(you);
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (WorldPoint tile : framed) {
            minX = Math.min(minX, tile.getX() - 1);
            maxX = Math.max(maxX, tile.getX() + 1);
            minY = Math.min(minY, tile.getY() - 1);
            maxY = Math.max(maxY, tile.getY() + 1);
        }
        if (maxX - minX >= MAX_PICTURE_SIZE || maxY - minY >= MAX_PICTURE_SIZE) {
            log.emit("SHAPE", "{} {} spans more than {} tiles, not drawn", kind, id, MAX_PICTURE_SIZE);
            return;
        }
        log.emit("SHAPE", "{} {} x{}, columns x {}..{}: # {}, D Dreadnoth, J jelly, @ you "
                + "(x, j, ! = {} on Dreadnoth, a jelly, you)", kind, id, shape.size(), minX, maxX, kind, kind);
        for (String row : picture(minX, maxX, minY, maxY, shape, boss, jellies, you)) {
            log.emit("SHAPE", "{}", row);
        }
    }

    // Rows of the SHAPE picture from north to south, each led by its y.
    static List<String> picture(int minX, int maxX, int minY, int maxY, Set<WorldPoint> shape,
            Set<WorldPoint> boss, Set<WorldPoint> jellies, WorldPoint you) {
        List<String> rows = new ArrayList<>();
        int plane = shape.isEmpty() ? 0 : shape.iterator().next().getPlane();
        for (int y = maxY; y >= minY; y--) {
            StringBuilder row = new StringBuilder().append(y).append(" |");
            for (int x = minX; x <= maxX; x++) {
                WorldPoint tile = new WorldPoint(x, y, plane);
                boolean hit = shape.contains(tile);
                if (tile.equals(you)) {
                    row.append(hit ? '!' : '@');
                } else if (boss.contains(tile)) {
                    row.append(hit ? 'x' : 'D');
                } else if (jellies.contains(tile)) {
                    row.append(hit ? 'j' : 'J');
                } else {
                    row.append(hit ? '#' : '.');
                }
            }
            rows.add(row.append('|').toString());
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Arena map
    // ------------------------------------------------------------------

    // His arena as a picture, north at the top, flooded from his tile (or yours
    // if his is not walkable), so its size and walls can be read off the log.
    private void logArenaMap(Client client, NPC boss, List<NPC> jellies) {
        WorldView wv = client.getTopLevelWorldView();
        int[][] flags = ArchiveArenaSimplifier.collisionFlags(wv);
        LocalPoint start = boss.getLocalLocation();
        if (flags == null || start == null) {
            log.emit("MAP", "Dreadnoth's arena: no collision data, not drawn");
            return;
        }
        int startX = start.getSceneX();
        int startY = start.getSceneY();
        if (!ArchiveArenaSimplifier.isWalkable(flags, startX, startY)) {
            Player player = client.getLocalPlayer();
            LocalPoint lp = player != null ? player.getLocalLocation() : null;
            if (lp == null) {
                return;
            }
            startX = lp.getSceneX();
            startY = lp.getSceneY();
        }
        boolean[][] flooded = EzekielTracker.floodArena(flags, startX, startY, ARENA_SEARCH_RADIUS);

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int floor = 0;
        for (int x = 0; x < flooded.length; x++) {
            for (int y = 0; y < flooded[x].length; y++) {
                if (flooded[x][y]) {
                    floor++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (floor == 0) {
            log.emit("MAP", "Dreadnoth's arena: start tile not walkable, not drawn");
            return;
        }
        minX = Math.max(0, minX - 2);
        minY = Math.max(0, minY - 2);
        maxX = Math.min(flags.length - 1, maxX + 2);
        maxY = Math.min(flags[maxX].length - 1, maxY + 2);

        int plane = wv.getPlane();
        WorldPoint sw = WorldPoint.fromScene(wv, minX, minY, plane);
        WorldPoint ne = WorldPoint.fromScene(wv, maxX, maxY, plane);
        LocalPoint swLocal = LocalPoint.fromWorld(wv, sw);
        WorldPoint swTemplate = swLocal != null ? WorldPoint.fromLocalInstance(client, swLocal) : null;
        Set<WorldPoint> bossTiles = EzekielTracker.footprint(boss);
        Set<WorldPoint> jellyTiles = new HashSet<>();
        for (NPC jelly : jellies) {
            jellyTiles.add(jelly.getWorldLocation());
        }
        Player player = client.getLocalPlayer();
        WorldPoint you = player != null ? player.getWorldLocation() : null;

        log.emit("MAP", "Dreadnoth's arena: {} floor tiles, x {}..{} y {}..{} (template SW {}, flood radius {}). "
                        + ". floor, # blocked, o walkable outside the flood, D Dreadnoth, J jelly, @ you",
                floor, sw.getX(), ne.getX(), sw.getY(), ne.getY(), FracturedArchiveHandler.tile(swTemplate),
                ARENA_SEARCH_RADIUS);
        for (int y = maxY; y >= minY; y--) {
            StringBuilder row = new StringBuilder();
            for (int x = minX; x <= maxX; x++) {
                WorldPoint tile = WorldPoint.fromScene(wv, x, y, plane);
                char c;
                if (tile.equals(you)) {
                    c = '@';
                } else if (bossTiles.contains(tile)) {
                    c = 'D';
                } else if (jellyTiles.contains(tile)) {
                    c = 'J';
                } else if (flooded[x][y]) {
                    c = '.';
                } else {
                    c = ArchiveArenaSimplifier.isWalkable(flags, x, y) ? 'o' : '#';
                }
                row.append(c);
            }
            log.emit("MAP", "{} |{}|", WorldPoint.fromScene(wv, minX, y, plane).getY(), row);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    // Matched by name too, in case a phase swaps him or a jelly to another id.
    static boolean isDreadnoth(NPC npc) {
        return npc.getId() == DREADNOTH_ID || nameContains(npc, "dreadnoth");
    }

    static boolean isJelly(NPC npc) {
        return npc.getId() == JELLY_ID || (nameContains(npc, "jelly") && !nameContains(npc, "dreadnoth"));
    }

    private static boolean nameContains(NPC npc, String keyword) {
        String name = npc.getName();
        return name != null && Text.removeTags(name).toLowerCase(Locale.ENGLISH).contains(keyword);
    }

    static NPC findDreadnoth(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && isDreadnoth(npc)) {
                return npc;
            }
        }
        return null;
    }

    private static List<NPC> findJellies(Client client) {
        List<NPC> jellies = new ArrayList<>();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && isJelly(npc)) {
                jellies.add(npc);
            }
        }
        return jellies;
    }

    static int size(NPC npc) {
        NPCComposition composition = npc.getComposition();
        return composition != null ? composition.getSize() : 1;
    }

    private static WorldPoint centre(NPC npc) {
        WorldPoint sw = npc.getWorldLocation();
        int half = size(npc) / 2;
        return sw != null ? sw.dx(half).dy(half) : null;
    }

    // Tiles moved between two south-west tiles, on the longer axis.
    private static int step(WorldPoint from, WorldPoint to) {
        return Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getY() - from.getY()));
    }

    // Tiles between a tile and the boss's footprint on the longer axis: 0 under
    // him, 1 next to him (diagonals included).
    static int chebyshevToFootprint(WorldPoint tile, WorldPoint sw, int size) {
        int dx = Math.max(0, Math.max(sw.getX() - tile.getX(), tile.getX() - (sw.getX() + size - 1)));
        int dy = Math.max(0, Math.max(sw.getY() - tile.getY(), tile.getY() - (sw.getY() + size - 1)));
        return Math.max(dx, dy);
    }

    private static String distanceTo(WorldPoint tile, NPC boss) {
        WorldPoint sw = boss != null ? boss.getWorldLocation() : null;
        if (tile == null || sw == null) {
            return "?";
        }
        int d = chebyshevToFootprint(tile, sw, size(boss));
        return d == 0 ? "UNDER (0)" : String.valueOf(d);
    }

    private static String youDistance(Client client, NPC boss) {
        Player player = client.getLocalPlayer();
        return distanceTo(player != null ? player.getWorldLocation() : null, boss);
    }

    private static List<String> jelliesNextTo(Client client, List<NPC> jellies) {
        Player player = client.getLocalPlayer();
        WorldPoint you = player != null ? player.getWorldLocation() : null;
        List<String> near = new ArrayList<>();
        if (you == null) {
            return near;
        }
        for (NPC jelly : jellies) {
            WorldPoint at = jelly.getWorldLocation();
            if (at != null && at.getPlane() == you.getPlane()
                    && chebyshevToFootprint(at, you, 1) <= JELLY_REACH) {
                near.add("#" + jelly.getIndex() + " d" + chebyshevToFootprint(at, you, 1)
                        + " anim " + jelly.getAnimation());
            }
        }
        return near;
    }

    private static String offset(WorldPoint tile, WorldPoint origin) {
        if (tile == null || origin == null) {
            return "?";
        }
        return String.format(Locale.ROOT, "(%+d,%+d)", tile.getX() - origin.getX(), tile.getY() - origin.getY());
    }

    private static String xy(WorldPoint point) {
        return point == null ? "?" : point.getX() + "," + point.getY();
    }

    private static String label(Client client, Actor actor) {
        if (actor == null) {
            return "none";
        }
        if (actor instanceof NPC) {
            return "'" + actor.getName() + "'#" + ((NPC) actor).getIndex();
        }
        return actor == client.getLocalPlayer() ? "you" : "player '" + actor.getName() + "'";
    }
}
