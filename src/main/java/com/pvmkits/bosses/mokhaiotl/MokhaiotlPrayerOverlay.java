package com.pvmkits.bosses.mokhaiotl;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dedicated prayer-book highlight system for the Doom of Mokhaiotl. It reads the
 * scheduled prayer events from {@link MokhaiotlHandler} and fills the required
 * protection prayer icon in the open prayer book with a transparent colour, shown
 * on the single most imminent incoming attack until it is prayed. It mimics the
 * transparent-fill highlight style of the prayer flick tool without sharing its
 * internal logic.
 *
 * <p>Only one prayer is highlighted at a time, and it does not advance to the next
 * attack until the current one impacts (it stops being the most imminent), so the
 * player is guided through back-to-back sequences (the boulder shatter Range -&gt;
 * Magic -&gt; Range) one switch at a time without being prompted early.</p>
 */
public class MokhaiotlPrayerOverlay extends Overlay {

    // Prayer book slots: InterfaceID.PRAYERBOOK child 9 (PRAYER1) .. 38 (PRAYER30).
    private static final int FIRST_PRAYER_CHILD = 9;
    private static final int LAST_PRAYER_CHILD = 38;
    private static final Color PRAYER_HIGHLIGHT_COLOR = new Color(150, 220, 165);

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;

    @Inject
    public MokhaiotlPrayerOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showMokhaiotlPrayerOutline()) {
            return null;
        }

        MokhaiotlHandler handler = plugin.getMokhaiotlHandler();
        if (handler == null) {
            return null;
        }

        Map<Integer, Color> spriteColors = buildSpriteColors(handler);
        if (spriteColors.isEmpty()) {
            return null;
        }

        // Only draw while the prayer book tab is open, else the slots hold stale
        // bounds and we would draw over whatever tab is showing.
        Widget prayerRoot = client.getWidget(InterfaceID.PRAYERBOOK, 0);
        if (prayerRoot == null || prayerRoot.isHidden()) {
            return null;
        }

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (int child = FIRST_PRAYER_CHILD; child <= LAST_PRAYER_CHILD; child++) {
            Widget slot = client.getWidget(InterfaceID.PRAYERBOOK, child);
            if (slot == null || slot.isHidden()) {
                continue;
            }
            Color color = colorForSlot(slot, spriteColors);
            if (color == null) {
                continue;
            }
            Rectangle b = slot.getBounds();
            if (b == null || b.width <= 0 || b.height <= 0) {
                continue;
            }
            // Transparent fill over the icon area so the icon still shows through.
            int alpha = config.mokhaiotlPrayerGreenTransparency();
            graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
            graphics.fillRect(b.x, b.y, b.width, b.height);
        }
        return null;
    }

    private Map<Integer, Color> buildSpriteColors(MokhaiotlHandler handler) {
        Map<Integer, Color> spriteColors = new HashMap<>();

        int currentTick = client.getTickCount();
        MokhaiotlHandler.PrayerEvent imminent = mostImminentFuture(handler.getPrayerEvents(), currentTick);
        if (imminent != null && !isPrayerActive(imminent.style)) {
            spriteColors.put(imminent.style.getSpriteId(), PRAYER_HIGHLIGHT_COLOR);
        }
        return spriteColors;
    }

    // The pending attack with the lowest impact tick. An event stays the highlight
    // target through its own impact tick and is only dropped once that tick has passed,
    // so the player is never told to swap off a prayer while its hit is still pending.
    private static MokhaiotlHandler.PrayerEvent mostImminentFuture(
            List<MokhaiotlHandler.PrayerEvent> events, int currentTick) {
        MokhaiotlHandler.PrayerEvent soonest = null;
        for (MokhaiotlHandler.PrayerEvent event : events) {
            if (event.impactTick < currentTick) {
                continue;
            }
            if (soonest == null || event.impactTick < soonest.impactTick) {
                soonest = event;
            }
        }
        return soonest;
    }

    // True when the protection prayer for this style is already active.
    private boolean isPrayerActive(MokhaiotlHandler.Style style) {
        return client.getVarbitValue(style.getPrayer().getVarbit()) != 0;
    }

    private Color colorForSlot(Widget slot, Map<Integer, Color> spriteColors) {
        Color direct = spriteColors.get(slot.getSpriteId());
        if (direct != null) {
            return direct;
        }
        // The icon sprite sits on a child of the slot, not the slot itself.
        Color child = childColor(slot.getChildren(), spriteColors);
        if (child != null) {
            return child;
        }
        child = childColor(slot.getStaticChildren(), spriteColors);
        if (child != null) {
            return child;
        }
        child = childColor(slot.getDynamicChildren(), spriteColors);
        if (child != null) {
            return child;
        }
        return childColor(slot.getNestedChildren(), spriteColors);
    }

    private static Color childColor(Widget[] children, Map<Integer, Color> spriteColors) {
        if (children == null) {
            return null;
        }
        for (Widget child : children) {
            if (child == null) {
                continue;
            }
            Color color = spriteColors.get(child.getSpriteId());
            if (color != null) {
                return color;
            }
        }
        return null;
    }

    // Exposed for readability of the sprite set this overlay reacts to.
    static Set<Integer> protectionPrayerSprites() {
        return Set.of(127, 128, 129);
    }
}
