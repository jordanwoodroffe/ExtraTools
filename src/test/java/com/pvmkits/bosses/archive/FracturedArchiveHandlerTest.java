package com.pvmkits.bosses.archive;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FracturedArchiveHandlerTest {

    @Test
    public void recognisesTheAnnouncedEncounterNames() {
        assertTrue(FracturedArchiveHandler.isArchiveName("The Archive Guardian"));
        assertTrue(FracturedArchiveHandler.isArchiveName("Smork"));
        assertTrue(FracturedArchiveHandler.isArchiveName("Ezekiel"));
        assertTrue(FracturedArchiveHandler.isArchiveName("Dreadnoth the Jellied"));
        assertTrue(FracturedArchiveHandler.isArchiveName("Xarth"));
        assertTrue(FracturedArchiveHandler.isArchiveName("Dagon'hai elder"));
        assertTrue(FracturedArchiveHandler.isArchiveName("Hooded figure"));
        // Colour tags on the name must not hide it.
        assertTrue(FracturedArchiveHandler.isArchiveName("<col=ff0000>Archive Guardian</col>"));
    }

    // Bork also has his own instance in the Chaos Tunnels, so he alone must not
    // latch the logger; only his Smorks are unique to the Archive.
    @Test
    public void ignoresBorkAndUnrelatedNames() {
        assertFalse(FracturedArchiveHandler.isArchiveName("Bork"));
        assertFalse(FracturedArchiveHandler.isArchiveName("Doom of Mokhaiotl"));
        assertFalse(FracturedArchiveHandler.isArchiveName(null));
    }

    @Test
    public void describesAGroupOfTilesWithItsBoundingBox() {
        List<WorldPoint> tiles = Arrays.asList(
                new WorldPoint(3200, 4100, 0),
                new WorldPoint(3202, 4103, 0),
                new WorldPoint(3201, 4101, 0));
        assertEquals("[3200,4100 3202,4103 3201,4101] bbox 3200..3202 x 4100..4103 p0",
                FracturedArchiveHandler.describeTiles(tiles, 12));
    }

    @Test
    public void truncatesLongTileGroupsButKeepsTheFullBoundingBox() {
        List<WorldPoint> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            tiles.add(new WorldPoint(3000 + x, 3000, 1));
        }
        assertEquals("[3000,3000 3001,3000 +3 more] bbox 3000..3004 x 3000..3000 p1",
                FracturedArchiveHandler.describeTiles(tiles, 2));
    }

    @Test
    public void singleAndUnknownTilesStayReadable() {
        assertEquals("[3000,3000] p2",
                FracturedArchiveHandler.describeTiles(List.of(new WorldPoint(3000, 3000, 2)), 12));
        assertEquals("[]", FracturedArchiveHandler.describeTiles(Arrays.asList((WorldPoint) null), 12));
    }

    // The attack tick shows 5, counting down to "1" on the tick right before the
    // next attack (the step-under tick), then 5 again on that attack - and it
    // keeps that beat if an attack is skipped.
    @Test
    public void stepTimerReachesOneOnTheTickBeforeTheNextAttack() {
        int[] expected = {5, 4, 3, 2, 1, 5, 4, 3, 2, 1, 5};
        for (int since = 0; since < expected.length; since++) {
            assertEquals("ticks since attack " + since,
                    expected[since], FracturedArchiveHandler.stepTimerValue(since));
        }
    }

    // Two slams in a row once left a 15-tick gap between counted attacks and hid
    // the timer; the slam takes an attack slot like the ranged attack does.
    @Test
    public void aSlamTakesAnAttackSlot() {
        assertEquals(Integer.valueOf(200), FracturedArchiveHandler.cycleStartTick(14752, 200));
        assertEquals(Integer.valueOf(200), FracturedArchiveHandler.cycleStartTick(14751, 200));
        assertNull(FracturedArchiveHandler.cycleStartTick(14747, 200));
    }

    // A reposition at tick 100 puts the next attack on 103, so "1" shows on 102.
    @Test
    public void aRepositionResyncsTheTimerToTheDelayedAttack() {
        int start = FracturedArchiveHandler.cycleStartTick(14750, 100);
        assertEquals(3, FracturedArchiveHandler.stepTimerValue(100 - start));
        assertEquals(1, FracturedArchiveHandler.stepTimerValue(102 - start));
        assertEquals(5, FracturedArchiveHandler.stepTimerValue(103 - start));
    }

    // Waking at tick 50, it first attacks on 54.
    @Test
    public void wakingStartsTheTimerBeforeTheFirstAttack() {
        int start = FracturedArchiveHandler.cycleStartTick(14745, 50);
        assertEquals(1, FracturedArchiveHandler.stepTimerValue(53 - start));
        assertEquals(5, FracturedArchiveHandler.stepTimerValue(54 - start));
    }

    // The warning covers the last 12% of the 550 shield (66 and below, including
    // the ticks it sits on 0), and never fires on a cleared HUD (max 0 or 1).
    @Test
    public void shieldWarningCoversTheLastTwelvePercent() {
        assertFalse(FracturedArchiveHandler.isShieldLow(67, 550));
        assertTrue(FracturedArchiveHandler.isShieldLow(66, 550));
        assertTrue(FracturedArchiveHandler.isShieldLow(0, 550));
        assertFalse(FracturedArchiveHandler.isShieldLow(0, 0));
        assertFalse(FracturedArchiveHandler.isShieldLow(0, 1));
    }

    // The beam hits 10 ticks after its animation starts: 10 on the start tick,
    // 0 on the hit tick (the tick to eat), hidden (-1) after it.
    @Test
    public void beamTickEatTimerCountsDownToZeroOnTheHit() {
        assertEquals(10, FracturedArchiveHandler.beamTicksToLand(0));
        assertEquals(1, FracturedArchiveHandler.beamTicksToLand(9));
        assertEquals(0, FracturedArchiveHandler.beamTicksToLand(10));
        assertEquals(-1, FracturedArchiveHandler.beamTicksToLand(11));
        assertEquals(-1, FracturedArchiveHandler.beamTicksToLand(-1));
    }

    // Logged charges (anim 14787 on T, burst damage on D): t9304 -> 9341 (11-07),
    // t6700 -> 6737 and t2630 -> 2667, t2671 -> 2706, t2710 -> 2743 (11-10).
    @Test
    public void moiaBurstLandsThirtySevenTicksAfterTheFirstChargeAndTwoSoonerEachAgain() {
        assertEquals(37, FracturedArchiveHandler.moiaBurstDelayTicks(1));
        assertEquals(35, FracturedArchiveHandler.moiaBurstDelayTicks(2));
        assertEquals(33, FracturedArchiveHandler.moiaBurstDelayTicks(3));
        assertEquals(2667, 2630 + FracturedArchiveHandler.moiaBurstDelayTicks(1));
        assertEquals(2706, 2671 + FracturedArchiveHandler.moiaBurstDelayTicks(2));
        assertEquals(2743, 2710 + FracturedArchiveHandler.moiaBurstDelayTicks(3));
        assertEquals(1, FracturedArchiveHandler.moiaBurstDelayTicks(100));
    }

    @Test
    public void beamTimerCoversSharksBrewsTeaFlasksAndPurpleSweets() {
        assertTrue(ArchiveTickEatOverlay.isCoveredFoodName("Shark"));
        assertTrue(ArchiveTickEatOverlay.isCoveredFoodName("Saradomin brew(4)"));
        assertTrue(ArchiveTickEatOverlay.isCoveredFoodName("Saradomin brew(1)"));
        assertTrue(ArchiveTickEatOverlay.isCoveredFoodName("Tea flask"));
        assertTrue(ArchiveTickEatOverlay.isCoveredFoodName("Purple sweets"));
        assertFalse(ArchiveTickEatOverlay.isCoveredFoodName("purple sweets"));
        assertFalse(ArchiveTickEatOverlay.isCoveredFoodName("Raw shark"));
        assertFalse(ArchiveTickEatOverlay.isCoveredFoodName("Shark lure"));
        assertFalse(ArchiveTickEatOverlay.isCoveredFoodName(null));
    }

    @Test
    public void formatsIdCountsInIdOrder() {
        Map<Integer, Integer> counts = new TreeMap<>();
        counts.put(9001, 3);
        counts.put(42, 1);
        assertEquals("{42x1, 9001x3}", FracturedArchiveHandler.formatCounts(counts));
        assertEquals("{}", FracturedArchiveHandler.formatCounts(new TreeMap<>()));
    }

    // Outside her enrage an axe first seen on tick A flies 6 ticks and lands its
    // damage on A+5, and only the prayer on at A+5 counts (71 of 71 logged axes).
    // Overlays read the tick a click lands on, so render ticks A+1..A+4 count
    // 5-4-3-2, A+5 is the Magic "1", and nothing shows on the part-tick the axe
    // appears on or after the Magic tick. Unchanged by the enrage fix.
    @Test
    public void moiaAxeCountdownPutsTheMagicTickOnTheAxeHit() {
        int axe = 15304;
        int hit = FracturedArchiveHandler.moiaProjectileLandTick(axe, 6);
        assertEquals(axe + 5, hit);
        assertEquals(0, FracturedArchiveHandler.moiaCountdownAt(hit, axe - 1));
        assertEquals(0, FracturedArchiveHandler.moiaCountdownAt(hit, axe));
        assertEquals(5, FracturedArchiveHandler.moiaCountdownAt(hit, axe + 1));
        assertEquals(4, FracturedArchiveHandler.moiaCountdownAt(hit, axe + 2));
        assertEquals(3, FracturedArchiveHandler.moiaCountdownAt(hit, axe + 3));
        assertEquals(2, FracturedArchiveHandler.moiaCountdownAt(hit, axe + 4));
        assertEquals(1, FracturedArchiveHandler.moiaCountdownAt(hit, axe + 5));
        assertEquals(0, FracturedArchiveHandler.moiaCountdownAt(hit, axe + 6));
        assertEquals(0, FracturedArchiveHandler.moiaCountdownAt(hit, axe + 25));
    }

    // In her enrage the axe flies 7 ticks and its damage lands on A+6 (2 of 2:
    // t9330 on 2026-11-07, t6726 on 2026-11-10, where Magic on A+5 and back to
    // Missiles took 29). The Magic "1" moves to A+6 and the 5 starts on A+2.
    @Test
    public void enrageAxeFliesATickLongerSoTheMagicTickIsAPlusSix() {
        int axe = 6726;
        int hit = FracturedArchiveHandler.moiaProjectileLandTick(axe, 7);
        assertEquals(axe + 6, hit);
        assertEquals(0, FracturedArchiveHandler.moiaCountdownAt(hit, axe + 1));
        assertEquals(5, FracturedArchiveHandler.moiaCountdownAt(hit, axe + 2));
        assertEquals(2, FracturedArchiveHandler.moiaCountdownAt(hit, axe + 5));
        assertEquals(1, FracturedArchiveHandler.moiaCountdownAt(hit, axe + 6));
        assertEquals(0, FracturedArchiveHandler.moiaCountdownAt(hit, axe + 7));
    }

    // The cough at t6442 on 2026-11-10: smoke 4218 first seen stamped t6443
    // (the frames right after the cough) with a 4-tick flight; "*Cough*" and
    // the poisoning on t6446, the first poison hitsplat on t6447. The countdown
    // is the flight, 4-3-2-1, gone as it poisons you; the same in all 29 logged.
    @Test
    public void coughCountdownRunsTheSmokesFourTickFlight() {
        int cough = 6442;
        int land = FracturedArchiveHandler.moiaProjectileLandTick(cough + 1, 4);
        assertEquals(6446, land);
        // Until the smoke shows, the land tick held is the previous cough's.
        assertEquals(0, FracturedArchiveHandler.moiaCoughCountdownAt(6386, cough));
        assertEquals(4, FracturedArchiveHandler.moiaCoughCountdownAt(land, cough + 1));
        assertEquals(3, FracturedArchiveHandler.moiaCoughCountdownAt(land, cough + 2));
        assertEquals(2, FracturedArchiveHandler.moiaCoughCountdownAt(land, cough + 3));
        assertEquals(1, FracturedArchiveHandler.moiaCoughCountdownAt(land, cough + 4));
        assertEquals(0, FracturedArchiveHandler.moiaCoughCountdownAt(land, cough + 5));
        // No smoke yet this fight.
        assertEquals(0, FracturedArchiveHandler.moiaCoughCountdownAt(-1, cough));
    }

    // The flight is the projectile's remaining client cycles in whole ticks,
    // rounded up, exactly as the PROJ lines have always logged it.
    @Test
    public void flightTicksRoundsRemainingCyclesUp() {
        assertEquals(6, FracturedArchiveHandler.flightTicks(180));
        assertEquals(6, FracturedArchiveHandler.flightTicks(151));
        assertEquals(7, FracturedArchiveHandler.flightTicks(181));
        assertEquals(7, FracturedArchiveHandler.flightTicks(210));
    }

    // The Venator highlight flickered off under a binder that never moved
    // whenever another binder spawned (every 30 ticks) or stepped. Only the
    // centre binder's own movement may hide it.
    @Test
    public void centreBinderStaysPickedWhileOtherBindersSpawnAndStep() {
        WorldPoint centre = new WorldPoint(11295, 15455, 0);
        List<WorldPoint> centres = List.of(centre);
        Map<String, WorldPoint> last = new TreeMap<>();
        last.put("centre", centre);
        last.put("walker", new WorldPoint(11291, 15459, 0));

        Map<String, WorldPoint> now = new TreeMap<>(last);
        now.put("walker", new WorldPoint(11292, 15458, 0));
        now.put("spawned", new WorldPoint(11291, 15459, 0));
        assertEquals("centre", FracturedArchiveHandler.pickCentreBinder(now, last, centres, "centre"));
    }

    @Test
    public void binderOnlyLightsUpAfterAWholeTickOnTheCentre() {
        WorldPoint centre = new WorldPoint(11295, 15455, 0);
        WorldPoint beside = new WorldPoint(11294, 15456, 0);
        List<WorldPoint> centres = List.of(centre);

        // Stepped onto the centre this tick: not yet.
        assertNull(FracturedArchiveHandler.pickCentreBinder(
                Map.of("a", centre), Map.of("a", beside), centres, null));
        // Spawned on the centre this tick: not yet either.
        assertNull(FracturedArchiveHandler.pickCentreBinder(
                Map.of("a", centre), Map.of(), centres, null));
        // Still there a tick later: lit.
        assertEquals("a", FracturedArchiveHandler.pickCentreBinder(
                Map.of("a", centre), Map.of("a", centre), centres, null));
        // Stepped off: gone.
        assertNull(FracturedArchiveHandler.pickCentreBinder(
                Map.of("a", beside), Map.of("a", centre), centres, "a"));
    }

    // Two binders can stack on the centre; the lit one must not swap.
    @Test
    public void stackedCentreBindersKeepTheOneAlreadyLit() {
        WorldPoint centre = new WorldPoint(11295, 15455, 0);
        Map<String, WorldPoint> tiles = new TreeMap<>();
        tiles.put("a", centre);
        tiles.put("b", centre);
        assertEquals("b", FracturedArchiveHandler.pickCentreBinder(tiles, tiles, List.of(centre), "b"));
        assertEquals("a", FracturedArchiveHandler.pickCentreBinder(tiles, tiles, List.of(centre), null));
    }

    // From the 2026-11-07 18:56 enrage (template 4081,5664 = instance
    // 13425,15456, so the arena centre 4063,5663 is 13407,15455). Moia's tiles
    // are her logged south-west tiles.
    @Test
    public void moiaCountsAsCentredOnlyWhenHerFootprintCoversTheArenaCentre() {
        List<WorldPoint> centres = List.of(new WorldPoint(13407, 15455, 0));
        // Respawned in the middle of the arena in her enrage.
        assertTrue(FracturedArchiveHandler.footprintCovers(new WorldPoint(13406, 15454, 0), 3, centres));
        // Floating off the north side just before it.
        assertFalse(FracturedArchiveHandler.footprintCovers(new WorldPoint(13406, 15461, 0), 3, centres));
        // A tile off either way still covers it.
        assertTrue(FracturedArchiveHandler.footprintCovers(new WorldPoint(13405, 15453, 0), 3, centres));
        assertFalse(FracturedArchiveHandler.footprintCovers(new WorldPoint(13404, 15453, 0), 3, centres));
        // Another plane, or no location.
        assertFalse(FracturedArchiveHandler.footprintCovers(new WorldPoint(13406, 15454, 1), 3, centres));
        assertFalse(FracturedArchiveHandler.footprintCovers(null, 3, centres));
    }

    // ---- Moia's enrage safe tiles, from the 2026-11-10 19:52 enrage ----
    // That instance's arena ran 10715..10723 x 9115..9123, centre 10719,9119.
    // Binder positions are the logged BIND tiles; Moia stood with her
    // south-west tile on 10718,9118, so her 3x3 is offsets 3..5 both ways.

    private static final WorldPoint ARENA_CENTRE = new WorldPoint(10719, 9119, 0);

    // An arena tile by its offset from the south-west corner, 0..8 each way.
    private static WorldPoint arena(int dx, int dy) {
        return new WorldPoint(10715 + dx, 9115 + dy, 0);
    }

    private static Set<WorldPoint> block(int minDx, int maxDx, int minDy, int maxDy) {
        Set<WorldPoint> tiles = new HashSet<>();
        for (int dx = minDx; dx <= maxDx; dx++) {
            for (int dy = minDy; dy <= maxDy; dy++) {
                tiles.add(arena(dx, dy));
            }
        }
        return tiles;
    }

    private static final Set<WorldPoint> ENRAGED_MOIA = block(3, 5, 3, 5);

    // t6701, as her binders take their ring around her: the north-west quarter
    // is out of reach, the corner you stand on through the fight included.
    @Test
    public void enrageOpensWithTheNorthWestQuarterSafe() {
        List<WorldPoint> binders = List.of(arena(2, 2), arena(6, 2), arena(6, 6), arena(4, 1), arena(8, 4));
        Set<WorldPoint> expected = block(0, 3, 5, 8);
        expected.remove(arena(3, 5));
        assertEquals(expected, FracturedArchiveHandler.binderSafeTiles(ARENA_CENTRE, binders, ENRAGED_MOIA));
    }

    // t6727, two anticlockwise steps later: a binder on the ring's north-west
    // place has the north-west corner in reach - you were hit there at t6733 and
    // again at t6737 - and the safe tiles have moved to the south-west quarter.
    @Test
    public void twoStepsInTheSafeTilesMoveToTheSouthWestQuarter() {
        List<WorldPoint> binders = List.of(arena(2, 6), arena(6, 6), arena(8, 4), arena(6, 2));
        Set<WorldPoint> safe = FracturedArchiveHandler.binderSafeTiles(ARENA_CENTRE, binders, ENRAGED_MOIA);
        Set<WorldPoint> expected = block(0, 3, 0, 3);
        expected.remove(arena(3, 3));
        assertEquals(expected, safe);
        assertFalse(safe.contains(arena(0, 8)));
    }

    // t6741 "Again!" refills the ring to six, leaving only the north-east 2x2;
    // the clockwise step at t6743-t6744 then moves it to the north-west 2x2.
    @Test
    public void afterAgainOnlyACornerTwoByTwoIsSafe() {
        List<WorldPoint> refilled = List.of(arena(6, 2), arena(2, 6), arena(4, 7), arena(2, 2), arena(4, 1), arena(8, 4));
        assertEquals(block(7, 8, 7, 8), FracturedArchiveHandler.binderSafeTiles(ARENA_CENTRE, refilled, ENRAGED_MOIA));

        List<WorldPoint> stepped = List.of(arena(6, 6), arena(4, 7), arena(0, 4), arena(2, 2), arena(4, 1), arena(6, 2));
        assertEquals(block(0, 1, 7, 8), FracturedArchiveHandler.binderSafeTiles(ARENA_CENTRE, stepped, ENRAGED_MOIA));
    }

    // The green only shows in full while no binder stepped this tick.
    @Test
    public void enrageGreenWaitsForEveryBinderToStandStill() {
        Map<String, WorldPoint> last = Map.of("a", arena(4, 1), "b", arena(8, 4));
        // Both on the same tiles as last tick: settled.
        assertTrue(FracturedArchiveHandler.bindersSettled(Map.of("a", arena(4, 1), "b", arena(8, 4)), last));
        // One stepped this tick: still moving.
        assertFalse(FracturedArchiveHandler.bindersSettled(Map.of("a", arena(5, 1), "b", arena(8, 4)), last));
        // One stepped, or died, out of the picture: the rest standing still is enough.
        assertTrue(FracturedArchiveHandler.bindersSettled(Map.of("b", arena(8, 4)), last));
        // No binders at all.
        assertTrue(FracturedArchiveHandler.bindersSettled(Map.of(), last));
    }

    // t25465 on 2026-11-12, "Again!": the new binders spawn on ring places with
    // the line (NE 6,6 and SE 6,2 among them) and count as standing that tick,
    // so the green takes them in at once. They stand a tick, step clockwise on
    // t25467 and t25468, and stand on their new places from t25469.
    @Test
    public void newBindersCountAsStandingFromTheirSpawnTick() {
        Map<String, WorldPoint> before = Map.of();
        Map<String, WorldPoint> spawned = Map.of("ne", arena(6, 6), "se", arena(6, 2));
        Map<String, WorldPoint> midStep = Map.of("ne", arena(7, 5), "se", arena(5, 1));
        Map<String, WorldPoint> arrived = Map.of("ne", arena(8, 4), "se", arena(4, 1));
        assertTrue(FracturedArchiveHandler.bindersSettled(spawned, before));
        assertTrue(FracturedArchiveHandler.bindersSettled(spawned, spawned));
        assertFalse(FracturedArchiveHandler.bindersSettled(midStep, spawned));
        assertFalse(FracturedArchiveHandler.bindersSettled(arrived, midStep));
        assertTrue(FracturedArchiveHandler.bindersSettled(arrived, arrived));
        // A spawn while the others are mid-step still leaves them moving.
        Map<String, WorldPoint> spawnMidStep = new TreeMap<>(midStep);
        spawnMidStep.put("new", arena(0, 4));
        assertFalse(FracturedArchiveHandler.bindersSettled(spawnMidStep, spawned));
    }

    // The green the binders leave fades out evenly over their 2-tick step (60
    // client cycles), from full as they set off to nothing as they stop.
    @Test
    public void fadingGreenRunsOutOverTheTwoTickStep() {
        assertEquals(1.0, FracturedArchiveHandler.moiaFadeOpacity(0), 1e-9);
        assertEquals(0.75, FracturedArchiveHandler.moiaFadeOpacity(15), 1e-9);
        assertEquals(0.5, FracturedArchiveHandler.moiaFadeOpacity(30), 1e-9);
        assertEquals(0.0, FracturedArchiveHandler.moiaFadeOpacity(60), 1e-9);
        assertEquals(0.0, FracturedArchiveHandler.moiaFadeOpacity(90), 1e-9);
        assertEquals(1.0, FracturedArchiveHandler.moiaFadeOpacity(-3), 1e-9);
    }

    // The enrage green starts on her first charge line, not on "Cease...".
    @Test
    public void chargeLinesAreUselessCorpsesAndAgain() {
        assertTrue(FracturedArchiveHandler.isMoiaChargeLine("Useless corpses... Give me your strength!"));
        assertTrue(FracturedArchiveHandler.isMoiaChargeLine("Again!"));
        assertFalse(FracturedArchiveHandler.isMoiaChargeLine("Cease this futile resistance!"));
        assertFalse(FracturedArchiveHandler.isMoiaChargeLine("Again and again!"));
    }

    // Two tiles away counts diagonally too (the t6733 hit came from 2,2 off).
    @Test
    public void binderReachIsTwoTilesIncludingDiagonals() {
        assertTrue(FracturedArchiveHandler.inBinderReach(arena(0, 8), List.of(arena(2, 6))));
        assertTrue(FracturedArchiveHandler.inBinderReach(arena(0, 8), List.of(arena(2, 8))));
        assertTrue(FracturedArchiveHandler.inBinderReach(arena(0, 8), List.of(arena(0, 8))));
        assertFalse(FracturedArchiveHandler.inBinderReach(arena(0, 8), List.of(arena(3, 6))));
        assertFalse(FracturedArchiveHandler.inBinderReach(arena(0, 8), List.of(arena(2, 5))));
        assertFalse(FracturedArchiveHandler.inBinderReach(arena(0, 8), List.of()));
    }

    // With no Moia in the arena (she is gone for 10 ticks as the enrage starts),
    // only the binders' reach matters: one on the centre leaves the outer ring.
    @Test
    public void withoutMoiaOnlyTheBindersReachIsLeftOut() {
        Set<WorldPoint> safe = FracturedArchiveHandler.binderSafeTiles(
                ARENA_CENTRE, List.of(arena(4, 4)), Set.of());
        assertEquals(81 - 25, safe.size());
        assertTrue(safe.contains(arena(1, 1)));
        assertFalse(safe.contains(arena(2, 2)));
    }

    // The MSAFE log picture of the t6727 arrangement, with you on the corner.
    @Test
    public void safeTilePictureShowsBindersMoiaAndYou() {
        List<WorldPoint> binders = List.of(arena(2, 6), arena(6, 6), arena(8, 4), arena(6, 2));
        Set<WorldPoint> safe = FracturedArchiveHandler.binderSafeTiles(ARENA_CENTRE, binders, ENRAGED_MOIA);
        assertEquals(Arrays.asList(
                        "9123 |!........|",
                        "9122 |.........|",
                        "9121 |..B...B..|",
                        "9120 |...MMM...|",
                        "9119 |...MMM..B|",
                        "9118 |###MMM...|",
                        "9117 |####..B..|",
                        "9116 |####.....|",
                        "9115 |####.....|"),
                FracturedArchiveHandler.moiaSafePicture(ARENA_CENTRE, safe, binders, ENRAGED_MOIA, arena(0, 8)));
    }
}
