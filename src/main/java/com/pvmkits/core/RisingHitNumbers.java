package com.pvmkits.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The hit numbers that rise out of the hitpoints number beside the status bars.
 * Each one fades in as it leaves the hitpoints number, rises with an ease-out (quick
 * at first, then settling) and fades away over the end of its time. When a newer
 * number catches up with an older one, the older one is pushed up to stay a line
 * above it, so numbers never overlap, even when several land on the same tick.
 * The push is eased over a few frames, so nothing jumps.
 *
 * This class only does the bookkeeping and the motion. The time is passed in so it
 * can be tested, and {@link StatusBarsOverlay} draws the numbers (moving each one
 * sideways, away from the bars, by as much as it rises, for a 45-degree path).
 */
final class RisingHitNumbers {

    // At most this many numbers at once; the oldest is dropped first.
    static final int MAX_SHOWN = 5;

    // How far a number rises over its time, in lines of text.
    static final double RISE_LINES = 3.0;

    // How long a new number takes to fade in.
    static final double FADE_IN_MILLIS = 90.0;

    // Share of the time, at its end, over which a number fades out.
    static final double FADE_OUT_SHARE = 0.45;

    // Time constant of the ease when a new number pushes an older one up.
    private static final double PUSH_EASE_MILLIS = 60.0;

    // Live numbers, newest first.
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long lastFrameMillis = -1;

    void add(int amount, boolean heal, long nowMillis) {
        entries.addFirst(new Entry(amount, heal, nowMillis));
        while (entries.size() > MAX_SHOWN) {
            entries.removeLast();
        }
    }

    void clear() {
        entries.clear();
        lastFrameMillis = -1;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Moves every live number on to the given time and returns where each one sits,
     * newest first. Rise is in pixels above the hitpoints number's baseline. lineStep
     * is the pixel distance between two stacked lines of text.
     */
    List<Frame> frame(long nowMillis, long lifetimeMillis, double lineStep) {
        entries.removeIf(entry -> nowMillis - entry.startMillis >= lifetimeMillis);

        double dtMillis = lastFrameMillis < 0 ? 0.0 : Math.max(0, nowMillis - lastFrameMillis);
        lastFrameMillis = nowMillis;
        double decay = Math.exp(-dtMillis / PUSH_EASE_MILLIS);

        // Each number's exact place is its own rise, or a line above the newer number
        // if that is higher. That only jumps when a new number arrives, so the layout
        // is worked out twice, with and without the numbers not yet drawn, and the
        // difference is the jump. It is drawn as a lag that eases away, while
        // everything else follows the exact places.
        List<Frame> frames = new ArrayList<>(entries.size());
        double floor = Double.NEGATIVE_INFINITY;
        double floorBefore = Double.NEGATIVE_INFINITY;
        for (Entry entry : entries) {
            long age = nowMillis - entry.startMillis;
            double natural = RISE_LINES * lineStep * easeOutCubic(age / (double) lifetimeMillis);
            double target = Math.max(natural, floor);
            floor = target + lineStep;
            if (entry.shown) {
                double before = Math.max(natural, floorBefore);
                floorBefore = before + lineStep;
                entry.lag = entry.lag * decay + (target - before);
            } else {
                entry.shown = true;
                entry.lag = 0.0;
            }
            double rise = Math.max(natural, target - entry.lag);
            frames.add(new Frame(entry.amount, entry.heal, rise, opacity(age, lifetimeMillis)));
        }
        return frames;
    }

    // Fades in over the first FADE_IN_MILLIS, then out (smoothly) over the last
    // FADE_OUT_SHARE of the time.
    static double opacity(long ageMillis, long lifetimeMillis) {
        double age = Math.max(0, ageMillis);
        double left = lifetimeMillis - age;
        if (left <= 0) {
            return 0.0;
        }
        double fadeIn = Math.min(1.0, age / FADE_IN_MILLIS);
        double fadeOutMillis = lifetimeMillis * FADE_OUT_SHARE;
        double fadeOut = left >= fadeOutMillis ? 1.0 : smoothstep(left / fadeOutMillis);
        return Math.min(fadeIn, fadeOut);
    }

    // "-12" for damage (with the given minus sign), "+12" for a heal, plain "0" for a
    // blocked hit.
    static String label(int amount, boolean heal, String minus) {
        if (heal) {
            return "+" + amount;
        }
        return amount == 0 ? "0" : minus + amount;
    }

    private static double easeOutCubic(double t) {
        double u = 1.0 - Math.max(0.0, Math.min(1.0, t));
        return 1.0 - u * u * u;
    }

    private static double smoothstep(double x) {
        return x * x * (3.0 - 2.0 * x);
    }

    // Where one number sits this frame.
    static final class Frame {
        final int amount;
        final boolean heal;
        final double rise;
        final double opacity;

        Frame(int amount, boolean heal, double rise, double opacity) {
            this.amount = amount;
            this.heal = heal;
            this.rise = rise;
            this.opacity = opacity;
        }

        String text(String minus) {
            return label(amount, heal, minus);
        }
    }

    private static final class Entry {
        final int amount;
        final boolean heal;
        final long startMillis;
        // Drawn at least once, so a jump in its place is eased rather than snapped.
        boolean shown;
        // Pixels it still trails its exact place by, after being pushed up.
        double lag;

        Entry(int amount, boolean heal, long startMillis) {
            this.amount = amount;
            this.heal = heal;
            this.startMillis = startMillis;
        }
    }
}
