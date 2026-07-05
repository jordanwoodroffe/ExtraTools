package com.pvmkits.bosses.nightmare;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.*;

import javax.inject.Inject;
import java.awt.*;
import java.util.Set;

public class NightmareOverlay extends Overlay {

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;
    private static final int NIGHTMARE_SIZE = 5; // Nightmare is 5x5 tiles

    private static final Set<Integer> NIGHTMARE_IDS = Set.of(9425, 9426, 9427, 9428, 9429, 9430, 9431, 9432, 9433);

    @Inject
    public NightmareOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.HIGHEST);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        // Only render if any Nightmare features are enabled. The shared adds
        // (sleepwalkers, husks, parasites, totems and spore danger zones) are NPC- and
        // object-id based and are already drawn by the Phosani overlay during this
        // fight, so this overlay only handles the cues that depend on The Nightmare's
        // own NPC ids: attack style, curse, attack timers, safe tiles and surge.
        if (!config.highlightPhosani() && !config.showPhosaniAttackTimers()
                && !config.showPhosaniSafeTile() && !config.highlightPhosaniSurge()
                && !config.highlightPhosaniParasiteOutline()) {
            return null;
        }

        // Get the Nightmare handler from the plugin
        NightmareHandler nightmareHandler = plugin.getNightmareHandler();
        if (nightmareHandler == null) {
            return null;
        }

        // Render existing Nightmare highlights
        boolean nightmareVisible = false;
        // During the spore phase Nightmare doesn't attack, so suppress the attack
        // style overlay to cut down on UI clutter.
        boolean sporePhaseActive = nightmareHandler.isSporePhaseActive();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null || !NIGHTMARE_IDS.contains(npc.getId())) {
                continue;
            }
            nightmareVisible = true;

            // Get the effective phase color for this Nightmare (accounts for curse).
            // This is the same logic that drives the overlay colour, so the prayer
            // we compare against is correct even while the curse shuffles prayers.
            int npcIndex = npc.getIndex();
            NightmareHandler.NightmarePhase effectivePhase = nightmareHandler.getEffectivePhase(npcIndex);
            Color tileColor = effectivePhase.getColor();

            // Get base tile location of the NPC
            LocalPoint basePoint = npc.getLocalLocation();
            if (basePoint == null) {
                continue;
            }

            // Render attack style overlay if enabled (hidden during the spore phase).
            if (config.highlightPhosani() && !sporePhaseActive) {
                renderAttackStyleOverlay(graphics, npc, tileColor);

                // While cursed, draw the center tile purple on top of the attack
                // style overlay so the curse phase is clearly distinguishable.
                if (nightmareHandler.isNightmareCursed(npcIndex)) {
                    renderCurseCenterTile(graphics, npc);
                }
            }

            // Render attack timer if enabled
            if (config.showPhosaniAttackTimers()) {
                renderAttackTimer(graphics, npc, nightmareHandler);
            }
        }

        // Highlight the local player outline red while infected by parasite.
        if (config.highlightPhosaniParasiteOutline() && nightmareVisible && isLocalPlayerParasiteDebuffed()) {
            renderPlayerOutlineFlash(graphics);
        }

        // Render the surge (charge) flight-path danger zone if enabled
        if (config.highlightPhosaniSurge()) {
            renderSurgeDangerZone(graphics, nightmareHandler);
        }

        // Render the shadow phase safe tile if enabled
        if (config.showPhosaniSafeTile()) {
            renderSafeTile(graphics, nightmareHandler);
        }

        return null;
    }

    private void renderPlayerOutlineFlash(Graphics2D graphics) {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return;
        }

        Shape outline = localPlayer.getConvexHull();

        // Fallback to true-tile border when the model hull is unavailable.
        if (outline == null) {
            WorldPoint trueTile = localPlayer.getWorldLocation();
            if (trueTile == null) {
                return;
            }

            LocalPoint localTile = LocalPoint.fromWorld(client, trueTile);
            if (localTile == null) {
                return;
            }

            outline = Perspective.getCanvasTilePoly(client, localTile);
            if (outline == null) {
                return;
            }
        }

        graphics.setColor(new Color(255, 0, 0, 240));
        graphics.setStroke(new BasicStroke(2));
        graphics.draw(outline);
    }

    private boolean isLocalPlayerParasiteDebuffed() {
        return client.getVarbitValue(Varbits.PARASITE) > 0;
    }

    @SuppressWarnings("deprecation") // Using deprecated LocalPoint constructor to match working example
    private void renderAttackStyleOverlay(Graphics2D graphics, NPC npc, Color tileColor) {
        LocalPoint basePoint = npc.getLocalLocation();
        if (basePoint == null) {
            return;
        }

        // Calculate the southwest corner of the 5x5 area
        int swX = basePoint.getX() - (Perspective.LOCAL_TILE_SIZE * (NIGHTMARE_SIZE - 1) / 2);
        int swY = basePoint.getY() - (Perspective.LOCAL_TILE_SIZE * (NIGHTMARE_SIZE - 1) / 2);

        // Calculate the northeast corner of the 5x5 area
        int neX = swX + ((NIGHTMARE_SIZE - 1) * Perspective.LOCAL_TILE_SIZE);
        int neY = swY + ((NIGHTMARE_SIZE - 1) * Perspective.LOCAL_TILE_SIZE);

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

    @SuppressWarnings("deprecation") // Using deprecated LocalPoint constructor to match working example
    private void renderCurseCenterTile(Graphics2D graphics, NPC npc) {
        LocalPoint basePoint = npc.getLocalLocation();
        if (basePoint == null) {
            return;
        }

        Polygon tilePoly = Perspective.getCanvasTilePoly(client, basePoint);
        if (tilePoly == null) {
            return;
        }

        Color purple = new Color(128, 0, 128);

        // Fill the center tile with a semi-transparent purple (same opacity config
        // as the safe tile), then draw a solid 1px purple border to match its style.
        graphics.setColor(new Color(purple.getRed(), purple.getGreen(),
                purple.getBlue(), config.phosaniTransparency()));
        graphics.fill(tilePoly);

        graphics.setColor(purple);
        graphics.setStroke(new BasicStroke(1));
        graphics.draw(tilePoly);
    }

    private void renderAttackTimer(Graphics2D graphics, NPC npc, NightmareHandler nightmareHandler) {
        // Display attack timer at a static position relative to Nightmare's hull
        int npcIndex = npc.getIndex();
        int attackTimer = nightmareHandler.getNightmareAttackTimer(npcIndex);

        // Only render if timer is valid and greater than 0
        if (attackTimer > 0) {
            // Use NPC's base tile location for rock-solid positioning
            // This moves with Nightmare but doesn't wobble with animations
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

    @SuppressWarnings("deprecation") // Using deprecated LocalPoint usage to match working example
    private void renderSurgeDangerZone(Graphics2D graphics, NightmareHandler nightmareHandler) {
        Set<WorldPoint> surgeTiles = nightmareHandler.getSurgeDangerZone();
        if (surgeTiles == null || surgeTiles.isEmpty()) {
            return;
        }

        Color surgeColor = config.phosaniSurgeColor();
        Color fillColor = new Color(surgeColor.getRed(), surgeColor.getGreen(),
                surgeColor.getBlue(), config.phosaniTransparency());
        Color borderColor = new Color(surgeColor.getRed(), surgeColor.getGreen(),
                surgeColor.getBlue(), Math.min(255, config.phosaniTransparency() + 100));

        // First pass: fill every tile in the surge zone.
        for (WorldPoint tile : surgeTiles) {
            LocalPoint localPoint = LocalPoint.fromWorld(client, tile);
            if (localPoint == null) {
                continue;
            }

            Polygon tilePoly = Perspective.getCanvasTilePoly(client, localPoint);
            if (tilePoly == null) {
                continue;
            }

            graphics.setColor(fillColor);
            graphics.fill(tilePoly);
        }

        // Second pass: draw only the outer perimeter by stroking each tile edge
        // whose neighbouring tile is not part of the surge zone. This forms a single
        // larger border around the full shape rather than per-tile boxes.
        graphics.setColor(borderColor);
        graphics.setStroke(new BasicStroke(2));
        for (WorldPoint tile : surgeTiles) {
            LocalPoint localPoint = LocalPoint.fromWorld(client, tile);
            if (localPoint == null) {
                continue;
            }

            Polygon tilePoly = Perspective.getCanvasTilePoly(client, localPoint);
            if (tilePoly == null || tilePoly.npoints < 4) {
                continue;
            }

            // Tile poly corners: 0 = SW, 1 = SE, 2 = NE, 3 = NW.
            // South edge (no tile to the south)
            if (!surgeTiles.contains(tile.dy(-1))) {
                drawTileEdge(graphics, tilePoly, 0, 1);
            }
            // East edge (no tile to the east)
            if (!surgeTiles.contains(tile.dx(1))) {
                drawTileEdge(graphics, tilePoly, 1, 2);
            }
            // North edge (no tile to the north)
            if (!surgeTiles.contains(tile.dy(1))) {
                drawTileEdge(graphics, tilePoly, 2, 3);
            }
            // West edge (no tile to the west)
            if (!surgeTiles.contains(tile.dx(-1))) {
                drawTileEdge(graphics, tilePoly, 3, 0);
            }
        }
    }

    private void drawTileEdge(Graphics2D graphics, Polygon tilePoly, int startIdx, int endIdx) {
        graphics.drawLine(tilePoly.xpoints[startIdx], tilePoly.ypoints[startIdx],
                tilePoly.xpoints[endIdx], tilePoly.ypoints[endIdx]);
    }

    @SuppressWarnings("deprecation") // Using deprecated LocalPoint usage to match working example
    private void renderSafeTile(Graphics2D graphics, NightmareHandler nightmareHandler) {
        Set<WorldPoint> safeTiles = nightmareHandler.getSafeTiles();
        if (safeTiles == null || safeTiles.isEmpty()) {
            return;
        }

        Color safeColor = config.phosaniSafeTileColor();
        Color fillColor = new Color(safeColor.getRed(), safeColor.getGreen(),
                safeColor.getBlue(), config.phosaniTransparency());

        // Highlight every safe tile option so the player can pick where to move.
        for (WorldPoint safeTile : safeTiles) {
            LocalPoint localPoint = LocalPoint.fromWorld(client, safeTile);
            if (localPoint == null) {
                continue;
            }

            Polygon tilePoly = Perspective.getCanvasTilePoly(client, localPoint);
            if (tilePoly == null) {
                continue;
            }

            // Fill the safe tile with a semi-transparent green
            graphics.setColor(fillColor);
            graphics.fill(tilePoly);

            // Draw a solid green border
            graphics.setColor(safeColor);
            graphics.setStroke(new BasicStroke(1));
            graphics.draw(tilePoly);
        }
    }
}
