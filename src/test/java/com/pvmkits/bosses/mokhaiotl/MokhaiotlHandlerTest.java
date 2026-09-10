package com.pvmkits.bosses.mokhaiotl;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MokhaiotlHandlerTest {

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
}
