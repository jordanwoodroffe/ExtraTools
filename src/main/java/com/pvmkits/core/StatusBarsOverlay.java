package com.pvmkits.core;

import com.pvmkits.PvmKitsConfig;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Restyled prayer and health bars drawn over the local player. The prayer bar sits
 * in the same spot as the core RuneLite Prayer plugin's bar (directly under the
 * overhead health bar) and the health bar sits just above it, over where the
 * game's own bar would be ({@link OwnHealthBarHider} hides that one). Both are a
 * rounded pill with a solid border, a dark track and a softly shaded fill showing
 * the current level as a share of the real level. Fills ease toward a new value
 * over a few frames rather than jumping, so drains, hits and restores read smoothly.
 *
 * Each hitsplat that lands on the player rises out of the hitpoints number as "-12"
 * (or "+12" for a heal) in the same font, drifting up and away from the bars at 45
 * degrees, then fades away ({@link RisingHitNumbers}).
 * It stands in for the game's own splats, which {@link OwnHitsplatHider} removes.
 */
public class StatusBarsOverlay extends Overlay {

    // Height (local units) above the player's model the prayer bar is anchored at,
    // matching the core Prayer plugin so it sits directly under the health bar.
    private static final int HEAD_CLEARANCE = 10;

    // Border thickness in pixels.
    private static final float BORDER = 1f;

    // Time constant (ms) of the fills' ease toward their target values.
    private static final double EASE_MILLIS = 120.0;

    private final Client client;
    private final PvmKitsConfig config;

    // Gap in pixels between the numbers and the left edge of the bars, and between
    // the two stacked numbers.
    private static final int LABEL_GAP = 3;
    private static final int LABEL_LINE_GAP = 2;
    private static final Color TEXT_SHADOW = new Color(0, 0, 0, 220);

    // A true minus sign: the width of a plus and centred on the digits, unlike a hyphen.
    private static final char MINUS_SIGN = '−';

    // The rising hit numbers have a fixed font size of their own, a touch bigger than
    // the default Text Size, which only sizes the hitpoints and prayer numbers.
    private static final int HIT_NUMBER_FONT_SIZE = 12;

    private final EasedValue prayerRatio = new EasedValue();
    private final EasedValue healthRatio = new EasedValue();
    private final RisingHitNumbers hitNumbers = new RisingHitNumbers();

    // Hitpoints/prayer number font, rebuilt only when the text size setting changes.
    private Font labelFont;
    private int labelFontSize;
    private Rectangle2D digitBounds;

    private final Font hitFont = new Font(Font.SANS_SERIF, Font.BOLD, HIT_NUMBER_FONT_SIZE);
    private Rectangle2D hitDigitBounds;
    // MINUS_SIGN, or a hyphen if the hit font has no glyph for it.
    private final String minus = hitFont.canDisplay(MINUS_SIGN) ? String.valueOf(MINUS_SIGN) : "-";

