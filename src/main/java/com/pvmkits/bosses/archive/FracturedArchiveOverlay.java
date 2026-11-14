package com.pvmkits.bosses.archive;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.PvmKitsPlugin;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.util.Collection;
import java.util.Set;

/**
 * Archive Guardian, Ezekiel, Moia and Dreadnoth highlights. Colours are borrowed
 * from the fights that already use them, so a colour means the same thing across
 * the plugin: Phosani's soft green for the step-under tick and every safe tile,
 * and its muted red spore zones for the Guardian's floor hazards (the mushrooms
 * and the 3x3 around each vine). Ezekiel's
 * lines are a pastel blue. Moia's safe corner, and in her enrage her tiles out
 * of every Undead binder's reach, use the same safe green, the enrage green
 * fading out as the binders walk off it. Dreadnoth's true
 * tiles are the Guardian's faint grey, his base square Phosani's range green
 * while he is ranging, and his floor splats and jellies' tiles and hulls
 * Moia's soft pastel red.
 */
public class FracturedArchiveOverlay extends Overlay {

    // Phosani's RANGE phase colour (PhosaniHandler.PhosaniPhase.RANGE), a soft
    // pastel green, for the "1" of the step-under timer.
    private static final Color STEP_TICK_COLOR = new Color(144, 238, 144);
    // Moia's cough countdown, a soft pastel red on every number: the Doom of
    // Mokhaiotl's pastel red (MokhaiotlOverlay.STATUE_ATTACKED_COLOR).
    private static final Color COUGH_TIMER_COLOR = new Color(255, 105, 97);
    // Phosani's melee phase colour (PvmKitsConfig.phosaniMeleeColor), a soft red,
    // for the Guardian's hull as its shield is about to break.
    private static final Color SHIELD_BREAK_COLOR = new Color(240, 100, 100);
    // Phosani's spore danger zone: a muted red, filled lightly with a floor so it
    // never vanishes at a low transparency setting.
    private static final Color MUSHROOM_COLOR = new Color(190, 55, 55);
    private static final int MUSHROOM_MIN_FILL_ALPHA = 45;
    private static final int MUSHROOM_BORDER_ALPHA_BOOST = 180;
    private static final BasicStroke HAZARD_BORDER_STROKE = new BasicStroke(2);
    // The Doom of Mokhaiotl's dormant statue white (its base fill alpha 30 scaled by
    // 0.3, and a 110 border), for the plugin's usual "nothing to do here yet" look.
    private static final Color TRUE_TILE_COLOR = new Color(225, 225, 225);
    private static final int TRUE_TILE_FILL_ALPHA = Math.round(30 * 0.3f);
    private static final int TRUE_TILE_BORDER_ALPHA = 110;
    // Vines lash the 3x3 around them; drawn in the mushroom red so the fight's two
    // floor hazards read as one colour.
    private static final int VINE_SIZE = 3;
    // Phosani's range green again for every Ezekiel tile that is safe to stand on,
    // the same "go here" colour as the Doom of Mokhaiotl's car-phase safe tiles.
    private static final Color SAFE_TILE_COLOR = new Color(144, 238, 144);
    // A pastel blue to sit beside the safe tiles' pastel green, for the converge
    // line and the lines either side of the four-sword zone.
    private static final Color EZEKIEL_LINE_COLOR = new Color(160, 200, 250);
    // Ezekiel's green and blue fills are a fixed 37% opaque (borderless), down
    // from 60% on request (2026-10-23).
    private static final int EZEKIEL_FILL_ALPHA = Math.round(255 * 0.37f);
    private static final BasicStroke BORDER_STROKE = new BasicStroke(1);
    // Phosani's range green (PhosaniHandler.PhosaniPhase), for Moia's 3x3 base,
    // which stays green throughout. While an axe is in flight her centre tile is
    // drawn on top in Phosani's curse purple (PhosaniOverlay.renderCurseCenterTile)
    // instead; it flicked blue and green until 2026-11-10.
    private static final Color MOIA_RANGE_COLOR = new Color(144, 238, 144);
    private static final Color MOIA_AXE_CENTRE_COLOR = new Color(128, 0, 128);
    // Dreadnoth's 5x5 base in Phosani's range green while a projectile of his
    // flies. His melee (his main attack) gets nothing: the Phosani melee red
    // square the user saw on him in their first fight came out on 2026-11-12, so
    // only his grey true tiles stay on him between ranged attacks.
    private static final Color DREADNOTH_RANGE_COLOR = new Color(144, 238, 144);
    // Dreadnoth's floor splats and his jellies' hulls: the soft pastel red of
    // Moia's cough timer (the Doom of Mokhaiotl's STATUE_ATTACKED_COLOR), with a
    // thin, lighter border than the mushrooms' so the splats read as softer.
    private static final Color FLOOR_SPLAT_COLOR = new Color(255, 105, 97);
    private static final int FLOOR_SPLAT_BORDER_ALPHA_BOOST = 90;
    private final Client client;
    private final PvmKitsPlugin plugin;
    private final PvmKitsConfig config;

