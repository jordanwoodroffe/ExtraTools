package com.pvmkits.bosses.phosani;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.*;

import javax.inject.Inject;
import java.awt.*;
import java.util.Set;

public class PhosaniOverlay extends Overlay {

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;
    private static final int PHOSANI_SIZE = 5; // Phosani is 5x5 tiles
    private static final Set<Integer> PHOSANI_IDS = Set.of(9416, 9417, 9418, 9419, 9420, 9421, 9422, 9423, 9424, 11153,
            11154, 11155, 377);

    // Sleepwalker NPC IDs for highlighting
    private static final Set<Integer> SLEEPWALKER_IDS = Set.of(1029, 1030, 1031, 1032, 5267, 5368, 9446, 9447, 9448,
            9449, 9450, 9451, 9470, 9801, 9802);

    // Husk NPC IDs for highlighting
    private static final Set<Integer> HUSK_IDS = Set.of(9454, 9455, 9466, 9467);

    @Inject
    public PhosaniOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        // Only render if any Phosani features are enabled
        if (!config.highlightPhosani() && !config.showPhosaniAttackTimers() && !config.highlightSporeDangerZones()
                && !config.highlightSleepwalkers()) {
            return null;
        }

        // Get the Phosani handler from the plugin
        PhosaniHandler phosaniHandler = plugin.getPhosaniHandler();
        if (phosaniHandler == null) {
            return null;
        }

