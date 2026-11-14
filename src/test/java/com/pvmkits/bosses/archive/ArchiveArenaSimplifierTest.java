package com.pvmkits.bosses.archive;

import net.runelite.api.CollisionDataFlag;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArchiveArenaSimplifierTest {

    private static final int WALL = CollisionDataFlag.BLOCK_MOVEMENT_OBJECT;

    // A walled 21x11 room (x 5..25, y 5..15) inside a 40x40 scene, with an open
    // doorway in the east wall leading into a long corridor.
    private static int[][] roomWithOpenDoor() {
        int[][] flags = new int[40][40];
        for (int x = 0; x < 40; x++) {
            for (int y = 0; y < 40; y++) {
                boolean inRoom = x >= 5 && x <= 25 && y >= 5 && y <= 15;
                boolean corridor = y == 10 && x >= 26;
                flags[x][y] = inRoom || corridor ? 0 : WALL;
            }
        }
        return flags;
    }

    @Test
    public void floodsTheWholeRoomButNotPastItsWalls() {
        boolean[][] floor = ArchiveArenaSimplifier.floodFloor(roomWithOpenDoor(), 15, 10, 15, 10, 30);
        assertTrue(floor[5][5]);
        assertTrue(floor[25][15]);
        assertFalse(floor[4][10]);
        assertFalse(floor[15][16]);
    }

    // An open door must not run the flood down the corridor beyond the box.
    @Test
    public void stopsAtTheSearchBoxThroughAnOpenDoor() {
        boolean[][] floor = ArchiveArenaSimplifier.floodFloor(roomWithOpenDoor(), 15, 10, 15, 10, 15);
        assertTrue(floor[30][10]);
        assertFalse(floor[31][10]);
    }

    @Test
    public void aBlockedStartFloodsNothing() {
        boolean[][] floor = ArchiveArenaSimplifier.floodFloor(roomWithOpenDoor(), 0, 0, 0, 0, 30);
        assertFalse(floor[0][0]);
        assertFalse(floor[15][10]);
    }
}