    @Inject
    public FracturedArchiveOverlay(Client client, PvmKitsPlugin plugin, PvmKitsConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        FracturedArchiveHandler handler = plugin.getFracturedArchiveHandler();
        if (handler == null || plugin.getActiveBossHandler() != handler) {
            return null;
        }
        graphics.setStroke(BORDER_STROKE);

        // The true-tile footprint goes underneath everything: it is the faintest.
        if (config.showArchiveGuardianTrueTiles()) {
            renderTrueTiles(graphics, handler.getGuardian());
        }
        if (config.highlightArchiveMushrooms()) {
            renderMushrooms(graphics, handler);
        }
        if (config.highlightArchiveVines()) {
            renderVines(graphics);
        }
        // Ezekiel's room. While the slam's safe tiles are up they are all that is
        // drawn; the sword attacks' highlights take over on the tick they go.
        EzekielTracker ezekiel = handler.getEzekiel();
        boolean slamShown = config.highlightEzekielSlam() && !ezekiel.getSlamSafeTiles().isEmpty();
        if (slamShown) {
            renderSafeTiles(graphics, ezekiel.getSlamSafeTiles());
        }
        if (!slamShown && config.highlightEzekielConvergingSwords()) {
            renderConverge(graphics, ezekiel);
        }
        if (!slamShown && config.highlightEzekielFourSwords()) {
            renderFourSwords(graphics, ezekiel);
        }
        if (!slamShown && config.highlightEzekielSwordSwipe()) {
            renderSafeTiles(graphics, ezekiel.getSwipeSafeTiles());
        }
        if (config.highlightEzekielEnrage()) {
            renderEnrage(graphics, ezekiel);
        }
        if (config.highlightMoiaSafeTiles()) {
            // In her enrage, the green the binders are walking away from fades
            // out as they go, and is gone as the green for where they stop shows.
            renderSafeTiles(graphics, handler.getMoiaFadingSafeTiles(), handler.getMoiaFadeOpacity());
            renderSafeTiles(graphics, handler.getMoiaSafeTiles());
        }
        if (config.highlightMoiaVenatorBinders()) {
            renderVenatorBinders(graphics, handler);
        }
        if (config.highlightMoiaAttackStyle()) {
            renderMoiaStyle(graphics, handler);
        }
        // Dreadnoth's room: the floor first, then the jellies and his own
        // tiles. His true tiles also show where he lands when he jumps (the
        // server moves him there while his model is in the air), so the jump
        // landing highlight was removed on 2026-11-12.
        DreadnothTracker dreadnoth = handler.getDreadnoth();
        if (config.highlightDreadnothFloor()) {
            renderFloorSplats(graphics, dreadnoth.getFloorTiles());
        }
        if (config.highlightDreadnothJellies()) {
            renderJellies(graphics);
        }
        NPC dreadnothNpc = DreadnothTracker.findDreadnoth(client);
        if (config.showDreadnothTrueTiles() && dreadnothNpc != null && !dreadnothNpc.isDead()) {
            drawTrueTiles(graphics, dreadnothNpc);
        }
        if (config.highlightDreadnothAttackStyle()) {
            renderDreadnothStyle(graphics, dreadnothNpc, dreadnoth);
        }
        if (config.showArchiveShieldBreakHull() && handler.isShieldBreakImminent()) {
            renderShieldBreakHull(graphics, handler.getGuardian());
        }
        // The countdown last, so no fill ever sits over the number. The beam
        // countdown is drawn over food by ArchiveTickEatOverlay instead.
        if (config.showArchiveGuardianStepTimer()) {
            int step = handler.getGuardianStepTimer();
            renderTimer(graphics, handler.getGuardian(), step, step == 1 ? STEP_TICK_COLOR : Color.WHITE);
        }
        if (config.showMoiaCoughTimer()) {
            renderTimer(graphics, handler.getMoia(), handler.getMoiaCoughCountdown(), COUGH_TIMER_COLOR);
        }
        return null;
    }

