package com.pvmkits.bosses.cox;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;

/**
 * Renders Tekton's attack countdown timer, styled to match the Phosani/Yama
 * attack timers (bold Arial number at the boss's feet). Tekton is melee-only, so
 * no attack-style highlight is drawn.
 */
public class TektonOverlay extends Overlay {

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;

    @Inject
    public TektonOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.HIGHEST);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showTektonAttackTimer()) {
            return null;
        }

        TektonHandler handler = plugin.getTektonHandler();
        if (handler == null) {
            return null;
        }

        for (NPC npc : handler.getTektonNpcs()) {
            if (npc == null) {
                continue;
            }

            // 0 means Tekton isn't actively meleeing (at the anvil / disengaged).
            int attackTimer = handler.getTektonAttackTimer(npc.getIndex());
            if (attackTimer <= 0) {
                continue;
            }

            renderAttackTimer(graphics, npc, attackTimer);
        }

        return null;
    }

    private void renderAttackTimer(Graphics2D graphics, NPC npc, int attackTimer) {
        LocalPoint basePoint = npc.getLocalLocation();
        if (basePoint == null) {
            return;
        }

        Polygon baseTilePoly = Perspective.getCanvasTilePoly(client, basePoint);
        if (baseTilePoly == null) {
            return;
        }

        Rectangle tileRect = baseTilePoly.getBounds();
        int centerX = tileRect.x + tileRect.width / 2;
        int stableY = tileRect.y + tileRect.height;

        String timerText = String.valueOf(attackTimer);
        int fontSize = Math.max(20, config.tektonTimerTextSize() + 8);
        graphics.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, fontSize));

        FontMetrics metrics = graphics.getFontMetrics();
        int textX = centerX - (metrics.stringWidth(timerText) / 2);
        int textY = stableY + (metrics.getHeight() / 4);

        // "1" is the step-in tick (click into melee); highlight it in the step-in colour.
        Color color = attackTimer == 1 ? config.tektonStepTickColor() : config.tektonAttackTimerColor();
        graphics.setColor(color);
        graphics.drawString(timerText, textX, textY);
    }
}
