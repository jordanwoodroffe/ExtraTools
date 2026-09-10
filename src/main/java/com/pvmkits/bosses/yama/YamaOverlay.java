package com.pvmkits.bosses.yama;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.*;

import javax.inject.Inject;
import java.awt.*;
import java.util.Collection;
import java.util.Set;

public class YamaOverlay extends Overlay {

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;
    private static final int YAMA_SIZE = 5; // Yama is 5x5 tiles
    private static final int YAMA_ID = 14176;

    // Shared border stroke so every Yama highlight border matches the width used
    // by the Phosani overlay's boss/tile borders (1px).
    private static final BasicStroke BORDER_STROKE = new BasicStroke(1);

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
        if (!config.highlightYama() && !config.showAttackTimers() && !config.highlightBoulders()
                && !config.showYamaFireballSafeTiles()) {
            return null;
        }

        if (config.highlightBoulders()) {
            renderGlyphs(graphics);
        }

        if (config.showYamaFireballSafeTiles()) {
            renderFireballSafeTiles(graphics);
        }

        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null || npc.getId() != YAMA_ID) {
                continue;
            }

            YamaHandler.YamaPhase phase = plugin.getYamaHandler().getYamaPhase(npc.getIndex());
            Color tileColor = phase.getColor();

            if (npc.getLocalLocation() == null) {
                continue;
            }

            if (config.highlightYama()) {
                renderAttackStyleOverlay(graphics, npc, tileColor);
            }

            if (config.showAttackTimers()) {
                renderAttackTimer(graphics, npc);
            }
        }

        return null;
    }

    private void renderGlyphs(Graphics2D graphics) {
        YamaHandler handler = plugin.getYamaHandler();
        Collection<GameObject> glyphs = handler.getActiveGlyphObjects();
        if (glyphs == null || glyphs.isEmpty()) {
            return;
        }

        // Reuse the fire/shadow special colours since the glyph lights up during
        // the matching special. Draw the border opaque so the special colour's own
        // low alpha doesn't wash it out, and fill at the configured transparency.
        YamaHandler.GlyphType type = handler.getActiveGlyphType();
        Color base = (type == YamaHandler.GlyphType.SHADOW)
                ? config.shadowSpecialColor()
                : config.fireSpecialColor();
        Color outline = new Color(base.getRed(), base.getGreen(), base.getBlue());
        Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), config.yamaTransparency());

        for (GameObject glyph : glyphs) {
            if (glyph == null) {
                continue;
            }

            // Highlight just the glyph's centre tile rather than its full 3x3
            // footprint. getLocalLocation() reports the object's centre tile.
            Polygon tile = Perspective.getCanvasTilePoly(client, glyph.getLocalLocation());
            if (tile == null) {
                continue;
            }

            graphics.setColor(fill);
            graphics.fill(tile);
            graphics.setColor(outline);
            graphics.setStroke(BORDER_STROKE);
            graphics.draw(tile);
        }
    }

    /**
     * Highlights the two safe tiles for the active 3-fireball line special attack.
     * The handler solves these from the fireballs' geometry, so this just paints
     * whatever tiles it reports (empty when no line is active).
     */
    private void renderFireballSafeTiles(Graphics2D graphics) {
        Set<WorldPoint> safeTiles = plugin.getYamaHandler().getFireballSafeTiles();
        if (safeTiles == null || safeTiles.isEmpty()) {
            return;
        }

        Color base = config.yamaFireballSafeTileColor();
        Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), config.yamaTransparency());

        for (WorldPoint tile : safeTiles) {
            if (tile == null) {
                continue;
            }

            LocalPoint lp = LocalPoint.fromWorld(client, tile);
            if (lp == null) {
                continue;
            }

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) {
                continue;
            }

            graphics.setColor(fill);
            graphics.fill(poly);
            graphics.setColor(base);
            graphics.setStroke(BORDER_STROKE);
            graphics.draw(poly);
        }
    }

    private void renderAttackStyleOverlay(Graphics2D graphics, NPC npc, Color tileColor) {
        LocalPoint basePoint = npc.getLocalLocation();
        if (basePoint == null) {
            return;
        }

        Polygon borderPoly = buildOuterBorderPoly(basePoint, YAMA_SIZE);
        if (borderPoly == null) {
            return;
        }

        graphics.setColor(new Color(tileColor.getRed(), tileColor.getGreen(),
                tileColor.getBlue(), config.yamaTransparency()));
        graphics.fill(borderPoly);
        graphics.setColor(tileColor);
        graphics.setStroke(BORDER_STROKE);
        graphics.draw(borderPoly);
    }

    private void renderAttackTimer(Graphics2D graphics, NPC npc) {
        int npcIndex = npc.getIndex();
        int attackTimer = plugin.getYamaHandler().getYamaAttackTimer(npcIndex);

        if (attackTimer <= 0) {
            return;
        }

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
        int fontSize = Math.max(20, config.timerTextSize() + 8);
        graphics.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, fontSize));

        java.awt.FontMetrics metrics = graphics.getFontMetrics();
        int textX = centerX - (metrics.stringWidth(timerText) / 2);
        int textY = stableY + (metrics.getHeight() / 4);

        graphics.setColor(config.attackTimerColor());
        graphics.drawString(timerText, textX, textY);
    }

    @SuppressWarnings("deprecation")
    private Polygon buildOuterBorderPoly(LocalPoint center, int size) {
        int half = (size - 1) / 2;
        int swX = center.getX() - (Perspective.LOCAL_TILE_SIZE * half);
        int swY = center.getY() - (Perspective.LOCAL_TILE_SIZE * half);
        int neX = center.getX() + (Perspective.LOCAL_TILE_SIZE * half);
        int neY = center.getY() + (Perspective.LOCAL_TILE_SIZE * half);

        Polygon swPoly = Perspective.getCanvasTilePoly(client, new LocalPoint(swX, swY));
        Polygon sePoly = Perspective.getCanvasTilePoly(client, new LocalPoint(neX, swY));
        Polygon nePoly = Perspective.getCanvasTilePoly(client, new LocalPoint(neX, neY));
        Polygon nwPoly = Perspective.getCanvasTilePoly(client, new LocalPoint(swX, neY));

        if (swPoly == null || sePoly == null || nePoly == null || nwPoly == null) {
            return null;
        }

        Polygon border = new Polygon();
        addPointsToPolygon(border, swPoly, 0, 1);
        addPointsToPolygon(border, sePoly, 1, 2);
        addPointsToPolygon(border, nePoly, 2, 3);
        addPointsToPolygon(border, nwPoly, 3, 0);
        return border;
    }

    private void addPointsToPolygon(Polygon target, Polygon source, int startIdx, int endIdx) {
        if (startIdx >= source.npoints || endIdx >= source.npoints) {
            return;
        }
        target.addPoint(source.xpoints[startIdx], source.ypoints[startIdx]);
        target.addPoint(source.xpoints[endIdx], source.ypoints[endIdx]);
    }
}