    // The Guardian's model hull in Phosani's melee red while its shield is in its
    // last 12%: a reminder to step away before the shield breaks and it crashes down.
    private void renderShieldBreakHull(Graphics2D graphics, NPC guardian) {
        if (guardian == null) {
            return;
        }
        Shape hull = guardian.getConvexHull();
        if (hull == null) {
            return;
        }
        graphics.setColor(withAlpha(SHIELD_BREAK_COLOR, config.archiveTransparency()));
        graphics.fill(hull);
        graphics.setColor(SHIELD_BREAK_COLOR);
        graphics.draw(hull);
    }

    // What to shoot with the Venator bow in the middle of the arena: the binder
    // on the centre tile, with a soft red fill on its tile and its hull. The tile
    // sits under the model (its local location, which glides with it instead of
    // snapping like the true tile). Moia got the same red while she stood there
    // in her enrage until 2026-11-10, when the user had that part removed.
    private void renderVenatorBinders(Graphics2D graphics, FracturedArchiveHandler handler) {
        Color fill = withAlpha(SHIELD_BREAK_COLOR, config.archiveTransparency());
        NPC binder = handler.getVenatorBinder();
        if (binder != null && !binder.isDead()) {
            drawArea(graphics, binder.getLocalLocation(), 1, fill, SHIELD_BREAK_COLOR);
            drawHull(graphics, binder, fill, SHIELD_BREAK_COLOR);
        }
    }

    private static void drawHull(Graphics2D graphics, NPC npc, Color fill, Color border) {
        Shape hull = npc.getConvexHull();
        if (hull != null) {
            graphics.setColor(fill);
            graphics.fill(hull);
            graphics.setColor(border);
            graphics.draw(hull);
        }
    }

    // Moia's base tiles, filled and outlined like Phosani's attack-style square,
    // always green. While an axe is in flight (the same window the axe sound
    // loops) her centre tile goes purple on top, like Phosani's cursed centre tile.
    private void renderMoiaStyle(Graphics2D graphics, FracturedArchiveHandler handler) {
        NPC moia = handler.getMoia();
        if (moia == null || moia.isDead()) {
            return;
        }
        NPCComposition composition = moia.getComposition();
        int size = composition != null ? composition.getSize() : 3;
        LocalPoint centre = moia.getLocalLocation();
        drawArea(graphics, centre, size,
                withAlpha(MOIA_RANGE_COLOR, config.archiveTransparency()), MOIA_RANGE_COLOR);
        if (handler.isMoiaAxeInFlight()) {
            drawArea(graphics, centre, 1,
                    withAlpha(MOIA_AXE_CENTRE_COLOR, config.archiveTransparency()), MOIA_AXE_CENTRE_COLOR);
        }
    }

    // Dreadnoth's 5x5 base under his model (his local location, which glides as
    // he walks), filled and outlined in Phosani's range green while one of his
    // projectiles is in the air, and not drawn at all otherwise.
    private void renderDreadnothStyle(Graphics2D graphics, NPC boss, DreadnothTracker tracker) {
        if (boss == null || boss.isDead() || !tracker.isRanging(client.getGameCycle())) {
            return;
        }
        drawArea(graphics, boss.getLocalLocation(), DreadnothTracker.size(boss),
                withAlpha(DREADNOTH_RANGE_COLOR, config.archiveTransparency()), DREADNOTH_RANGE_COLOR);
    }

