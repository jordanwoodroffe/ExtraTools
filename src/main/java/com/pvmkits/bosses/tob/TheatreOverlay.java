package com.pvmkits.bosses.tob;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;

/**
 * Overlay for the Theatre of Blood. Draws attack timers, the Bloat sleep/wake
 * state, the role-based Nylocas highlights and the Verzik P3 attack-style colour.
 */
public class TheatreOverlay extends Overlay {

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;
    private static final int VERZIK_SIZE = 5; // Verzik occupies a 5x5 tile area

    @Inject
    public TheatreOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        TheatreHandler handler = plugin.getTheatreHandler();
        if (handler == null || handler.getCurrentRoom() == TheatreHandler.Room.NONE) {
            return null;
        }

        switch (handler.getCurrentRoom()) {
            case SOTETSEG:
                if (config.tobShowSotetsegTimer()) {
                    renderBossTimer(graphics, handler.getBoss(), handler.getBossAttackTimer());
                }
                break;
            case BLOAT:
                if (config.tobShowBloatSafeTiles()) {
                    renderBloatSafeTiles(graphics, handler);
                }
                if (config.tobShowBloatTimer()) {
                    renderBloat(graphics, handler);
                }
                break;
            case NYLOCAS:
                renderNyloHighlights(graphics, handler);
                break;
            case VERZIK:
                renderVerzik(graphics, handler);
                break;
            case XARPUS:
            default:
                break;
        }

