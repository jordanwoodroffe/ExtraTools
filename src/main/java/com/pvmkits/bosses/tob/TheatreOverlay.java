package com.pvmkits.bosses.tob;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.*;
import java.util.List;

/**
 * Draws Verzik's phase 3 attack-style area overlay and attack countdown, using
 * the same rendering as the Phosani overlay, plus the step-back countdowns for
 * Verzik's phase 2 and Xarpus and the tiles Bloat's falling hands land on.
 * Sotetseg's death ball tick eat countdown is drawn separately over inventory
 * food by {@link TheatreTickEatOverlay}.
 */
public class TheatreOverlay extends Overlay {

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;

    // Fallback footprint if Verzik's world area is unavailable for a frame.
    private static final int DEFAULT_VERZIK_SIZE = 5;

    @Inject
    public TheatreOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.HIGHEST);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        TheatreHandler handler = plugin.getTheatreHandler();
        if (handler == null) {
            return null;
        }

        renderVerzik(graphics, handler);
        renderVerzikP2(graphics, handler);
        renderXarpus(graphics, handler);
        renderBloatHands(graphics, handler);

        return null;
    }

    private void renderVerzik(Graphics2D graphics, TheatreHandler handler) {
        if (!config.tobVerzikP3StyleOverlay() && !config.tobShowVerzikTimer()) {
            return;
        }

        if (!handler.isVerzikP3()) {
            return;
        }

        NPC verzik = handler.getVerzik();
        if (verzik == null) {
            return;
        }

        if (config.tobVerzikP3StyleOverlay()) {
            renderAttackStyleOverlay(graphics, verzik, styleColor(handler.getVerzikStyle()));
        }

        if (config.tobShowVerzikTimer()) {
            renderAttackTimer(graphics, verzik, handler.getAttackTimer());
        }
    }

    // Verzik phase 2 and Xarpus only get an attack countdown - both are fixed 4-tick
    // attackers, drawn the same way as her P3 timer at the boss's feet.
    private void renderVerzikP2(Graphics2D graphics, TheatreHandler handler) {
        if (!config.tobShowVerzikP2Timer()) {
            return;
        }

        NPC verzik = handler.getVerzikP2();
        if (verzik == null) {
            return;
        }

        renderAttackTimer(graphics, verzik, handler.getVerzikP2AttackTimer());
    }

    private void renderXarpus(Graphics2D graphics, TheatreHandler handler) {
        if (!config.tobShowXarpusTimer()) {
            return;
        }

        NPC xarpus = handler.getXarpus();
        if (xarpus == null) {
            return;
        }

        renderAttackTimer(graphics, xarpus, handler.getXarpusAttackTimer());
    }

    // Bloat's falling hands, one highlighted tile per chunk of flesh in the air.
    // Each is a single tile, so unlike Verzik's footprint there is no border to
    // build - the tile polygon is filled and outlined as it comes.
    private void renderBloatHands(Graphics2D graphics, TheatreHandler handler) {
        if (!config.tobBloatHandHighlight()) {
            return;
        }

        List<LocalPoint> handTiles = handler.getBloatHandTiles();
        if (handTiles.isEmpty()) {
            return;
        }

        Color color = config.tobBloatHandColor();
        Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), config.tobTransparency());

        for (LocalPoint tile : handTiles) {
            Polygon poly = Perspective.getCanvasTilePoly(client, tile);
            if (poly == null) {
                continue;
            }
            graphics.setColor(fill);
            graphics.fill(poly);
            graphics.setColor(color);
            graphics.draw(poly);
        }
    }

    // Unknown falls back to the style's own grey rather than drawing nothing, so
    // the overlay is still visible while waiting for her first range/magic attack.
    private Color styleColor(TheatreHandler.VerzikStyle style) {
        switch (style) {
            case RANGE:
                return config.tobVerzikRangeColor();
            case MAGE:
                return config.tobVerzikMageColor();
            case UNKNOWN:
            default:
                return style.getColor();
        }
    }

    @SuppressWarnings("deprecation") // Deprecated LocalPoint constructor, as in PhosaniOverlay
    private void renderAttackStyleOverlay(Graphics2D graphics, NPC npc, Color tileColor) {
        LocalPoint basePoint = npc.getLocalLocation();
        if (basePoint == null) {
            return;
        }

        // Verzik's footprint differs between raid modes and phases, so take it from
        // her world area rather than assuming a fixed size.
        WorldArea area = npc.getWorldArea();
        int size = area != null ? area.getWidth() : DEFAULT_VERZIK_SIZE;

        // Southwest corner of her footprint
        int swX = basePoint.getX() - (Perspective.LOCAL_TILE_SIZE * (size - 1) / 2);
        int swY = basePoint.getY() - (Perspective.LOCAL_TILE_SIZE * (size - 1) / 2);

        // Northeast corner of her footprint
        int neX = swX + ((size - 1) * Perspective.LOCAL_TILE_SIZE);
        int neY = swY + ((size - 1) * Perspective.LOCAL_TILE_SIZE);

        Polygon swPoly = Perspective.getCanvasTilePoly(client, new LocalPoint(swX, swY));
        Polygon sePoly = Perspective.getCanvasTilePoly(client, new LocalPoint(neX, swY));
        Polygon nePoly = Perspective.getCanvasTilePoly(client, new LocalPoint(neX, neY));
        Polygon nwPoly = Perspective.getCanvasTilePoly(client, new LocalPoint(swX, neY));

        if (swPoly == null || sePoly == null || nePoly == null || nwPoly == null) {
            return;
        }

        // Consolidate the corner tiles into a single border around the footprint
        Polygon borderPoly = new Polygon();
        addPointsToPolygon(borderPoly, swPoly, 0, 1); // South edge (SW to SE)
        addPointsToPolygon(borderPoly, sePoly, 1, 2); // East edge (SE to NE)
        addPointsToPolygon(borderPoly, nePoly, 2, 3); // North edge (NE to NW)
        addPointsToPolygon(borderPoly, nwPoly, 3, 0); // West edge (NW to SW)

        // Fill the area with a semi-transparent colour, then outline it solid
        graphics.setColor(new Color(tileColor.getRed(), tileColor.getGreen(),
                tileColor.getBlue(), config.tobTransparency()));
        graphics.fill(borderPoly);

        graphics.setColor(tileColor);
        graphics.draw(borderPoly);
    }

    private void renderAttackTimer(Graphics2D graphics, NPC npc, int attackTimer) {
        if (attackTimer <= 0) {
            return;
        }

        // Anchor to the base tile so the timer moves with Verzik without wobbling
        // along with her animations.
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
        // Position the timer below the base tile centre (at her feet)
        int stableY = tileRect.y + tileRect.height;

        String timerText = String.valueOf(attackTimer);
        int fontSize = Math.max(20, config.tobTimerTextSize() + 8);
        graphics.setFont(new Font("Arial", Font.BOLD, fontSize));

        FontMetrics metrics = graphics.getFontMetrics();
        int textWidth = metrics.stringWidth(timerText);
        int textHeight = metrics.getHeight();
        int textX = centerX - (textWidth / 2);
        int textY = stableY + (textHeight / 4);

        graphics.setColor(attackTimer == 1 ? config.tobAttackTimerOneTickColor() : config.tobAttackTimerColor());
        graphics.drawString(timerText, textX, textY);
    }

    // Helper method to add points from one polygon to another with safety checks
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
}
