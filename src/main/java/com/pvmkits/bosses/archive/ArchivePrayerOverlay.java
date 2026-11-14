package com.pvmkits.bosses.archive;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.Prayer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * The Archive's prayer timing in the open prayer book, built like the Doom of
 * Mokhaiotl's prayer highlight: Doom's soft green fill over the protection prayer
 * to be on, shown only while that prayer is not already on. Moia and Dreadnoth
 * are the two choices for the third boss, so at most one of them is ever up.
 *
 * Moia: Protect from Missiles for the whole fight, except Protect from Magic
 * for the one tick an axe's damage lands (5 ticks after it is thrown, 6 in her
 * enrage, read off the axe's flight), then straight back to Protect from
 * Missiles (see {@link FracturedArchiveHandler#getMoiaPrayer()}). While an axe
 * is in the air a 5-4-3-2-1 countdown sits on Protect from Magic, its green "1"
 * on the tick the Magic highlight turns on. Moia's attack-style 3x3 in
 * FracturedArchiveOverlay stays green, with a purple centre tile while an axe
 * is in flight.
 *
 * Dreadnoth: Protect from Melee for the whole fight, except Protect from
 * Missiles on each tick one of his projectiles hits you, which after each slam
 * is three ticks in a row, then straight back to Melee for the next slam (see
 * {@link FracturedArchiveHandler#getDreadnothPrayer()}). From the tick after
 * each slam a 4-3-2-1 countdown sits on Protect from Missiles, its green "1"
 * on the first Missiles tick.
 */
public class ArchivePrayerOverlay extends Overlay {

    // Prayer book slots: InterfaceID.PRAYERBOOK child 9 (PRAYER1) .. 38 (PRAYER30).
    private static final int FIRST_PRAYER_CHILD = 9;
    private static final int LAST_PRAYER_CHILD = 38;
    private static final int PROTECT_MAGIC_SPRITE = 127;
    private static final int PROTECT_MISSILES_SPRITE = 128;
    private static final int PROTECT_MELEE_SPRITE = 129;
    // Doom's prayer highlight colour (MokhaiotlPrayerOverlay), a soft green.
    private static final Color PRAYER_HIGHLIGHT_COLOR = new Color(150, 220, 165);
    // Doom's highlight opacity, so the icon still shows through.
    private static final int PRAYER_FILL_ALPHA = 120;
    // Axe countdown text: white while counting, the Archive timers' pastel
    // green (FracturedArchiveOverlay's STEP_TICK_COLOR) on the Magic tick.
    private static final Color COUNTDOWN_COLOR = Color.WHITE;
    private static final Color COUNTDOWN_GO_COLOR = new Color(144, 238, 144);

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;

    @Inject
    public ArchivePrayerOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        boolean showMoiaFill = config.showMoiaPrayerTiming();
        boolean showMoiaCountdown = config.showMoiaAxeCountdown();
        boolean showDreadnothFill = config.showDreadnothPrayerTiming();
        boolean showDreadnothCountdown = config.showDreadnothRangedCountdown();
        if (!showMoiaFill && !showMoiaCountdown && !showDreadnothFill && !showDreadnothCountdown) {
            return null;
        }
        FracturedArchiveHandler handler = plugin.getFracturedArchiveHandler();
        if (handler == null || plugin.getActiveBossHandler() != handler) {
            return null;
        }
        Prayer moiaPrayer = handler.getMoiaPrayer();
        Prayer dreadnothPrayer = moiaPrayer == null && (showDreadnothFill || showDreadnothCountdown)
                ? handler.getDreadnothPrayer() : null;
        Prayer fillPrayer = showMoiaFill ? moiaPrayer : null;
        if (fillPrayer == null && showDreadnothFill) {
            fillPrayer = dreadnothPrayer;
        }
        // Each countdown only runs while its boss is up: Moia's axe over
        // Protect from Magic, Dreadnoth's ranged hits over Protect from Missiles.
        int countdown = 0;
        int countdownSprite = PROTECT_MAGIC_SPRITE;
        int countdownTextSize = config.moiaAxeCountdownTextSize();
        if (moiaPrayer != null && showMoiaCountdown) {
            countdown = handler.getMoiaAxeCountdown();
        } else if (dreadnothPrayer != null && showDreadnothCountdown) {
            countdown = handler.getDreadnothRangedCountdown();
            countdownSprite = PROTECT_MISSILES_SPRITE;
            countdownTextSize = config.dreadnothRangedCountdownTextSize();
        }
        if (fillPrayer == null && countdown == 0) {
            return null;
        }

        // Only while the prayer book tab is open, else the slots hold stale bounds.
        Widget prayerRoot = client.getWidget(InterfaceID.PRAYERBOOK, 0);
        if (prayerRoot == null || prayerRoot.isHidden()) {
            return null;
        }

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Like Doom's: only while the prayer to be on is not already on.
        if (fillPrayer != null && client.getVarbitValue(fillPrayer.getVarbit()) == 0) {
            Rectangle b = slotBounds(spriteFor(fillPrayer));
            if (b != null) {
                Color color = PRAYER_HIGHLIGHT_COLOR;
                graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), PRAYER_FILL_ALPHA));
                graphics.fillRect(b.x, b.y, b.width, b.height);
            }
        }

        // The countdown sits on its prayer whether or not it is on, drawn like
        // the Archive's boss timers: bold Dialog with OverlayUtil's black drop
        // shadow, white until the "1" (the tick to switch), in green.
        if (countdown > 0) {
            Rectangle b = slotBounds(countdownSprite);
            if (b != null) {
                String text = String.valueOf(countdown);
                graphics.setFont(new Font(Font.DIALOG, Font.BOLD, countdownTextSize));
                FontMetrics metrics = graphics.getFontMetrics();
                int x = b.x + (b.width - metrics.stringWidth(text)) / 2;
                int y = b.y + (b.height - metrics.getHeight()) / 2 + metrics.getAscent();
                OverlayUtil.renderTextLocation(graphics, new Point(x, y), text,
                        countdown == 1 ? COUNTDOWN_GO_COLOR : COUNTDOWN_COLOR);
            }
        }
        return null;
    }

    private static int spriteFor(Prayer prayer) {
        switch (prayer) {
            case PROTECT_FROM_MAGIC:
                return PROTECT_MAGIC_SPRITE;
            case PROTECT_FROM_MELEE:
                return PROTECT_MELEE_SPRITE;
            default:
                return PROTECT_MISSILES_SPRITE;
        }
    }

    // The bounds of the prayer book slot showing the given icon, or null.
    private Rectangle slotBounds(int sprite) {
        for (int child = FIRST_PRAYER_CHILD; child <= LAST_PRAYER_CHILD; child++) {
            Widget slot = client.getWidget(InterfaceID.PRAYERBOOK, child);
            if (slot == null || slot.isHidden() || !hasSprite(slot, sprite)) {
                continue;
            }
            Rectangle b = slot.getBounds();
            if (b != null && b.width > 0 && b.height > 0) {
                return b;
            }
        }
        return null;
    }

    // The icon sprite sits on a child of the slot, not always the slot itself.
    private static boolean hasSprite(Widget slot, int sprite) {
        return slot.getSpriteId() == sprite
                || anyHasSprite(slot.getChildren(), sprite)
                || anyHasSprite(slot.getStaticChildren(), sprite)
                || anyHasSprite(slot.getDynamicChildren(), sprite)
                || anyHasSprite(slot.getNestedChildren(), sprite);
    }

    private static boolean anyHasSprite(Widget[] children, int sprite) {
        if (children == null) {
            return false;
        }
        for (Widget child : children) {
            if (child != null && child.getSpriteId() == sprite) {
                return true;
            }
        }
        return false;
    }
}
