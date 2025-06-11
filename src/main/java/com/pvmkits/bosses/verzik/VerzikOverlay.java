package com.pvmkits.bosses.verzik;

import com.pvmkits.PvmKitsConfig;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.*;

import javax.inject.Inject;
import java.awt.*;

public class VerzikOverlay extends Overlay {

    private final Client client;
    private final VerzikHandler verzikHandler;
    private final PvmKitsConfig config;

    @Inject
    public VerzikOverlay(Client client, VerzikHandler verzikHandler, PvmKitsConfig config) {
        this.client = client;
        this.verzikHandler = verzikHandler;
        this.config = config;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        // Check if Verzik features are enabled
        if (!config.enableVerzik()) {
            return null;
        }

        // Get all Verzik NPCs in the scene
        for (NPC npc : verzikHandler.getVerzikNpcs()) {
            if (npc == null) {
                continue;
            }

            int npcIndex = npc.getIndex();

            // Render attack timer if enabled
            if (config.showVerzikTimer()) {
                renderAttackTimer(graphics, npc, npcIndex);
            }
        }

        return null;
    }

    private void renderAttackTimer(Graphics2D graphics, NPC npc, int npcIndex) {
        int attackTimer = verzikHandler.getVerzikAttackTimer(npcIndex);
        if (attackTimer <= 0) {
            return;
        }

        // Get center point of the NPC for text positioning
        LocalPoint center = npc.getLocalLocation();
        if (center == null) {
            return;
        }

        net.runelite.api.Point textPoint = Perspective.localToCanvas(client, center, 0);
        if (textPoint == null) {
            return;
        }

        // Set text properties
        String timerText = String.valueOf(attackTimer);
        Font font = new Font("Arial", Font.BOLD, config.verzikTimerSize());
        graphics.setFont(font);

        FontMetrics metrics = graphics.getFontMetrics();
        int textWidth = metrics.stringWidth(timerText);
        int textHeight = metrics.getHeight();
        int textX = textPoint.getX() - (textWidth / 2);
        int textY = textPoint.getY() + (textHeight / 4); // Center vertically

        // Draw outline for visibility
        graphics.setColor(Color.BLACK);
        graphics.drawString(timerText, textX - 2, textY - 2);
        graphics.drawString(timerText, textX + 2, textY - 2);
        graphics.drawString(timerText, textX - 2, textY + 2);
        graphics.drawString(timerText, textX + 2, textY + 2);
        graphics.drawString(timerText, textX - 2, textY);
        graphics.drawString(timerText, textX + 2, textY);
        graphics.drawString(timerText, textX, textY - 2);
        graphics.drawString(timerText, textX, textY + 2);

        // Draw main text - use warning color for '1', normal color for others
        Color textColor;
        if (attackTimer == 1) {
            textColor = config.verzikWarningColor();
        } else {
            textColor = config.verzikTimerColor();
        }
        graphics.setColor(textColor);
        graphics.drawString(timerText, textX, textY);
    }
}