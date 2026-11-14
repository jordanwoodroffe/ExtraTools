package com.pvmkits.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RisingHitNumbersTest {

    private static final long LIFETIME = 1500;
    private static final double LINE = 10.0;

    @Test
    public void damageGetsTheMinusSignHealsAPlusAndBlocksNoSign() {
        assertEquals("−12", RisingHitNumbers.label(12, false, "−"));
        assertEquals("-12", RisingHitNumbers.label(12, false, "-"));
        assertEquals("+12", RisingHitNumbers.label(12, true, "−"));
        assertEquals("0", RisingHitNumbers.label(0, false, "−"));
    }

    @Test
    public void keepsTheNewestFirstAndDropsTheOldestPastTheCap() {
        RisingHitNumbers hits = new RisingHitNumbers();
        for (int i = 1; i <= RisingHitNumbers.MAX_SHOWN + 2; i++) {
            hits.add(i, false, i);
        }

        List<RisingHitNumbers.Frame> frames = hits.frame(10, LIFETIME, LINE);
        assertEquals(RisingHitNumbers.MAX_SHOWN, frames.size());
        for (int i = 0; i < frames.size(); i++) {
            assertEquals(RisingHitNumbers.MAX_SHOWN + 2 - i, frames.get(i).amount);
        }
    }

    @Test
    public void dropsNumbersOnceTheirTimeIsUp() {
        RisingHitNumbers hits = new RisingHitNumbers();
        hits.add(5, false, 0);
        hits.add(9, false, 600);

        assertEquals(2, hits.frame(1499, LIFETIME, LINE).size());
        assertEquals(1, hits.frame(1500, LIFETIME, LINE).size());
        assertEquals(0, hits.frame(2100, LIFETIME, LINE).size());
        assertTrue(hits.isEmpty());
    }

    @Test
    public void risesFromTheHitpointsNumberQuicklyThenSettles() {
        RisingHitNumbers hits = new RisingHitNumbers();
        hits.add(12, false, 0);

        double full = RisingHitNumbers.RISE_LINES * LINE;
        assertEquals(0.0, hits.frame(0, LIFETIME, LINE).get(0).rise, 1e-9);
        double quarter = hits.frame(375, LIFETIME, LINE).get(0).rise;
        double half = hits.frame(750, LIFETIME, LINE).get(0).rise;
        double end = hits.frame(1499, LIFETIME, LINE).get(0).rise;

        // Ease-out: more than half the height in the first quarter of the time.
        assertTrue(quarter > full / 2);
        assertTrue(half > quarter);
        assertEquals(full, end, 0.01);
    }

    @Test
    public void numbersLandingOnTheSameTickArePushedALineApart() {
        RisingHitNumbers hits = new RisingHitNumbers();
        hits.add(4, false, 0);
        hits.add(7, false, 0);

        for (long now = 0; now <= 400; now += 16) {
            List<RisingHitNumbers.Frame> frames = hits.frame(now, LIFETIME, LINE);
            assertEquals(LINE, frames.get(1).rise - frames.get(0).rise, 1e-9);
        }
    }

    @Test
    public void aNumberAlreadyOnScreenGlidesUpWhenPushedRatherThanJumping() {
        RisingHitNumbers hits = new RisingHitNumbers();
        hits.add(4, false, 0);
        double before = hits.frame(16, LIFETIME, LINE).get(0).rise;
        assertTrue(before < LINE);

        // A second hit a frame later needs the first a whole line up.
        hits.add(7, false, 20);
        List<RisingHitNumbers.Frame> frames = hits.frame(32, LIFETIME, LINE);
        double step = frames.get(1).rise - before;
        assertTrue("moved " + step + "px in one frame", step < LINE / 2);

        for (long now = 48; now <= 500; now += 16) {
            frames = hits.frame(now, LIFETIME, LINE);
        }
        assertEquals(LINE, frames.get(1).rise - frames.get(0).rise, 0.05);
    }

    @Test
    public void anOlderNumberNeverFallsBelowALineAboveANewerOne() {
        RisingHitNumbers hits = new RisingHitNumbers();
        hits.add(3, false, 0);
        hits.add(8, false, 600); // the next tick

        for (long now = 600; now < 1500; now += 16) {
            List<RisingHitNumbers.Frame> frames = hits.frame(now, LIFETIME, LINE);
            // Once the push has had a few frames to catch up.
            if (now >= 800) {
                assertTrue(frames.get(1).rise - frames.get(0).rise >= LINE - 0.05);
            }
        }
    }

    @Test
    public void fadesInQuicklyHoldsThenFadesOutSmoothly() {
        assertEquals(0.0, RisingHitNumbers.opacity(0, 1000), 1e-9);
        assertEquals(0.5, RisingHitNumbers.opacity(45, 1000), 1e-9);
        assertEquals(1.0, RisingHitNumbers.opacity(400, 1000), 1e-9);
        // Halfway through the fade-out, smoothstep is at exactly one half.
        double halfFade = 1000 - 1000 * RisingHitNumbers.FADE_OUT_SHARE / 2;
        assertEquals(0.5, RisingHitNumbers.opacity(Math.round(halfFade), 1000), 0.01);
        assertEquals(0.0, RisingHitNumbers.opacity(1000, 1000), 1e-9);
    }
}