        return null;
    }

    private void renderVerzik(Graphics2D graphics, TheatreHandler handler) {
        NPC boss = handler.getBoss();
        if (boss == null) {
            return;
        }
        if (config.tobVerzikP3StyleOverlay() && handler.getVerzikPhase() == 3) {
            Color color = verzikStyleColor(handler.getVerzikStyle());
            if (color != null) {
                renderAreaOverlay(graphics, boss, color);
            }
        }
        if (config.tobShowVerzikTimer()) {
            renderBossTimer(graphics, boss, handler.getBossAttackTimer());
        }
    }

    private Color verzikStyleColor(TheatreHandler.VerzikStyle style) {
        switch (style) {
            case MELEE:
                return config.tobVerzikMeleeColor();
            case RANGE:
                return config.tobVerzikRangeColor();
            case MAGE:
                return config.tobVerzikMageColor();
            default:
                return null;
        }
    }

    private void renderNyloHighlights(Graphics2D graphics, TheatreHandler handler) {
        TheatreHandler.NyloRole role = handler.getNyloRole();
        if (role == TheatreHandler.NyloRole.OFF) {
            return;
        }

        Color color;
        java.util.Set<Integer> targets;
        switch (role) {
            case MELEE:
                color = config.tobNyloMeleeColor();
                targets = TheatreHandler.NYLO_MELEE_IDS;
                break;
            case RANGE:
                color = config.tobNyloRangeColor();
                targets = TheatreHandler.NYLO_RANGE_IDS;
                break;
            case MAGE:
                color = config.tobNyloMageColor();
                targets = TheatreHandler.NYLO_MAGE_IDS;
                break;
            default:
                return;
        }

        Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), config.tobTransparency());
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null || !targets.contains(npc.getId())) {
                continue;
            }
            Shape hull = npc.getConvexHull();
            if (hull == null) {
                LocalPoint lp = npc.getLocalLocation();
                if (lp == null) {
                    continue;
                }
                hull = Perspective.getCanvasTilePoly(client, lp);
            }
            if (hull == null) {
                continue;
            }
            graphics.setColor(fill);
            graphics.fill(hull);
            graphics.setColor(color);
            graphics.draw(hull);
        }
    }

    private void renderBloatSafeTiles(Graphics2D graphics, TheatreHandler handler) {
        java.util.List<net.runelite.api.coords.WorldPoint> safe = handler.getBloatSafeTiles();
        Color safeColor = config.tobBloatSafeColor();
        Color safeFill = new Color(safeColor.getRed(), safeColor.getGreen(), safeColor.getBlue(),
                config.tobTransparency());
        for (net.runelite.api.coords.WorldPoint wp : safe) {
            drawTile(graphics, wp, safeColor, safeFill);
        }
    }

    private void drawTile(Graphics2D graphics, net.runelite.api.coords.WorldPoint wp, Color border, Color fill) {
        LocalPoint lp = LocalPoint.fromWorld(client, wp);
        if (lp == null) {
            return;
        }
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) {
            return;
        }
        graphics.setColor(fill);
        graphics.fill(poly);
        graphics.setColor(border);
        graphics.draw(poly);
    }

    private void renderBloat(Graphics2D graphics, TheatreHandler handler) {
        NPC bloat = handler.getBoss();
        // Bloat is the only NPC in the room; fall back to scanning for him.
        if (bloat == null) {
            for (NPC npc : client.getTopLevelWorldView().npcs()) {
                if (npc != null && npc.getId() == 8359) {
                    bloat = npc;
                    break;
                }
            }
        }
        if (bloat == null) {
            return;
        }
        int timer = handler.getBloatStateTimer();
        if (timer <= 0) {
            return;
        }
        String label = handler.isBloatAsleep() ? "Wake " + timer : "Sleep " + timer;
        drawCenteredText(graphics, bloat, label, handler.isBloatAsleep()
                ? config.tobWarningColor()
                : config.tobNormalTimerColor());
    }

    private void renderBossTimer(Graphics2D graphics, NPC boss, int timer) {
        if (boss == null || timer <= 0) {
            return;
        }
        Color color = timer == 1 ? config.tobWarningColor() : config.tobNormalTimerColor();
        drawCenteredText(graphics, boss, String.valueOf(timer), color);
    }

    private void drawCenteredText(Graphics2D graphics, NPC npc, String text, Color color) {
        LocalPoint center = npc.getLocalLocation();
        if (center == null) {
            return;
        }
        net.runelite.api.Point textPoint = Perspective.localToCanvas(client, center, 0);
        if (textPoint == null) {
            return;
        }
        Font font = new Font("Arial", Font.BOLD, config.tobTimerTextSize());
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        int textWidth = metrics.stringWidth(text);
        int textHeight = metrics.getHeight();
        int textX = textPoint.getX() - (textWidth / 2);
        int textY = textPoint.getY() + (textHeight / 4);

        graphics.setColor(Color.BLACK);
        graphics.drawString(text, textX - 2, textY - 2);
        graphics.drawString(text, textX + 2, textY - 2);
        graphics.drawString(text, textX - 2, textY + 2);
        graphics.drawString(text, textX + 2, textY + 2);
        graphics.setColor(color);
        graphics.drawString(text, textX, textY);
    }

    @SuppressWarnings("deprecation")
    private void renderAreaOverlay(Graphics2D graphics, NPC npc, Color tileColor) {
        LocalPoint basePoint = npc.getLocalLocation();
        if (basePoint == null) {
            return;
        }
        int swX = basePoint.getX() - (Perspective.LOCAL_TILE_SIZE * (VERZIK_SIZE - 1) / 2);
        int swY = basePoint.getY() - (Perspective.LOCAL_TILE_SIZE * (VERZIK_SIZE - 1) / 2);
        int neX = swX + ((VERZIK_SIZE - 1) * Perspective.LOCAL_TILE_SIZE);
        int neY = swY + ((VERZIK_SIZE - 1) * Perspective.LOCAL_TILE_SIZE);

        Polygon swPoly = Perspective.getCanvasTilePoly(client, new LocalPoint(swX, swY));
        Polygon sePoly = Perspective.getCanvasTilePoly(client, new LocalPoint(neX, swY));
        Polygon nePoly = Perspective.getCanvasTilePoly(client, new LocalPoint(neX, neY));
        Polygon nwPoly = Perspective.getCanvasTilePoly(client, new LocalPoint(swX, neY));
        if (swPoly == null || sePoly == null || nePoly == null || nwPoly == null) {
            return;
        }

        Polygon borderPoly = new Polygon();
        addPointsToPolygon(borderPoly, swPoly, 0, 1);
        addPointsToPolygon(borderPoly, sePoly, 1, 2);
        addPointsToPolygon(borderPoly, nePoly, 2, 3);
        addPointsToPolygon(borderPoly, nwPoly, 3, 0);

        graphics.setColor(new Color(tileColor.getRed(), tileColor.getGreen(), tileColor.getBlue(),
                config.tobTransparency()));
        graphics.fill(borderPoly);
        graphics.setColor(tileColor);
        graphics.draw(borderPoly);
    }

    private void addPointsToPolygon(Polygon targetPoly, Polygon sourcePoly, int startIdx, int endIdx) {
        int sourcePoints = sourcePoly.npoints;
        if (startIdx >= sourcePoints || endIdx >= sourcePoints) {
            return;
        }
        targetPoly.addPoint(sourcePoly.xpoints[startIdx], sourcePoly.ypoints[startIdx]);
        targetPoly.addPoint(sourcePoly.xpoints[endIdx], sourcePoly.ypoints[endIdx]);
    }
}
