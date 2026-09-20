package com.pvmkits.bosses.mokhaiotl;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.NPC;
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
import java.util.List;
import java.util.Set;

/**
 * Scene overlay for the Doom of Mokhaiotl. Renders the larvae true tiles, the
 * boulder shatter tiles, the boss's 5x5 true tile during the shield and
 * melee-punish phases, the car (dash) phase danger path and boulder safe tiles,
 * the first-dash eye candidates.
 */
public class MokhaiotlOverlay extends Overlay {

    private static final int BOSS_SIZE = 5; // Doom is 5x5 tiles
    // Ground patch drawn under a highlighted statue, centred on the statue's tile.
    private static final int STATUE_GROUND_SIZE = 3;
    private static final Color BOULDER_TILE_COLOR = new Color(232, 154, 154);
    private static final Color SHIELD_TILE_COLOR = new Color(170, 190, 235);
    private static final Color MELEE_PUNISH_TILE_COLOR = new Color(225, 150, 165); // pastel red
    // The same pastel red as the melee-punish tile, so the fight's two "get out of
    // the way" highlights read as one colour. It is the one that must not be missed
    // though, so it is drawn a shade stronger than the rest (see DASH_*_BOOST).
    private static final Color DASH_PATH_COLOR = MELEE_PUNISH_TILE_COLOR;
    // Added to the configured fill alpha for the dash path only.
    private static final int DASH_FILL_ALPHA_BOOST = 45;
    // Added on top of that again for the path's outer perimeter stroke.
    private static final int DASH_BORDER_ALPHA_BOOST = 145;
    private static final Color STATUE_COLOR = new Color(119, 221, 119); // pastel green
    private static final Color STATUE_ATTACKED_COLOR = new Color(255, 105, 97); // pastel red
    // The pair's second statue while it is still waiting its turn: off-white grey,
    // drawn at a fraction of the configured fill so it reads as dormant.
    private static final Color STATUE_DORMANT_COLOR = new Color(225, 225, 225);
    private static final float DORMANT_FILL_SCALE = 0.3f;
    private static final int DORMANT_BORDER_ALPHA = 110;
    // Car-phase tiles a boulder shields from the boss. The same soft green the
    // range-weak larvae use, that being the fight's other "go here" highlight.
    private static final Color CAR_SAFE_TILE_COLOR = new Color(144, 238, 144);

    // How long the first-dash eye marks take to sweep the whole hue wheel. Slow
    // enough to read as a colour rather than a flicker, quick enough that the marks
    // are visibly alive next to the fight's static highlights.
    private static final long RAINBOW_CYCLE_MS = 3000L;
    private static final Color ORB_TILE_COLOR = new Color(255, 179, 71); // pastel orange
    private static final Color DEFAULT_BORDER_COLOR = Color.WHITE;
    private static final BasicStroke BORDER_STROKE = new BasicStroke(1);
    private static final BasicStroke PATH_BORDER_STROKE = new BasicStroke(2);

    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;

    @Inject
    public MokhaiotlOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        MokhaiotlHandler handler = plugin.getMokhaiotlHandler();
        if (handler == null || handler.getBoss() == null) {
            return null;
        }

        // The first-dash eye marks are a standing reference for the whole delve, so
        // they go down first and every live warning draws over the top of them.
        if (config.showMokhaiotlEyeSpawnTiles()) {
            renderEyeSpawnTiles(graphics, handler);
        }
        // The handler only ever fills one of these two at a time - the dash path
        // while a dash is telegraphed, the safe tiles the rest of the car phase - so
        // they cannot overlap and this is the original order.
        if (config.showMokhaiotlDashPath()) {
            renderDashPath(graphics, handler);
        }
        if (config.showMokhaiotlCarSafeTiles()) {
            renderCarSafeTiles(graphics, handler);
        }
        if (config.showMokhaiotlBoulderTiles()) {
            renderBoulderTiles(graphics, handler);
        }
        if (config.highlightMokhaiotlLarvae()) {
            renderLarvae(graphics);
        }
        if (config.showMokhaiotlStatues()) {
            renderStatues(graphics, handler);
        }
        if (config.showMokhaiotlOrbTile()) {
            renderOrbTile(graphics, handler);
        }
        renderBossPhaseTile(graphics, handler);


