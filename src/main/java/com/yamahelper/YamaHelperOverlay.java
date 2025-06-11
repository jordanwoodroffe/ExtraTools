package com.yamahelper;

import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.*;

import javax.inject.Inject;
import java.awt.*;

public class YamaHelperOverlay extends Overlay {

    private final Client client;
    private final YamaHelperPlugin plugin;
    private final YamaHelperConfig config;
    private static final int YAMA_SIZE = 5; // Yama is 5x5 tiles

    @Inject
    public YamaHelperOverlay(Client client, YamaHelperPlugin plugin, YamaHelperConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        // Get all Yama NPCs in the scene
        for (NPC npc : plugin.getYamaNpcs()) {
            if (npc == null) {
                continue;
            }

            int npcIndex = npc.getIndex();

            // Render boulder highlighting if enabled
            if (config.highlightBoulders()) {
                renderBoulderHighlight(graphics, npc);
            }

            // Render attack style overlay if enabled
            if (config.highlightYama()) {
                renderAttackStyleOverlay(graphics, npc, npcIndex);
            }

            // Render attack timer if enabled
            if (config.showAttackTimers()) {
                renderAttackTimer(graphics, npc, npcIndex);
            }
        }

        return null;
    }

    private void renderBoulderHighlight(Graphics2D graphics, NPC npc) {
        // Get base tile location of the NPC
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

        if (config.showBorderOnly()) {
            // Draw just the outer border
            graphics.setColor(Color.YELLOW);
            graphics.setStroke(new BasicStroke(2));
            graphics.draw(borderPoly);
        } else {
            // Fill entire 5x5 area with semi-transparent color
            int transparency = config.boulderTransparency();
            graphics.setColor(new Color(255, 255, 0, transparency)); // Yellow with transparency
            graphics.fill(borderPoly);

            // Draw the border
            graphics.setColor(Color.YELLOW);
            graphics.setStroke(new BasicStroke(2));
            graphics.draw(borderPoly);
        }
    }

    private void renderAttackStyleOverlay(Graphics2D graphics, NPC npc, int npcIndex) {
        YamaHelperPlugin.YamaPhase phase = plugin.getYamaPhase(npcIndex);
        Color phaseColor = getPhaseColor(phase);

        if (phaseColor == null) {
            return;
        }

        // Get base tile location of the NPC
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
        addPointsToPolygon(borderPoly, swPoly, 0, 1);
        addPointsToPolygon(borderPoly, sePoly, 1, 2);
        addPointsToPolygon(borderPoly, nePoly, 2, 3);
        addPointsToPolygon(borderPoly, nwPoly, 3, 0);

        // Fill with phase color
        graphics.setColor(phaseColor);
        graphics.fill(borderPoly);

        // Draw border with solid color
        graphics.setColor(new Color(phaseColor.getRed(), phaseColor.getGreen(), phaseColor.getBlue(), 255));
        graphics.setStroke(new BasicStroke(2));
        graphics.draw(borderPoly);
    }

    private void renderAttackTimer(Graphics2D graphics, NPC npc, int npcIndex) {
        int attackTimer = plugin.getYamaAttackTimer(npcIndex);
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
        Font font = new Font("Arial", Font.BOLD, config.timerTextSize());
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
            textColor = config.warningColor();
        } else {
            textColor = config.normalTimerColor();
        }
        graphics.setColor(textColor);
        graphics.drawString(timerText, textX, textY);
    }

    private Color getPhaseColor(YamaHelperPlugin.YamaPhase phase) {
        switch (phase) {
            case MELEE:
                return config.meleeColor();
            case RANGE:
                return config.rangedColor();
            case MAGE:
                return config.magicColor();
            case FIRE_SPECIAL:
                return config.fireSpecialColor();
            case SHADOW_SPECIAL:
                return config.shadowSpecialColor();
            default:
                return null;
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
