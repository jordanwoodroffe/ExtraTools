package com.pvmkits.core;

import com.pvmkits.PvmKitsConfig;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.ui.overlay.components.TextComponent;

import javax.inject.Inject;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * Draws, over the locator orb in the inventory, the number of clicks needed to
 * bring the player just above the Redemption threshold so the next click procs it.
 * Each use of the orb deals a fixed 10 damage (down to a minimum of 1 hitpoint),
 * and Redemption procs when hitpoints fall to 10% or below of the maximum (9 at
 * 99 hitpoints). So the count is how many 10-damage clicks remain before the proc:
 * it counts down ...3, 2, 1 (armed - the next click procs) then 0 (already at or
 * below the threshold, Redemption procs), and resets once the heal restores the
 * player. Styled like RuneLite's weapon/item charges text: a small number in the
 * top-left corner of the item.
 */
public class LocatorOrbOverlay extends WidgetItemOverlay {

    // Locator orb (Dragon Slayer II reward) inventory item id.
    private static final int LOCATOR_ORB_ITEM_ID = 22081;

    // Hitpoints lost per use of the orb (the game clamps this to a minimum of 1 hp).
    private static final int DAMAGE_PER_USE = 10;

    private final Client client;
    private final PvmKitsConfig config;

    @Inject
    public LocatorOrbOverlay(Client client, PvmKitsConfig config) {
        this.client = client;
        this.config = config;
        showOnInventory();
    }

    @Override
    public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem) {
        if (itemId != LOCATOR_ORB_ITEM_ID || !config.showLocatorOrbRedemptionCounter()) {
            return;
        }

        int maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
        int currentHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        if (maxHp <= 0) {
            return;
        }

        // Redemption procs at 10% or below of maximum hitpoints (e.g. 9 at 99 hp).
        int threshold = maxHp / 10;

        // Clicks of 10 damage until current hp reaches the threshold: reads 1 when a
        // single further click will proc, and 0 once already at or below it.
        int clicksToProc = currentHp > threshold
                ? (int) Math.ceil((currentHp - threshold) / (double) DAMAGE_PER_USE)
                : 0;

        Rectangle bounds = widgetItem.getCanvasBounds();
        if (bounds == null) {
            return;
        }

        graphics.setFont(FontManager.getRunescapeSmallFont());
        TextComponent textComponent = new TextComponent();
        textComponent.setPosition(new Point(bounds.x - 1, bounds.y + 15));
        textComponent.setText(String.valueOf(clicksToProc));
        textComponent.setColor(clicksToProc <= 1
                ? config.locatorOrbArmedColor()
                : config.locatorOrbCounterColor());
        textComponent.render(graphics);
    }
}
