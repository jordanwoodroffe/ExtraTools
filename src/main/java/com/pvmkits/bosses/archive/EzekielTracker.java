package com.pvmkits.bosses.archive;

import net.runelite.api.Client;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Ezekiel, the Archive's second boss: where to stand for his four special
 * attacks. Only one short attempt has been logged (2026-10-22: 36 ticks and a
 * single sword attack before the death), so every shape is read off the live
 * scene - the slam's ground telegraph and the Animated Swords' spawn tiles -
 * rather than hardcoded. The EZEK, SWORD, SHAPE and MAP log lines exist to
 * check these rules against the next kill.
 *
 * <p>Confirmed from that attempt: Ezekiel (16652) and his Animated Swords
 * (16653) are both 3x3. Four swords spawned at the arena's north, south, west
 * and east edges (anim 14687, spotanim 4169), and each despawned 16 ticks later
 * on the start tile of the sword opposite - they fly straight across. The
 * north/south pair shared a column and the west/east pair a row, so their lanes
 * cross in one 3x3. On the same tick graphics 4170, 4171 and 4172 appeared on
 * the same 21 tiles, starting 1, 2 and 3 ticks out, and you took 29 three
 * ticks later: the slam's telegraph. His 5-tick auto attack (14685) puts
 * graphic 4394 on 13 tiles around him; it is not a special attack.
 */
public class EzekielTracker {

    /** Writes a line to the Archive log (the handler's emit, gated by its config). */
    @FunctionalInterface
    interface Emitter {
        void emit(String tag, String format, Object... args);
    }

    static final int EZEKIEL_ID = 16652;
    static final int SWORD_ID = 16653;
    static final int SWORD_SIZE = 3;

    // The slam's telegraph: three graphics on the same tiles, one after another.
    static final Set<Integer> SLAM_GRAPHIC_IDS = Set.of(4170, 4171, 4172);
    // The slam hits at +3 and again at +5 (both times you stayed on it in the
    // 15:21 kill), so its safe tiles stay up through the second hit even if the
    // graphics have finished.
    static final int SLAM_LAST_HIT_TICKS = 5;
    // The slam's ring can run right round him, so only the safe tiles within a
    // one-tile gap of where you stood as the telegraph went up are shown:
    // dx² + dy² <= 5 takes in two tiles straight, one diagonal and the L-shape
    // (2,1), but not two diagonals (2,2). With none that close, the whole ring.
    static final int SLAM_NEAR_DISTANCE_SQUARED = 5;
    // His turns come every 5 ticks: an auto attack (14685) or his special (14687:
    // the slam telegraph and the swords, all on one tick). 14688 ("Saradomin,
    // give me strength.", spotanims 4173/4174) came 4 ticks after an auto and the
    // next auto 5 after it; it is not counted as an auto.
    static final int AUTO_ATTACK_ANIM = 14685;
    static final int SPECIAL_ANIM = 14687;
    static final int STRENGTH_ANIM = 14688;
    static final int ATTACK_CYCLE_TICKS = 5;
    // In both logged fights the first special came after 2 autos and every one
    // after that after 4, always 5 ticks after the last of them.
    static final int AUTOS_BEFORE_FIRST_SPECIAL = 2;
    static final int AUTOS_BETWEEN_SPECIALS = 4;

    // Swords spawning within this many ticks of the first one belong to the same attack.
    static final int WAVE_JOIN_TICKS = 2;
    // Two swords at least this far apart sit on opposite sides of the arena rather
    // than side by side (the logged converge swords were 12 apart).
    static final int OPPOSITE_SIDE_MIN_GAP = 6;
    // How far the four-sword zone's blue side lines and green outer lines sit
    // from its edge, each with a one-tile gap inside it.
    static final int SIDE_LINE_OFFSET = 2;
    static final int OUTER_LINE_OFFSET = 4;
    // How far the floor flood may reach from where it starts, on either axis. The
    // logged swords spanned 15 tiles, so the arena fits well inside; this only
    // stops a leak through an open doorway.
    static final int ARENA_SEARCH_RADIUS = 16;
    // Tiles checked on each side of a swipe line to tell which side is the arena.
    private static final int FRONT_PROBE_TILES = 12;
    // How far into the arena the swipe's safe tiles sit, counting the tile just
    // in front of the swords as 1: the 4th, nearer the middle (asked for
    // 2026-10-23). Each sword sweeps its whole lane, so the depth changes nothing
    // about which tiles are safe.
    static final int SWIPE_SAFE_DEPTH = 4;
    // The enrage (two kills logged, 2026-10-23): walls (16654, 3x3) box you into
    // the arena's middle 3x3 next to Ezekiel, and three small swords (16655) stand
    // on his edge - one facing you, one each side. Every 11 ticks two of them
    // light up, three times a tick apart, and 4 ticks after each the floor
    // strikes all of the 3x3 but the side of the sword left dark (the back row
    // when it is the front one): 12 of 12 strikes on 2026-10-23.
    static final int ENRAGE_SWORD_ID = 16655;
    // The strikes are two graphics mixed across the struck tiles. All three of a
    // cycle's strikes are queued on its first tick, each with its own start tick,
    // and the hit lands on that tick, so their tiles are read straight off the
    // floor rather than worked out from the swords.
    static final Set<Integer> ENRAGE_STRIKE_GRAPHIC_IDS = Set.of(4197, 4198);
    // A SHAPE picture wider or taller than this is skipped rather than flooding the log.
    private static final int MAX_PICTURE_SIZE = 40;
    // All eight one-tile wall edge flags (north-west through west).
    private static final int WALL_EDGE_FLAGS = 0xFF;

    enum Formation {
        NONE,
        // Swords fly in from different sides and cross in one 3x3.
        CONVERGE,
        // Three swords in a line along one edge fire across the arena one at a time.
        SWIPE,
        // Two swords on each of two opposite sides sweep their lanes, leaving the
        // lanes between them clear.
        FOUR_SWORDS
    }

    private static final class Sword {
        final NPC npc;
        // South-west tile it spawned on.
        final WorldPoint home;
        // Tick it was first seen off its spawn tile (it fired), or -1.
        int firedTick = -1;
        // Tick its charge bar first showed, or -1.
        int barTick = -1;
        boolean gone;
        String lastLogged;

        Sword(NPC npc, WorldPoint home) {
            this.npc = npc;
            this.home = home;
        }
    }

    private final Emitter log;

    // Walkable floor flooded out from Ezekiel with walls respected, or empty until
    // it has been measured.
    private Set<WorldPoint> arenaFloor = Set.of();

    private Set<WorldPoint> slamTelegraph = Set.of();
    // The 3x3 Ezekiel stood on as the telegraph went up; he walks between slams.
    private Set<WorldPoint> slamBossTiles = Set.of();
    // The telegraph plus his 3x3: every tile the slam hits, until its last hit.
    private Set<WorldPoint> slamDanger = Set.of();
    private Set<WorldPoint> slamSafeTiles = Set.of();
    // Your tile as the current slam telegraph appeared, or null.
    private WorldPoint slamPlayerTile;
    // Tick the current slam telegraph appeared, or -1.
    private int slamStartTick = -1;
    private boolean slamGraphicsGone;

    // Ezekiel's rhythm, for seeing the next special coming: autos since the last
    // special, whether he has cast one yet this fight, and the ticks of his last
    // turn (an auto or 14688) and his last special.
    private int autosSinceSpecial;
    private boolean specialSeen;
    private int lastTurnTick = -1;
    private int lastSpecialTick = -1;