        return null;
    }

    // ------------------------------------------------------------------
    // Boss 5x5 tile (shield = blue, melee punish = red)
    // ------------------------------------------------------------------

    private void renderBossPhaseTile(Graphics2D graphics, MokhaiotlHandler handler) {
        NPC boss = handler.getBoss();
        LocalPoint basePoint = boss.getLocalLocation();
        if (basePoint == null) {
            return;
        }
        Polygon poly = buildOuterBorderPoly(basePoint, BOSS_SIZE);
        if (poly == null) {
            return;
        }

        Color base = null;
        if (handler.isShieldPhase() && config.showMokhaiotlShieldTile()) {
            base = SHIELD_TILE_COLOR;
        } else if (handler.isMeleePunishActive() && config.showMokhaiotlMeleePunishTile()) {
            base = MELEE_PUNISH_TILE_COLOR;
        }

        if (base != null) {
            fillAndOutline(graphics, poly, base, BORDER_STROKE);
        } else {
            // No phase-specific highlight active: default thin white outline, same as neutral larvae.
            renderThinBorder(graphics, poly, DEFAULT_BORDER_COLOR);
        }

        // Highlight the 3 center tiles on the side of the boss that correlates to the remaining rainbow tiles with boulders
        if (handler.isShieldPhase()) {
            Set<WorldPoint> eyes = handler.getEyeSpawnTiles();
            Set<WorldPoint> boulders = handler.getBoulderObjectTiles();
            WorldPoint center = boss.getWorldLocation().dx(2).dy(2);

            for (WorldPoint eye : eyes) {
                if (boulders.contains(eye)) {
                    int dx = eye.getX() - center.getX();
                    int dy = eye.getY() - center.getY();

                    Color rainbow = rainbowColor();

                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (dx > 0) {
                            // East
                            renderWorldTile(graphics, center.dx(2).dy(-1), rainbow, BORDER_STROKE);
                            renderWorldTile(graphics, center.dx(2).dy(0), rainbow, BORDER_STROKE);
                            renderWorldTile(graphics, center.dx(2).dy(1), rainbow, BORDER_STROKE);
                        } else {
                            // West
                            renderWorldTile(graphics, center.dx(-2).dy(-1), rainbow, BORDER_STROKE);
                            renderWorldTile(graphics, center.dx(-2).dy(0), rainbow, BORDER_STROKE);
                            renderWorldTile(graphics, center.dx(-2).dy(1), rainbow, BORDER_STROKE);
                        }
                    } else {
                        if (dy > 0) {
                            // North
                            renderWorldTile(graphics, center.dx(-1).dy(2), rainbow, BORDER_STROKE);
                            renderWorldTile(graphics, center.dx(0).dy(2), rainbow, BORDER_STROKE);
                            renderWorldTile(graphics, center.dx(1).dy(2), rainbow, BORDER_STROKE);
                        } else {
                            // South
                            renderWorldTile(graphics, center.dx(-1).dy(-2), rainbow, BORDER_STROKE);
                            renderWorldTile(graphics, center.dx(0).dy(-2), rainbow, BORDER_STROKE);
                            renderWorldTile(graphics, center.dx(1).dy(-2), rainbow, BORDER_STROKE);
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Statue & orb (shockwave) phase
    // ------------------------------------------------------------------

    // Both statues of the pair are drawn, but only one reads as a live target: the
    // far one is green and goes red once hit, while the near one waits in a faint
    // grey until the far one has been hit and then turns green itself. Each is drawn
    // as its model hull plus a 3x3 ground patch centred on its tile, both in the
    // same colour, and a destroyed one leaves its red patch behind for a few ticks.
    private void renderStatues(Graphics2D graphics, MokhaiotlHandler handler) {

        renderDestroyedStatueTiles(graphics, handler);
        // Drawn ahead of the statues themselves, and outside the early return below,
        // because these tiles are tied to the shockwave countdown rather than to the
        // statue NPCs and so outlive them.
        renderStatueDirectionTiles(graphics, handler);

        List<NPC> highlighted = handler.getHighlightedStatues();
        if (highlighted.isEmpty()) {
            return;
        }

        for (NPC npc : highlighted) {
            boolean dormant = handler.isStatueDormant(npc);
            boolean skipOverlay = handler.isStatueAttacked(npc) && handler.isStartingStatue(npc);

            LocalPoint lp = npc.getLocalLocation();
            Polygon ground = lp == null ? null : buildOuterBorderPoly(lp, STATUE_GROUND_SIZE);
            java.awt.Shape hull = npc.getConvexHull();

            if (!skipOverlay) {
                Color base;
                if (handler.isStatueAttacked(npc)) {
                    base = STATUE_ATTACKED_COLOR;
                } else if (dormant) {
                    base = STATUE_DORMANT_COLOR;
                } else {
                    base = STATUE_COLOR;
                }
                // A dormant statue is washed out so it cannot be mistaken for the one to
                // hit, but stays visible enough to show where the pair runs.
                int fillAlpha = dormant
                        ? Math.round(config.mokhaiotlTransparency() * DORMANT_FILL_SCALE)
                        : config.mokhaiotlTransparency();
                Color border = dormant ? withAlpha(base, DORMANT_BORDER_ALPHA) : base;

                if (ground != null) {
                    graphics.setColor(withAlpha(base, fillAlpha));
                    graphics.fill(ground);
                    graphics.setColor(border);
                    graphics.setStroke(BORDER_STROKE);
                    graphics.draw(ground);
                }

                if (hull != null) {
                    graphics.setColor(withAlpha(base, fillAlpha));
                    graphics.fill(hull);
                    graphics.setColor(dormant ? border : base.darker());
                    graphics.setStroke(BORDER_STROKE);
                    graphics.draw(hull);
                }
            }

            if (handler.isStartingStatue(npc)) {
                int shockwaveTimer = handler.getStatueShockwaveTimer();
                if (shockwaveTimer >= 0) {
                    java.awt.Rectangle bounds = hull != null ? hull.getBounds() : (ground != null ? ground.getBounds() : null);
                    if (bounds != null) {
                        renderTimerText(graphics, bounds, shockwaveTimer);
                    }
                }
            }
        }

    }

    // The 3 rainbow tiles showing which way the earthen shield runs. They stay up for
    // the whole shockwave countdown, including the ticks after the statues themselves
    // have gone, so they clear on the same tick the timer does.
    private void renderStatueDirectionTiles(Graphics2D graphics, MokhaiotlHandler handler) {
        Set<WorldPoint> directionTiles = handler.getStatueDirectionTiles();
        if (directionTiles.isEmpty()) {
            return;
        }
        // A falling boulder is the more urgent thing to read on a tile, and these are
        // drawn with SRC so they would otherwise paint straight over its red mark.
        // Leave those tiles to the boulder overlay, but only where it is on to draw
        // them, so turning it off does not silently lose the direction tile as well.
        Set<WorldPoint> boulderTiles = config.showMokhaiotlBoulderTiles()
                ? handler.getBoulderTiles()
                : java.util.Collections.emptySet();

        for (WorldPoint directionTile : directionTiles) {
            if (boulderTiles.contains(directionTile)) {
                continue;
            }
            Polygon poly = worldTilePoly(directionTile);
            if (poly != null) {
                java.awt.Composite old = graphics.getComposite();
                graphics.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC));
                Color base = rainbowColor();
                graphics.setColor(withAlpha(base, config.mokhaiotlTransparency()));
                graphics.fill(poly);
                graphics.setColor(base);
                graphics.setStroke(BORDER_STROKE);
                graphics.draw(poly);
                graphics.setComposite(old);
            }
        }
    }

    // The 3x3 red patch a destroyed statue leaves behind, so its position is still
    // readable for a few ticks after the model is gone.
    private void renderDestroyedStatueTiles(Graphics2D graphics, MokhaiotlHandler handler) {
        for (WorldPoint tile : handler.getDestroyedStatueTiles()) {
            LocalPoint lp = LocalPoint.fromWorld(client, tile);
            if (lp == null) {
                continue;
            }
            Polygon poly = buildOuterBorderPoly(lp, STATUE_GROUND_SIZE);
            if (poly != null) {
                fillAndOutline(graphics, poly, STATUE_ATTACKED_COLOR, BORDER_STROKE);
            }
        }
    }

    private void renderOrbTile(Graphics2D graphics, MokhaiotlHandler handler) {
        NPC orb = handler.getOrb();
        if (orb == null) {
            return;
        }
        LocalPoint lp = orb.getLocalLocation();
        if (lp == null) {
            return;
        }
        Polygon poly = buildOuterBorderPoly(lp, MokhaiotlHandler.ORB_SIZE);
        if (poly == null) {
            return;
        }
        fillAndOutline(graphics, poly, ORB_TILE_COLOR, BORDER_STROKE);
    }

    private void renderTimerText(Graphics2D graphics, java.awt.Rectangle r, int number) {
        String text = String.valueOf(number);
        int fontSize = Math.max(16, config.mokhaiotlTimerTextSize());
        graphics.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, fontSize));
        java.awt.FontMetrics metrics = graphics.getFontMetrics();
        int x = r.x + r.width / 2 - metrics.stringWidth(text) / 2;
        int y = r.y + r.height / 2 + metrics.getHeight() / 4;

        graphics.setColor(Color.BLACK);
        graphics.drawString(text, x - 1, y - 1);
        graphics.drawString(text, x + 1, y - 1);
        graphics.drawString(text, x - 1, y + 1);
        graphics.drawString(text, x + 1, y + 1);

        graphics.setColor(number == 0 ? STATUE_COLOR : Color.WHITE);
        graphics.drawString(text, x, y);
    }

    // ------------------------------------------------------------------
    // Larvae true tiles (weakness style colour)
    // ------------------------------------------------------------------

    private void renderLarvae(Graphics2D graphics) {
        MokhaiotlHandler handler = plugin.getMokhaiotlHandler();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (!MokhaiotlHandler.isLarva(npc)) {
                continue;
            }
            MokhaiotlHandler.Style style = MokhaiotlHandler.larvaStyle(npc);

            LocalPoint lp = npc.getLocalLocation();
            if (lp == null) {
                continue;
            }
            Polygon area = buildOuterBorderPoly(lp, 3);
            if (area == null) {
                continue;
            }

            if (style != null) {
                fillAndOutline(graphics, area, handler.styleColor(style), BORDER_STROKE);
            } else {
                renderThinBorder(graphics, area, DEFAULT_BORDER_COLOR);
            }
        }
    }

    /**
     * Unfilled, thin outline used for tiles with no specific weakness/colour
     * assigned (e.g. neutral larvae). Never fills so it can't obscure other
     * overlays drawn on the same tile.
     */
    private void renderThinBorder(Graphics2D graphics, Polygon poly, Color color) {
        graphics.setColor(color);
        graphics.setStroke(BORDER_STROKE);
        graphics.draw(poly);
    }

    // ------------------------------------------------------------------
    // Boulder shatter / landing tiles
    // ------------------------------------------------------------------

    private void renderBoulderTiles(Graphics2D graphics, MokhaiotlHandler handler) {
        Set<WorldPoint> tiles = handler.getBoulderTiles();
        if (tiles.isEmpty()) {
            return;
        }
        Color base = BOULDER_TILE_COLOR;
        for (WorldPoint tile : tiles) {
            renderWorldTile(graphics, tile, base, BORDER_STROKE);
        }
    }

    // ------------------------------------------------------------------
    // Car (dash) phase
    // ------------------------------------------------------------------

    private void renderDashPath(Graphics2D graphics, MokhaiotlHandler handler) {
        Set<WorldPoint> path = handler.getDashPathTiles();
        if (path.isEmpty()) {
            return;
        }
        Color base = DASH_PATH_COLOR;
        Color fill = withAlpha(base, config.mokhaiotlTransparency() + DASH_FILL_ALPHA_BOOST);
        Color border = withAlpha(base,
                config.mokhaiotlTransparency() + DASH_FILL_ALPHA_BOOST + DASH_BORDER_ALPHA_BOOST);

        // Fill every path tile.
        for (WorldPoint tile : path) {
            Polygon poly = worldTilePoly(tile);
            if (poly == null) {
                continue;
            }
            graphics.setColor(fill);
            graphics.fill(poly);
        }

        strokePerimeter(graphics, path, border, PATH_BORDER_STROKE);
    }

    // ------------------------------------------------------------------
    // Car-phase boulder safe tiles
    // ------------------------------------------------------------------

    // Tiles a boulder puts between the player and the boss, so the dash cannot
    // reach them. Pastel green, the same "this is the one you want" colour the
    // range-weak larvae are drawn in.
    private void renderCarSafeTiles(Graphics2D graphics, MokhaiotlHandler handler) {
        for (WorldPoint tile : handler.getCarSafeTiles()) {
            renderWorldTile(graphics, tile, CAR_SAFE_TILE_COLOR, BORDER_STROKE);
        }
    }

    // ------------------------------------------------------------------
    // First-dash eye candidates
    // ------------------------------------------------------------------

    // The tiles the delve's first dash could send its eye to, marked for the whole
    // delve. Cycled through the spectrum so they read as a standing reference mark
    // rather than one of the fight's "move now" warnings, none of which animate.
    //
    // Once the shield phase starts the marks are hidden — they served their purpose
    // before the shield. The exception is any eye-spawn tile that still has a live
    // boulder ground object on it: that tile stays rainbow-highlighted until the
    // boulder is removed, so the player can see the boulder is sitting on an eye
    // location.
    private void renderEyeSpawnTiles(Graphics2D graphics, MokhaiotlHandler handler) {
        Set<WorldPoint> tiles = handler.getEyeSpawnTiles();
        if (tiles.isEmpty() || handler.getDepthLevel() < 6) {
            return;
        }

        boolean shieldActive = handler.isShieldPhase();
        Set<WorldPoint> boulderObjectTiles = shieldActive ? handler.getBoulderObjectTiles() : null;

        Color base = rainbowColor();
        
        boolean showWarning = handler.getDepthLevel() == 7 
            && handler.getEyeSpawnTick() != -1 
            && (client.getTickCount() - handler.getEyeSpawnTick()) <= 16;
            
        boolean flashOn = (client.getTickCount() % 2) == 0;

        for (WorldPoint tile : tiles) {
            // During shield phase, only keep tiles that have a boulder on them.
            if (shieldActive && !boulderObjectTiles.contains(tile)) {
                continue;
            }

            renderWorldTile(graphics, tile, base, BORDER_STROKE);
            
            if (!shieldActive && showWarning && flashOn) {
                Polygon poly = worldTilePoly(tile);
                if (poly != null) {
                    java.awt.Rectangle r = poly.getBounds();
                    String text = "wait for larvae";
                    graphics.setFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 11));
                    java.awt.FontMetrics metrics = graphics.getFontMetrics();
                    int x = r.x + r.width / 2 - metrics.stringWidth(text) / 2;
                    int y = r.y + r.height + metrics.getHeight();
                    
                    graphics.setColor(Color.BLACK);
                    graphics.drawString(text, x - 1, y - 1);
                    graphics.drawString(text, x + 1, y - 1);
                    graphics.drawString(text, x - 1, y + 1);
                    graphics.drawString(text, x + 1, y + 1);
                    
                    graphics.setColor(Color.RED);
                    graphics.drawString(text, x, y);
                }
            }
        }
    }

    // Hue swept once every RAINBOW_CYCLE_MS, at full saturation so every step of it
    // stays clearly a colour rather than washing out to white at the top of the
    // sweep. Driven off wall-clock time, so all four tiles are always the same hue.
    private static Color rainbowColor() {
        float hue = (System.currentTimeMillis() % RAINBOW_CYCLE_MS) / (float) RAINBOW_CYCLE_MS;
        return Color.getHSBColor(hue, 1f, 1f);
    }



    // ------------------------------------------------------------------
    // Shared drawing helpers
    // ------------------------------------------------------------------

    /**
     * Strokes only the outer edges of a set of tiles: an edge is drawn when the
     * neighbour across it is not in the set, which leaves the interior tile grid
     * invisible and makes the whole set read as one region rather than many tiles.
     */
    private void strokePerimeter(Graphics2D graphics, Set<WorldPoint> tiles, Color color,
            BasicStroke stroke) {
        graphics.setColor(color);
        graphics.setStroke(stroke);
        for (WorldPoint tile : tiles) {
            Polygon poly = worldTilePoly(tile);
            if (poly == null || poly.npoints < 4) {
                continue;
            }
            if (!tiles.contains(tile.dy(-1))) {
                drawEdge(graphics, poly, 0, 1);
            }
            if (!tiles.contains(tile.dx(1))) {
                drawEdge(graphics, poly, 1, 2);
            }
            if (!tiles.contains(tile.dy(1))) {
                drawEdge(graphics, poly, 2, 3);
            }
            if (!tiles.contains(tile.dx(-1))) {
                drawEdge(graphics, poly, 3, 0);
            }
        }
    }

    private void renderWorldTile(Graphics2D graphics, WorldPoint tile, Color base, BasicStroke stroke) {
        Polygon poly = worldTilePoly(tile);
        if (poly == null) {
            return;
        }
        fillAndOutline(graphics, poly, base, stroke);
    }

    private Polygon worldTilePoly(WorldPoint tile) {
        LocalPoint lp = LocalPoint.fromWorld(client, tile);
        if (lp == null) {
            return null;
        }
        return Perspective.getCanvasTilePoly(client, lp);
    }

    private void fillAndOutline(Graphics2D graphics, Polygon poly, Color base, BasicStroke stroke) {
        graphics.setColor(withAlpha(base, config.mokhaiotlTransparency()));
        graphics.fill(poly);
        graphics.setColor(base);
        graphics.setStroke(stroke);
        graphics.draw(poly);
    }

    private static Color withAlpha(Color base, int alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private void drawEdge(Graphics2D graphics, Polygon poly, int startIdx, int endIdx) {
        graphics.drawLine(poly.xpoints[startIdx], poly.ypoints[startIdx],
                poly.xpoints[endIdx], poly.ypoints[endIdx]);
    }

    @SuppressWarnings("deprecation") // LocalPoint constructor matches the existing overlay pattern
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
        addPoints(border, swPoly, 0, 1);
        addPoints(border, sePoly, 1, 2);
        addPoints(border, nePoly, 2, 3);
        addPoints(border, nwPoly, 3, 0);
        return border;
    }

    private void addPoints(Polygon target, Polygon source, int startIdx, int endIdx) {
        if (source == null || startIdx >= source.npoints || endIdx >= source.npoints) {
            return;
        }
        target.addPoint(source.xpoints[startIdx], source.ypoints[startIdx]);
        target.addPoint(source.xpoints[endIdx], source.ypoints[endIdx]);
    }
}
