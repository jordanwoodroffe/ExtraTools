package com.pvmkits.core;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Repaints a chosen set of floor tiles one flat colour and puts them back
 * afterwards - the machinery behind a "Simplify Arena" setting, kept separate
 * from how each fight decides which tiles its arena covers.
 *
 * <p>Same approach as the Doom of Mokhaiotl's simplifier, which has the full
 * reasoning: the client's own {@link SceneTilePaint} and {@link SceneTileModel}
 * objects are written through in place (a third-party implementation throws
 * ClassCastException), the originals are saved by value, shared colour arrays
 * are recoloured once, and every GPU zone is invalidated so the GPU and 117 HD
 * renderers re-upload the floor.
 */
@Slf4j
public class FloorRepainter {

    // Sentinel for "no texture": the client falls back to the vertex colours.
    private static final int NO_TEXTURE = -1;

    // GPU zones are 8x8 tiles over the extended (184) scene.
    private static final int ZONE_SIZE = 8;
    private static final int ZONE_GRID = Constants.EXTENDED_SCENE_SIZE / ZONE_SIZE;

    private final Client client;
    private final String label;

    private boolean applied;
    private Scene appliedScene;
    private int appliedHsl;

    private final List<SavedPaint> savedPaints = new ArrayList<>();
    private final List<SavedModel> savedModels = new ArrayList<>();

    public FloorRepainter(Client client, String label) {
        this.client = client;
        this.label = label;
    }

    public boolean isApplied() {
        return applied;
    }

    // True if the floor is currently painted in this colour on this scene, so a
    // caller can skip repainting when nothing has changed.
    public boolean isAppliedTo(Scene scene, Color color) {
        return applied && scene == appliedScene && packHsl(color) == appliedHsl;
    }

    /**
     * Paints every tile matching {@code inArena} the given colour, saving what
     * was there. Restore before calling this again.
     */
    public void apply(Scene scene, Predicate<Tile> inArena, Color color) {
        Tile[][][] tiles = scene.getExtendedTiles();
        if (tiles == null) {
            tiles = scene.getTiles();
        }
        if (tiles == null) {
            return;
        }
        int hsl = packHsl(color);
        Map<int[], Boolean> seenArrays = new IdentityHashMap<>();
        int paintCount = 0;
        int modelCount = 0;
        for (Tile[][] plane : tiles) {
            if (plane == null) {
                continue;
            }
            for (Tile[] column : plane) {
                if (column == null) {
                    continue;
                }
                for (Tile tile : column) {
                    if (tile == null || !inArena.test(tile)) {
                        continue;
                    }
                    if (flattenPaint(tile, hsl)) {
                        paintCount++;
                    }
                    if (flattenModel(tile, seenArrays, hsl)) {
                        modelCount++;
                    }
                }
            }
        }
        applied = true;
        appliedScene = scene;
        appliedHsl = hsl;
        invalidateZones(scene);
        log.info("[{}] Simplify Arena applied: {} tile paints and {} tile model arrays repainted "
                + "to rgb({},{},{})", label, paintCount, modelCount,
                color.getRed(), color.getGreen(), color.getBlue());
    }

    // Puts the saved floor back, if the scene it came from is still the live one.
    public void restore() {
        if (!applied) {
            return;
        }
        Scene scene = currentScene();
        if (scene == appliedScene) {
            for (SavedPaint saved : savedPaints) {
                saved.restore();
            }
            for (SavedModel saved : savedModels) {
                saved.restore();
            }
            invalidateZones(scene);
        }
        log.info("[{}] Simplify Arena restored {} tile paints and {} tile model arrays",
                label, savedPaints.size(), savedModels.size());
        discard();
    }

    // Forgets the saved floor without writing it back, for a scene rebuild (the
    // tiles it came from are gone) or after an error part-way through.
    public void discard() {
        applied = false;
        appliedScene = null;
        savedPaints.clear();
        savedModels.clear();
    }

    private Scene currentScene() {
        WorldView wv = client.getTopLevelWorldView();
        return wv == null ? null : wv.getScene();
    }

    private boolean flattenPaint(Tile tile, int hsl) {
        SceneTilePaint paint = tile.getSceneTilePaint();
        if (paint == null) {
            return false;
        }
        savedPaints.add(new SavedPaint(paint));
        paint.setSwColor(hsl);
        paint.setSeColor(hsl);
        paint.setNwColor(hsl);
        paint.setNeColor(hsl);
        paint.setTexture(NO_TEXTURE);
        return true;
    }

    private boolean flattenModel(Tile tile, Map<int[], Boolean> seen, int hsl) {
        SceneTileModel model = tile.getSceneTileModel();
        if (model == null) {
            return false;
        }
        boolean touched = false;
        touched |= fill(model.getTriangleColorA(), seen, hsl);
        touched |= fill(model.getTriangleColorB(), seen, hsl);
        touched |= fill(model.getTriangleColorC(), seen, hsl);
        touched |= fill(model.getTriangleTextureId(), seen, NO_TEXTURE);
        return touched;
    }

    private boolean fill(int[] array, Map<int[], Boolean> seen, int value) {
        if (array == null || seen.put(array, Boolean.TRUE) != null) {
            return false;
        }
        savedModels.add(new SavedModel(array));
        Arrays.fill(array, value);
        return true;
    }

    private void invalidateZones(Scene scene) {
        DrawCallbacks callbacks = client.getDrawCallbacks();
        if (callbacks == null) {
            return;
        }
        for (int zoneX = 0; zoneX < ZONE_GRID; zoneX++) {
            for (int zoneY = 0; zoneY < ZONE_GRID; zoneY++) {
                callbacks.invalidateZone(scene, zoneX, zoneY);
            }
        }
    }

    /**
     * RGB to the client's packed HSL short ({@code hhhhhhssslllllll}), the
     * format every tile colour is stored in.
     */
    public static int packHsl(Color color) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        int h = clamp(Math.round(hsb[0] * 63f), 0, 63);
        int s = clamp(Math.round(hsb[1] * 7f), 0, 7);
        int l = clamp(Math.round(hsb[2] * 127f), 0, 127);
        return (h << 10) | (s << 7) | l;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class SavedPaint {
        private final SceneTilePaint paint;
        private final int sw;
        private final int se;
        private final int nw;
        private final int ne;
        private final int texture;

        SavedPaint(SceneTilePaint paint) {
            this.paint = paint;
            this.sw = paint.getSwColor();
            this.se = paint.getSeColor();
            this.nw = paint.getNwColor();
            this.ne = paint.getNeColor();
            this.texture = paint.getTexture();
        }

        void restore() {
            paint.setSwColor(sw);
            paint.setSeColor(se);
            paint.setNwColor(nw);
            paint.setNeColor(ne);
            paint.setTexture(texture);
        }
    }

    private static final class SavedModel {
        private final int[] array;
        private final int[] original;

        SavedModel(int[] array) {
            this.array = array;
            this.original = array.clone();
        }

        void restore() {
            System.arraycopy(original, 0, array, 0, original.length);
        }
    }
}
