package com.pvmkits.bosses.yama;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.*;

import javax.inject.Inject;
import java.awt.*;

public class YamaOverlay extends Overlay {

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;
    private static final int YAMA_SIZE = 5; // Yama is 5x5 tiles
    private static final int YAMA_ID = 14176;

    @Inject
    public YamaOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        // Only render if Yama highlighting is enabled
        if (!config.highlightYama() && !config.showAttackTimers()) {
            return null;
        }

        // First, render existing Yama highlights
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null || npc.getId() != YAMA_ID) {
                continue;
            }

            // Get the phase color for this Yama
            YamaHandler.YamaPhase phase = plugin.getYamaHandler().getYamaPhase(npc.getIndex());
            Color tileColor = phase.getColor();

            // Get base tile location of the NPC
            LocalPoint basePoint = npc.getLocalLocation();
            if (basePoint == null) {
                continue;
            }

            // Render attack style overlay if enabled
            if (config.highlightYama()) {
                renderAttackStyleOverlay(graphics, npc, tileColor);
            }

            // Render attack timer if enabled
            if (config.showAttackTimers()) {
                renderAttackTimer(graphics, npc);
            }
        }

        return null;
    }

    @SuppressWarnings("deprecation") // Using deprecated LocalPoint constructor to match working example
    private void renderAttackStyleOverlay(Graphics2D graphics, NPC npc, Color tileColor) {
        LocalPoint basePoint = npc.getLocalLocation();
        if (basePoint == null) {
            return;
        }

        // Calculate the southwest corner of the 5x5 area
        int swX = basePoint.getX() - (Perspective.LOCAL_TILE_SIZE * (YAMA_SIZE - 1) / 2);
        int swY = basePoint.getY() - (Perspective.LOCAL_TILE_SIZE * (YAMA_SIZE - 1) / 2);

        // Calculate the northeast corner of the 5x5 area
        int neX = swX + ((YAMA_SIZE - 1) * Perspective.LOCAL_TILE_SIZE);
        int neY = swY + ((YAMA_SIZE - 1) * Perspective.LOCAL_TILE_SIZE);

        // Create LocalPoints for the four corners of the 5x5 area
        LocalPoint swPoint = new LocalPoint(swX, swY);
        LocalPoint sePoint = new LocalPoint(neX, swY);
        LocalPoint nePoint = new LocalPoint(neX, neY);
        LocalPoint nwPoint = new LocalPoint(swX, neY);

        // Get the polygons for each corner tile
        Polygon swPoly = Perspective.getCanvasTilePoly(client, swPoint);
        Polygon sePoly = Perspective.getCanvasTilePoly(client, sePoint);
        Polygon nePoly = Perspective.getCanvasTilePoly(client, nePoint);
        Polygon nwPoly = Perspective.getCanvasTilePoly(client, nwPoint);

        if (swPoly == null || sePoly == null || nePoly == null || nwPoly == null) {
            return;
        }

        // Create a consolidated area polygon
        Polygon borderPoly = new Polygon();

        // Add the outer points of the 5x5 area to create the border
        // South edge (SW to SE)
        addPointsToPolygon(borderPoly, swPoly, 0, 1);
        // East edge (SE to NE)
        addPointsToPolygon(borderPoly, sePoly, 1, 2);
        // North edge (NE to NW)
        addPointsToPolygon(borderPoly, nePoly, 2, 3);
        // West edge (NW to SW)
        addPointsToPolygon(borderPoly, nwPoly, 3, 0);

        // Fill entire 5x5 area with semi-transparent color
        graphics.setColor(new Color(tileColor.getRed(), tileColor.getGreen(),
                tileColor.getBlue(), config.boulderTransparency()));
        graphics.fill(borderPoly);

        // Draw just the outer border with solid color
        graphics.setColor(tileColor);
        graphics.draw(borderPoly);
    }

    private void renderAttackTimer(Graphics2D graphics, NPC npc) {
        // Display attack timer over the center of Yama
        int npcIndex = npc.getIndex();
        int attackTimer = plugin.getYamaHandler().getYamaAttackTimer(npcIndex);

        // Only render if timer is valid and greater than 0
        if (attackTimer > 0) {
            // Get center point of the NPC for text positioning
            LocalPoint center = npc.getLocalLocation();
            if (center != null) {
                net.runelite.api.Point textPoint = Perspective.localToCanvas(client, center, 0);
                if (textPoint != null) {
                    // Set text properties
                    String timerText = String.valueOf(attackTimer);
                    java.awt.Font font = new java.awt.Font("Arial", java.awt.Font.BOLD, config.timerTextSize());
                    graphics.setFont(font);

                    java.awt.FontMetrics metrics = graphics.getFontMetrics();
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

                    // Draw main text - use config colors
                    Color textColor;
                    if (attackTimer == 1) {
                        textColor = config.warningColor(); // Bright red for '1'
                    } else {
                        textColor = config.normalTimerColor(); // Bright teal for other numbers
                    }
                    graphics.setColor(textColor);
                    graphics.drawString(timerText, textX, textY);
                }
            }
        }
    }

    // Helper method to add points from one polygon to another
    private void addPointsToPolygon(Polygon targetPoly, Polygon sourcePoly, int startIdx, int endIdx) {
        int sourcePoints = sourcePoly.npoints;
        if (startIdx >= sourcePoints || endIdx >= sourcePoints) {
            return;
        }

        targetPoly.addPoint(sourcePoly.xpoints[startIdx], sourcePoly.ypoints[startIdx]);
        targetPoly.addPoint(sourcePoly.xpoints[endIdx], sourcePoly.ypoints[endIdx]);
    }
}
