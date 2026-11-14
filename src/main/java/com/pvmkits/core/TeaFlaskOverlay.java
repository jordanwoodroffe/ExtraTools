package com.pvmkits.core;

import com.pvmkits.PvmKitsConfig;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.ui.overlay.components.TextComponent;

import javax.inject.Inject;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * Draws, over the tea flask in the inventory, the servings of tea left in it
 * (0-5), as tracked by {@link TeaFlaskTracker}. Shows "?" until the count is
 * known: look in the flask (or fill it to full) once to sync it. Styled like
 * the locator orb counter and RuneLite's item charges text: a small number in
 * the top-left corner of the item.
 */
public class TeaFlaskOverlay extends WidgetItemOverlay {

    private final PvmKitsConfig config;
    private final TeaFlaskTracker tracker;

    @Inject
    public TeaFlaskOverlay(PvmKitsConfig config, TeaFlaskTracker tracker) {
        this.config = config;
        this.tracker = tracker;
        showOnInventory();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem) {
        if (itemId != TeaFlaskTracker.TEA_FLASK_ITEM_ID || !config.showTeaFlaskCounter()) {
            return;
        }

        Rectangle bounds = widgetItem.getCanvasBounds();
        if (bounds == null) {
            return;
        }

        int servings = tracker.getServings();

        graphics.setFont(FontManager.getRunescapeSmallFont());
        TextComponent textComponent = new TextComponent();
        textComponent.setPosition(new Point(bounds.x - 1, bounds.y + 15));
        textComponent.setText(servings == TeaFlaskTracker.UNKNOWN ? "?" : String.valueOf(servings));
        textComponent.setColor(servings == 0
                ? config.teaFlaskEmptyColor()
                : config.teaFlaskCounterColor());
        textComponent.render(graphics);
    }
}
