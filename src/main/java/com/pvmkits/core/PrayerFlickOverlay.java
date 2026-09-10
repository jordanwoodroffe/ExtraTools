package com.pvmkits.core;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.Prayer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Draws a "heartbeat" cue for prayer flicking (unlimited prayer) that flashes
 * once, right in the middle of each game tick (0.5 of the way through),
 * then stays dark until the next tick. Tap the prayer on the flash so the click
 * lands tight and slightly ahead of the tick centre, toggling it off and back on
 * and draining no prayer points. The same pulse drives two cues: a dot just above
 * the minimap prayer orb, and a transparent red fill over the icon of each active
 * offensive prayer (e.g. Piety/Rigour/Augury) in the open prayer book. The rhythm
 * is interpolated from the wall-clock time of the last game tick so it stays
 * locked to the server's 600ms rhythm rather than the client frame rate.
 */
public class PrayerFlickOverlay extends Overlay {

    private static final long TICK_MILLIS = 600L;

    // Gap in pixels between the top of the orb and the bottom of the dot.
    private static final int ORB_GAP = 3;

    // The heartbeat is a single narrow flash placed at the tick centre (0.5 of the
    // way through), read as one quick tap in the middle of the tick.
    private static final double FLASH_CENTER = 0.2;
    private static final double FLASH_WIDTH = 0.045;

    // The prayer book's 30 prayer slots (InterfaceID.Prayerbook.PRAYER1 = child 9
    // .. PRAYER30 = child 38) inside interface InterfaceID.PRAYERBOOK. Each slot's
    // icon sprite lives on a child widget, not on the slot itself.
    private static final int FIRST_PRAYER_CHILD = 9;
    private static final int LAST_PRAYER_CHILD = 38;

    // Peak opacity (0-255) of the transparent red fill over an active prayer's icon;
    // the pulse scales alpha up to this so the icon still shows through at the flash.
    private static final int PRAYER_FILL_MAX_ALPHA = 120;

    // Keep an icon highlighted this many ticks after its prayer is switched off, so a
    // flick's off-phase doesn't blink the highlight.
    private static final int STICKY_TICKS = 2;

    // Offensive (damage/accuracy) prayers mapped to their icon sprite id
    // (net.runelite.api.SpriteID.PRAYER_*), used to find the icon in the book.
    private static final Map<Prayer, Integer> OFFENSIVE_PRAYER_SPRITES = Map.ofEntries(
            Map.entry(Prayer.CLARITY_OF_THOUGHT, 117),
            Map.entry(Prayer.IMPROVED_REFLEXES, 120),
            Map.entry(Prayer.INCREDIBLE_REFLEXES, 126),
            Map.entry(Prayer.BURST_OF_STRENGTH, 116),
            Map.entry(Prayer.SUPERHUMAN_STRENGTH, 119),
            Map.entry(Prayer.ULTIMATE_STRENGTH, 125),
            Map.entry(Prayer.CHIVALRY, 945),
            Map.entry(Prayer.PIETY, 946),
            Map.entry(Prayer.SHARP_EYE, 133),
            Map.entry(Prayer.HAWK_EYE, 502),
            Map.entry(Prayer.EAGLE_EYE, 504),
            Map.entry(Prayer.RIGOUR, 1420),
            Map.entry(Prayer.MYSTIC_WILL, 134),
            Map.entry(Prayer.MYSTIC_LORE, 503),
            Map.entry(Prayer.MYSTIC_MIGHT, 505),
            Map.entry(Prayer.AUGURY, 1421));

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;

    // Sprite id -> last game tick that prayer was active, for the sticky outline.
    private final Map<Integer, Integer> lastActiveTickBySprite = new HashMap<>();

    @Inject
    public PrayerFlickOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        boolean showOrb = config.showPrayerFlickHeartbeat();
        boolean showHighlight = config.showPrayerFlickPrayerOutline();
        if (!showOrb && !showHighlight) {
            return null;
        }

        long now = System.currentTimeMillis();
        long sinceTick = now - plugin.getLastGameTickMillis();
        // No recent tick (logged out, loading, etc.) means there is no rhythm to
        // hold, so draw nothing rather than a stale/frozen pulse.
        if (sinceTick < 0 || sinceTick > TICK_MILLIS * 2) {
            return null;
        }

        // Derive the phase from the smoothed 600ms grid (server-locked but
        // jitter-filtered) rather than the raw last-tick time, so the flash spacing
        // stays even even when individual game ticks arrive a little early or late.
        long sinceAnchor = now - plugin.getTickPhaseAnchorMillis();
        double phase = ((sinceAnchor % TICK_MILLIS) + TICK_MILLIS) % TICK_MILLIS / (double) TICK_MILLIS;
        // A single narrow flash at the tick centre (0.5 through) that fully
        // brightens on the one click, then stays dark the rest of the tick so the
        // pause before the next flash reads.
        double pulse = flash(phase, FLASH_CENTER);

