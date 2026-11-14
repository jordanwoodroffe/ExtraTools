package com.pvmkits.bosses.mokhaiotl;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MokhaiotlHandlerTest {

    // The 5x5 boss: its footprint reaches 2 tiles either side of its centre.
    private static final int BOSS_HALF = MokhaiotlHandler.BOSS_HALF_SIZE;

    @Test
    public void statuePairNeedsToBeAxisAlignedAndAtLeastThirteenTilesApart() {
        WorldPoint origin = new WorldPoint(3000, 3000, 0);

        // Exactly 13 tiles apart in a straight line: the inclusive lower bound.
        assertTrue(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3013, 3000, 0)));
        assertTrue(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3000, 3013, 0)));
        assertTrue(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(2987, 3000, 0)));
        assertTrue(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3000, 2987, 0)));

        // In line but too close.
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3012, 3000, 0)));

        // Far enough apart but off the axis by a single tile: the orb would waver.
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3020, 3001, 0)));

        // Pure diagonal, however far apart.
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3020, 3020, 0)));
    }

    @Test
    public void relaxedStatuePairTiersAcceptShorterAndOneTileOffAxisWalks() {
        WorldPoint origin = new WorldPoint(3000, 3000, 0);

        // Tier 2: still perfectly in line, but only 8 tiles apart.
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3008, 3000, 0), 0, 13));
        assertTrue(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3008, 3000, 0), 0, 8));

        // Tier 3: one tile off the axis is tolerated, two is not.
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3020, 3001, 0), 0, 8));
        assertTrue(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3020, 3001, 0), 1, 8));
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3020, 3002, 0), 1, 8));

        // Even the loosest tier still rejects a pair that is simply too close.
        assertFalse(MokhaiotlHandler.isStraightPair(origin, new WorldPoint(3007, 3001, 0), 1, 8));
    }

    // When no tier matches at all, the last-resort pick ranks pairs so that the most
    // nearly straight one always wins, however much separation or proximity the
    // crooked alternatives offer.
    @Test
    public void lastResortPairPrefersTheStraightestPairOverALongerCrookedOne() {
        WorldPoint player = new WorldPoint(3000, 3000, 0);
        WorldPoint origin = new WorldPoint(3000, 3000, 0);

        int nearlyStraight = MokhaiotlHandler.lastResortPairScore(
                origin, new WorldPoint(3006, 3002, 0), player);
        int longButCrooked = MokhaiotlHandler.lastResortPairScore(
                origin, new WorldPoint(3020, 3006, 0), player);
        assertTrue(nearlyStraight < longButCrooked);

        // Among equally crooked pairs the longer walk wins.
        int shortLeg = MokhaiotlHandler.lastResortPairScore(
                origin, new WorldPoint(3006, 3002, 0), player);
        int longLeg = MokhaiotlHandler.lastResortPairScore(
                origin, new WorldPoint(3014, 3002, 0), player);
        assertTrue(longLeg < shortLeg);

        // A perfectly straight pair still beats anything off-axis.
        int straight = MokhaiotlHandler.lastResortPairScore(
                origin, new WorldPoint(3005, 3000, 0), player);
        assertTrue(straight < nearlyStraight);
    }

    @Test
    public void statuesInsideTheBossMeleeBufferRingAreNotUsableEndpoints() {
        WorldPoint bossCentre = new WorldPoint(3000, 3000, 0);

        // Dead centre, and anywhere inside the 5x5 itself.
        assertTrue(MokhaiotlHandler.overlapsBossFootprint(bossCentre, 1, bossCentre));
        assertTrue(MokhaiotlHandler.overlapsBossFootprint(
                new WorldPoint(3000 + BOSS_HALF, 3000 - BOSS_HALF, 0), 1, bossCentre));

        // Touching is NOT enough to be usable: the exclusion carries a buffer ring so
        // that attacking the statue never puts the player in melee range. A 3x3 statue
        // flush against the boss's east column still falls inside it.
        assertTrue(MokhaiotlHandler.overlapsBossFootprint(
                new WorldPoint(3003, 3000, 0), 3, bossCentre));

        // The ring is measured off the statue's centre, so a single tile at the same
        // south-west corner sits one closer in and is likewise excluded.
        assertTrue(MokhaiotlHandler.overlapsBossFootprint(
                new WorldPoint(3003, 3000, 0), 1, bossCentre));

        // One tile further in the footprints themselves share a column.
        assertTrue(MokhaiotlHandler.overlapsBossFootprint(
                new WorldPoint(3002, 3000, 0), 3, bossCentre));

        // Clear of the ring on the x axis: the first 3x3 statue that is usable.
        assertFalse(MokhaiotlHandler.overlapsBossFootprint(
                new WorldPoint(3004, 3000, 0), 3, bossCentre));

        // The ring applies on both axes at once, so a diagonal statue has to clear it
        // on each. Flush diagonally is still excluded; a tile further out is usable.
        assertTrue(MokhaiotlHandler.overlapsBossFootprint(
                new WorldPoint(3003, 3003, 0), 3, bossCentre));
        assertFalse(MokhaiotlHandler.overlapsBossFootprint(
                new WorldPoint(3004, 3004, 0), 3, bossCentre));

        // The ring is symmetric: the same clearances apply on the boss's west side.
        assertTrue(MokhaiotlHandler.overlapsBossFootprint(
                new WorldPoint(2996, 3000, 0), 3, bossCentre));
        assertFalse(MokhaiotlHandler.overlapsBossFootprint(
                new WorldPoint(2994, 3000, 0), 3, bossCentre));

        // A different plane never overlaps.
        assertFalse(MokhaiotlHandler.overlapsBossFootprint(
                new WorldPoint(3000, 3000, 1), 1, bossCentre));
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
     * The four candidate tiles for a delve's first car-phase dash eye, measured off
     * the dashes in client.log. Pinning the exact tiles rather than just the
     * distance is what records that these are one tile per cardinal ray and nothing
     * in between: the boss only ever dashes along a compass ray.
     */
    @Test
    public void firstDashEyeCandidatesAreOneTilePerCardinalRayAtTheDashDistance() {
        WorldPoint centre = new WorldPoint(3000, 3000, 0);
        Set<WorldPoint> candidates = MokhaiotlHandler.cardinalEyeCandidates(centre);
        int d = MokhaiotlHandler.CAR_EYE_DASH_DISTANCE;

        assertEquals(4, candidates.size());
        assertTrue(candidates.contains(new WorldPoint(3000, 3000 + d, 0)));
        assertTrue(candidates.contains(new WorldPoint(3000, 3000 - d, 0)));
        assertTrue(candidates.contains(new WorldPoint(3000 + d, 3000, 0)));
        assertTrue(candidates.contains(new WorldPoint(3000 - d, 3000, 0)));

        // Never the diagonals, which the dash does also use but which this mark
        // deliberately leaves out, and never the ring of tiles short of the reach.
        assertFalse(candidates.contains(new WorldPoint(3000 + d, 3000 + d, 0)));
        assertFalse(candidates.contains(new WorldPoint(3000 + d - 1, 3000, 0)));
        assertFalse(candidates.contains(centre));
    }

    @Test
    public void firstDashEyeCandidatesClearTheBossFootprintSoTheyAreAlwaysVisible() {
        WorldPoint centre = new WorldPoint(3000, 3000, 0);

        // Every candidate is outside the boss's own 5x5, or it would be marked under
        // the boss where it could never be read.
        for (WorldPoint tile : MokhaiotlHandler.cardinalEyeCandidates(centre)) {
            int cheb = Math.max(Math.abs(tile.getX() - 3000), Math.abs(tile.getY() - 3000));
            assertTrue(tile.toString(), cheb > BOSS_HALF);
        }
    }
}
