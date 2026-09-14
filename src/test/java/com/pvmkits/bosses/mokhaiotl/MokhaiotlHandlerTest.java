package com.pvmkits.bosses.mokhaiotl;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MokhaiotlHandlerTest {

    // The 5x5 boss: its footprint reaches 2 tiles either side of its centre.
    private static final int BOSS_HALF = 2;

    @Test
    public void statuePairNeedsToBeAxisAlignedAndAtLeastFourteenTilesApart() {
        WorldPoint origin = new WorldPoint(3000, 3000, 0);

        // Exactly 14 tiles apart in a straight line: the inclusive lower bound.
        assertTrue(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3014, 3000, 0)));
        assertTrue(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3000, 3014, 0)));
        assertTrue(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(2986, 3000, 0)));
        assertTrue(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3000, 2986, 0)));

        // In line but too close.
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3013, 3000, 0)));

        // Far enough apart but off the axis by a single tile: the orb would waver.
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3020, 3001, 0)));

        // Pure diagonal, however far apart.
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3020, 3020, 0)));
    }

    @Test
    public void relaxedStatuePairTiersAcceptShorterAndOneTileOffAxisWalks() {
        WorldPoint origin = new WorldPoint(3000, 3000, 0);

        // Tier 2: still perfectly in line, but only 8 tiles apart.
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3008, 3000, 0), 0, 14));
        assertTrue(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3008, 3000, 0), 0, 8));

        // Tier 3: one tile off the axis is tolerated, two is not.
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3020, 3001, 0), 0, 8));
        assertTrue(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3020, 3001, 0), 1, 8));
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3020, 3002, 0), 1, 8));

        // Even the loosest tier still rejects a pair that is simply too close.
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3007, 3001, 0), 1, 8));
    }

    @Test
    public void cardinalDashSweepsAFiveWideCorridorTwoTilesPastTheEye() {
        WorldPoint centre = new WorldPoint(3000, 3000, 0);
        WorldPoint eye = new WorldPoint(3006, 3000, 0);
        Set<WorldPoint> path = MokhaiotlHandler.sweptFootprint(centre, eye, BOSS_HALF);

        // 5 wide perpendicular to travel, and 2 tiles either end of the centre line.
        assertEquals(5 * (6 + 1 + 2 * BOSS_HALF), path.size());
        for (int y = 2998; y <= 3002; y++) {
            for (int x = 2998; x <= 3008; x++) {
                assertTrue(x + "," + y, path.contains(new WorldPoint(x, y, 0)));
            }
        }
        // The eye is where the boss's centre lands, so 3008 is the far edge.
        assertFalse(path.contains(new WorldPoint(3009, 3000, 0)));
        assertFalse(path.contains(new WorldPoint(3003, 3003, 0)));
    }

    @Test
    public void diagonalDashSweepsASolidBandWithNoMissingInnerTiles() {
        WorldPoint centre = new WorldPoint(3000, 3000, 0);
        WorldPoint eye = new WorldPoint(3006, 3006, 0);
        Set<WorldPoint> path = MokhaiotlHandler.sweptFootprint(centre, eye, BOSS_HALF);

        // Every tile of the footprint at each step of the diagonal, which is what a
        // perpendicular sweep along a diagonal used to leave gaps in. Walking the
        // centre line and checking the full 5x5 around it covers the inner rows.
        for (int s = 0; s <= 6; s++) {
            for (int ox = -BOSS_HALF; ox <= BOSS_HALF; ox++) {
                for (int oy = -BOSS_HALF; oy <= BOSS_HALF; oy++) {
                    WorldPoint tile = new WorldPoint(3000 + s + ox, 3000 + s + oy, 0);
                    assertTrue(tile.toString(), path.contains(tile));
                }
            }
        }

        // The band is connected: every tile has an orthogonal neighbour in the path,
        // so it reads as one continuous area rather than separate diagonal rows.
        for (WorldPoint tile : path) {
            assertTrue(tile.toString(), path.contains(tile.dx(1)) || path.contains(tile.dx(-1))
                    || path.contains(tile.dy(1)) || path.contains(tile.dy(-1)));
        }

        // And it reaches the 2 tiles past the eye that the boss's footprint covers.
        assertTrue(path.contains(new WorldPoint(3008, 3008, 0)));
        assertFalse(path.contains(new WorldPoint(3009, 3009, 0)));
    }

    @Test
    public void dashSweepIsEmptyWhenTheEyeSitsOnTheBossCentre() {
        WorldPoint centre = new WorldPoint(3000, 3000, 0);
        assertTrue(MokhaiotlHandler.sweptFootprint(centre, centre, BOSS_HALF).isEmpty());
    }

    /**
     * The slam's reach per row, measured off the unclipped slam footprints in
     * client.log (index = |dy|, value = max |dx|). The point of pinning the whole
     * table rather than just the radius is that it is what distinguishes the disc
     * from a square: 15 along the axis but only 11 on the diagonal.
     */
    private static final int[] SLAM_MAX_DX_BY_DY = {
            15, 15, 15, 15, 15, 15, 14, 14, 13, 13, 12, 11, 10, 9, 7, 5};

    @Test
    public void slamAreaIsADiscMatchingTheReachMeasuredFromTheLogs() {
        WorldPoint centre = new WorldPoint(3000, 3000, 0);
        Set<WorldPoint> disc = MokhaiotlHandler.slamDisc(centre);

        for (int dy = 0; dy < SLAM_MAX_DX_BY_DY.length; dy++) {
            int maxDx = SLAM_MAX_DX_BY_DY[dy];
            for (int sy : new int[]{1, -1}) {
                for (int sx : new int[]{1, -1}) {
                    // The furthest tile of the row is in.
                    assertTrue("dy " + (sy * dy) + " should reach dx " + (sx * maxDx),
                            disc.contains(new WorldPoint(3000 + sx * maxDx, 3000 + sy * dy, 0)));
                    // The one past it is not, which is what makes this a fit and not
                    // merely an upper bound.
                    assertFalse("dy " + (sy * dy) + " should stop at dx " + (sx * maxDx),
                            disc.contains(new WorldPoint(3000 + sx * (maxDx + 1), 3000 + sy * dy, 0)));
                }
            }
        }

        // Nothing at all beyond the last row.
        assertFalse(disc.contains(new WorldPoint(3000, 3000 + SLAM_MAX_DX_BY_DY.length, 0)));
        assertFalse(disc.contains(new WorldPoint(3000, 3000 - SLAM_MAX_DX_BY_DY.length, 0)));
    }

    @Test
    public void slamAreaArmsOnTheTickTheDashPathHighlightClears() {
        // zooming, telegraphUp, dashSeen, alreadyArmed

        // The handover tick: dash done, telegraph (and so the dash path) gone.
        assertTrue(MokhaiotlHandler.shouldArmSlamWindow(false, false, true, false));

        // Still dashing, or the telegraph still up: the dash path owns the screen, so
        // the slam area must not be drawn over it.
        assertFalse(MokhaiotlHandler.shouldArmSlamWindow(true, false, true, false));
        assertFalse(MokhaiotlHandler.shouldArmSlamWindow(false, true, true, false));
        assertFalse(MokhaiotlHandler.shouldArmSlamWindow(true, true, true, false));

        // Start of a car phase, before any dash: no slam is coming yet.
        assertFalse(MokhaiotlHandler.shouldArmSlamWindow(false, false, false, false));

        // Already armed - by an earlier tick of this same window, or by the windup
        // animation landing on this tick - so the estimate must not re-arm and
        // overwrite the exact impact tick.
        assertFalse(MokhaiotlHandler.shouldArmSlamWindow(false, false, true, true));
    }

    @Test
    public void slamAreaCoversTheBossItselfAndIsCentredOnIt() {
        WorldPoint centre = new WorldPoint(3000, 3000, 0);
        Set<WorldPoint> disc = MokhaiotlHandler.slamDisc(centre);

        assertTrue(disc.contains(centre));
        // The boss's own 5x5 footprint is inside the area it slams.
        for (int dx = -BOSS_HALF; dx <= BOSS_HALF; dx++) {
            for (int dy = -BOSS_HALF; dy <= BOSS_HALF; dy++) {
                assertTrue(disc.contains(new WorldPoint(3000 + dx, 3000 + dy, 0)));
            }
        }
        // Symmetric about the centre, so the highlight never looks lopsided.
        for (WorldPoint tile : disc) {
            int dx = tile.getX() - 3000;
            int dy = tile.getY() - 3000;
            assertTrue(disc.contains(new WorldPoint(3000 - dx, 3000 - dy, 0)));
        }
    }
}
