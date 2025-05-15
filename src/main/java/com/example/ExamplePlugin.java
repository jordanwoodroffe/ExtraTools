package com.example;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.GameObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.api.Perspective;

import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Polygon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.Set;

@PluginDescriptor(name = "Yama Helper", description = "Highlights Yama based on their current combat style")
public class ExamplePlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private YamaOverlay overlay;

    // Track current Yama phases by NPC index
    private Map<Integer, YamaPhase> yamaPhases = new HashMap<>();

    // Fixed tile positions to highlight
    private static final List<TileHighlight> FIXED_TILE_HIGHLIGHTS = new ArrayList<>();

    // Special object highlights for special attacks
    private Map<WorldPoint, SpecialObjectHighlight> specialObjectHighlights = new HashMap<>();

    // Yama animation IDs
    private static final int ANIMATION_MAGE_RANGE = 12144;
    private static final int ANIMATION_SPECIAL = 12145;

    // Yama graphic IDs
    private static final int GRAPHIC_MAGE = 3246;
    private static final int GRAPHIC_RANGE = 3243;
    private static final int GRAPHIC_FIRE_SPECIAL = 3253;
    private static final int GRAPHIC_SHADOW_SPECIAL = 3256;

    // Object IDs to highlight during special attacks
    private static final int OBJECT_FIRE = 56336;
    private static final int OBJECT_SHADOW = 56335;

    private static final int GRAPHIC_FIRE_SPECIAL_PHASE2 = 3270; // Fire ball
    private static final int GRAPHIC_SHADOW_SPECIAL_PHASE2 = 3259; // Shadow ball

    // Yama NPC ID
    private static final int YAMA_ID = 14176;

    @Override
    protected void startUp() throws Exception {
        yamaPhases.clear();
        specialObjectHighlights.clear();
        overlayManager.add(overlay);
    }

    @Override
    protected void shutDown() throws Exception {
        yamaPhases.clear();
        specialObjectHighlights.clear();
        overlayManager.remove(overlay);
    }

    // Static class to represent fixed tile highlights
    static class TileHighlight {
        private final WorldPoint worldPoint;
        private final Color color;

        public TileHighlight(WorldPoint worldPoint, Color color) {
            this.worldPoint = worldPoint;
            this.color = color;
        }

        public WorldPoint getWorldPoint() {
            return worldPoint;
        }

        public Color getColor() {
            return color;
        }
    }

    // Class to track special object highlights
    static class SpecialObjectHighlight {
        private final WorldPoint center;
        private final YamaPhase type;
        private int ticksRemaining;

        public SpecialObjectHighlight(WorldPoint center, YamaPhase type) {
            this.center = center;
            this.type = type;
            this.ticksRemaining = 2;
        }

        public WorldPoint getCenter() {
            return center;
        }

        public YamaPhase getType() {
            return type;
        }

        public boolean updateTicks() {
            return --ticksRemaining > 0;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (client.getGameState().getState() < 30) {
            return;
        }

        boolean yamaPresent = false;
        // Track all visible Yamas in the scene
        for (NPC npc : client.getNpcs()) {
            if (npc != null && npc.getId() == YAMA_ID) {
                yamaPresent = true;
                int index = npc.getIndex();

                // Initialize with UNKNOWN if we haven't seen this Yama before
                if (!yamaPhases.containsKey(index)) {
                    yamaPhases.put(index, YamaPhase.UNKNOWN);
                }

                // Update phase based on animation if available
                int animationId = npc.getAnimation();
                if (animationId != -1) {
                    updateYamaPhaseFromAnimation(index, animationId);
                }
            }
        }

        // If no Yama exists, clear all highlights and phases
        if (!yamaPresent) {
            yamaPhases.clear();
            specialObjectHighlights.clear();
            return;
        }

        // Update special object highlights
        Iterator<Map.Entry<WorldPoint, SpecialObjectHighlight>> it = specialObjectHighlights.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WorldPoint, SpecialObjectHighlight> entry = it.next();
            if (!entry.getValue().updateTicks()) {
                it.remove();
            }
        }

        // Find and highlight special objects when needed
        findSpecialObjects();
    }

    @Subscribe
    public void onGraphicChanged(GraphicChanged event) {
        Actor actor = event.getActor();

        if (!(actor instanceof NPC)) {
            return;
        }

        NPC npc = (NPC) actor;
        if (npc.getId() != YAMA_ID) {
            return;
        }

        int index = npc.getIndex();
        int graphicId = npc.getGraphic();

        if (graphicId == GRAPHIC_MAGE) {
            yamaPhases.put(index, YamaPhase.MAGE);
        } else if (graphicId == GRAPHIC_RANGE) {
            yamaPhases.put(index, YamaPhase.RANGE);
        } else if (graphicId == GRAPHIC_FIRE_SPECIAL || graphicId == GRAPHIC_FIRE_SPECIAL_PHASE2) {
            yamaPhases.put(index, YamaPhase.FIRE_SPECIAL);
        } else if (graphicId == GRAPHIC_SHADOW_SPECIAL || graphicId == GRAPHIC_SHADOW_SPECIAL_PHASE2) {
            yamaPhases.put(index, YamaPhase.SHADOW_SPECIAL);
        }
    }

    private void updateYamaPhaseFromAnimation(int npcIndex, int animationId) {
        if (animationId == ANIMATION_MAGE_RANGE) {
            // Animation is shared between mage and range
            // We don't update the phase here as we'll set it in onGraphicChanged
        } else if (animationId == ANIMATION_SPECIAL) {
            // This is a special attack animation
            // Phase will be set in onGraphicChanged based on the graphic ID
        }
    }

    private void findSpecialObjects() {
        // Check if any Yama is doing a special attack
        boolean fireSpecialActive = false;
        boolean shadowSpecialActive = false;

        for (YamaPhase phase : yamaPhases.values()) {
            if (phase == YamaPhase.FIRE_SPECIAL) {
                fireSpecialActive = true;
            } else if (phase == YamaPhase.SHADOW_SPECIAL) {
                shadowSpecialActive = true;
            }
        }

        // If neither special is active, don't add new highlights
        if (!fireSpecialActive && !shadowSpecialActive) {
            return;
        }

        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();

        for (int z = 0; z < tiles.length; z++) {
            for (int x = 0; x < tiles[z].length; x++) {
                for (int y = 0; y < tiles[z][x].length; y++) {
                    Tile tile = tiles[z][x][y];
                    if (tile == null) {
                        continue;
                    }

                    GameObject[] gameObjects = tile.getGameObjects();
                    if (gameObjects == null) {
                        continue;
                    }

                    for (GameObject gameObject : gameObjects) {
                        if (gameObject == null) {
                            continue;
                        }

                        int objectId = gameObject.getId();
                        WorldPoint worldPoint = gameObject.getWorldLocation();

                        if (fireSpecialActive && objectId == OBJECT_FIRE) {
                            specialObjectHighlights.put(worldPoint,
                                    new SpecialObjectHighlight(worldPoint, YamaPhase.FIRE_SPECIAL));
                        } else if (shadowSpecialActive && objectId == OBJECT_SHADOW) {
                            specialObjectHighlights.put(worldPoint,
                                    new SpecialObjectHighlight(worldPoint, YamaPhase.SHADOW_SPECIAL));
                        }
                    }
                }
            }
        }
    }

    // Get the current phase for a specific Yama
    public YamaPhase getYamaPhase(int npcIndex) {
        return yamaPhases.getOrDefault(npcIndex, YamaPhase.UNKNOWN);
    }

    // Get special object highlights
    public Map<WorldPoint, SpecialObjectHighlight> getSpecialObjectHighlights() {
        return specialObjectHighlights;
    }

    // Yama combat phases
    public enum YamaPhase {
        MAGE(new Color(100, 149, 237)), // Soft blue
        RANGE(new Color(144, 238, 144)), // Soft green
        FIRE_SPECIAL(new Color(255, 200, 100, 50)), // Lighter soft orange, more transparent
        SHADOW_SPECIAL(new Color(180, 150, 240, 50)), // Lighter soft purple, more transparent
        UNKNOWN(Color.GRAY);

        private final Color color;

        YamaPhase(Color color) {
            this.color = color;
        }

        public Color getColor() {
            return color;
        }
    }

    static class YamaOverlay extends Overlay {
        private final Client client;
        private final ExamplePlugin plugin;
        private static final int YAMA_SIZE = 5; // Yama is 5x5 tiles

        @Inject
        private YamaOverlay(Client client, ExamplePlugin plugin) {
            super(plugin);
            this.client = client;
            this.plugin = plugin;
            setPosition(OverlayPosition.DYNAMIC);
            setPriority(OverlayPriority.MED);
            setLayer(OverlayLayer.ABOVE_SCENE);
        }

        @Override
        public Dimension render(Graphics2D graphics) {
            // First, render existing Yama highlights
            for (NPC npc : client.getNpcs()) {
                if (npc == null || npc.getId() != YAMA_ID) {
                    continue;
                }

                // Get the phase color for this Yama
                YamaPhase phase = plugin.getYamaPhase(npc.getIndex());
                Color tileColor = phase.getColor();

                // Get base tile location of the NPC
                LocalPoint basePoint = npc.getLocalLocation();
                if (basePoint == null) {
                    continue;
                }

                // Calculate the southwest corner of the 5x5 area
                int swX = basePoint.getX() - (Perspective.LOCAL_TILE_SIZE * (YAMA_SIZE - 1) / 2);
                int swY = basePoint.getY() - (Perspective.LOCAL_TILE_SIZE * (YAMA_SIZE - 1) / 2);

                // Calculate the northeast corner of the 5x5 area
                int neX = swX + ((YAMA_SIZE - 1) * Perspective.LOCAL_TILE_SIZE);
                int neY = swY + ((YAMA_SIZE - 1) * Perspective.LOCAL_TILE_SIZE);

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
                    continue;
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
                        tileColor.getBlue(), 50));
                graphics.fill(borderPoly);

                // Draw just the outer border with solid color
                graphics.setColor(tileColor);
                graphics.draw(borderPoly);
            }

            // Render fixed tile highlights
            renderFixedTileHighlights(graphics);

            // Render special object highlights
            renderSpecialObjectHighlights(graphics);

            return null;
        }

        // Helper method to add points from one polygon to another
        private void addPointsToPolygon(Polygon targetPoly, Polygon sourcePoly, int startIdx, int endIdx) {
            int sourcePoints = sourcePoly.npoints;
            if (startIdx >= sourcePoints || endIdx >= sourcePoints) {
                return;
            }

            targetPoly.addPoint(sourcePoly.xpoints[startIdx], sourcePoly.ypoints[startIdx]);
            targetPoly.addPoint(sourcePoly.xpoints[endIdx], sourcePoly.ypoints[endIdx]);
        }

        // Method to render the fixed tile highlights
        private void renderFixedTileHighlights(Graphics2D graphics) {
            for (TileHighlight tileHighlight : FIXED_TILE_HIGHLIGHTS) {
                WorldPoint worldPoint = tileHighlight.getWorldPoint();

                // If the tile is on a different plane, skip it
                if (worldPoint.getPlane() != client.getPlane()) {
                    continue;
                }

                // Convert world point to local point
                LocalPoint localPoint = LocalPoint.fromWorld(client, worldPoint);
                if (localPoint == null) {
                    continue;
                }

                // Get the tile polygon
                Polygon tilePoly = Perspective.getCanvasTilePoly(client, localPoint);
                if (tilePoly == null) {
                    continue;
                }

                // Draw filled tile with semi-transparent color
                graphics.setColor(tileHighlight.getColor());
                graphics.fill(tilePoly);

                // Draw tile border with solid color
                graphics.setColor(new Color(
                        tileHighlight.getColor().getRed(),
                        tileHighlight.getColor().getGreen(),
                        tileHighlight.getColor().getBlue(),
                        255));
                graphics.draw(tilePoly);
            }
        }

        private void renderSpecialObjectHighlights(Graphics2D graphics) {
            Map<WorldPoint, SpecialObjectHighlight> highlights = plugin.getSpecialObjectHighlights();

            for (SpecialObjectHighlight highlight : highlights.values()) {
                WorldPoint center = highlight.getCenter();

                // Skip if on different plane
                if (center.getPlane() != client.getPlane()) {
                    continue;
                }

                Color highlightColor = highlight.getType().getColor();
                // Create a lighter variant for surrounding tiles
                Color lighterColor = new Color(
                        Math.min(highlightColor.getRed() + 50, 255),
                        Math.min(highlightColor.getGreen() + 50, 255),
                        Math.min(highlightColor.getBlue() + 50, 255),
                        highlightColor.getAlpha());

                // Get the corner points for the 3x3 area
                WorldPoint swCorner = new WorldPoint(center.getX() - 1, center.getY() - 1, center.getPlane());
                WorldPoint seCorner = new WorldPoint(center.getX() + 1, center.getY() - 1, center.getPlane());
                WorldPoint neCorner = new WorldPoint(center.getX() + 1, center.getY() + 1, center.getPlane());
                WorldPoint nwCorner = new WorldPoint(center.getX() - 1, center.getY() + 1, center.getPlane());

                LocalPoint swLocal = LocalPoint.fromWorld(client, swCorner);
                LocalPoint seLocal = LocalPoint.fromWorld(client, seCorner);
                LocalPoint neLocal = LocalPoint.fromWorld(client, neCorner);
                LocalPoint nwLocal = LocalPoint.fromWorld(client, nwCorner);

                if (swLocal == null || seLocal == null || neLocal == null || nwLocal == null) {
                    continue;
                }

                Polygon swPoly = Perspective.getCanvasTilePoly(client, swLocal);
                Polygon sePoly = Perspective.getCanvasTilePoly(client, seLocal);
                Polygon nePoly = Perspective.getCanvasTilePoly(client, neLocal);
                Polygon nwPoly = Perspective.getCanvasTilePoly(client, nwLocal);

                if (swPoly == null || sePoly == null || nePoly == null || nwPoly == null) {
                    continue;
                }

                // Create the outer border polygon
                Polygon outerBorderPoly = new Polygon();

                // Add points to create the outer border
                // South edge (SW to SE)
                addPointsToPolygon(outerBorderPoly, swPoly, 0, 1);
                // East edge (SE to NE)
                addPointsToPolygon(outerBorderPoly, sePoly, 1, 2);
                // North edge (NE to NW)
                addPointsToPolygon(outerBorderPoly, nePoly, 2, 3);
                // West edge (NW to SW)
                addPointsToPolygon(outerBorderPoly, nwPoly, 3, 0);

                // Draw all tiles in the 3x3 area
                for (int xOffset = -1; xOffset <= 1; xOffset++) {
                    for (int yOffset = -1; yOffset <= 1; yOffset++) {
                        WorldPoint currentTile = new WorldPoint(
                                center.getX() + xOffset,
                                center.getY() + yOffset,
                                center.getPlane());

                        LocalPoint localTile = LocalPoint.fromWorld(client, currentTile);
                        if (localTile == null) {
                            continue;
                        }

                        Polygon tilePoly = Perspective.getCanvasTilePoly(client, localTile);
                        if (tilePoly == null) {
                            continue;
                        }

                        // Determine if this is the center tile
                        boolean isCenter = xOffset == 0 && yOffset == 0;

                        // Fill with appropriate color
                        graphics.setColor(isCenter ? highlightColor : lighterColor);
                        graphics.fill(tilePoly);

                        // Draw border only for center tile
                        if (isCenter) {
                            graphics.setColor(new Color(
                                    highlightColor.getRed(),
                                    highlightColor.getGreen(),
                                    highlightColor.getBlue(),
                                    255));
                            graphics.draw(tilePoly);
                        }
                    }
                }

                // Draw the outer border with solid color
                graphics.setColor(new Color(
                        highlightColor.getRed(),
                        highlightColor.getGreen(),
                        highlightColor.getBlue(),
                        255));
                graphics.draw(outerBorderPoly);
            }
        }
    }
}