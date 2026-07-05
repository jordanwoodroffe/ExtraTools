package com.pvmkits.bosses.maggotking;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.util.Set;

public class MaggotKingOverlay extends Overlay {

    // Maggot larva NPC id (user-confirmed). Highlighted like Phosani's sleepwalkers.
    private static final Set<Integer> LARVA_NPC_IDS = Set.of(15743);

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;

    @Inject
    public MaggotKingOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.HIGHEST);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        MaggotKingHandler handler = plugin.getMaggotKingHandler();
        if (handler == null) {
            return null;
        }

        // Always render boss overlays to show screech attacks
        renderBossOverlays(graphics, handler);

        if (config.showMaggotKingScreechWarning()
                && handler.isAnyBossScreeching()
                && handler.isOverheadPrayerEnabled()) {
            renderPlayerScreechOutline(graphics);
        }

        if (config.highlightMaggotKingLarvae()) {
            renderLarvaeHighlights(graphics);
        }

        if (config.showMaggotKingSafeTile()) {
            renderSafeTiles(graphics, handler);
        }

        return null;
    }

    private void renderBossOverlays(Graphics2D graphics, MaggotKingHandler handler) {
        if (!config.showMaggotKingAttackStyleOverlay()) {
            return;
        }

        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (!handler.isKnownBossNpc(npc)) {
                continue;
            }

            Color styleColor;

            // Show screech color during screech warning window
            if (handler.isScreechWarningActive()) {
                styleColor = config.maggotKingScreechStyleColor();
            } else {
                // Show attack style color (predicted prayer style)
                styleColor = getStyleColor(handler, npc.getIndex());
            }

            if (styleColor != null) {
                renderBossArea(graphics, npc, styleColor);
            }
        }
    }

    /**
     * Draws an outline around the local player while the boss is screeching and the
     * player still has an overhead prayer on. Clears the instant all overheads are
     * switched off (condition met) or the screech ends. Mirrors the Phosani
     * parasite outline check.
     */
    private void renderPlayerScreechOutline(Graphics2D graphics) {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return;
        }

        Shape outline = localPlayer.getConvexHull();
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

        Color color = config.maggotKingScreechColor();
        graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 240));
        graphics.setStroke(new BasicStroke(2));
        graphics.draw(outline);
    }

    /**
     * Highlights the maggot larvae (NPC id 15743) with a hull + tile cue in soft
     * red, mirroring the way Phosani's sleepwalkers are highlighted so they stand
     * out in the crowd.
     */
    private void renderLarvaeHighlights(Graphics2D graphics) {
        Color larvaColor = new Color(255, 100, 100, config.maggotKingTransparency());

        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null || !LARVA_NPC_IDS.contains(npc.getId())) {
                continue;
            }

            renderNpcHighlight(graphics, npc, larvaColor);
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

        // Highlight the tile under the NPC.
        Polygon tilePoly = Perspective.getCanvasTilePoly(client, npcLocation);
        if (tilePoly != null) {
            graphics.setColor(highlightColor);
            graphics.fill(tilePoly);

            graphics.setColor(new Color(highlightColor.getRed(), highlightColor.getGreen(),
                    highlightColor.getBlue(), Math.min(255, highlightColor.getAlpha() + 100)));
            graphics.setStroke(new BasicStroke(2));
            graphics.draw(tilePoly);
        }
    }

    private void renderSafeTiles(Graphics2D graphics, MaggotKingHandler handler) {
        Color safeColor = config.maggotKingSafeTileColor();
        Color fill = new Color(safeColor.getRed(), safeColor.getGreen(), safeColor.getBlue(), config.maggotKingTransparency());

        for (WorldPoint tile : handler.getSafeTiles()) {
            LocalPoint local = LocalPoint.fromWorld(client, tile);
            if (local == null) {
                continue;
            }

            Polygon poly = Perspective.getCanvasTilePoly(client, local);
            if (poly == null) {
                continue;
            }

            graphics.setColor(fill);
            graphics.fill(poly);

            graphics.setColor(safeColor);
            graphics.setStroke(new BasicStroke(1));
            graphics.draw(poly);
        }
    }

    /**
     * Draws the boss's whole footprint as a single big tile: the outer edges of
     * the four corner tiles are stitched into one border polygon so there are no
     * inner grid lines (mirrors the Phosani attack-style overlay).
     */
    private void renderBossArea(Graphics2D graphics, NPC npc, Color color) {
        WorldArea area = npc == null ? null : npc.getWorldArea();
        if (area == null) {
            return;
        }

        int plane = area.getPlane();
        int minX = area.getX();
        int minY = area.getY();
        int maxX = minX + area.getWidth() - 1;
        int maxY = minY + area.getHeight() - 1;

        Polygon swPoly = tilePolygon(new WorldPoint(minX, minY, plane));
        Polygon sePoly = tilePolygon(new WorldPoint(maxX, minY, plane));
        Polygon nePoly = tilePolygon(new WorldPoint(maxX, maxY, plane));
        Polygon nwPoly = tilePolygon(new WorldPoint(minX, maxY, plane));

        if (swPoly == null || sePoly == null || nePoly == null || nwPoly == null) {
            return;
        }

        Polygon border = new Polygon();
        addPointsToPolygon(border, swPoly, 0, 1);
        addPointsToPolygon(border, sePoly, 1, 2);
        addPointsToPolygon(border, nePoly, 2, 3);
        addPointsToPolygon(border, nwPoly, 3, 0);

        graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), config.maggotKingTransparency()));
        graphics.fill(border);

        graphics.setColor(color);
        graphics.setStroke(new BasicStroke(1));
        graphics.draw(border);
    }

    private Polygon tilePolygon(WorldPoint worldPoint) {
        LocalPoint local = LocalPoint.fromWorld(client, worldPoint);
        if (local == null) {
            return null;
        }
        return Perspective.getCanvasTilePoly(client, local);
    }

    private void addPointsToPolygon(Polygon targetPoly, Polygon sourcePoly, int startIdx, int endIdx) {
        if (sourcePoly == null || sourcePoly.npoints == 0) {
            return;
        }

        int sourcePoints = sourcePoly.npoints;
        startIdx = Math.max(0, Math.min(startIdx, sourcePoints - 1));
        endIdx = Math.max(0, Math.min(endIdx, sourcePoints - 1));

        targetPoly.addPoint(sourcePoly.xpoints[startIdx], sourcePoly.ypoints[startIdx]);
        targetPoly.addPoint(sourcePoly.xpoints[endIdx], sourcePoly.ypoints[endIdx]);
    }

    /**
     * Gets the overlay color for the current attack style.
     * Returns null for unknown or special styles so no overlay is rendered.
     */
    private Color getStyleColor(MaggotKingHandler handler, int npcIndex) {
        MaggotKingHandler.AttackStyle style = handler.getDisplayAttackStyle(npcIndex);
        if (style == null) {
            return null;
        }

        switch (style) {
            case RANGE:
                return config.maggotKingRangeStyleColor();
            case MAGE:
                return config.maggotKingMageStyleColor();
            default:
                return null;
        }
    }
}