    // Every live jelly's true tile and model hull, both in the floor splats'
    // pastel red with the same fill and border, so a jelly and the jelly on the
    // floor read as one thing. The tile is the server tile (its world location),
    // the one it crushes from, rather than the one under its gliding model.
    // The tiles are merged and filled once, then the hulls on top the same
    // way: jellies pile onto the tile they are crushing (11 on one tile in the
    // user's first fight), and a fill per jelly stacked into a solid blob. That
    // is how their grey true tiles, drawn until 2026-11-12, became the "bright
    // white tile" the user reported.
    private void renderJellies(Graphics2D graphics) {
        Area tiles = new Area();
        Area hulls = new Area();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null || npc.isDead() || !DreadnothTracker.isJelly(npc)) {
                continue;
            }
            WorldPoint tile = npc.getWorldLocation();
            Polygon poly = tile != null ? tilePoly(tile) : null;
            if (poly != null) {
                tiles.add(new Area(poly));
            }
            Shape hull = npc.getConvexHull();
            if (hull != null) {
                hulls.add(new Area(hull));
            }
        }
        Color fill = withAlpha(FLOOR_SPLAT_COLOR, config.archiveTransparency());
        Color border = withAlpha(FLOOR_SPLAT_COLOR,
                Math.min(255, config.archiveTransparency() + FLOOR_SPLAT_BORDER_ALPHA_BOOST));
        for (Area area : new Area[] {tiles, hulls}) {
            if (!area.isEmpty()) {
                graphics.setColor(fill);
                graphics.fill(area);
                graphics.setColor(border);
                graphics.draw(area);
            }
        }
    }

    // Every tile with jelly (or anything else) on Dreadnoth's floor, one by one.
    private void renderFloorSplats(Graphics2D graphics, Collection<WorldPoint> tiles) {
        Color fill = withAlpha(FLOOR_SPLAT_COLOR, config.archiveTransparency());
        Color border = withAlpha(FLOOR_SPLAT_COLOR,
                Math.min(255, config.archiveTransparency() + FLOOR_SPLAT_BORDER_ALPHA_BOOST));
        for (WorldPoint tile : tiles) {
            Polygon poly = tilePoly(tile);
            if (poly == null) {
                continue;
            }
            graphics.setColor(fill);
            graphics.fill(poly);
            graphics.setColor(border);
            graphics.draw(poly);
        }
    }

    // An NPC's true (server) tiles in the Guardian's faint grey, drawn as one
    // square from its south-west tile and size. A walking NPC's square steps
    // ahead of its gliding model to where the server has it.
    private void drawTrueTiles(Graphics2D graphics, NPC npc) {
        WorldPoint sw = npc.getWorldLocation();
        LocalPoint swLocal = sw != null ? LocalPoint.fromWorld(client, sw) : null;
        if (swLocal == null) {
            return;
        }
        int size = DreadnothTracker.size(npc);
        int toCentre = (size - 1) * Perspective.LOCAL_TILE_SIZE / 2;
        LocalPoint centre = new LocalPoint(swLocal.getX() + toCentre, swLocal.getY() + toCentre,
                client.getTopLevelWorldView());
        drawArea(graphics, centre, size,
                withAlpha(TRUE_TILE_COLOR, TRUE_TILE_FILL_ALPHA), withAlpha(TRUE_TILE_COLOR, TRUE_TILE_BORDER_ALPHA));
    }

    private void renderMushrooms(Graphics2D graphics, FracturedArchiveHandler handler) {
        Color fill = hazardFill();
        Color border = hazardBorder();
        graphics.setStroke(HAZARD_BORDER_STROKE);
        for (WorldPoint centre : handler.getMushroomTiles()) {
            LocalPoint lp = LocalPoint.fromWorld(client, centre);
            drawArea(graphics, lp, FracturedArchiveHandler.MUSHROOM_SIZE, fill, border);
        }
        graphics.setStroke(BORDER_STROKE);
    }

    // A quarter of the configured fill, so the floor stays readable; the strong,
    // thicker border is what marks the zone.
    private Color hazardFill() {
        return hazardFill(MUSHROOM_COLOR);
    }

    private Color hazardBorder() {
        return hazardBorder(MUSHROOM_COLOR);
    }

    private Color hazardFill(Color color) {
        return withAlpha(color, Math.max(MUSHROOM_MIN_FILL_ALPHA, config.archiveTransparency()) / 4);
    }

    private Color hazardBorder(Color color) {
        return withAlpha(color, Math.min(255, config.archiveTransparency() + MUSHROOM_BORDER_ALPHA_BOOST));
    }

    // The Guardian's true tiles, in both its flying and downed forms. Flying, its
    // model floats above them, so this shows where it really stands. Its size comes
    // from its composition (5x5 in the logs) rather than a constant, so a size
    // change cannot misalign it.
    private void renderTrueTiles(Graphics2D graphics, NPC guardian) {
        if (guardian == null) {
            return;
        }
        NPCComposition composition = guardian.getComposition();
        int size = composition != null ? composition.getSize() : 5;
        drawArea(graphics, guardian.getLocalLocation(), size,
                withAlpha(TRUE_TILE_COLOR, TRUE_TILE_FILL_ALPHA), withAlpha(TRUE_TILE_COLOR, TRUE_TILE_BORDER_ALPHA));
    }

    // Drawn exactly like Phosani's attack timer: bold Dialog (the Attack Timer
    // Metronome's "Bold" font) at the boss's base tile with OverlayUtil's black
    // drop shadow, in the given colour: the step-under timer is white with a
    // pastel green "1", Moia's cough timer red throughout.
    private void renderTimer(Graphics2D graphics, NPC boss, int timer, Color color) {
        if (timer <= 0 || boss == null) {
            return;
        }
        LocalPoint basePoint = boss.getLocalLocation();
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

        String timerText = String.valueOf(timer);
        graphics.setFont(new Font(Font.DIALOG, Font.BOLD, config.archiveTimerTextSize()));

        FontMetrics metrics = graphics.getFontMetrics();
        int textX = centerX - (metrics.stringWidth(timerText) / 2);
        int textY = stableY + (metrics.getHeight() / 4);

        OverlayUtil.renderTextLocation(graphics, new net.runelite.api.Point(textX, textY), timerText, color);
    }

    // The tiles of the enrage 3x3 that a floor strike misses, in green, only on
    // the tick to click onto them: the one before that strike's lightning shows.
    // You move on the strike's own tick and it checks the tile you end on, so
    // with the three strikes a tick apart each set shows as the one before lands.
    private void renderEnrage(Graphics2D graphics, EzekielTracker ezekiel) {
        renderSafeTiles(graphics, ezekiel.getEnrageSafeTiles(client.getTickCount()));
    }

    private void renderVines(Graphics2D graphics) {
        Color fill = hazardFill();
        Color border = hazardBorder();
        graphics.setStroke(HAZARD_BORDER_STROKE);
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == FracturedArchiveHandler.VINE_ID) {
                drawArea(graphics, npc.getLocalLocation(), VINE_SIZE, fill, border);
            }
        }
        graphics.setStroke(BORDER_STROKE);
    }

    // The one-tile line between Ezekiel's converging swords, in blue,
    // with the corners of their meeting 3x3 that no sword crosses, and
    // the nook where two crossing lines stop, in green.
    private void renderConverge(Graphics2D graphics, EzekielTracker ezekiel) {
        renderZone(graphics, ezekiel.getConvergeLines(), withAlpha(EZEKIEL_LINE_COLOR, EZEKIEL_FILL_ALPHA));
        renderSafeTiles(graphics, ezekiel.getConvergeSafeTiles());
    }

    // The four swords' green middle lane, a blue line a tile either side of it,
    // and a green line a tile outside each blue one.
    private void renderFourSwords(Graphics2D graphics, EzekielTracker ezekiel) {
        Color safeFill = safeFill();
        renderZone(graphics, ezekiel.getFourSwordSafeTiles(), safeFill);
        renderZone(graphics, ezekiel.getFourSwordSideLines(), withAlpha(EZEKIEL_LINE_COLOR, EZEKIEL_FILL_ALPHA));
        renderZone(graphics, ezekiel.getFourSwordOuterLines(), safeFill);
    }

    private Color safeFill() {
        return withAlpha(SAFE_TILE_COLOR, EZEKIEL_FILL_ALPHA);
    }

    private void renderSafeTiles(Graphics2D graphics, Collection<WorldPoint> tiles) {
        renderSafeTiles(graphics, tiles, 1.0);
    }

    // At a share of the usual green fill, from 1 (full) down to 0 (not drawn).
    private void renderSafeTiles(Graphics2D graphics, Collection<WorldPoint> tiles, double opacity) {
        int alpha = (int) Math.round(EZEKIEL_FILL_ALPHA * Math.max(0.0, Math.min(1.0, opacity)));
        if (alpha <= 0 || tiles.isEmpty()) {
            return;
        }
        Color fill = withAlpha(SAFE_TILE_COLOR, alpha);
        for (WorldPoint tile : tiles) {
            Polygon poly = tilePoly(tile);
            if (poly == null) {
                continue;
            }
            graphics.setColor(fill);
            graphics.fill(poly);
        }
    }

    // A whole zone filled tile by tile, with no outline.
    private void renderZone(Graphics2D graphics, Set<WorldPoint> tiles, Color fill) {
        graphics.setColor(fill);
        for (WorldPoint tile : tiles) {
            Polygon poly = tilePoly(tile);
            if (poly != null) {
                graphics.fill(poly);
            }
        }
    }

    private Polygon tilePoly(WorldPoint tile) {
        LocalPoint lp = LocalPoint.fromWorld(client, tile);
        return lp != null ? Perspective.getCanvasTilePoly(client, lp) : null;
    }

    // A size x size square centred on the given local point (an NPC's or a
    // multi-tile object's local location is its centre tile).
    private void drawArea(Graphics2D graphics, LocalPoint centre, int size, Color fill, Color border) {
        if (centre == null) {
            return;
        }
        Polygon poly = Perspective.getCanvasTileAreaPoly(client, centre, size);
        if (poly == null) {
            return;
        }
        graphics.setColor(fill);
        graphics.fill(poly);
        graphics.setColor(border);
        graphics.draw(poly);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }
}
