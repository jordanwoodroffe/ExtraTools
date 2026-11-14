package com.pvmkits.core;

import net.runelite.api.Point;
import net.runelite.client.ui.overlay.components.ProgressPieComponent;
import net.runelite.client.ui.overlay.components.TextComponent;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * Draws a tick-eat countdown over an inventory item: a light grey pie that
 * drains from a full wheel to empty as the hit closes in, behind a centred
 * number that counts down in the given colour and turns green on 0, the tick
 * to click the food on. Shared by Sotetseg's death ball and the Archive
 * Guardian's beam so both look the same.
 */
public final class TickEatTimerRenderer {

    // The eat tick (0) turns the number this colour - the cue to click the food.
    private static final Color TICK_EAT_COLOR = new Color(0, 255, 0);

    // The draining wheel behind the number: light grey and translucent so the
    // count stays readable on top of it.
    private static final Color PIE_FILL_COLOR = new Color(210, 210, 210, 110);
    private static final Color PIE_BORDER_COLOR = new Color(210, 210, 210, 160);

    private TickEatTimerRenderer() {
    }

    public static void render(Graphics2D graphics, Rectangle bounds, int ticksToLand, int totalTicks,
                              double tickFraction, Color countColor) {
        int centerX = bounds.x + bounds.width / 2;
        int centerY = bounds.y + bounds.height / 2;

        // Wheel drains full -> empty across the flight. The whole-tick count steps
        // once per tick, so the fraction of the current tick already elapsed is
        // subtracted to drain the wheel smoothly between ticks. Guard the total so a
        // missing flight length can never divide by zero.
        double smoothTicks = Math.max(0.0, ticksToLand - tickFraction);
        double progress = totalTicks > 0
                ? Math.max(0.0, Math.min(1.0, smoothTicks / totalTicks))
                : 0.0;

        ProgressPieComponent pie = new ProgressPieComponent();
        pie.setDiameter(Math.min(bounds.width, bounds.height));
        pie.setPosition(new Point(centerX, centerY));
        pie.setFill(PIE_FILL_COLOR);
        pie.setBorderColor(PIE_BORDER_COLOR);
        pie.setProgress(progress);
        pie.render(graphics);

        // Number over the wheel, centred on the item. Counting colour while
        // counting down, green on 0 - the tick to click.
        String text = String.valueOf(ticksToLand);
        graphics.setFont(new Font("Arial", Font.BOLD, 16));
        FontMetrics metrics = graphics.getFontMetrics();
        int textX = centerX - metrics.stringWidth(text) / 2;
        int textY = centerY + (metrics.getAscent() - metrics.getDescent()) / 2;

        TextComponent textComponent = new TextComponent();
        textComponent.setText(text);
        textComponent.setColor(ticksToLand == 0 ? TICK_EAT_COLOR : countColor);
        textComponent.setPosition(new java.awt.Point(textX, textY));
        textComponent.render(graphics);
    }
}
