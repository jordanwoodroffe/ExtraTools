package com.pvmkits.bosses.tob;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Point;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.ui.overlay.components.ProgressPieComponent;
import net.runelite.client.ui.overlay.components.TextComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Set;

/**
 * Draws a tick-eat countdown over food in the inventory, so the number to eat on
 * sits on the item you actually click rather than on the boss (who is often
 * off-screen while you tick eat at range). Covers Sotetseg's death ball, drawn over
 * sharks and brews. A light grey pie behind the number drains from a full wheel to
 * empty as the hit closes in, the same style RuneLite plugins use for inventory
 * timers; the number counts down in red and turns green on 0, the tick to eat on.
 * Renders only while the ball is airborne and only over the covered foods.
 */
public class TheatreTickEatOverlay extends WidgetItemOverlay {

    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;

    // The eat tick (0) turns the number this colour - the cue to click the food.
    private static final Color TICK_EAT_COLOR = new Color(0, 255, 0);

    // The draining wheel behind the number: light grey and translucent so the
    // count stays readable on top of it.
    private static final Color PIE_FILL_COLOR = new Color(210, 210, 210, 110);
    private static final Color PIE_BORDER_COLOR = new Color(210, 210, 210, 160);

    // Foods the Sotetseg death-ball countdown draws over: cooked shark and every
    // Saradomin brew dose (brew ids step down a dose at a time as it is drunk).
    private static final Set<Integer> DEATH_BALL_FOOD_ITEM_IDS = Set.of(
            385,   // Shark
            6685,  // Saradomin brew(4)
            6687,  // Saradomin brew(3)
            6689,  // Saradomin brew(2)
            6691); // Saradomin brew(1)

    @Inject
    public TheatreTickEatOverlay(PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.plugin = plugin;
        this.config = config;
        showOnInventory();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem) {
        if (!DEATH_BALL_FOOD_ITEM_IDS.contains(itemId)) {
            return;
        }

        TheatreHandler handler = plugin.getTheatreHandler();
        if (handler == null) {
            return;
        }

        Rectangle bounds = widgetItem.getCanvasBounds();
        if (bounds == null) {
            return;
        }

        // A negative timer means no death ball is airborne, which is the gate that
        // keeps the number off every food item at every other time.
        int deathBall = handler.getDeathBallTimer();
        if (config.tobSotetsegTickEatTimer() && deathBall >= 0) {
            renderTimer(graphics, bounds, deathBall, handler.getDeathBallFlightTicks(), handler.getGameTickFraction());
        }
    }

    /**
     * Draws one tick-eat countdown over a food item: a draining pie behind a
     * centred number that is red while counting down and green on 0, the tick to
     * click the food on.
     */
    private void renderTimer(Graphics2D graphics, Rectangle bounds, int ticksToLand, int totalTicks, double tickFraction) {
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

        // Number over the wheel, centred on the item. Red while counting down,
        // green on 0 - the tick to click.
        String text = String.valueOf(ticksToLand);
        graphics.setFont(new Font("Arial", Font.BOLD, 16));
        FontMetrics metrics = graphics.getFontMetrics();
        int textX = centerX - metrics.stringWidth(text) / 2;
        int textY = centerY + (metrics.getAscent() - metrics.getDescent()) / 2;

        TextComponent textComponent = new TextComponent();
        textComponent.setText(text);
        textComponent.setColor(ticksToLand == 0 ? TICK_EAT_COLOR : config.tobAttackTimerColor());
        textComponent.setPosition(new java.awt.Point(textX, textY));
        textComponent.render(graphics);
    }
}
