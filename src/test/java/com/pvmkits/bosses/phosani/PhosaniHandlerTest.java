package com.pvmkits.bosses.phosani;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PhosaniHandlerTest {

    @Test
    public void safeTilesRecentreWhenPlayerMovesDuringSameShadowHandCast() {
        PhosaniHandler handler = new PhosaniHandler();
        Set<WorldPoint> hands = Set.of(new WorldPoint(12, 10, 0));

        handler.updateSafeTilesForShadowHands(hands, new WorldPoint(10, 10, 0));
        Set<WorldPoint> initialSafeTiles = handler.getSafeTiles();

        WorldPoint newlyReachableTile = new WorldPoint(13, 10, 0);
        assertFalse(initialSafeTiles.contains(newlyReachableTile));

        handler.updateSafeTilesForShadowHands(hands, new WorldPoint(11, 10, 0));
        Set<WorldPoint> movedSafeTiles = handler.getSafeTiles();

        assertTrue(movedSafeTiles.contains(newlyReachableTile));
    }
}