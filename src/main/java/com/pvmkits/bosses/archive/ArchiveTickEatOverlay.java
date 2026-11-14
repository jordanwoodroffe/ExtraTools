package com.pvmkits.bosses.archive;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import com.pvmkits.core.TickEatTimerRenderer;
import net.runelite.api.ItemComposition;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

/**
 * Tick-eat countdowns over food in the inventory, drawn exactly like Sotetseg's
 * death-ball timer: a draining grey pie behind a red number that counts down to
 * a green 0 on the tick the hit lands. Used for the Archive Guardian's beam.
 */
public class ArchiveTickEatOverlay extends WidgetItemOverlay {

    // Sotetseg's default counting colour, so the two timers read the same.
    private static final Color COUNT_COLOR = new Color(255, 0, 0);

    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;
    private final ItemManager itemManager;

    // Item id -> whether it is a covered food, so names are only looked up once.
    private final Map<Integer, Boolean> foodByItemId = new HashMap<>();

    @Inject
    public ArchiveTickEatOverlay(PvmKitsPlugin plugin, PvmKitsConfig config, ItemManager itemManager) {
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
        showOnInventory();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem) {
        if (!config.showArchiveGuardianBeamTimer()) {
            return;
        }

        FracturedArchiveHandler handler = plugin.getFracturedArchiveHandler();
        if (handler == null || plugin.getActiveBossHandler() != handler) {
            return;
        }

        // A negative timer means nothing is charging, which keeps the number off
        // every food item the rest of the fight.
        int ticksToLand = handler.getBeamTickEatTimer();
        if (ticksToLand < 0 || !isCoveredFood(itemId)) {
            return;
        }

        Rectangle bounds = widgetItem.getCanvasBounds();
        if (bounds == null) {
            return;
        }
        TickEatTimerRenderer.render(graphics, bounds, ticksToLand, handler.getBeamChargeTicks(),
                handler.getGameTickFraction(), COUNT_COLOR);
    }

    // Matched by name rather than id so every brew dose and the Archive's Tea
    // flask are covered without hardcoding their ids. The match is case-sensitive,
    // so "Purple sweets" (capital P) is covered but lowercase "purple sweets" is not.
    private boolean isCoveredFood(int itemId) {
        return foodByItemId.computeIfAbsent(itemId, id -> {
            ItemComposition item = itemManager.getItemComposition(id);
            return item.getNote() == -1 && isCoveredFoodName(item.getName());
        });
    }

    static boolean isCoveredFoodName(String name) {
        if (name == null) {
            return false;
        }
        return name.equals("Shark")
                || name.startsWith("Saradomin brew")
                || name.startsWith("Tea flask")
                || name.equals("Purple sweets");
    }
}