        // Render existing Phosani highlights
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null || !PHOSANI_IDS.contains(npc.getId())) {
                continue;
            }

            // Get the effective phase color for this Phosani (accounts for curse)
            PhosaniHandler.PhosaniPhase effectivePhase = phosaniHandler.getEffectivePhase(npc.getIndex());
            Color tileColor = effectivePhase.getColor();

            // Get base tile location of the NPC
            LocalPoint basePoint = npc.getLocalLocation();
            if (basePoint == null) {
                continue;
            }

            // Render attack style overlay if enabled
            if (config.highlightPhosani()) {
                renderAttackStyleOverlay(graphics, npc, tileColor);
            }

            // Render attack timer if enabled
            if (config.showPhosaniAttackTimers()) {
                renderAttackTimer(graphics, npc, phosaniHandler);
            }
        }

        // Render spore danger zones if enabled
        if (config.highlightSporeDangerZones()) {
            renderSporeDangerZones(graphics, phosaniHandler);
        }

        // Render sleepwalker highlighting if enabled
        if (config.highlightSleepwalkers()) {
            renderSleepwalkerHighlights(graphics);
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
        int swX = basePoint.getX() - (Perspective.LOCAL_TILE_SIZE * (PHOSANI_SIZE - 1) / 2);
        int swY = basePoint.getY() - (Perspective.LOCAL_TILE_SIZE * (PHOSANI_SIZE - 1) / 2);

        // Calculate the northeast corner of the 5x5 area
        int neX = swX + ((PHOSANI_SIZE - 1) * Perspective.LOCAL_TILE_SIZE);
        int neY = swY + ((PHOSANI_SIZE - 1) * Perspective.LOCAL_TILE_SIZE);

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
                tileColor.getBlue(), config.phosaniTransparency()));
        graphics.fill(borderPoly);

        // Draw just the outer border with solid color
        graphics.setColor(tileColor);
        graphics.draw(borderPoly);
    }

    private void renderAttackTimer(Graphics2D graphics, NPC npc, PhosaniHandler phosaniHandler) {
        // Display attack timer at a static position relative to Phosani's hull
        int npcIndex = npc.getIndex();
        int attackTimer = phosaniHandler.getPhosaniAttackTimer(npcIndex);

        // Only render if timer is valid and greater than 0
        if (attackTimer > 0) {
            // Use NPC's base tile location for rock-solid positioning
            // This moves with Phosani but doesn't wobble with animations
            LocalPoint basePoint = npc.getLocalLocation();
            if (basePoint != null) {
                // Convert to canvas coordinates using the stable base tile location
                Polygon baseTilePoly = Perspective.getCanvasTilePoly(client, basePoint);
                if (baseTilePoly != null) {
                    // Get the center of the base tile polygon
                    Rectangle tileRect = baseTilePoly.getBounds();
                    int centerX = tileRect.x + tileRect.width / 2;
                    // Position timer below the base tile center (at feet level)
                    int stableY = tileRect.y + tileRect.height;

                    // Set text properties - make text bigger
                    String timerText = String.valueOf(attackTimer);
                    int fontSize = Math.max(20, config.phosaniTimerTextSize() + 8); // Minimum 24px, +8 from config
                    java.awt.Font font = new java.awt.Font("Arial", java.awt.Font.BOLD, fontSize);
                    graphics.setFont(font);

                    java.awt.FontMetrics metrics = graphics.getFontMetrics();
                    int textWidth = metrics.stringWidth(timerText);
                    int textHeight = metrics.getHeight();
                    int textX = centerX - (textWidth / 2);
                    int textY = stableY + (textHeight / 4);

                    // Draw main text - use config colors
                    Color textColor;
                    if (attackTimer == 1) {
                        textColor = config.phosaniWarningColor(); // Bright red for '1'
                    } else {
                        textColor = config.phosaniNormalTimerColor(); // Bright teal for other numbers
                    }
                    graphics.setColor(textColor);
                    graphics.drawString(timerText, textX, textY);
                }
            }
        }
    }

    // Helper method to add points from one polygon to another with safety checks
    private void addPointsToPolygon(Polygon targetPoly, Polygon sourcePoly, int startIdx, int endIdx) {
        if (sourcePoly == null || sourcePoly.npoints == 0) {
            return;
        }

        int sourcePoints = sourcePoly.npoints;
        // Ensure indices are within bounds
        startIdx = Math.max(0, Math.min(startIdx, sourcePoints - 1));
        endIdx = Math.max(0, Math.min(endIdx, sourcePoints - 1));

        if (startIdx < sourcePoints && endIdx < sourcePoints) {
            targetPoly.addPoint(sourcePoly.xpoints[startIdx], sourcePoly.ypoints[startIdx]);
            targetPoly.addPoint(sourcePoly.xpoints[endIdx], sourcePoly.ypoints[endIdx]);
        }
    }

    private void renderSporeDangerZones(Graphics2D graphics, PhosaniHandler phosaniHandler) {
        // Get spore danger zones from handler
        for (WorldPoint sporeLocation : phosaniHandler.getSporeDangerZones()) {
            // Render 3x3 danger zone around each spore (center + 1 tile radius)
            renderSporeDangerZone(graphics, sporeLocation);
        }
    }

    @SuppressWarnings("deprecation") // Using deprecated LocalPoint constructor to match working example
    private void renderSporeDangerZone(Graphics2D graphics, WorldPoint centerLocation) {
        // Create 3x3 area around the spore location (center + 1 tile radius)
        LocalPoint centerPoint = LocalPoint.fromWorld(client, centerLocation);
        if (centerPoint == null) {
            return;
        }

        // Calculate the southwest corner of the 3x3 area
        int swX = centerPoint.getX() - Perspective.LOCAL_TILE_SIZE;
        int swY = centerPoint.getY() - Perspective.LOCAL_TILE_SIZE;

        // Calculate the northeast corner of the 3x3 area
        int neX = swX + (2 * Perspective.LOCAL_TILE_SIZE);
        int neY = swY + (2 * Perspective.LOCAL_TILE_SIZE);

        // Create LocalPoints for the four corners of the 3x3 area
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

        // Create a consolidated area polygon for the 3x3 border
        Polygon borderPoly = new Polygon();

        // Add the outer points of the 3x3 area to create the border
        // South edge (SW to SE)
        addPointsToPolygon(borderPoly, swPoly, 0, 1);
        // East edge (SE to NE)
        addPointsToPolygon(borderPoly, sePoly, 1, 2);
        // North edge (NE to NW)
        addPointsToPolygon(borderPoly, nePoly, 2, 3);
        // West edge (NW to SW)
        addPointsToPolygon(borderPoly, nwPoly, 3, 0);

        // Fill the 3x3 area with soft red semi-transparent color
        graphics.setColor(new Color(255, 0, 0, 80)); // Soft red fill with low opacity
        graphics.fill(borderPoly);

        // Draw only the border with soft red color (more opaque)
        graphics.setColor(new Color(255, 0, 0, 180)); // Soft red with transparency
        graphics.setStroke(new BasicStroke(3)); // Make border thicker for visibility
        graphics.draw(borderPoly);
    }

    private void renderSleepwalkerHighlights(Graphics2D graphics) {
        // Soft red color for sleepwalkers and husks
        Color softRed = new Color(255, 100, 100, 120); // Soft red with transparency

        // Find and highlight all sleepwalkers and husks
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null) {
                continue;
            }

            // Check if this NPC is a sleepwalker or husk
            boolean isSleepwalker = SLEEPWALKER_IDS.contains(npc.getId());
            boolean isHusk = HUSK_IDS.contains(npc.getId());

            if (isSleepwalker || isHusk) {
                renderNpcHighlight(graphics, npc, softRed);
            }
        }
    }

    private void renderNpcHighlight(Graphics2D graphics, NPC npc, Color highlightColor) {
        LocalPoint npcLocation = npc.getLocalLocation();
        if (npcLocation == null) {
            return;
        }

        // Highlight the tile
        Polygon tilePoly = Perspective.getCanvasTilePoly(client, npcLocation);
        if (tilePoly != null) {
            // Fill tile with semi-transparent color
            graphics.setColor(highlightColor);
            graphics.fill(tilePoly);

            // Draw tile border with solid color
            graphics.setColor(new Color(highlightColor.getRed(), highlightColor.getGreen(),
                    highlightColor.getBlue(), 255)); // Full opacity for border
            graphics.setStroke(new BasicStroke(2));
            graphics.draw(tilePoly);
        }

        // Highlight the hull
        Shape hull = npc.getConvexHull();
        if (hull != null) {
            // Draw hull outline with soft red
            graphics.setColor(new Color(255, 0, 0, 200)); // Slightly more opaque red for hull
            graphics.setStroke(new BasicStroke(2));
            graphics.draw(hull);
        }
    }
}