    @Inject
    public StatusBarsOverlay(Client client, PvmKitsConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(PRIORITY_HIGH);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    // Starts a rising hit number for each hitsplat that lands on the player.
    public void onHitsplatApplied(HitsplatApplied event) {
        if (!config.showOwnHitNumbers()) {
            return;
        }
        Actor actor = event.getActor();
        Hitsplat hitsplat = event.getHitsplat();
        if (actor == null || hitsplat == null || actor != client.getLocalPlayer()) {
            return;
        }
        hitNumbers.add(hitsplat.getAmount(), hitsplat.getHitsplatType() == HitsplatID.HEAL,
                System.currentTimeMillis());
    }

    public void clearHitNumbers() {
        hitNumbers.clear();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        Player local = client.getLocalPlayer();
        boolean showPrayer = local != null && shouldShowPrayerBar(local);
        boolean showHealth = local != null && shouldShowHealthBar(local);
        if (!showPrayer) {
            prayerRatio.reset();
        }
        if (!showHealth) {
            healthRatio.reset();
        }
        if (!showPrayer && !showHealth) {
            return null;
        }

        Point anchor = barAnchor(local);
        if (anchor == null) {
            return null;
        }

        int width = Math.max(10, config.prayerBarWidth());
        int height = Math.max(3, config.prayerBarHeight());
        float x = anchor.getX() - width / 2f;
        // The prayer bar's slot is fixed, so the health bar above it stays put even
        // while the prayer bar itself is hidden.
        float prayerY = anchor.getY() + config.prayerBarVerticalOffset();
        float healthY = prayerY - Math.max(0, config.healthBarGap()) - height;

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        Color healthFill = healthFillColor();

        // Vertical extent of whatever bars are drawn, so the numbers can be centred on it.
        float groupTop;
        float groupBottom;
        if (showHealth && showPrayer && config.joinStatusBars()) {
            // One capsule: the prayer half stays where the prayer bar sits, and the
            // health half sits on top of it, sharing the divider line.
            float totalHeight = 2 * height - BORDER;
            float top = prayerY + height - totalHeight;
            drawJoinedBar(graphics, x, top, width, totalHeight,
                    healthRatio.update(skillRatio(Skill.HITPOINTS)), healthFill,
                    prayerRatio.update(skillRatio(Skill.PRAYER)), config.prayerBarFillColor());
            groupTop = top;
            groupBottom = top + totalHeight;
        } else {
            if (showHealth) {
                double ratio = healthRatio.update(skillRatio(Skill.HITPOINTS));
                drawBar(graphics, x, healthY, width, height, ratio, healthFill);
            }
            if (showPrayer) {
                double ratio = prayerRatio.update(skillRatio(Skill.PRAYER));
                drawBar(graphics, x, prayerY, width, height, ratio, config.prayerBarFillColor());
            }
            groupTop = showHealth ? healthY : prayerY;
            groupBottom = (showPrayer ? prayerY : healthY) + height;
        }

        if (!config.showOwnHitNumbers()) {
            hitNumbers.clear();
        }
        if (config.showStatusBarNumbers() || !hitNumbers.isEmpty()) {
            drawNumbers(graphics, x, (groupTop + groupBottom) / 2f, showHealth, showPrayer, healthFill);
        }
        return null;
    }

    // The configured health green, or the darker poisoned shade while the player is
    // poisoned or venomed. The poison varp is positive only while poison or venom is
    // ticking: 0 is clear and negative is anti-poison/venom immunity.
    private Color healthFillColor() {
        return client.getVarpValue(VarPlayerID.POISON) > 0
                ? config.healthBarPoisonedColor()
                : config.healthBarFillColor();
    }

    // Current hitpoints (green) over current prayer (blue), stacked and centred
    // vertically on the bars, right-aligned just left of them, with the hit numbers
    // rising out of the top one (the hitpoints number while the health bar shows).
    private void drawNumbers(Graphics2D g, float barX, float centerY, boolean health, boolean prayer,
            Color healthColor) {
        int size = Math.max(6, config.statusBarTextSize());
        if (labelFont == null || labelFontSize != size) {
            labelFont = new Font(Font.SANS_SERIF, Font.BOLD, size);
            labelFontSize = size;
            digitBounds = null;
        }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (digitBounds == null) {
            // Visible extent of the digits (no ascender/descender padding), so the
            // two lines stack tightly and centre exactly on the bars.
            digitBounds = digitBounds(g, labelFont);
        }

        float lineHeight = (float) digitBounds.getHeight();
        int lines = (health ? 1 : 0) + (prayer ? 1 : 0);
        float blockHeight = lines * lineHeight + (lines - 1) * LABEL_LINE_GAP;
        float lineTop = centerY - blockHeight / 2f;
        int right = Math.round(barX - LABEL_GAP);

        // Drawn first, so the hitpoints number stays on top while a hit number fades
        // in and rises off it. They share its baseline, and are kept a line of their
        // own (bigger) font apart.
        if (!hitNumbers.isEmpty()) {
            if (hitDigitBounds == null) {
                hitDigitBounds = digitBounds(g, hitFont);
            }
            g.setFont(hitFont);
            drawHitNumbers(g, g.getFontMetrics(), right, lineTop - (float) digitBounds.getY(),
                    (float) hitDigitBounds.getHeight() + LABEL_LINE_GAP);
        }
        if (!config.showStatusBarNumbers()) {
            return;
        }

        g.setFont(labelFont);
        FontMetrics metrics = g.getFontMetrics();

        if (health) {
            drawNumber(g, metrics, client.getBoostedSkillLevel(Skill.HITPOINTS), right,
                    lineTop - (float) digitBounds.getY(), healthColor);
            lineTop += lineHeight + LABEL_LINE_GAP;
        }
        if (prayer) {
            drawNumber(g, metrics, client.getBoostedSkillLevel(Skill.PRAYER), right,
                    lineTop - (float) digitBounds.getY(), config.prayerBarFillColor());
        }
    }

    private static Rectangle2D digitBounds(Graphics2D g, Font font) {
        return font.createGlyphVector(g.getFontRenderContext(), "0123456789").getVisualBounds();
    }

    private static void drawNumber(Graphics2D g, FontMetrics metrics, int value, int right, float baseline, Color color) {
        String text = String.valueOf(value);
        int x = right - metrics.stringWidth(text);
        drawShadowedText(g, text, x, Math.round(baseline), new Color(color.getRed(), color.getGreen(), color.getBlue()));
    }

    // Each hit number starts right-aligned to the same edge as the hitpoints number,
    // then rises up and away from the bars (to the left) at 45 degrees, faded to
    // wherever its animation is this frame. It drifts left by as much as it rises,
    // so numbers kept a line apart vertically are also a line apart sideways.
    private void drawHitNumbers(Graphics2D g, FontMetrics metrics, int right, float baseline, float lineStep) {
        Color color = config.ownHitNumbersColor();
        long lifetime = Math.max(1, config.ownHitNumbersDuration());
        for (RisingHitNumbers.Frame hit : hitNumbers.frame(System.currentTimeMillis(), lifetime, lineStep)) {
            int alpha = (int) Math.round(color.getAlpha() * hit.opacity);
            if (alpha <= 0) {
                continue;
            }
            String text = hit.text(minus);
            int x = (int) Math.round(right - metrics.stringWidth(text) - hit.rise);
            drawShadowedText(g, text, x, Math.round(baseline - (float) hit.rise),
                    new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
        }
    }

    // Text over a dark shadow 1px down and to the right. The shadow's alpha follows
    // the text's, so see-through or fading text takes its shadow with it.
    private static void drawShadowedText(Graphics2D g, String text, int x, int y, Color color) {
        int shadowAlpha = TEXT_SHADOW.getAlpha() * color.getAlpha() / 255;
        g.setColor(new Color(TEXT_SHADOW.getRed(), TEXT_SHADOW.getGreen(), TEXT_SHADOW.getBlue(), shadowAlpha));
        g.drawString(text, x + 1, y + 1);
        g.setColor(color);
        g.drawString(text, x, y);
    }

    private boolean shouldShowPrayerBar(Player local) {
        if (!config.showPrayerBar()) {
            return false;
        }
        if (config.prayerBarOnlyWhenPraying() && !anyPrayerActive()) {
            return false;
        }
        // Health scale is -1 while the player's own health bar is not on screen.
        return !config.prayerBarOnlyWithHealthBar() || local.getHealthScale() != -1;
    }

    private boolean shouldShowHealthBar(Player local) {
        if (!config.showStyledHealthBar()) {
            return false;
        }
        return !config.healthBarOnlyInCombat() || local.getHealthScale() != -1;
    }

    // Current (boosted) level as a share of the real level, capped at a full bar.
    private double skillRatio(Skill skill) {
        int real = client.getRealSkillLevel(skill);
        if (real <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, client.getBoostedSkillLevel(skill) / (double) real));
    }

    private void drawBar(Graphics2D g, float x, float y, int width, int height, double ratio, Color fill) {
        // Outer pill = the border; everything else is inset inside it so the border
        // stays an even, crisp ring all the way round (no half-pixel stroke blur).
        RoundRectangle2D outer = new RoundRectangle2D.Float(x, y, width, height, height, height);
        g.setColor(config.prayerBarBorderColor());
        g.fill(outer);

        float ix = x + BORDER;
        float iy = y + BORDER;
        float iw = width - BORDER * 2;
        float ih = height - BORDER * 2;
        if (iw <= 0 || ih <= 0) {
            return;
        }
        RoundRectangle2D track = new RoundRectangle2D.Float(ix, iy, iw, ih, ih, ih);
        g.setColor(config.prayerBarBackgroundColor());
        g.fill(track);

        float fillWidth = (float) (iw * ratio);
        if (fillWidth <= 0.01f) {
            return;
        }

        Shape oldClip = g.getClip();
        // Clip to the track so the fill always follows its rounded ends, even when it
        // is narrower than the corner radius at a very low level.
        g.clip(track);

        Color top = blend(fill, Color.WHITE, 0.28);
        Color bottom = blend(fill, Color.BLACK, 0.22);
        g.setPaint(new GradientPaint(0, iy, top, 0, iy + ih, bottom));
        g.fill(new RoundRectangle2D.Float(ix, iy, fillWidth, ih, ih, ih));

        // Soft gloss along the upper third of the fill.
        float glossHeight = Math.max(1f, ih / 3f);
        g.setPaint(new GradientPaint(0, iy, new Color(255, 255, 255, 90),
                0, iy + glossHeight, new Color(255, 255, 255, 0)));
        g.fill(new Rectangle2D.Float(ix, iy, fillWidth, glossHeight));

        g.setClip(oldClip);
    }

    // Health and prayer as one rounded capsule: a single pill-shaped border whose
    // inside is split into a top (health) and bottom (prayer) half by a straight
    // divider line in the border colour. Only the outer edge is curved; each half's
    // fill follows that curve on the left and ends in a straight edge.
    private void drawJoinedBar(Graphics2D g, float x, float y, int width, float totalHeight,
            double topRatio, Color topFill, double bottomRatio, Color bottomFill) {
        g.setColor(config.prayerBarBorderColor());
        g.fill(new RoundRectangle2D.Float(x, y, width, totalHeight, totalHeight, totalHeight));

        float ix = x + BORDER;
        float iy = y + BORDER;
        float iw = width - BORDER * 2;
        float trackHeight = totalHeight - BORDER * 2;
        float halfHeight = (trackHeight - BORDER) / 2f;
        if (iw <= 0 || halfHeight <= 0) {
            return;
        }
        // Each half is cut from the rounded track by intersection (not by clipping)
        // so the curved corners stay anti-aliased. The 1px left between the halves
        // shows the border colour underneath: that is the divider line.
        Area track = new Area(new RoundRectangle2D.Float(ix, iy, iw, trackHeight, trackHeight, trackHeight));
        drawJoinedHalf(g, track, ix, iy, iw, halfHeight, topRatio, topFill);
        drawJoinedHalf(g, track, ix, iy + halfHeight + BORDER, iw, halfHeight, bottomRatio, bottomFill);
    }

    private void drawJoinedHalf(Graphics2D g, Area track, float x, float y, float w, float h,
            double ratio, Color fill) {
        g.setColor(config.prayerBarBackgroundColor());
        g.fill(intersect(track, new Rectangle2D.Float(x, y, w, h)));

        float fillWidth = (float) (w * ratio);
        if (fillWidth <= 0.01f) {
            return;
        }
        Color top = blend(fill, Color.WHITE, 0.28);
        Color bottom = blend(fill, Color.BLACK, 0.22);
        g.setPaint(new GradientPaint(0, y, top, 0, y + h, bottom));
        g.fill(intersect(track, new Rectangle2D.Float(x, y, fillWidth, h)));

        // Soft gloss along the upper third of the fill.
        float glossHeight = Math.max(1f, h / 3f);
        g.setPaint(new GradientPaint(0, y, new Color(255, 255, 255, 90),
                0, y + glossHeight, new Color(255, 255, 255, 0)));
        g.fill(intersect(track, new Rectangle2D.Float(x, y, fillWidth, glossHeight)));
    }

    private static Area intersect(Area shape, Shape cut) {
        Area result = new Area(shape);
        result.intersect(new Area(cut));
        return result;
    }

    // The same anchor the core Prayer plugin uses: just above the player's model,
    // which lands the top of the prayer bar directly under the overhead health bar.
    private Point barAnchor(Player local) {
        LocalPoint coord = local.getLocalLocation();
        if (coord == null) {
            return null;
        }
        int plane = local.getWorldView().getPlane();
        int actorHeight = local.getLogicalHeight() + local.getAnimationHeightOffset() + HEAD_CLEARANCE;
        int groundZ = Perspective.getFootprintTileHeight(client, coord, plane, local.getFootprintSize());
        return Perspective.localToCanvas(client, local.getWorldView().getId(),
                coord.getX(), coord.getY(), groundZ - actorHeight);
    }

    @SuppressWarnings("deprecation") // isPrayerActive(Prayer) is the stable active-prayer check in this pinned version
    private boolean anyPrayerActive() {
        for (Prayer prayer : Prayer.values()) {
            if (client.isPrayerActive(prayer)) {
                return true;
            }
        }
        return false;
    }

    // Linear mix of a toward b by t (0..1), keeping a's alpha.
    private static Color blend(Color a, Color b, double t) {
        int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
        int gr = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return new Color(r, gr, bl, a.getAlpha());
    }

    // A bar's drawn fill ratio, eased toward its live value with a frame-rate
    // independent exponential approach; snaps on the first frame after a reset.
    private static final class EasedValue {
        private double value = Double.NaN;
        private long lastNanos;

        double update(double target) {
            long now = System.nanoTime();
            if (Double.isNaN(value)) {
                value = target;
            } else {
                double dtMillis = (now - lastNanos) / 1_000_000.0;
                double k = 1.0 - Math.exp(-Math.max(0.0, dtMillis) / EASE_MILLIS);
                value += (target - value) * k;
                if (Math.abs(target - value) < 0.001) {
                    value = target;
                }
            }
            lastNanos = now;
            return value;
        }

        void reset() {
            value = Double.NaN;
        }
    }
}