    private final List<Sword> wave = new ArrayList<>();
    private int waveStartTick = -1;
    private boolean waveChanged;
    private Formation formation = Formation.NONE;
    private WorldPoint convergeArea;
    private Set<WorldPoint> convergeLines = Set.of();
    private Set<WorldPoint> convergeSafeTiles = Set.of();
    private Set<WorldPoint> fourSwordSafeTiles = Set.of();
    private Set<WorldPoint> fourSwordSideLines = Set.of();
    private Set<WorldPoint> fourSwordOuterLines = Set.of();
    // The swipe's swords sorted along their line (south to north, or west to
    // east), which end of it fires first (0 or 2, -1 until known), and the two
    // safe tiles that order gives.
    private List<Sword> swipeLine = List.of();
    private int swipeFirstEnd = -1;
    private List<WorldPoint> swipeSafeTiles = List.of();

    // Enrage strike graphics already read, so each is counted once.
    private final Set<GraphicsObject> seenStrikeGraphics = Collections.newSetFromMap(new IdentityHashMap<>());
    // Strike tick -> the tiles of your 3x3 that strike misses, until it lands.
    private final TreeMap<Integer, Set<WorldPoint>> enrageSafeTiles = new TreeMap<>();

    // The tile set last drawn as a SHAPE picture and when, so the slam's three
    // graphics on the same tiles are drawn once.
    private Set<WorldPoint> lastPicture = Set.of();
    private int lastPictureTick = -1;

    EzekielTracker(Emitter log) {
        this.log = log;
    }

    // ------------------------------------------------------------------
    // Events (forwarded by FracturedArchiveHandler while it is latched)
    // ------------------------------------------------------------------

    void onGameTick(Client client) {
        if (!FracturedArchiveHandler.isInEzekielRoom(client)) {
            reset();
            return;
        }
        if (arenaFloor.isEmpty()) {
            NPC ezekiel = findEzekiel(client);
            if (ezekiel != null) {
                measureArena(client, ezekiel);
            }
        }
        Predicate<WorldPoint> floor = floor();
        updateSlam(client, floor);
        updateSwords(client, floor);
        updateEnrage(client);
        logEnrageTile(client);
    }

    void onNpcSpawned(Client client, NPC npc) {
        // He spawns when the room loads, well before "Challenge started".
        if (npc.getId() == EZEKIEL_ID) {
            resetRhythm();
            return;
        }
        if (npc.getId() == ENRAGE_SWORD_ID) {
            logEnrageSword(client, npc);
            return;
        }
        if (!isSword(npc)) {
            return;
        }
        int tick = client.getTickCount();
        if (waveStartTick < 0 || tick - waveStartTick > WAVE_JOIN_TICKS) {
            clearWave();
            waveStartTick = tick;
        }
        wave.add(new Sword(npc, npc.getWorldLocation()));
        waveChanged = true;
    }

    void onEzekielAnimation(int anim, int tick) {
        switch (anim) {
            case AUTO_ATTACK_ANIM:
                // The event can fire twice in a tick; count the auto once.
                if (tick != lastTurnTick) {
                    autosSinceSpecial++;
                }
                lastTurnTick = tick;
                break;
            case STRENGTH_ANIM:
                lastTurnTick = tick;
                break;
            case SPECIAL_ANIM:
                markSpecial(tick, "anim 14687");
                break;
            default:
                break;
        }
    }

    void onNpcDespawned(NPC npc) {
        for (Sword sword : wave) {
            if (sword.npc == npc) {
                sword.gone = true;
            }
        }
    }

    // The rebuilt scene may be another room; measure the floor again once back.
    void onSceneLoading() {
        arenaFloor = Set.of();
    }

    void reset() {
        arenaFloor = Set.of();
        slamTelegraph = Set.of();
        slamBossTiles = Set.of();
        slamDanger = Set.of();
        slamSafeTiles = Set.of();
        slamPlayerTile = null;
        slamStartTick = -1;
        slamGraphicsGone = false;
        lastPicture = Set.of();
        lastPictureTick = -1;
        seenStrikeGraphics.clear();
        enrageSafeTiles.clear();
        resetRhythm();
        clearWave();
    }

    private void resetRhythm() {
        autosSinceSpecial = 0;
        specialSeen = false;
        lastTurnTick = -1;
        lastSpecialTick = -1;
    }

    // ------------------------------------------------------------------
    // State read by FracturedArchiveOverlay
    // ------------------------------------------------------------------

    public Set<WorldPoint> getSlamSafeTiles() {
        return slamSafeTiles;
    }

    // South-west tile of the 3x3 the converging swords meet in, or null.
    public WorldPoint getConvergeArea() {
        return formation == Formation.CONVERGE ? convergeArea : null;
    }

    // The one-tile line between the converging swords nearest the arena's middle, or empty.
    public Set<WorldPoint> getConvergeLines() {
        return formation == Formation.CONVERGE ? convergeLines : Set.of();
    }


    // The converge comes with a slam too, and it can land on the corners (t3632,
    // 2026-10-23: one under Ezekiel, one in the telegraph; standing there killed
    // you), so a corner the slam hits is hidden until the slam's last hit.
    public Set<WorldPoint> getConvergeSafeTiles() {
        return formation == Formation.CONVERGE ? withoutTiles(convergeSafeTiles, slamDanger) : Set.of();
    }

    // The four swords come with a slam, which lands long before they do, so the
    // slam's tiles are left out of the zone only until its last hit.
    public Set<WorldPoint> getFourSwordSafeTiles() {
        return formation == Formation.FOUR_SWORDS ? withoutTiles(fourSwordSafeTiles, slamDanger) : Set.of();
    }

    // The two lines a one-tile gap either side of the four-sword zone, or empty.
    public Set<WorldPoint> getFourSwordSideLines() {
        return formation == Formation.FOUR_SWORDS ? fourSwordSideLines : Set.of();
    }

    // The two lines a one-tile gap outside the side lines. They are drawn as safe
    // tiles, so like the zone they leave out the slam's tiles until its last hit.
    public Set<WorldPoint> getFourSwordOuterLines() {
        return formation == Formation.FOUR_SWORDS ? withoutTiles(fourSwordOuterLines, slamDanger) : Set.of();
    }

    // Tile A (in the second-last sword's lane) and tile B (in the last one's),
    // from the moment the firing order is known until the last sword fires. The
    // swipe comes with a slam too, so like the other formations a tile the slam
    // hits is hidden until its last hit.
    public List<WorldPoint> getSwipeSafeTiles() {
        if (formation != Formation.SWIPE || swipeFirstEnd < 0) {
            return List.of();
        }
        Sword last = swipeLine.get(2 - swipeFirstEnd);
        if (last.firedTick >= 0 || last.gone) {
            return List.of();
        }
        List<WorldPoint> shown = new ArrayList<>(swipeSafeTiles);
        shown.removeAll(slamDanger);
        return shown;
    }

    // The tiles the enrage strike landing on the given tick misses, or empty. The
    // overlay passes its tick count, which already reads the tick being played
    // out - the one to click in, as the strike checks the tile you end it on.
    public Set<WorldPoint> getEnrageSafeTiles(int tick) {
        return enrageSafeTiles.getOrDefault(tick, Set.of());
    }

