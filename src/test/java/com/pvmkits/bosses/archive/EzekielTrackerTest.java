package com.pvmkits.bosses.archive;

import com.pvmkits.bosses.archive.EzekielTracker.Formation;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EzekielTrackerTest {

    private static final Predicate<WorldPoint> ANYWHERE = tile -> true;

    private static WorldPoint p(int x, int y) {
        return new WorldPoint(x, y, 0);
    }

    // The swords' spawn tiles from the one logged attack (2026-10-22): north,
    // south, west and east of the arena.
    private static final List<WorldPoint> LOGGED_CONVERGE = List.of(
            p(10715, 5284), p(10715, 5272), p(10712, 5278), p(10724, 5278));

    @Test
    public void theLoggedSwordsConvergeWhereTheirLanesCross() {
        assertEquals(Formation.CONVERGE, EzekielTracker.classify(LOGGED_CONVERGE));
        // The north/south pair's column crossed with the west/east pair's row.
        assertEquals(p(10715, 5278), EzekielTracker.convergenceArea(LOGGED_CONVERGE));
    }

    // Every tile touching the 3x3 is in a sword's lane except its four corners.
    @Test
    public void convergeSafeTilesAreTheCornersNoLaneCrosses() {
        WorldPoint area = EzekielTracker.convergenceArea(LOGGED_CONVERGE);
        assertEquals(Set.of(p(10714, 5277), p(10718, 5277), p(10714, 5281), p(10718, 5281)),
                EzekielTracker.convergeSafeTiles(area, LOGGED_CONVERGE, ANYWHERE));
    }

    // The swords span x 10712..10726 and y 5272..5286, so the arena's middle is
    // 10719,5279. The row runs right through it and the column is 3 off, so
    // only the row is kept.
    @Test
    public void convergeKeepsTheLineThroughTheMiddleOfTheArena() {
        WorldPoint area = EzekielTracker.convergenceArea(LOGGED_CONVERGE);
        Set<WorldPoint> expected = new HashSet<>();
        for (int x = 10715; x <= 10723; x++) {
            expected.add(p(x, 5279));
        }
        assertEquals(expected, EzekielTracker.convergeLines(area, LOGGED_CONVERGE, ANYWHERE));
    }

    // A 20:51 wave: the column runs down the middle of the 9x9 floor (x 12831)
    // and the row is 3 tiles south of it, by the edge, so only the column is kept.
    @Test
    public void convergeKeepsTheColumnWhenItIsInTheMiddle() {
        List<WorldPoint> homes = List.of(p(12830, 6232), p(12824, 6235), p(12836, 6235), p(12830, 6244));
        WorldPoint area = EzekielTracker.convergenceArea(homes);
        Set<WorldPoint> expected = new HashSet<>();
        for (int y = 6235; y <= 6243; y++) {
            expected.add(p(12831, y));
        }
        assertEquals(expected, EzekielTracker.convergeLines(area, homes, ANYWHERE));
    }

    // The swords meet near a corner here, so both lines are 3 tiles off the
    // middle (12447,3551) and both are kept.
    @Test
    public void convergeLinesNeverTouchASafeTileOrASword() {
        List<WorldPoint> homes = List.of(p(12452, 3553), p(12443, 3556), p(12440, 3553), p(12443, 3544));
        WorldPoint area = EzekielTracker.convergenceArea(homes);
        Set<WorldPoint> lines = EzekielTracker.convergeLines(area, homes, ANYWHERE);
        assertEquals(17, lines.size());
        for (WorldPoint safe : EzekielTracker.convergeSafeTiles(area, homes, ANYWHERE)) {
            assertFalse(lines.contains(safe));
        }
        for (WorldPoint home : homes) {
            for (int dx = 0; dx < EzekielTracker.SWORD_SIZE; dx++) {
                for (int dy = 0; dy < EzekielTracker.SWORD_SIZE; dy++) {
                    assertFalse(lines.contains(home.dx(dx).dy(dy)));
                }
            }
        }
    }

    // t2559 on 2026-10-23, 9x9 floor x 11867..11875, y 9499..9507: the lines
    // cross at 11874,9506 and each runs one tile on, north and east, so the
    // nook is the floor's north-east corner.
    @Test
    public void crossingLinesLeaveANookBetweenTheirStubs() {
        List<WorldPoint> homes = List.of(p(11873, 9496), p(11864, 9505), p(11876, 9505), p(11873, 9508));
        Predicate<WorldPoint> floor = tile -> tile.getX() >= 11867 && tile.getX() <= 11875
                && tile.getY() >= 9499 && tile.getY() <= 9507;
        WorldPoint area = EzekielTracker.convergenceArea(homes);
        Set<WorldPoint> lines = EzekielTracker.convergeLines(area, homes, floor);
        assertEquals(17, lines.size());
        assertEquals(Set.of(p(11875, 9507)), EzekielTracker.convergeNooks(area, lines, floor));
    }

    // t8476 on 2026-10-23: the stubs run north and west, so the nook is north-west.
    @Test
    public void theNookFollowsTheStubs() {
        List<WorldPoint> homes = List.of(p(13403, 2392), p(13400, 2401), p(13412, 2401), p(13403, 2404));
        Predicate<WorldPoint> floor = tile -> tile.getX() >= 13403 && tile.getX() <= 13411
                && tile.getY() >= 2395 && tile.getY() <= 2403;
        WorldPoint area = EzekielTracker.convergenceArea(homes);
        Set<WorldPoint> lines = EzekielTracker.convergeLines(area, homes, floor);
        assertEquals(Set.of(p(13403, 2403)), EzekielTracker.convergeNooks(area, lines, floor));
    }

    @Test
    public void aSingleLineHasNoNook() {
        WorldPoint area = EzekielTracker.convergenceArea(LOGGED_CONVERGE);
        Set<WorldPoint> lines = EzekielTracker.convergeLines(area, LOGGED_CONVERGE, ANYWHERE);
        assertTrue(EzekielTracker.convergeNooks(area, lines, ANYWHERE).isEmpty());
    }

    @Test
    public void convergeSafeTilesSkipWalls() {
        WorldPoint area = EzekielTracker.convergenceArea(LOGGED_CONVERGE);
        Predicate<WorldPoint> floor = tile -> !tile.equals(p(10714, 5277));
        assertEquals(3, EzekielTracker.convergeSafeTiles(area, LOGGED_CONVERGE, floor).size());
    }

    // The third wave of the 15:21 kill: the west/east pair moved up 3 rows, so
    // the meeting 3x3 sat right against the north and west swords. You bled for
    // 54 fourteen ticks in, beside Ezekiel at 12444,3549 - in the north/south
    // column lane, not on a safe corner.
    @Test
    public void theMeetingAreaFollowsTheSwordsWhenTheirRowMoves() {
        List<WorldPoint> homes = List.of(p(12452, 3553), p(12443, 3556), p(12440, 3553), p(12443, 3544));
        assertEquals(Formation.CONVERGE, EzekielTracker.classify(homes));
        WorldPoint area = EzekielTracker.convergenceArea(homes);
        assertEquals(p(12443, 3553), area);
        assertEquals(Set.of(p(12442, 3552), p(12446, 3552), p(12442, 3556), p(12446, 3556)),
                EzekielTracker.convergeSafeTiles(area, homes, ANYWHERE));
        assertTrue(EzekielTracker.inLane(p(12444, 3549), p(12443, 3544), area));
    }

    // The logged swords flew on to the far edge, so a sword's lane runs right
    // across the arena, not only up to the meeting area.
    @Test
    public void aSwordsLaneRunsAcrossTheWholeArena() {
        WorldPoint area = p(10715, 5278);
        WorldPoint north = p(10715, 5284);
        assertTrue(EzekielTracker.inLane(p(10716, 5273), north, area));
        assertFalse(EzekielTracker.inLane(p(10718, 5279), north, area));
        WorldPoint west = p(10712, 5278);
        assertTrue(EzekielTracker.inLane(p(10725, 5280), west, area));
        assertFalse(EzekielTracker.inLane(p(10713, 5281), west, area));
    }

    // Two swords stacked on each of the west and east sides, sweeping rows
    // 0-5 and 8-13 of a 15-wide arena: rows 6 and 7 are the safe zone.
    @Test
    public void fourSwordsLeaveTheRowsBetweenThemSafe() {
        List<WorldPoint> homes = List.of(p(0, 0), p(0, 3), p(12, 8), p(12, 11));
        assertEquals(Formation.FOUR_SWORDS, EzekielTracker.classify(homes));

        Set<WorldPoint> expected = new HashSet<>();
        for (int x = 0; x <= 14; x++) {
            expected.add(p(x, 6));
            expected.add(p(x, 7));
        }
        assertEquals(expected, EzekielTracker.fourSwordSafeTiles(homes, ANYWHERE));
    }

    @Test
    public void fourSwordSafeZoneSkipsWalls() {
        List<WorldPoint> homes = List.of(p(0, 0), p(0, 3), p(12, 8), p(12, 11));
        Predicate<WorldPoint> floor = tile -> tile.getX() >= 1 && tile.getX() <= 13;
        assertEquals(26, EzekielTracker.fourSwordSafeTiles(homes, floor).size());
    }

    @Test
    public void threeSwordsInALineAreASwipe() {
        assertEquals(Formation.SWIPE, EzekielTracker.classify(List.of(p(0, 0), p(0, 3), p(0, 6))));
        assertEquals(Formation.SWIPE, EzekielTracker.classify(List.of(p(0, 12), p(3, 12), p(6, 12))));
        assertEquals(Formation.NONE, EzekielTracker.classify(List.of(p(0, 0), p(3, 3), p(6, 6))));
    }

    // Swords stacked on the west edge (x 0..2) fire east into an arena of x 0..14.
    // Firing bottom to top, the middle sword is second-last and the top one last:
    // A is in the middle sword's lane on its top row, B in the top sword's lane
    // on its bottom row, both 4 tiles out (x 6; x 3 is the tile just in front).
    @Test
    public void swipeTilesSitWhereTheLastTwoLanesMeet() {
        Predicate<WorldPoint> arena = tile -> tile.getX() >= 0 && tile.getX() <= 14;
        List<WorldPoint> bottomFirst = List.of(p(0, 0), p(0, 3), p(0, 6));
        assertEquals(List.of(p(6, 5), p(6, 6)), EzekielTracker.swipeSafeTiles(bottomFirst, arena));

        List<WorldPoint> topFirst = List.of(p(0, 6), p(0, 3), p(0, 0));
        assertEquals(List.of(p(6, 3), p(6, 2)), EzekielTracker.swipeSafeTiles(topFirst, arena));
    }

    @Test
    public void swipeTilesFaceTheArenaFromTheEastEdgeToo() {
        Predicate<WorldPoint> arena = tile -> tile.getX() >= 0 && tile.getX() <= 14;
        List<WorldPoint> bottomFirst = List.of(p(12, 0), p(12, 3), p(12, 6));
        assertEquals(List.of(p(8, 5), p(8, 6)), EzekielTracker.swipeSafeTiles(bottomFirst, arena));
    }

    // A tile that is not floor 4 out comes back towards the swords until one is.
    @Test
    public void swipeTilesStepBackOffUnwalkableTiles() {
        Predicate<WorldPoint> arena = tile -> tile.getX() >= 0 && tile.getX() <= 14
                && !tile.equals(p(6, 6)) && !tile.equals(p(5, 6));
        List<WorldPoint> bottomFirst = List.of(p(0, 0), p(0, 3), p(0, 6));
        assertEquals(List.of(p(6, 5), p(4, 6)), EzekielTracker.swipeSafeTiles(bottomFirst, arena));
    }

    // Five swipes on 2026-10-23 (t2698, t8449, t8512, t8580, t8647): the bars
    // showed at +0, +1 and +2 and the swords fired at +14, +15 and +16 in that
    // same order.
    @Test
    public void theEndWhoseBarShowsFirstFiresFirst() {
        assertEquals(Integer.valueOf(2), EzekielTracker.firstEndByBarTicks(2700, 2698));
        assertEquals(Integer.valueOf(0), EzekielTracker.firstEndByBarTicks(10, 12));
        // On the spawn tick only the first sword's bar is up (t8449: high end).
        assertEquals(Integer.valueOf(2), EzekielTracker.firstEndByBarTicks(-1, 8449));
        assertEquals(Integer.valueOf(0), EzekielTracker.firstEndByBarTicks(8647, -1));
        assertNull(EzekielTracker.firstEndByBarTicks(-1, -1));
        assertNull(EzekielTracker.firstEndByBarTicks(5, 5));
    }

    // A bar drains while its sword charges, so the lower one fires first.
    @Test
    public void theLowerBarFiresFirst() {
        assertEquals(Integer.valueOf(0), EzekielTracker.firstEndByBars(0.2, 0.8));
        assertEquals(Integer.valueOf(2), EzekielTracker.firstEndByBars(0.9, 0.1));
        assertNull(EzekielTracker.firstEndByBars(0.5, 0.5));
        assertNull(EzekielTracker.firstEndByBars(Double.NaN, 0.5));
        assertTrue(Double.isNaN(EzekielTracker.barFraction(-1, 30)));
        assertEquals(0.5, EzekielTracker.barFraction(15, 30), 1e-9);
    }

    // A plus of five tiles: its border is every tile around it, diagonals
    // included, that is not part of it.
    @Test
    public void slamSafeTilesRingTheTelegraph() {
        Set<WorldPoint> plus = Set.of(p(5, 5), p(4, 5), p(6, 5), p(5, 4), p(5, 6));
        Set<WorldPoint> border = EzekielTracker.borderTiles(plus, ANYWHERE);
        assertEquals(16, border.size());
        assertTrue(border.contains(p(4, 4)));
        assertTrue(border.contains(p(3, 5)));
        assertTrue(border.contains(p(3, 4)));
        assertFalse(border.contains(p(5, 5)));
        assertFalse(border.contains(p(3, 3)));
    }

    @Test
    public void slamSafeTilesSkipWalls() {
        Set<WorldPoint> single = Set.of(p(5, 5));
        Predicate<WorldPoint> floor = tile -> tile.getX() <= 5;
        assertEquals(5, EzekielTracker.borderTiles(single, floor).size());
    }

    // A thin wall on a tile edge (not a blocked tile) still stops the flood.
    @Test
    public void theArenaFloodStopsAtEdgeWalls() {
        int[][] flags = new int[6][6];
        for (int y = 0; y < 6; y++) {
            flags[2][y] |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
            flags[3][y] |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
        }
        boolean[][] flooded = EzekielTracker.floodArena(flags, 0, 0, 10);
        assertTrue(flooded[2][5]);
        assertFalse(flooded[3][0]);
        assertFalse(flooded[5][5]);
    }

    @Test
    public void theArenaFloodStaysInsideItsRadius() {
        boolean[][] flooded = EzekielTracker.floodArena(new int[10][10], 0, 0, 3);
        assertTrue(flooded[3][3]);
        assertFalse(flooded[4][0]);
    }

    // The four-sword wave at t2239 of the 20:49 kill (2026-10-22): swords on the
    // west and east edges leave row 6239 clear, but the slam that came with
    // them covered 12832..12834 of it. You stood on 12832,6239 and took 25 and
    // 27 at +3 and +5.
    private static final Set<WorldPoint> T2239_TELEGRAPH = Set.of(
            p(12833, 6237), p(12833, 6238), p(12832, 6239), p(12833, 6239), p(12834, 6239),
            p(12831, 6240), p(12832, 6240), p(12833, 6240), p(12834, 6240), p(12835, 6240),
            p(12830, 6241), p(12831, 6241), p(12835, 6241), p(12828, 6242), p(12829, 6242),
            p(12830, 6242), p(12831, 6242), p(12835, 6242), p(12830, 6243), p(12831, 6243),
            p(12835, 6243));
    private static final List<WorldPoint> T2239_SWORDS = List.of(
            p(12824, 6236), p(12836, 6236), p(12824, 6240), p(12836, 6240));
    private static final Predicate<WorldPoint> T2239_ARENA = tile -> tile.getX() >= 12827
            && tile.getX() <= 12835 && tile.getY() >= 6235 && tile.getY() <= 6243;

    private static Set<WorldPoint> square(WorldPoint sw, int size) {
        Set<WorldPoint> tiles = new HashSet<>();
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                tiles.add(sw.dx(dx).dy(dy));
            }
        }
        return tiles;
    }

    // The t2239 zone is the single row y 6239 across x 12827..12835: blue at
    // 6237 and 6241, green at 6235 and 6243 (the arena's edges), with 6236,
    // 6238, 6240 and 6242 as the gaps.
    @Test
    public void theFourSwordLinesRunATileApartOutToTheEdges() {
        Set<WorldPoint> zone = EzekielTracker.fourSwordSafeTiles(T2239_SWORDS, T2239_ARENA);
        Set<WorldPoint> sides = new HashSet<>();
        Set<WorldPoint> outer = new HashSet<>();
        for (int x = 12827; x <= 12835; x++) {
            assertTrue(zone.contains(p(x, 6239)));
            sides.add(p(x, 6237));
            sides.add(p(x, 6241));
            outer.add(p(x, 6235));
            outer.add(p(x, 6243));
        }
        assertEquals(sides, EzekielTracker.fourSwordLines(T2239_SWORDS, zone,
                EzekielTracker.SIDE_LINE_OFFSET, T2239_ARENA));
        assertEquals(outer, EzekielTracker.fourSwordLines(T2239_SWORDS, zone,
                EzekielTracker.OUTER_LINE_OFFSET, T2239_ARENA));
    }

    // A two-row zone (rows 6 and 7) puts the lines out from its edges: blue at
    // 4 and 9, green at 2 and 11.
    @Test
    public void theFourSwordLinesStartFromTheZonesEdges() {
        List<WorldPoint> homes = List.of(p(0, 0), p(0, 3), p(12, 8), p(12, 11));
        Set<WorldPoint> zone = EzekielTracker.fourSwordSafeTiles(homes, ANYWHERE);
        Set<WorldPoint> sides = new HashSet<>();
        Set<WorldPoint> outer = new HashSet<>();
        for (int x = 0; x <= 14; x++) {
            sides.add(p(x, 4));
            sides.add(p(x, 9));
            outer.add(p(x, 2));
            outer.add(p(x, 11));
        }
        assertEquals(sides, EzekielTracker.fourSwordLines(homes, zone, EzekielTracker.SIDE_LINE_OFFSET, ANYWHERE));
        assertEquals(outer, EzekielTracker.fourSwordLines(homes, zone, EzekielTracker.OUTER_LINE_OFFSET, ANYWHERE));
    }

    @Test
    public void theFourSwordZoneLeavesOutTheSlamsTiles() {
        Set<WorldPoint> zone = EzekielTracker.fourSwordSafeTiles(T2239_SWORDS, T2239_ARENA);
        assertEquals(9, zone.size());
        Set<WorldPoint> boss = square(p(12832, 6241), 3);
        Set<WorldPoint> shown = EzekielTracker.withoutTiles(zone,
                EzekielTracker.slamDanger(T2239_TELEGRAPH, boss));
        assertEquals(6, shown.size());
        assertFalse(shown.contains(p(12832, 6239)));
        assertTrue(shown.contains(p(12831, 6239)));
        assertTrue(shown.contains(p(12835, 6239)));
        // No slam: the whole zone.
        assertEquals(zone, EzekielTracker.withoutTiles(zone, Set.of()));
    }

    // The telegraph rings Ezekiel without covering him, so its border took in
    // seven of his own nine tiles; those are not safe.
    @Test
    public void slamSafeTilesLeaveOutTheTilesUnderEzekiel() {
        Set<WorldPoint> boss = square(p(12832, 6241), 3);
        Set<WorldPoint> ring = EzekielTracker.borderTiles(T2239_TELEGRAPH, T2239_ARENA);
        Set<WorldPoint> safe = EzekielTracker.slamSafeTiles(T2239_TELEGRAPH, boss, T2239_ARENA);
        assertEquals(28, ring.size());
        assertEquals(21, safe.size());
        for (WorldPoint tile : boss) {
            assertFalse(safe.contains(tile));
        }
    }

    // Only the safe tiles within a one-tile gap of you show: straight, diagonal
    // and the L-shape, but not two diagonals out.
    @Test
    public void slamSafeTilesNearYouAreTheOnlyOnesShown() {
        WorldPoint you = p(10, 10);
        Set<WorldPoint> safe = Set.of(p(10, 10), p(12, 10), p(11, 11), p(12, 11), p(9, 12),
                p(12, 12), p(13, 10), p(20, 20));
        assertEquals(Set.of(p(10, 10), p(12, 10), p(11, 11), p(12, 11), p(9, 12)),
                EzekielTracker.nearSafeTiles(safe, you));
    }

    // With none that close, or your tile unknown, the whole ring shows.
    @Test
    public void slamSafeTilesFallBackToTheWholeRing() {
        Set<WorldPoint> safe = Set.of(p(12, 12), p(13, 10), p(20, 20));
        assertEquals(safe, EzekielTracker.nearSafeTiles(safe, p(10, 10)));
        assertEquals(safe, EzekielTracker.nearSafeTiles(safe, null));
    }

    // Ezekiel's turns in the 15:21 kill (2026-10-22): 14685 autos, 14687
    // specials and one 14688. Each special came on the tick the rhythm predicts.
    @Test
    public void theRhythmPredictsEveryLoggedSpecial() {
        List<String> logged = new ArrayList<>();
        EzekielTracker tracker = new EzekielTracker((tag, format, args) -> logged.add(Arrays.toString(args)));

        autos(tracker, 23755, 23760);
        assertEquals(23765, tracker.predictedSpecialTick());
        tracker.onEzekielAnimation(EzekielTracker.SPECIAL_ANIM, 23765);

        autos(tracker, 23772, 23777, 23782);
        assertEquals(-1, tracker.predictedSpecialTick());
        autos(tracker, 23787);
        assertEquals(23792, tracker.predictedSpecialTick());
        tracker.onEzekielAnimation(EzekielTracker.SPECIAL_ANIM, 23792);

        autos(tracker, 23799, 23804);
        tracker.onEzekielAnimation(EzekielTracker.STRENGTH_ANIM, 23808);
        autos(tracker, 23813, 23818);
        assertEquals(23823, tracker.predictedSpecialTick());
        tracker.onEzekielAnimation(EzekielTracker.SPECIAL_ANIM, 23823);

        // Each line: the cue, autos since the last special, the gap, the prediction.
        assertEquals(List.of(
                "[anim 14687, 2, none, this tick]",
                "[anim 14687, 4, +27t, this tick]",
                "[anim 14687, 4, +31t, this tick]"), logged);
    }

    // 14688 takes a turn without being an auto, so after the 4th auto it pushes
    // the special back to 5 ticks after itself.
    @Test
    public void strengthPushesTheSpecialBack() {
        EzekielTracker tracker = new EzekielTracker((tag, format, args) -> { });
        autos(tracker, 0, 5);
        tracker.onEzekielAnimation(EzekielTracker.SPECIAL_ANIM, 10);
        autos(tracker, 17, 22, 27, 32);
        tracker.onEzekielAnimation(EzekielTracker.STRENGTH_ANIM, 36);
        assertEquals(41, tracker.predictedSpecialTick());
    }

    // The animation event can fire twice in one tick; that is still one auto.
    @Test
    public void anAutoIsCountedOncePerTick() {
        EzekielTracker tracker = new EzekielTracker((tag, format, args) -> { });
        autos(tracker, 0, 0);
        assertEquals(-1, tracker.predictedSpecialTick());
        autos(tracker, 5);
        assertEquals(10, tracker.predictedSpecialTick());
    }

    private static void autos(EzekielTracker tracker, int... ticks) {
        for (int tick : ticks) {
            tracker.onEzekielAnimation(EzekielTracker.AUTO_ATTACK_ANIM, tick);
        }
    }

    @Test
    public void shapePictureMarksTheGraphicOnEzekielSwordsAndYou() {
        List<String> rows = EzekielTracker.picture(0, 3, 0, 1,
                Set.of(p(0, 1), p(1, 0), p(3, 1)),
                Set.of(p(1, 0), p(1, 1)),
                Set.of(p(3, 1)),
                p(0, 1));
        assertEquals(List.of("1 |!E.s|", "0 |.x..|"), rows);
    }

    // The first enrage cycle of the 20:53 kill (2026-10-23), both strike graphics
    // together. Ezekiel stood north of the 3x3 (x 11870..11872, y 6430..6432), so
    // the back row is y 6430. The swords left the east, then the front, then the
    // west one dark, and each strike missed that sword's side.
    @Test
    public void enrageSafeTilesAreTheTilesOfTheThreeByThreeEachStrikeMisses() {
        Map<Integer, Set<WorldPoint>> struck = Map.of(
                2437, Set.of(p(11870, 6432), p(11871, 6432), p(11871, 6431),
                        p(11872, 6432), p(11870, 6431), p(11870, 6430), p(11871, 6430)),
                2438, Set.of(p(11870, 6431), p(11871, 6431),
                        p(11872, 6432), p(11870, 6432), p(11871, 6432), p(11872, 6431)),
                2439, Set.of(p(11871, 6431), p(11872, 6431), p(11872, 6430),
                        p(11872, 6432), p(11870, 6432), p(11871, 6432), p(11871, 6430)));

        Map<Integer, Set<WorldPoint>> safe = EzekielTracker.enrageSafeTiles(struck);

        assertEquals(Set.of(p(11872, 6431), p(11872, 6430)), safe.get(2437));
        assertEquals(Set.of(p(11870, 6430), p(11871, 6430), p(11872, 6430)), safe.get(2438));
        assertEquals(Set.of(p(11870, 6431), p(11870, 6430)), safe.get(2439));
    }
}
