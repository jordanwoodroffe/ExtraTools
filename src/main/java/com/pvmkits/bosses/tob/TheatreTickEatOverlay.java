package com.pvmkits.bosses.tob;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import com.pvmkits.core.TickEatTimerRenderer;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

import javax.inject.Inject;
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
            TickEatTimerRenderer.render(graphics, bounds, deathBall, handler.getDeathBallFlightTicks(),
                    handler.getGameTickFraction(), config.tobAttackTimerColor());
        }
    }
}