    // ------------------------------------------------------------------
    // Enrage
    // ------------------------------------------------------------------

    private void updateEnrage(Client client) {
        int tick = client.getTickCount();
        int gameCycle = client.getGameCycle();
        Set<GraphicsObject> live = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Integer, Set<WorldPoint>> struck = new TreeMap<>();
        for (GraphicsObject go : client.getTopLevelWorldView().getGraphicsObjects()) {
            if (!ENRAGE_STRIKE_GRAPHIC_IDS.contains(go.getId())) {
                continue;
            }
            live.add(go);
            LocalPoint lp = go.getLocation();
            if (!seenStrikeGraphics.add(go) || lp == null) {
                continue;
            }
            int strikeTick = tick + Math.round((go.getStartCycle() - gameCycle)
                    / (float) FracturedArchiveHandler.CYCLES_PER_GAME_TICK);
            struck.computeIfAbsent(strikeTick, k -> new HashSet<>()).add(WorldPoint.fromLocal(client, lp));
        }
        seenStrikeGraphics.retainAll(live);
        // Strikes that have landed are done with.
        enrageSafeTiles.headMap(tick).clear();
        if (struck.isEmpty()) {
            return;
        }
        Map<Integer, Set<WorldPoint>> safe = enrageSafeTiles(struck);
        enrageSafeTiles.putAll(safe);
        for (Map.Entry<Integer, Set<WorldPoint>> e : safe.entrySet()) {
            log.emit("ENRG", "strike at +{}t on {} tiles -> safe {}", e.getKey() - tick,
                    struck.get(e.getKey()).size(), describe(e.getValue()));
        }
    }

    // One cycle's strikes, keyed by the tick each lands, to the tiles each misses.
    // Every tile of your 3x3 is struck at least once a cycle (the row against
    // Ezekiel every time), so the cycle's strikes together make the 3x3.
    static Map<Integer, Set<WorldPoint>> enrageSafeTiles(Map<Integer, Set<WorldPoint>> struck) {
        Set<WorldPoint> box = new HashSet<>();
        for (Set<WorldPoint> tiles : struck.values()) {
            box.addAll(tiles);
        }
        Map<Integer, Set<WorldPoint>> safe = new TreeMap<>();
        for (Map.Entry<Integer, Set<WorldPoint>> e : struck.entrySet()) {
            safe.put(e.getKey(), withoutTiles(box, e.getValue()));
        }
        return safe;
    }

    // ------------------------------------------------------------------
    // Slam
    // ------------------------------------------------------------------

    private void updateSlam(Client client, Predicate<WorldPoint> floor) {
        Set<WorldPoint> telegraph = new HashSet<>();
        for (GraphicsObject go : client.getTopLevelWorldView().getGraphicsObjects()) {
            LocalPoint lp = go.getLocation();
            if (lp != null && SLAM_GRAPHIC_IDS.contains(go.getId())) {
                telegraph.add(WorldPoint.fromLocal(client, lp));
            }
        }
        int tick = client.getTickCount();
        if (slamStartTick >= 0 && telegraph.isEmpty() && !slamGraphicsGone) {
            slamGraphicsGone = true;
            log.emit("EZEK", "slam telegraph graphics gone after {} ticks", tick - slamStartTick);
        }
        if (slamStartTick >= 0 && tick - slamStartTick > SLAM_LAST_HIT_TICKS) {
            // Both hits have landed. The graphics linger until +8, thinning out
            // on their last tick, and those leftovers are neither a new slam nor
            // a reason to redraw the safe tiles.
            slamTelegraph = Set.of();
            slamDanger = Set.of();
            slamSafeTiles = Set.of();
            if (slamGraphicsGone) {
                slamStartTick = -1;
            }
            return;
        }
        // Tiles only ever join a live telegraph; one thinning out changes nothing.
        if (telegraph.isEmpty() || slamTelegraph.containsAll(telegraph)) {
            return;
        }
        if (slamStartTick < 0) {
            slamStartTick = tick;
            slamGraphicsGone = false;
            slamBossTiles = footprint(findEzekiel(client));
            Player player = client.getLocalPlayer();
            slamPlayerTile = player != null ? player.getWorldLocation() : null;
            // 14687 fires before the tick's graphics are scanned, so a telegraph
            // without it means the animation was missed; count the special anyway.
            if (lastSpecialTick < 0 || tick - lastSpecialTick > 1) {
                markSpecial(tick, "slam telegraph without anim 14687");
            } else {
                // The safe tiles show from here; 0 means on the animation's first tick.
                log.emit("EZEK", "slam telegraph and safe tiles up {} ticks after anim 14687",
                        tick - lastSpecialTick);
            }
        }
        Set<WorldPoint> all = new HashSet<>(slamTelegraph);
        all.addAll(telegraph);
        slamTelegraph = all;
        slamDanger = slamDanger(all, slamBossTiles);
        Set<WorldPoint> ring = slamSafeTiles(all, slamBossTiles, floor);
        slamSafeTiles = nearSafeTiles(ring, slamPlayerTile);
        log.emit("EZEK", "slam telegraph on {} tiles {} -> {} safe tiles {}, {} shown near you at {}",
                all.size(), describe(all), ring.size(), describe(ring), slamSafeTiles.size(),
                slamPlayerTile == null ? "?" : FracturedArchiveHandler.tile(slamPlayerTile));
    }

    // Logs how the special fit the rhythm, to check the prediction against the
    // next kill, and starts counting autos again.
    private void markSpecial(int tick, String cue) {
        if (tick == lastSpecialTick) {
            return;
        }
        int predicted = predictedSpecialTick();
        log.emit("EZEK", "special ({}) after {} autos, {} since the last one; rhythm predicted {}",
                cue, autosSinceSpecial,
                lastSpecialTick < 0 ? "none" : "+" + (tick - lastSpecialTick) + "t",
                predicted < 0 ? "nothing yet" : predicted == tick ? "this tick" : "t" + predicted);
        lastSpecialTick = tick;
        specialSeen = true;
        autosSinceSpecial = 0;
    }

    // The tick his next special is due, once enough autos have gone by, or -1.
    int predictedSpecialTick() {
        int needed = specialSeen ? AUTOS_BETWEEN_SPECIALS : AUTOS_BEFORE_FIRST_SPECIAL;
        return lastTurnTick >= 0 && autosSinceSpecial >= needed ? lastTurnTick + ATTACK_CYCLE_TICKS : -1;
    }

