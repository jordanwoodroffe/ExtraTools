package com.pvmkits.bosses.phosani;

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

    // Parasite NPC IDs for highlighting (9452/9453 = Nightmare, 9468/9469 = Phosani's)
    private static final Set<Integer> PARASITE_IDS = Set.of(9452, 9453, 9468, 9469);

    // Totem NPC IDs. Each corner totem has three consecutive ids: idle (dormant,
    // present the whole fight), charging (vulnerable - needs charging during the
    // totem phase) and full (charged). We only highlight charging/full so the
    // overlay is naturally limited to the totem phase. Confirmed in-game:
    // SW=9434/9435/9436, SE=9437/9438/9439, NW=9440/9441/9442, NE=9443/9444/9445.
    private static final Set<Integer> TOTEM_NEEDS_CHARGE_IDS = Set.of(9435, 9438, 9441, 9444);
    private static final Set<Integer> TOTEM_FULL_IDS = Set.of(9436, 9439, 9442, 9445);

    @Inject
    public PhosaniOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.HIGHEST);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        // Only render if any Phosani features are enabled
        if (!config.highlightPhosani() && !config.showPhosaniAttackTimers() && !config.highlightSporeDangerZones()
                && !config.highlightSleepwalkers() && !config.showPhosaniSafeTile()
                && !config.highlightPhosaniTotems() && !config.highlightPhosaniSurge()
                && !config.highlightPhosaniParasiteOutline()) {
            return null;
        }

        // Get the Phosani handler from the plugin
        PhosaniHandler phosaniHandler = plugin.getPhosaniHandler();
        if (phosaniHandler == null) {
            return null;
        }

        // Render existing Phosani highlights
        boolean phosaniVisible = false;
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null || !PHOSANI_IDS.contains(npc.getId())) {
                continue;
            }
            phosaniVisible = true;

            // Get the effective phase color for this Phosani (accounts for curse).
            // This is the same logic that drives the overlay colour, so the prayer
            // we compare against is correct even while the curse shuffles prayers.
            int npcIndex = npc.getIndex();
            PhosaniHandler.PhosaniPhase effectivePhase = phosaniHandler.getEffectivePhase(npcIndex);
            Color tileColor = effectivePhase.getColor();

            // Get base tile location of the NPC
            LocalPoint basePoint = npc.getLocalLocation();
            if (basePoint == null) {
                continue;
            }

            // Render attack style overlay if enabled
            if (config.highlightPhosani()) {
                renderAttackStyleOverlay(graphics, npc, tileColor);

                // While cursed, draw the center tile purple on top of the attack
                // style overlay so the curse phase is clearly distinguishable.
                if (phosaniHandler.isPhosaniCursed(npcIndex)) {
                    renderCurseCenterTile(graphics, npc);
                }
            }

            // Render attack timer if enabled
            if (config.showPhosaniAttackTimers()) {
                renderAttackTimer(graphics, npc, phosaniHandler);
            }
        }

        // Highlight the local player outline red while infected by parasite.
        if (config.highlightPhosaniParasiteOutline() && phosaniVisible && isLocalPlayerParasiteDebuffed()) {
            renderPlayerOutlineFlash(graphics);
        }

        // Render spore danger zones if enabled
        if (config.highlightSporeDangerZones()) {
            renderSporeDangerZones(graphics, phosaniHandler);
        }

        // Render the surge (charge) flight-path danger zone if enabled
        if (config.highlightPhosaniSurge()) {
            renderSurgeDangerZone(graphics, phosaniHandler);
        }

        // Render sleepwalker highlighting if enabled
        if (config.highlightSleepwalkers()) {
            renderSleepwalkerHighlights(graphics);
        }

        // Render the shadow phase safe tile if enabled
        if (config.showPhosaniSafeTile()) {
            renderSafeTile(graphics, phosaniHandler);
        }

        // Render totem highlights if enabled
        if (config.highlightPhosaniTotems()) {
            renderTotemHighlights(graphics);
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

        int transparency = config.phosaniTransparency();

        // Fill the 3x3 area with soft red semi-transparent color
        graphics.setColor(new Color(255, 0, 0, transparency));
        graphics.fill(borderPoly);

        // Draw only the border with soft red color (slightly more opaque for readability)
        graphics.setColor(new Color(255, 0, 0, Math.min(255, transparency + 100)));
        graphics.setStroke(new BasicStroke(1));
        graphics.draw(borderPoly);
    }

    @SuppressWarnings("deprecation") // Using deprecated LocalPoint usage to match working example
    private void renderSurgeDangerZone(Graphics2D graphics, PhosaniHandler phosaniHandler) {
        Set<WorldPoint> surgeTiles = phosaniHandler.getSurgeDangerZone();
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
    private void renderSafeTile(Graphics2D graphics, PhosaniHandler phosaniHandler) {
        Set<WorldPoint> safeTiles = phosaniHandler.getSafeTiles();
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

    private void renderSleepwalkerHighlights(Graphics2D graphics) {
        // Soft red color for sleepwalkers and husks
        Color softRed = new Color(255, 100, 100, config.phosaniTransparency());
        Color lighterRed = new Color(255, 140, 140, config.phosaniTransparency());
        boolean parasiteFlashTick = (client.getTickCount() & 1) == 0;

        // Find and highlight all sleepwalkers and husks
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null) {
                continue;
            }

            // Check if this NPC is a sleepwalker or husk
            boolean isSleepwalker = SLEEPWALKER_IDS.contains(npc.getId());
            boolean isHusk = HUSK_IDS.contains(npc.getId());
            boolean isParasite = PARASITE_IDS.contains(npc.getId());

            if (isSleepwalker || isHusk) {
                renderNpcHighlight(graphics, npc, softRed);
            } else if (isParasite) {
                renderNpcHighlight(graphics, npc, parasiteFlashTick ? lighterRed : softRed);
            }
        }
    }

    private void renderTotemHighlights(Graphics2D graphics) {
        int alpha = config.phosaniTransparency();
        Color emptyBase = config.phosaniTotemEmptyColor();
        Color fullBase = config.phosaniTotemFullColor();
        Color emptyColor = new Color(emptyBase.getRed(), emptyBase.getGreen(), emptyBase.getBlue(), alpha);
        Color fullColor = new Color(fullBase.getRed(), fullBase.getGreen(), fullBase.getBlue(), alpha);

        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null) {
                continue;
            }

            int id = npc.getId();
            if (TOTEM_NEEDS_CHARGE_IDS.contains(id)) {
                renderNpcHighlight(graphics, npc, emptyColor);
            } else if (TOTEM_FULL_IDS.contains(id)) {
                renderNpcHighlight(graphics, npc, fullColor);
            }
        }
    }

    private void renderNpcHighlight(Graphics2D graphics, NPC npc, Color highlightColor) {
        LocalPoint npcLocation = npc.getLocalLocation();
        if (npcLocation == null) {
            return;
        }

        // Draw the NPC hull as an additional cue so targets remain visible in crowds.
        Shape hull = npc.getConvexHull();
        if (hull != null) {
            int hullAlpha = Math.max(20, highlightColor.getAlpha() / 2);
            graphics.setColor(new Color(highlightColor.getRed(), highlightColor.getGreen(),
                    highlightColor.getBlue(), hullAlpha));
            graphics.fill(hull);

            graphics.setColor(new Color(highlightColor.getRed(), highlightColor.getGreen(),
                    highlightColor.getBlue(), Math.min(255, highlightColor.getAlpha() + 100)));
            graphics.setStroke(new BasicStroke(1));
            graphics.draw(hull);
        }

        // Highlight the tile
        Polygon tilePoly = Perspective.getCanvasTilePoly(client, npcLocation);
        if (tilePoly != null) {
            // Fill tile with semi-transparent color
            graphics.setColor(highlightColor);
            graphics.fill(tilePoly);

            // Draw tile border with solid color
            graphics.setColor(new Color(highlightColor.getRed(), highlightColor.getGreen(),
                    highlightColor.getBlue(), Math.min(255, highlightColor.getAlpha() + 100)));
            graphics.setStroke(new BasicStroke(2));
            graphics.draw(tilePoly);
        }

    }
}
