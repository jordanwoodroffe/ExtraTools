package com.pvmkits.bosses.cox;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
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

/**
 * Highlights the 5x5 hit area of each pending Lizardman Shaman acid spit so the
 * player can step out of the blast before it lands.
 */
public class ShamanOverlay extends Overlay {

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;
    private static final int SPIT_BLAST_SIZE = 5;
    private static final BasicStroke BORDER_STROKE = new BasicStroke(1);

    @Inject
    public ShamanOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.HIGHEST);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showShamanSpitBlast()) {
            return null;
        }

        ShamanHandler handler = plugin.getShamanHandler();
        if (handler == null) {
            return null;
        }

        Color color = config.shamanSpitBlastColor();
        int alpha = config.olmTransparency();

        for (WorldPoint tile : handler.getSpitBlastTiles()) {
            if (tile == null) {
                continue;
            }

            LocalPoint center = LocalPoint.fromWorld(client, tile);
            if (center == null) {
                continue;
            }

            Polygon border = buildOuterBorderPoly(center, SPIT_BLAST_SIZE);
            if (border == null) {
                continue;
            }

            graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
            graphics.fill(border);
            graphics.setColor(color);
            graphics.setStroke(BORDER_STROKE);
            graphics.draw(border);
        }

        return null;
    }

    @SuppressWarnings("deprecation") // Deprecated LocalPoint constructor matches the other boss overlays.
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