    /**
     * Walkable tiles outside a danger area that touch it, side-on or diagonally:
     * a ring hugging its edge.
     */
    static Set<WorldPoint> borderTiles(Set<WorldPoint> danger, Predicate<WorldPoint> floor) {
        Set<WorldPoint> border = new HashSet<>();
        for (WorldPoint tile : danger) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    WorldPoint next = tile.dx(dx).dy(dy);
                    if (!danger.contains(next) && floor.test(next)) {
                        border.add(next);
                    }
                }
            }
        }
        return border;
    }

    /**
     * Every tile the slam hits: its telegraph, and the 3x3 Ezekiel stands on,
     * which the telegraph rings without covering (standing under him is not
     * safe either; confirmed in game, 2026-10-22).
     */
    static Set<WorldPoint> slamDanger(Set<WorldPoint> telegraph, Set<WorldPoint> boss) {
        Set<WorldPoint> danger = new HashSet<>(telegraph);
        danger.addAll(boss);
        return danger;
    }

    /**
     * The slam's safe tiles: the ring hugging its telegraph, without the tiles
     * under Ezekiel. Because the telegraph rings him, that ring would otherwise
     * take in his own 3x3 bar its centre.
     */
    static Set<WorldPoint> slamSafeTiles(Set<WorldPoint> telegraph, Set<WorldPoint> boss,
            Predicate<WorldPoint> floor) {
        return borderTiles(telegraph, floor.and(tile -> !boss.contains(tile)));
    }

    /**
     * The safe tiles within a one-tile gap of you (see SLAM_NEAR_DISTANCE_SQUARED),
     * or all of them when none are that close or your tile is unknown.
     */
    static Set<WorldPoint> nearSafeTiles(Set<WorldPoint> safe, WorldPoint player) {
        if (player == null) {
            return safe;
        }
        Set<WorldPoint> near = new HashSet<>();
        for (WorldPoint tile : safe) {
            int dx = tile.getX() - player.getX();
            int dy = tile.getY() - player.getY();
            if (tile.getPlane() == player.getPlane() && dx * dx + dy * dy <= SLAM_NEAR_DISTANCE_SQUARED) {
                near.add(tile);
            }
        }
        return near.isEmpty() ? safe : near;
    }

    static Set<WorldPoint> withoutTiles(Set<WorldPoint> tiles, Set<WorldPoint> removed) {
        if (removed.isEmpty()) {
            return tiles;
        }
        Set<WorldPoint> kept = new HashSet<>(tiles);
        kept.removeAll(removed);
        return kept;
    }

    // ------------------------------------------------------------------
    // Swords
    // ------------------------------------------------------------------

    private void updateSwords(Client client, Predicate<WorldPoint> floor) {
        if (wave.isEmpty()) {
            return;
        }
        int tick = client.getTickCount();
        for (Sword sword : wave) {
            if (sword.gone) {
                continue;
            }
            WorldPoint at = sword.npc.getWorldLocation();
            if (sword.firedTick < 0 && at != null && !at.equals(sword.home)) {
                sword.firedTick = tick;
            }
            if (sword.barTick < 0 && sword.npc.getHealthScale() > 0) {
                sword.barTick = tick;
            }
            logSword(sword, at);
        }
        if (waveChanged) {
            waveChanged = false;
            classifyWave(floor);
        }
        if (formation == Formation.SWIPE) {
            updateSwipeOrder(floor);
        }
        if (wave.stream().allMatch(sword -> sword.gone)) {
            log.emit("EZEK", "sword wave over after {} ticks", tick - waveStartTick);
            clearWave();
        }
    }

    // One line per sword whenever its tile, bar, facing or animation changes: the
    // bar is what the swipe's firing order is read from.
    private void logSword(Sword sword, WorldPoint at) {
        NPC npc = sword.npc;
        String state = FracturedArchiveHandler.tile(at)
                + " bar " + npc.getHealthRatio() + "/" + npc.getHealthScale()
                + " face " + npc.getOrientation()
                + " anim " + npc.getAnimation()
                + (sword.firedTick >= 0 ? " fired t" + sword.firedTick : "");
        if (state.equals(sword.lastLogged)) {
            return;
        }
        sword.lastLogged = state;
        log.emit("SWORD", "#{} @{}", npc.getIndex(), state);
    }

    private void classifyWave(Predicate<WorldPoint> floor) {
        List<WorldPoint> homes = new ArrayList<>();
        for (Sword sword : wave) {
            homes.add(sword.home);
        }
        formation = classify(homes);
        convergeArea = null;
        convergeLines = Set.of();
        convergeSafeTiles = Set.of();
        fourSwordSafeTiles = Set.of();
        fourSwordSideLines = Set.of();
        fourSwordOuterLines = Set.of();
        swipeLine = List.of();
        swipeFirstEnd = -1;
        swipeSafeTiles = List.of();

        switch (formation) {
            case CONVERGE:
                convergeArea = convergenceArea(homes);
                convergeLines = convergeLines(convergeArea, homes, floor);
                Set<WorldPoint> nooks = convergeNooks(convergeArea, convergeLines, floor);
                convergeSafeTiles = new HashSet<>(convergeSafeTiles(convergeArea, homes, floor));
                convergeSafeTiles.addAll(nooks);
                log.emit("EZEK", "{} swords from {} -> CONVERGE on the 3x3 from {} -> {} line tiles {} -> safe {} ({} while the slam is live), nook {}",
                        homes.size(), describe(homes), FracturedArchiveHandler.tile(convergeArea),
                        convergeLines.size(), describe(convergeLines), describe(convergeSafeTiles),
                        describe(withoutTiles(convergeSafeTiles, slamDanger)), describe(nooks));
                break;
            case FOUR_SWORDS:
                fourSwordSafeTiles = fourSwordSafeTiles(homes, floor);
                fourSwordSideLines = fourSwordLines(homes, fourSwordSafeTiles, SIDE_LINE_OFFSET, floor);
                fourSwordOuterLines = fourSwordLines(homes, fourSwordSafeTiles, OUTER_LINE_OFFSET, floor);
                log.emit("EZEK", "{} swords from {} -> FOUR SWORDS, {} safe tiles {} ({} while the slam is live), {} side tiles {}, {} outer tiles {}",
                        homes.size(), describe(homes), fourSwordSafeTiles.size(), describe(fourSwordSafeTiles),
                        withoutTiles(fourSwordSafeTiles, slamDanger).size(),
                        fourSwordSideLines.size(), describe(fourSwordSideLines),
                        fourSwordOuterLines.size(), describe(fourSwordOuterLines));
                break;
            case SWIPE:
                swipeLine = new ArrayList<>(wave);
                swipeLine.sort(Comparator.comparingInt(
                        allShare(homes, WorldPoint::getX) ? s -> s.home.getY() : s -> s.home.getX()));
                log.emit("EZEK", "{} swords from {} -> SWIPE, waiting for the bars or the first to fire",
                        homes.size(), describe(homes));
                break;
            default:
                log.emit("EZEK", "{} swords from {} -> no known formation", homes.size(), describe(homes));
        }
    }

    // The first sword off its spawn tile is the ground truth for which end fires
    // first, but that is only ~14 ticks after they spawn. Until then the end
    // whose charge bar showed first is taken to go first (5 of 5 swipes on
    // 2026-10-23: bars at +0, +1, +2 and the swords fired in that same order), so
    // the tiles show on the spawn tick; failing that, the end with the lower bar.
    // The guess is kept unless a sword's movement
    // contradicts it.
    private void updateSwipeOrder(Predicate<WorldPoint> floor) {
        Sword low = swipeLine.get(0);
        Sword high = swipeLine.get(2);
        Sword firstMover = firstToFire(swipeLine);
        Integer byBarTicks = firstEndByBarTicks(low.barTick, high.barTick);
        int end;
        String how;
        if (firstMover == low || firstMover == high) {
            end = firstMover == low ? 0 : 2;
            how = "first to fire";
        } else if (swipeFirstEnd >= 0) {
            return;
        } else if (byBarTicks != null) {
            end = byBarTicks;
            how = "bars showed t" + low.barTick + " vs t" + high.barTick
                    + ", middle t" + swipeLine.get(1).barTick;
        } else {
            Integer byBars = firstEndByBars(
                    barFraction(low.npc.getHealthRatio(), low.npc.getHealthScale()),
                    barFraction(high.npc.getHealthRatio(), high.npc.getHealthScale()));
            if (byBars == null) {
                return;
            }
            end = byBars;
            how = "bars " + low.npc.getHealthRatio() + "/" + low.npc.getHealthScale()
                    + " vs " + high.npc.getHealthRatio() + "/" + high.npc.getHealthScale();
        }
        if (end == swipeFirstEnd) {
            return;
        }
        swipeFirstEnd = end;
        List<WorldPoint> order = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            order.add(swipeLine.get(end == 0 ? i : 2 - i).home);
        }
        swipeSafeTiles = swipeSafeTiles(order, floor);
        log.emit("EZEK", "swipe fires from {} first ({}) -> safe A then B {}",
                FracturedArchiveHandler.tile(order.get(0)), how, describe(swipeSafeTiles));
    }

    // The sword that left its spawn tile first, or null if none has (or two left
    // on the same tick).
    private static Sword firstToFire(List<Sword> swords) {
        Sword first = null;
        boolean tie = false;
        for (Sword sword : swords) {
            if (sword.firedTick < 0) {
                continue;
            }
            if (first == null || sword.firedTick < first.firedTick) {
                first = sword;
                tie = false;
            } else if (sword.firedTick == first.firedTick) {
                tie = true;
            }
        }
        return tie ? null : first;
    }

    static double barFraction(int ratio, int scale) {
        return ratio >= 0 && scale > 0 ? ratio / (double) scale : Double.NaN;
    }

    /**
     * Which end of a swipe line fires first, 0 (the low end) or 2 (the high end),
     * from the two end swords' charge bars: a bar drains while its sword charges,
     * so the lower one fires first. Null when a bar is missing or they are level.
     */
    static Integer firstEndByBars(double lowEnd, double highEnd) {
        if (Double.isNaN(lowEnd) || Double.isNaN(highEnd) || lowEnd == highEnd) {
            return null;
        }
        return lowEnd < highEnd ? 0 : 2;
    }

    /**
     * Which end of a swipe line fires first, 0 (the low end) or 2 (the high
     * end), from the ticks the two end swords' charge bars first showed (-1 if
     * not yet): the earlier one. The first bar shows on the spawn tick, so an end
     * whose bar is up while the other end's isn't is already the answer. Null
     * until an end's bar has shown, or when both showed together.
     */
    static Integer firstEndByBarTicks(int lowEnd, int highEnd) {
        if (lowEnd == highEnd) {
            return null;
        }
        if (lowEnd < 0 || highEnd < 0) {
            return lowEnd >= 0 ? 0 : 2;
        }
        return lowEnd < highEnd ? 0 : 2;
    }

    private void clearWave() {
        wave.clear();
        waveStartTick = -1;
        waveChanged = false;
        formation = Formation.NONE;
        convergeArea = null;
        convergeLines = Set.of();
        convergeSafeTiles = Set.of();
        fourSwordSafeTiles = Set.of();
        fourSwordSideLines = Set.of();
        fourSwordOuterLines = Set.of();
        swipeLine = List.of();
        swipeFirstEnd = -1;
        swipeSafeTiles = List.of();
    }

    // ------------------------------------------------------------------
    // Sword geometry. Every tile is a sword's south-west tile; a sword covers
    // SWORD_SIZE x SWORD_SIZE from there.
    // ------------------------------------------------------------------

    static Formation classify(List<WorldPoint> homes) {
        if (homes.size() == 3 && (allShare(homes, WorldPoint::getX) || allShare(homes, WorldPoint::getY))) {
            return Formation.SWIPE;
        }
        if (homes.size() == 4
                && (oppositePairs(homes, WorldPoint::getX) || oppositePairs(homes, WorldPoint::getY))) {
            return Formation.FOUR_SWORDS;
        }
        return convergenceArea(homes) != null ? Formation.CONVERGE : Formation.NONE;
    }

    /**
     * South-west tile of the 3x3 converging swords meet in: the column of a pair
     * facing each other north-south, crossed with the row of a pair facing each
     * other east-west. Null without both pairs.
     */
    static WorldPoint convergenceArea(List<WorldPoint> homes) {
        Integer column = sharedAcross(homes, WorldPoint::getX, WorldPoint::getY);
        Integer row = sharedAcross(homes, WorldPoint::getY, WorldPoint::getX);
        if (column == null || row == null) {
            return null;
        }
        return new WorldPoint(column, row, homes.get(0).getPlane());
    }

    /**
     * The converge's danger line: the middle column of the north and south
     * swords' lane or the middle row of the west and east swords' lane, one tile
     * wide and running only across the gap between the two swords facing each
     * other, not under them. The two would form a cross; only the one nearer
     * the middle of the arena is kept, since the other runs along an edge. The
     * swords stand on the arena's four edges, so its middle is the middle of
     * their bounding box (12831,6239 and 13023,11039 in the 2026-10-22 kills,
     * matching the measured 9x9 floor). Both are kept when they are equally far
     * from it.
     */
    static Set<WorldPoint> convergeLines(WorldPoint area, List<WorldPoint> homes, Predicate<WorldPoint> floor) {
        int column = area.getX() + SWORD_SIZE / 2;
        int row = area.getY() + SWORD_SIZE / 2;
        int plane = area.getPlane();
        Set<WorldPoint> columnLine = new HashSet<>();
        Set<WorldPoint> rowLine = new HashSet<>();
        // Each facing pair once, lower sword first: south of the other in the
        // area's column, or west of it in the area's row.
        for (WorldPoint a : homes) {
            for (WorldPoint b : homes) {
                if (a.getX() == area.getX() && b.getX() == area.getX() && a.getY() < b.getY()) {
                    for (int y = a.getY() + SWORD_SIZE; y < b.getY(); y++) {
                        addIfFloor(columnLine, new WorldPoint(column, y, plane), floor);
                    }
                }
                if (a.getY() == area.getY() && b.getY() == area.getY() && a.getX() < b.getX()) {
                    for (int x = a.getX() + SWORD_SIZE; x < b.getX(); x++) {
                        addIfFloor(rowLine, new WorldPoint(x, row, plane), floor);
                    }
                }
            }
        }
        int byCentre = Integer.compare(offCentre(homes, WorldPoint::getX, column),
                offCentre(homes, WorldPoint::getY, row));
        if (byCentre < 0 || rowLine.isEmpty()) {
            return columnLine;
        }
        if (byCentre > 0 || columnLine.isEmpty()) {
            return rowLine;
        }
        Set<WorldPoint> both = new HashSet<>(columnLine);
        both.addAll(rowLine);
        return both;
    }

    // How far a line sits from the middle of the swords' bounding box on one
    // axis, in half tiles so an even-width box needs no rounding.
    private static int offCentre(List<WorldPoint> homes, ToIntFunction<WorldPoint> axis, int line) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (WorldPoint home : homes) {
            min = Math.min(min, axis.applyAsInt(home));
            max = Math.max(max, axis.applyAsInt(home) + SWORD_SIZE - 1);
        }
        return Math.abs(2 * line - (min + max));
    }

    private static void addIfFloor(Set<WorldPoint> tiles, WorldPoint tile, Predicate<WorldPoint> floor) {
        if (floor.test(tile)) {
            tiles.add(tile);
        }
    }

    /**
     * The nook where the converge's two lines cross near a corner of the arena
     * (t2559 and t8476 on 2026-10-23): each line runs one tile past the other
     * and stops, and the tile diagonally between those two one-tile stubs is
     * safe (asked for 2026-10-23). Empty unless both lines are kept, and when
     * they cross in the middle of the arena, where every arm is long.
     */
    static Set<WorldPoint> convergeNooks(WorldPoint area, Set<WorldPoint> lines, Predicate<WorldPoint> floor) {
        WorldPoint crossing = area.dx(SWORD_SIZE / 2).dy(SWORD_SIZE / 2);
        Set<WorldPoint> nooks = new HashSet<>();
        if (!lines.contains(crossing)) {
            return nooks;
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                boolean xStub = lines.contains(crossing.dx(sx)) && !lines.contains(crossing.dx(2 * sx));
                boolean yStub = lines.contains(crossing.dy(sy)) && !lines.contains(crossing.dy(2 * sy));
                if (xStub && yStub) {
                    addIfFloor(nooks, crossing.dx(sx).dy(sy), floor);
                }
            }
        }
        return nooks;
    }

    /**
     * The converge's safe tiles: walkable tiles touching the meeting area, side-on
     * or diagonally, that no sword's lane crosses. With swords on all four sides
     * that leaves its four diagonal corners.
     */
    static Set<WorldPoint> convergeSafeTiles(WorldPoint area, List<WorldPoint> homes, Predicate<WorldPoint> floor) {
        Set<WorldPoint> safe = new HashSet<>();
        for (int dx = -1; dx <= SWORD_SIZE; dx++) {
            for (int dy = -1; dy <= SWORD_SIZE; dy++) {
                if (covers(0, dx) && covers(0, dy)) {
                    continue;
                }
                WorldPoint tile = area.dx(dx).dy(dy);
                if (floor.test(tile) && !inAnyLane(tile, homes, area)) {
                    safe.add(tile);
                }
            }
        }
        return safe;
    }

    private static boolean inAnyLane(WorldPoint tile, List<WorldPoint> homes, WorldPoint area) {
        for (WorldPoint home : homes) {
            if (inLane(tile, home, area)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a sword flying from its spawn through the meeting area sweeps a
     * tile. The logged swords flew on to the far edge, so a sword lined up with
     * the area sweeps its whole column (or row) band. One that is not lined up
     * falls back to the box between its spawn and the area.
     */
    static boolean inLane(WorldPoint tile, WorldPoint home, WorldPoint area) {
        if (home.getX() == area.getX()) {
            return covers(home.getX(), tile.getX());
        }
        if (home.getY() == area.getY()) {
            return covers(home.getY(), tile.getY());
        }
        return tile.getX() >= Math.min(home.getX(), area.getX())
                && tile.getX() < Math.max(home.getX(), area.getX()) + SWORD_SIZE
                && tile.getY() >= Math.min(home.getY(), area.getY())
                && tile.getY() < Math.max(home.getY(), area.getY()) + SWORD_SIZE;
    }

    /**
     * The four-sword attack's safe zone: the lanes between the swords that none
     * of them sweeps, across the arena from one side's swords to the other's.
     * Swords on the west and east sides fly along east-west rows, so the zone is
     * rows (the middle two, in game); on the north and south sides it would be
     * columns.
     */
    static Set<WorldPoint> fourSwordSafeTiles(List<WorldPoint> homes, Predicate<WorldPoint> floor) {
        boolean rows = oppositePairs(homes, WorldPoint::getX);
        ToIntFunction<WorldPoint> lane = rows ? WorldPoint::getY : WorldPoint::getX;
        ToIntFunction<WorldPoint> along = rows ? WorldPoint::getX : WorldPoint::getY;
        int laneMin = Integer.MAX_VALUE, laneMax = Integer.MIN_VALUE;
        int alongMin = Integer.MAX_VALUE, alongMax = Integer.MIN_VALUE;
        for (WorldPoint home : homes) {
            laneMin = Math.min(laneMin, lane.applyAsInt(home));
            laneMax = Math.max(laneMax, lane.applyAsInt(home) + SWORD_SIZE - 1);
            alongMin = Math.min(alongMin, along.applyAsInt(home));
            alongMax = Math.max(alongMax, along.applyAsInt(home) + SWORD_SIZE - 1);
        }
        int plane = homes.get(0).getPlane();
        Set<WorldPoint> safe = new HashSet<>();
        for (int l = laneMin; l <= laneMax; l++) {
            if (anyCovers(homes, lane, l)) {
                continue;
            }
            for (int a = alongMin; a <= alongMax; a++) {
                WorldPoint tile = rows ? new WorldPoint(a, l, plane) : new WorldPoint(l, a, plane);
                if (floor.test(tile)) {
                    safe.add(tile);
                }
            }
        }
        return safe;
    }

    /**
     * Two lines alongside the four-sword zone, the same length as it, the given
     * number of tiles out from its edges: below its lowest lane and above its
     * highest (rows south and north, or columns west and east). At 2 there is a
     * one-tile gap between each and the zone; at 4, a one-tile gap past those.
     */
    static Set<WorldPoint> fourSwordLines(List<WorldPoint> homes, Set<WorldPoint> zone, int offset,
            Predicate<WorldPoint> floor) {
        if (zone.isEmpty()) {
            return Set.of();
        }
        boolean rows = oppositePairs(homes, WorldPoint::getX);
        ToIntFunction<WorldPoint> lane = rows ? WorldPoint::getY : WorldPoint::getX;
        int laneMin = Integer.MAX_VALUE, laneMax = Integer.MIN_VALUE;
        for (WorldPoint tile : zone) {
            laneMin = Math.min(laneMin, lane.applyAsInt(tile));
            laneMax = Math.max(laneMax, lane.applyAsInt(tile));
        }
        Set<WorldPoint> lines = new HashSet<>();
        for (WorldPoint tile : zone) {
            int l = lane.applyAsInt(tile);
            if (l == laneMin) {
                addIfFloor(lines, rows ? tile.dy(-offset) : tile.dx(-offset), floor);
            }
            if (l == laneMax) {
                addIfFloor(lines, rows ? tile.dy(offset) : tile.dx(offset), floor);
            }
        }
        return lines;
    }

    /**
     * The swipe's two safe tiles, [A, B], given the three swords in firing order.
     * Both sit SWIPE_SAFE_DEPTH tiles out in front of the swords on the arena
     * side, where the lanes of the last two swords meet: A in the second-last
     * sword's lane, B in the last one's. Standing on B dodges the first two
     * swords; once the second-last has gone, its lane is clear, so stepping onto
     * A dodges the last. A tile that is not floor at that depth comes back
     * towards the swords until one is.
     */
    static List<WorldPoint> swipeSafeTiles(List<WorldPoint> order, Predicate<WorldPoint> floor) {
        // Stacked in one column they fire east or west; in one row, north or south.
        boolean column = allShare(order, WorldPoint::getX);
        Integer front = frontLine(order, column, floor);
        if (front == null) {
            return List.of();
        }
        int base = column ? order.get(1).getX() : order.get(1).getY();
        int inward = front > base ? 1 : -1;
        WorldPoint secondLast = order.get(1);
        WorldPoint last = order.get(2);
        int s = column ? secondLast.getY() : secondLast.getX();
        int l = column ? last.getY() : last.getX();
        int a = l > s ? s + SWORD_SIZE - 1 : s;
        int b = l > s ? l : l + SWORD_SIZE - 1;
        int plane = last.getPlane();
        List<WorldPoint> tiles = new ArrayList<>(2);
        for (int along : new int[] { a, b }) {
            for (int depth = SWIPE_SAFE_DEPTH - 1; depth >= 0; depth--) {
                int out = front + inward * depth;
                WorldPoint tile = column ? new WorldPoint(out, along, plane) : new WorldPoint(along, out, plane);
                if (floor.test(tile)) {
                    tiles.add(tile);
                    break;
                }
            }
        }
        return tiles;
    }

    // The line of tiles just in front of a swipe line, on whichever side of it more
    // of the arena lies (the side the swords fire into), or null if neither side
    // has floor.
    private static Integer frontLine(List<WorldPoint> line, boolean column, Predicate<WorldPoint> floor) {
        WorldPoint middle = line.get(1);
        int base = column ? middle.getX() : middle.getY();
        int across = (column ? middle.getY() : middle.getX()) + SWORD_SIZE / 2;
        int plane = middle.getPlane();
        int ahead = 0, behind = 0;
        for (int i = 0; i < FRONT_PROBE_TILES; i++) {
            int forward = base + SWORD_SIZE + i;
            int backward = base - 1 - i;
            ahead += floor.test(column ? new WorldPoint(forward, across, plane) : new WorldPoint(across, forward, plane)) ? 1 : 0;
            behind += floor.test(column ? new WorldPoint(backward, across, plane) : new WorldPoint(across, backward, plane)) ? 1 : 0;
        }
        if (ahead == 0 && behind == 0) {
            return null;
        }
        return ahead >= behind ? base + SWORD_SIZE : base - 1;
    }

    private static boolean allShare(List<WorldPoint> homes, ToIntFunction<WorldPoint> axis) {
        int first = axis.applyAsInt(homes.get(0));
        for (WorldPoint home : homes) {
            if (axis.applyAsInt(home) != first) {
                return false;
            }
        }
        return true;
    }

    // Two swords on each of two opposite sides: exactly two values on this axis,
    // two swords on each, and far enough apart to be across the arena.
    static boolean oppositePairs(List<WorldPoint> homes, ToIntFunction<WorldPoint> axis) {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (WorldPoint home : homes) {
            counts.merge(axis.applyAsInt(home), 1, Integer::sum);
        }
        if (counts.size() != 2 || !counts.values().stream().allMatch(count -> count == 2)) {
            return false;
        }
        List<Integer> values = new ArrayList<>(counts.keySet());
        return values.get(1) - values.get(0) >= OPPOSITE_SIDE_MIN_GAP;
    }

    // A value two swords share on one axis while they face each other from
    // opposite sides along the other, or null.
    private static Integer sharedAcross(List<WorldPoint> homes, ToIntFunction<WorldPoint> shared,
            ToIntFunction<WorldPoint> apart) {
        for (int i = 0; i < homes.size(); i++) {
            for (int j = i + 1; j < homes.size(); j++) {
                WorldPoint a = homes.get(i);
                WorldPoint b = homes.get(j);
                if (shared.applyAsInt(a) == shared.applyAsInt(b)
                        && Math.abs(apart.applyAsInt(a) - apart.applyAsInt(b)) >= OPPOSITE_SIDE_MIN_GAP) {
                    return shared.applyAsInt(a);
                }
            }
        }
        return null;
    }

    private static boolean anyCovers(List<WorldPoint> homes, ToIntFunction<WorldPoint> axis, int value) {
        for (WorldPoint home : homes) {
            if (covers(axis.applyAsInt(home), value)) {
                return true;
            }
        }
        return false;
    }

    // Whether a sword starting at `start` on an axis covers `value` on it.
    private static boolean covers(int start, int value) {
        return value >= start && value < start + SWORD_SIZE;
    }

    // ------------------------------------------------------------------
    // Arena floor
    // ------------------------------------------------------------------

    private Predicate<WorldPoint> floor() {
        // Unmeasured, nothing is ruled out, so the highlights still show.
        Set<WorldPoint> floor = arenaFloor;
        return floor.isEmpty() ? tile -> true : floor::contains;
    }

    private void measureArena(Client client, NPC ezekiel) {
        WorldView wv = client.getTopLevelWorldView();
        int[][] flags = ArchiveArenaSimplifier.collisionFlags(wv);
        LocalPoint centre = ezekiel.getLocalLocation();
        if (flags == null || centre == null) {
            return;
        }
        int startX = centre.getSceneX();
        int startY = centre.getSceneY();
        if (!ArchiveArenaSimplifier.isWalkable(flags, startX, startY)) {
            Player player = client.getLocalPlayer();
            LocalPoint lp = player != null ? player.getLocalLocation() : null;
            if (lp == null) {
                return;
            }
            startX = lp.getSceneX();
            startY = lp.getSceneY();
        }
        boolean[][] flooded = floodArena(flags, startX, startY, ARENA_SEARCH_RADIUS);
        Set<WorldPoint> floor = new HashSet<>();
        int plane = wv.getPlane();
        for (int x = 0; x < flooded.length; x++) {
            for (int y = 0; y < flooded[x].length; y++) {
                if (flooded[x][y]) {
                    floor.add(WorldPoint.fromScene(wv, x, y, plane));
                }
            }
        }
        if (floor.isEmpty()) {
            return;
        }
        arenaFloor = floor;
        logArenaMap(client, wv, flags, flooded, ezekiel);
    }

    /**
     * Floods the walkable floor from a start tile, never further than
     * {@code radius} tiles from it on either axis. Unlike the Guardian room's
     * flood, a step is also refused through a wall on a tile's edge, so a thin
     * wall keeps the floor beyond it out.
     */
    static boolean[][] floodArena(int[][] flags, int startX, int startY, int radius) {
        boolean[][] flooded = new boolean[flags.length][];
        for (int x = 0; x < flags.length; x++) {
            flooded[x] = new boolean[flags[x] == null ? 0 : flags[x].length];
        }
        if (!ArchiveArenaSimplifier.isWalkable(flags, startX, startY)) {
            return flooded;
        }
        int[][] steps = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] { startX, startY });
        flooded[startX][startY] = true;
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            for (int[] step : steps) {
                int nx = at[0] + step[0];
                int ny = at[1] + step[1];
                if (Math.abs(nx - startX) > radius || Math.abs(ny - startY) > radius) {
                    continue;
                }
                if (!canStep(flags, at[0], at[1], step[0], step[1]) || flooded[nx][ny]) {
                    continue;
                }
                flooded[nx][ny] = true;
                queue.add(new int[] { nx, ny });
            }
        }
        return flooded;
    }

    // One step north, south, east or west: the tile ahead must be walkable, with
    // no wall on the edge between the two.
    static boolean canStep(int[][] flags, int x, int y, int dx, int dy) {
        int nx = x + dx;
        int ny = y + dy;
        if (!ArchiveArenaSimplifier.isWalkable(flags, nx, ny)) {
            return false;
        }
        int out;
        int in;
        if (dx == 1) {
            out = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
            in = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
        } else if (dx == -1) {
            out = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
            in = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        } else if (dy == 1) {
            out = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
            in = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
        } else {
            out = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
            in = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        }
        return (flags[x][y] & out) == 0 && (flags[nx][ny] & in) == 0;
    }

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------

    // The measured arena as a picture, north at the top, so its size, walls and
    // middle rows can be read off the log and checked against the highlights.
    private void logArenaMap(Client client, WorldView wv, int[][] flags, boolean[][] flooded, NPC ezekiel) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int x = 0; x < flooded.length; x++) {
            for (int y = 0; y < flooded[x].length; y++) {
                if (flooded[x][y]) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
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
        Set<WorldPoint> boss = footprint(ezekiel);
        Player player = client.getLocalPlayer();
        WorldPoint you = player != null ? player.getWorldLocation() : null;

        log.emit("MAP", "Ezekiel's arena: {} floor tiles, x {}..{} y {}..{} (template SW {}). "
                        + ". floor, w floor with a wall on an edge, # blocked, o walkable outside the arena, E Ezekiel, @ you",
                arenaFloor.size(), sw.getX(), ne.getX(), sw.getY(), ne.getY(), FracturedArchiveHandler.tile(swTemplate));
        for (int y = maxY; y >= minY; y--) {
            StringBuilder row = new StringBuilder();
            for (int x = minX; x <= maxX; x++) {
                WorldPoint tile = WorldPoint.fromScene(wv, x, y, plane);
                char c;
                if (tile.equals(you)) {
                    c = '@';
                } else if (boss.contains(tile)) {
                    c = 'E';
                } else if (flooded[x][y]) {
                    c = (flags[x][y] & WALL_EDGE_FLAGS) != 0 ? 'w' : '.';
                } else {
                    c = ArchiveArenaSimplifier.isWalkable(flags, x, y) ? 'o' : '#';
                }
                row.append(c);
            }
            log.emit("MAP", "{} |{}|", WorldPoint.fromScene(wv, minX, y, plane).getY(), row);
        }
    }

    // Each enrage sword with its offset from Ezekiel's centre tile (he is 3x3, so
    // his south-west tile +1), which names it front or side whichever way he faces.
    private void logEnrageSword(Client client, NPC npc) {
        WorldPoint at = npc.getWorldLocation();
        NPC ezekiel = findEzekiel(client);
        WorldPoint sw = ezekiel != null ? ezekiel.getWorldLocation() : null;
        if (at == null || sw == null) {
            log.emit("ENRG", "sword #{} @{}", npc.getIndex(), FracturedArchiveHandler.tile(at));
            return;
        }
        log.emit("ENRG", "sword #{} @{}, dx {} dy {} from Ezekiel's centre", npc.getIndex(),
                FracturedArchiveHandler.tile(at), at.getX() - (sw.getX() + 1), at.getY() - (sw.getY() + 1));
    }

    // Your tile every tick while the enrage swords are up. HIT lines only show
    // where you stood when a strike landed, not the tiles you dodged to.
    private void logEnrageTile(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == ENRAGE_SWORD_ID) {
                Player player = client.getLocalPlayer();
                log.emit("ENRG", "you @{}",
                        FracturedArchiveHandler.tile(player != null ? player.getWorldLocation() : null));
                return;
            }
        }
    }

    /**
     * Draws one ground-graphic group as a picture, north at the top, with Ezekiel,
     * the swords and you on it, so a telegraph's exact shape can be read straight
     * off the log. The slam's three graphics share their tiles, so a picture
     * already drawn this tick is not drawn again.
     */
    void logShape(Client client, int graphicId, List<WorldPoint> tiles) {
        Set<WorldPoint> graphic = new HashSet<>();
        for (WorldPoint tile : tiles) {
            if (tile != null) {
                graphic.add(tile);
            }
        }
        int tick = client.getTickCount();
        if (graphic.isEmpty() || (tick == lastPictureTick && graphic.equals(lastPicture))) {
            return;
        }
        lastPicture = graphic;
        lastPictureTick = tick;

        Set<WorldPoint> boss = footprint(findEzekiel(client));
        Set<WorldPoint> swords = new HashSet<>();
        for (Sword sword : wave) {
            if (!sword.gone) {
                swords.addAll(footprint(sword.npc));
            }
        }
        Player player = client.getLocalPlayer();
        WorldPoint you = player != null ? player.getWorldLocation() : null;

        // The picture covers the graphic, Ezekiel and you; swords only where they fall inside it.
        List<WorldPoint> framed = new ArrayList<>(graphic);
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
            log.emit("SHAPE", "graphic {} spans more than {} tiles, not drawn", graphicId, MAX_PICTURE_SIZE);
            return;
        }
        log.emit("SHAPE", "graphic {} x{}, columns x {}..{}: # graphic, E Ezekiel, S sword, @ you "
                + "(x, s, ! = graphic on Ezekiel, a sword, you)", graphicId, graphic.size(), minX, maxX);
        for (String row : picture(minX, maxX, minY, maxY, graphic, boss, swords, you)) {
            log.emit("SHAPE", "{}", row);
        }
    }

    // Rows of the SHAPE picture from north to south, each led by its y.
    static List<String> picture(int minX, int maxX, int minY, int maxY, Set<WorldPoint> graphic,
            Set<WorldPoint> boss, Set<WorldPoint> swords, WorldPoint you) {
        List<String> rows = new ArrayList<>();
        int plane = graphic.isEmpty() ? 0 : graphic.iterator().next().getPlane();
        for (int y = maxY; y >= minY; y--) {
            StringBuilder row = new StringBuilder().append(y).append(" |");
            for (int x = minX; x <= maxX; x++) {
                WorldPoint tile = new WorldPoint(x, y, plane);
                boolean hit = graphic.contains(tile);
                if (tile.equals(you)) {
                    row.append(hit ? '!' : '@');
                } else if (boss.contains(tile)) {
                    row.append(hit ? 'x' : 'E');
                } else if (swords.contains(tile)) {
                    row.append(hit ? 's' : 'S');
                } else {
                    row.append(hit ? '#' : '.');
                }
            }
            rows.add(row.append('|').toString());
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static NPC findEzekiel(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == EZEKIEL_ID) {
                return npc;
            }
        }
        return null;
    }

    // Matched by name too, in case another of his attacks uses a sword variant
    // with its own id.
    static boolean isSword(NPC npc) {
        if (npc == null) {
            return false;
        }
        return npc.getId() == SWORD_ID
                || (npc.getName() != null && Text.removeTags(npc.getName()).equalsIgnoreCase("Animated Sword"));
    }

    // Every tile an NPC covers; its reported location is the south-west one.
    // Also used for Moia's 3x3 by FracturedArchiveHandler.
    static Set<WorldPoint> footprint(NPC npc) {
        Set<WorldPoint> tiles = new HashSet<>();
        WorldPoint sw = npc != null ? npc.getWorldLocation() : null;
        if (sw == null) {
            return tiles;
        }
        NPCComposition composition = npc.getComposition();
        int size = composition != null ? composition.getSize() : 1;
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                tiles.add(sw.dx(dx).dy(dy));
            }
        }
        return tiles;
    }

    // Tiles sorted south to north, then west to east, for stable log lines.
    private static String describe(Collection<WorldPoint> tiles) {
        List<WorldPoint> sorted = new ArrayList<>(tiles);
        sorted.sort(Comparator.comparingInt(WorldPoint::getY).thenComparingInt(WorldPoint::getX));
        return FracturedArchiveHandler.describeTiles(sorted, FracturedArchiveHandler.MAX_TILES_PER_LINE);
    }
}
