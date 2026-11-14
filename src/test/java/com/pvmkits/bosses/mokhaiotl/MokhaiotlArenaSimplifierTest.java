package com.pvmkits.bosses.mokhaiotl;

import org.junit.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MokhaiotlArenaSimplifierTest {

    // The scene stores every tile colour as a packed HSL short, so a colour that
    // does not fit in 16 bits would be silently mangled by the renderer.
    @Test
    public void packedColourFitsInAShort() {
        int packed = MokhaiotlArenaSimplifier.packHsl(new Color(166, 138, 108));
        assertTrue("packed HSL must fit in a short, was " + packed, packed >= 0 && packed <= 0xFFFF);
    }

    // Field layout is hhhhhhssslllllll: 6 bits hue, 3 saturation, 7 luminance.
    // Unpacking has to give back the components that went in, or the floor comes
    // out as the wrong hue entirely.
    @Test
    public void packedColourRoundTripsItsComponents() {
        Color brown = new Color(166, 138, 108);
        int packed = MokhaiotlArenaSimplifier.packHsl(brown);

        float[] hsb = Color.RGBtoHSB(brown.getRed(), brown.getGreen(), brown.getBlue(), null);
        assertEquals(Math.round(hsb[0] * 63f), (packed >> 10) & 63);
        assertEquals(Math.round(hsb[1] * 7f), (packed >> 7) & 7);
        assertEquals(Math.round(hsb[2] * 127f), packed & 127);
    }

    // Pure white and pure black sit at the ends of the luminance range; clamping
    // has to keep them inside their fields rather than overflowing into the next.
    @Test
    public void extremesStayWithinTheirFields() {
        int white = MokhaiotlArenaSimplifier.packHsl(Color.WHITE);
        assertEquals(127, white & 127);
        assertTrue(white <= 0xFFFF);

        int black = MokhaiotlArenaSimplifier.packHsl(Color.BLACK);
        assertEquals(0, black & 127);
        assertEquals(0, (black >> 7) & 7);
    }

    // A saturated red is the worst case for the 6-bit hue field wrapping around
    // to 64, which would spill a bit into nothing and read back as a different
    // colour. Hue 0 must stay 0.
    @Test
    public void redDoesNotOverflowTheHueField() {
        int red = MokhaiotlArenaSimplifier.packHsl(Color.RED);
        assertEquals(0, (red >> 10) & 63);
        assertTrue(red <= 0xFFFF);
    }

    // The painted room reaches past the 25x25 standable floor, out to the wall
    // ring and entrance alcove, and stops there.
    @Test
    public void arenaReachesFourteenOnTheAxesAndNoFurther() {
        assertTrue(MokhaiotlArenaSimplifier.isInArena(0, 0));
        assertTrue(MokhaiotlArenaSimplifier.isInArena(14, 0));
        assertTrue(MokhaiotlArenaSimplifier.isInArena(0, -14));
        assertFalse(MokhaiotlArenaSimplifier.isInArena(15, 0));
        assertFalse(MokhaiotlArenaSimplifier.isInArena(0, 15));
    }

    // The 25x25 floor's own corners must be inside the painted area - leaving
    // them out is what made the recolour read as a circle inside a larger room.
    @Test
    public void oldTwentyFiveByTwentyFiveCornersAreCovered() {
        assertTrue(MokhaiotlArenaSimplifier.isInArena(12, 12));
        assertTrue(MokhaiotlArenaSimplifier.isInArena(-12, 12));
        assertTrue(MokhaiotlArenaSimplifier.isInArena(12, -12));
        assertTrue(MokhaiotlArenaSimplifier.isInArena(-12, -12));
    }

    // The corners are still chamfered, so the room rounds off rather than
    // painting a hard square out to the full radius.
    @Test
    public void arenaCornersAreStillChamferedOff() {
        assertFalse(MokhaiotlArenaSimplifier.isInArena(14, 14));
        assertFalse(MokhaiotlArenaSimplifier.isInArena(14, 11));
        assertFalse(MokhaiotlArenaSimplifier.isInArena(13, 13));
        assertTrue(MokhaiotlArenaSimplifier.isInArena(14, 10));
        assertTrue(MokhaiotlArenaSimplifier.isInArena(11, 11));
    }

    // The arena-centre object and the boss are both 5x5, and both report their
    // south-west corner. Taking that corner as the centre is what shifted the
    // painted octagon 2 tiles south-west and left the north edge unpainted.
    @Test
    public void fiveByFiveFootprintCentresTwoTilesIn() {
        assertEquals(2, MokhaiotlArenaSimplifier.midpoint(0, 4));
        assertEquals(2, MokhaiotlArenaSimplifier.centreOffsetForSize(5));

        // A 5x5 whose south-west tile is (10909, 3171) has its middle at
        // (10911, 3173) - the boss centre the handler independently reports.
        assertEquals(10911, 10909 + MokhaiotlArenaSimplifier.centreOffsetForSize(5));
        assertEquals(3173, 3171 + MokhaiotlArenaSimplifier.centreOffsetForSize(5));
    }

    // A single-tile object is already its own centre; shifting it would move
    // the whole painted area off the room.
    @Test
    public void singleTileFootprintIsItsOwnCentre() {
        assertEquals(7, MokhaiotlArenaSimplifier.midpoint(7, 7));
        assertEquals(0, MokhaiotlArenaSimplifier.centreOffsetForSize(1));
    }

    // The room centre is found without the boss by taking the floor tile
    // furthest from any wall. A long entrance corridor must never win that,
    // however many tiles it adds, or the painted area slides out of the room.
    @Test
    public void roomCentreIgnoresTheEntranceCorridor() {
        // A 21x21 room at (10..30, 10..30) with a 1-wide corridor running off
        // its east side for 30 tiles.
        boolean[][] floor = new boolean[80][80];
        for (int x = 10; x <= 30; x++) {
            for (int y = 10; y <= 30; y++) {
                floor[x][y] = true;
            }
        }
        for (int x = 31; x <= 60; x++) {
            floor[x][20] = true;
        }

        int[] deepest = MokhaiotlArenaSimplifier.deepestTile(floor, tilesOf(floor));

        assertEquals(20, deepest[0]);
        assertEquals(20, deepest[1]);
        // An 11-deep clearance is the middle of a 21-wide room.
        assertEquals(11, deepest[2]);
    }

    // A corridor on its own has to be rejected, which is what stops a centre
    // being taken while the player is still walking in.
    @Test
    public void aCorridorAloneIsShallow() {
        boolean[][] floor = new boolean[80][80];
        for (int x = 5; x <= 60; x++) {
            floor[x][20] = true;
        }

        int[] deepest = MokhaiotlArenaSimplifier.deepestTile(floor, tilesOf(floor));

        // Every tile of a 1-wide corridor touches a wall, so nothing in it can
        // clear the threshold the real room comfortably passes.
        assertEquals(1, deepest[2]);
    }

    @Test
    public void emptyFloorHasNoDeepestTile() {
        boolean[][] floor = new boolean[10][10];
        assertNull(MokhaiotlArenaSimplifier.deepestTile(floor, tilesOf(floor)));
    }

    private static List<int[]> tilesOf(boolean[][] floor) {
        List<int[]> tiles = new ArrayList<>();
        for (int x = 0; x < floor.length; x++) {
            for (int y = 0; y < floor[x].length; y++) {
                if (floor[x][y]) {
                    tiles.add(new int[] { x, y });
                }
            }
        }
        return tiles;
    }

    // The bound has to be symmetric: the centre object sits in the middle, so a
    // sign error would slide the painted patch off to one side of the room.
    @Test
    public void arenaBoundIsSymmetric() {
        for (int dx = -18; dx <= 18; dx++) {
            for (int dy = -18; dy <= 18; dy++) {
                boolean in = MokhaiotlArenaSimplifier.isInArena(dx, dy);
                assertEquals(in, MokhaiotlArenaSimplifier.isInArena(-dx, dy));
                assertEquals(in, MokhaiotlArenaSimplifier.isInArena(dx, -dy));
                assertEquals(in, MokhaiotlArenaSimplifier.isInArena(dy, dx));
            }
        }
    }
}