        // Fully off through the pause so only the single flash shows, with no idle
        // red glow lingering between ticks.
        int alpha = (int) Math.round(255 * pulse);
        alpha = Math.max(0, Math.min(255, alpha));

        Color base = config.prayerFlickHeartbeatColor();
        Color pulseColor = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (showOrb) {
            renderOrb(graphics, pulse, pulseColor);
        }
        if (showHighlight) {
            // Transparent red fill so the prayer icon stays visible under the pulse.
            int fillAlpha = (int) Math.round(PRAYER_FILL_MAX_ALPHA * pulse);
            Color fillColor = new Color(base.getRed(), base.getGreen(), base.getBlue(), fillAlpha);
            renderActivePrayerHighlights(graphics, fillColor);
        }
        return null;
    }

    @SuppressWarnings("deprecation") // ComponentID + getWidget(int) are the stable prayer-orb lookup in this pinned version
    private void renderOrb(Graphics2D graphics, double pulse, Color pulseColor) {
        Widget prayerOrb = client.getWidget(ComponentID.MINIMAP_PRAYER_ORB);
        if (prayerOrb == null || prayerOrb.isHidden()) {
            return;
        }

        Rectangle bounds = prayerOrb.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return;
        }

        int baseRadius = Math.max(1, config.prayerFlickHeartbeatSize());
        int radius = (int) Math.round(baseRadius * (0.6 + 0.4 * pulse));

        int centerX = bounds.x + bounds.width / 2 + config.prayerFlickHeartbeatHorizontalOffset();
        int centerY = bounds.y - ORB_GAP - baseRadius + config.prayerFlickHeartbeatVerticalOffset();

        graphics.setColor(pulseColor);
        graphics.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
    }

    // Fills every active offensive prayer's icon in the open prayer book with a
    // transparent red that pulses in sync with the orb, lingering STICKY_TICKS ticks
    // after a prayer is switched off.
    @SuppressWarnings("deprecation") // isPrayerActive(Prayer) is the stable active-prayer check in this pinned version
    private void renderActivePrayerHighlights(Graphics2D graphics, Color fillColor) {
        int tick = client.getTickCount();
        // Stamp the current tick on every prayer that is on right now.
        for (Map.Entry<Prayer, Integer> entry : OFFENSIVE_PRAYER_SPRITES.entrySet()) {
            if (client.isPrayerActive(entry.getKey())) {
                lastActiveTickBySprite.put(entry.getValue(), tick);
            }
        }

        // Only while the prayer book tab is actually open, else the slots hold
        // stale bounds and we'd draw over whatever tab is showing.
        Widget prayerRoot = client.getWidget(InterfaceID.PRAYERBOOK, 0);
        if (prayerRoot == null || prayerRoot.isHidden()) {
            return;
        }

        // Include prayers on now (age 0) or switched off within the last STICKY_TICKS.
        Set<Integer> activeSprites = null;
        for (Map.Entry<Integer, Integer> entry : lastActiveTickBySprite.entrySet()) {
            int age = tick - entry.getValue();
            if (age >= 0 && age <= STICKY_TICKS) {
                if (activeSprites == null) {
                    activeSprites = new HashSet<>();
                }
                activeSprites.add(entry.getKey());
            }
        }
        if (activeSprites == null) {
            return;
        }

        graphics.setColor(fillColor);
        for (int child = FIRST_PRAYER_CHILD; child <= LAST_PRAYER_CHILD; child++) {
            Widget slot = client.getWidget(InterfaceID.PRAYERBOOK, child);
            if (slot == null || slot.isHidden()) {
                continue;
            }
            if (!slotShowsActiveSprite(slot, activeSprites)) {
                continue;
            }
            Rectangle b = slot.getBounds();
            if (b == null || b.width <= 0 || b.height <= 0) {
                continue;
            }
            graphics.fillRect(b.x, b.y, b.width, b.height);
        }
    }

    // The icon sprite sits on a child of the slot (child 1; child 0 is the active
    // background), so match the slot itself and every child array to survive layout
    // differences.
    private boolean slotShowsActiveSprite(Widget slot, Set<Integer> activeSprites) {
        if (activeSprites.contains(slot.getSpriteId())) {
            return true;
        }
        return childHasSprite(slot.getChildren(), activeSprites)
                || childHasSprite(slot.getStaticChildren(), activeSprites)
                || childHasSprite(slot.getDynamicChildren(), activeSprites)
                || childHasSprite(slot.getNestedChildren(), activeSprites);
    }

    private static boolean childHasSprite(Widget[] children, Set<Integer> activeSprites) {
        if (children == null) {
            return false;
        }
        for (Widget child : children) {
            if (child != null && activeSprites.contains(child.getSpriteId())) {
                return true;
            }
        }
        return false;
    }

    // Narrow Gaussian flash (0..1) peaking where phase == center within the tick.
    private static double flash(double phase, double center) {
        double d = phase - center;
        return Math.exp(-(d * d) / (2.0 * FLASH_WIDTH * FLASH_WIDTH));
    }
}
