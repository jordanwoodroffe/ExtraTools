package com.pvmkits.bosses.archive;

import net.runelite.api.Prayer;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;

public class DreadnothTrackerTest {

    // Dreadnoth is 5x5 with his south-west tile at 100,100.
    private static final WorldPoint SW = new WorldPoint(100, 100, 0);

    @Test
    public void distanceIsZeroUnderHimAndOneAlongsideDiagonalsIncluded() {
        assertEquals(0, DreadnothTracker.chebyshevToFootprint(new WorldPoint(102, 102, 0), SW, 5));
        assertEquals(0, DreadnothTracker.chebyshevToFootprint(new WorldPoint(104, 104, 0), SW, 5));
        assertEquals(1, DreadnothTracker.chebyshevToFootprint(new WorldPoint(105, 102, 0), SW, 5));
        assertEquals(1, DreadnothTracker.chebyshevToFootprint(new WorldPoint(99, 99, 0), SW, 5));
        assertEquals(3, DreadnothTracker.chebyshevToFootprint(new WorldPoint(97, 103, 0), SW, 5));
    }

    // The 45% phase of the user's 2026-11-14 fight: slams (anim 14777) on 4279,
    // 4285 and 4291, each followed two ticks later by three 4211s seen with 3,
    // 4 and 5-tick flights. The hitsplats landed on 4283-4285, 4289-4291 and
    // 4295-4297, and the slams hit on 4280, 4286 and 4292 (36 and 24 taken on
    // 4286 and 4292 with Missiles still on).
    @Test
    public void prayerIsMissilesOnlyOnTheTicksHisProjectilesHit() {
        Set<Integer> missiles = new TreeSet<>();
        for (int seen : new int[] { 4281, 4287, 4293 }) {
            for (int flight = 3; flight <= 5; flight++) {
                missiles.add(DreadnothTracker.rangedHitTick(seen, flight));
            }
        }
        assertEquals(Set.of(4283, 4284, 4285, 4289, 4290, 4291, 4295, 4296, 4297), missiles);

        for (int tick = 4278; tick <= 4300; tick++) {
            Prayer expected = missiles.contains(tick) ? Prayer.PROTECT_FROM_MISSILES : Prayer.PROTECT_FROM_MELEE;
            assertEquals("tick " + tick, expected, DreadnothTracker.prayerAt(missiles, tick));
        }
        // The slam hits, straight after each volley's third projectile.
        assertEquals(Prayer.PROTECT_FROM_MELEE, DreadnothTracker.prayerAt(missiles, 4280));
        assertEquals(Prayer.PROTECT_FROM_MELEE, DreadnothTracker.prayerAt(missiles, 4286));
        assertEquals(Prayer.PROTECT_FROM_MELEE, DreadnothTracker.prayerAt(missiles, 4292));
        // After the last volley it stays on Melee (the jellies the user died to).
        assertEquals(Prayer.PROTECT_FROM_MELEE, DreadnothTracker.prayerAt(missiles, 4298));
        assertEquals(Prayer.PROTECT_FROM_MELEE, DreadnothTracker.prayerAt(missiles, 4301));
    }

    // The same phase replayed one render tick at a time, with only what the
    // client knows by then: a slam from the tick after its anim (its anim is in
    // tick S's packets), a projectile from the render tick it first shows on.
    @Test
    public void rangedCountdownRunsFourToOneFromEachSlamToItsFirstHit() {
        int[] slams = { 4279, 4285, 4291 };
        int[] volleys = { 4281, 4287, 4293 };
        Map<Integer, Integer> shown = new TreeMap<>();
        for (int render = 4278; render <= 4300; render++) {
            Set<Integer> slamVolleyTicks = new TreeSet<>();
            for (int slam : slams) {
                if (slam < render) {
                    slamVolleyTicks.add(slam + DreadnothTracker.SLAM_TO_FIRST_RANGED_HIT_TICKS);
                }
            }
            Set<Integer> missiles = new TreeSet<>();
            for (int seen : volleys) {
                if (seen <= render) {
                    for (int flight = 3; flight <= 5; flight++) {
                        missiles.add(DreadnothTracker.rangedHitTick(seen, flight));
                    }
                }
            }
            int countdown = DreadnothTracker.rangedCountdownAt(missiles, slamVolleyTicks, render);
            if (countdown > 0) {
                shown.put(render, countdown);
            }
            // The 1 is always on a Missiles tick: the one the highlight asks for.
            if (countdown == 1) {
                assertEquals(Prayer.PROTECT_FROM_MISSILES, DreadnothTracker.prayerAt(missiles, render));
            }
        }
        Map<Integer, Integer> expected = new TreeMap<>();
        for (int slam : slams) {
            for (int left = 4; left >= 1; left--) {
                expected.put(slam + 5 - left, left);
            }
        }
        // 4280-4283, 4286-4289 and 4292-4295; nothing through each volley's
        // last two Missiles ticks or after the last one.
        assertEquals(expected, shown);
    }

    // A projectile with no slam before it (the 25% jump is said to bring one)
    // still counts down to its hit.
    @Test
    public void rangedCountdownAlsoRunsToALoneProjectile() {
        Set<Integer> missiles = Set.of(100);
        assertEquals(0, DreadnothTracker.rangedCountdownAt(missiles, Set.of(), 96));
        assertEquals(4, DreadnothTracker.rangedCountdownAt(missiles, Set.of(), 97));
        assertEquals(1, DreadnothTracker.rangedCountdownAt(missiles, Set.of(), 100));
        assertEquals(0, DreadnothTracker.rangedCountdownAt(missiles, Set.of(), 101));
    }

    @Test
    public void pictureMarksTheShapeOnHimJelliesAndYou() {
        Set<WorldPoint> shape = Set.of(new WorldPoint(1, 1, 0), new WorldPoint(2, 1, 0), new WorldPoint(0, 0, 0));
        Set<WorldPoint> boss = Set.of(new WorldPoint(1, 1, 0));
        Set<WorldPoint> jellies = Set.of(new WorldPoint(0, 0, 0), new WorldPoint(2, 0, 0));
        List<String> rows = DreadnothTracker.picture(0, 2, 0, 1, shape, boss, jellies, new WorldPoint(0, 1, 0));
        assertEquals(List.of("1 |@x#|", "0 |j.J|"), rows);
    }
}
