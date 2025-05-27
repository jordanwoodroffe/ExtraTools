package com.example;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.GraphicsObject;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
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
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;

@Slf4j
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
    private static final int ANIMATION_MELEE = 12146;
    private static final int ANIMATION_ORB_ATTACK = 12146; // Same as melee
    private static final int ANIMATION_MAGE = 12144;

    // Yama graphic IDs
    private static final int GRAPHIC_MAGE = 3246;
    private static final int GRAPHIC_RANGE = 3243;
    private static final int GRAPHIC_GLYPH_ATTACK = 3253;

    // Yama NPC ID
    private static final int YAMA_ID = 14176;

    // Attack cycle constants
    private static final int ATTACK_CYCLE_TICKS = 8;

    private static final int GRAPHIC_STRAIGHT_BOULDER = 3262;

    // Color for boulder safe tile highlights
    private static final Color BOULDER_SAFE_TILE_COLOR = new Color(0, 128, 128, 100); // Soft teal

    // Track boulder attack safe tiles
    private Map<WorldPoint, Integer> boulderSafeTiles = new HashMap<>();

    // Track last boulder attack to prevent duplicate logging
    private int lastBoulderAttackTick = -1;

    // Track last logged animation for each Yama to prevent duplicate logging
    private Map<Integer, Integer> lastLoggedAnimations = new HashMap<>();

    // Track attack timers for each Yama (NPC index -> ticks until next attack)
    private Map<Integer, Integer> yamaAttackTimers = new HashMap<>();

    // Track which timers were just initialized this tick to prevent immediate
    // countdown
    private Set<Integer> newlyInitializedTimers = new HashSet<>();

    // Track attack cooldowns to prevent multiple timer resets from duplicate
    // animations
    // Maps NPC index to the tick when the cooldown expires
    private Map<Integer, Integer> attackCooldowns = new HashMap<>();

    // Cooldown duration in ticks after detecting an attack
    private static final int ATTACK_COOLDOWN_TICKS = 6;

    @Override
    protected void startUp() throws Exception {
        yamaPhases.clear();
        specialObjectHighlights.clear();
        boulderSafeTiles.clear();
        lastLoggedAnimations.clear();
        yamaAttackTimers.clear();
        newlyInitializedTimers.clear();
        attackCooldowns.clear();
        overlayManager.add(overlay);
    }

    @Override
    protected void shutDown() throws Exception {
        yamaPhases.clear();
        specialObjectHighlights.clear();
        boulderSafeTiles.clear();
        lastLoggedAnimations.clear();
        yamaAttackTimers.clear();
        newlyInitializedTimers.clear();
        attackCooldowns.clear();
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
    public void onGraphicsObjectCreated(GraphicsObjectCreated event) {
        GraphicsObject graphicsObject = event.getGraphicsObject();
        int id = graphicsObject.getId();

        // Both diagonal and straight boulder attacks use the same graphic ID: 3262
        if (id != GRAPHIC_STRAIGHT_BOULDER) {
            return;
        }

        // Prevent duplicate logging within the same tick
        int currentTick = client.getTickCount();
        if (currentTick == lastBoulderAttackTick) {
            return;
        }
        lastBoulderAttackTick = currentTick;

        // Get player position and graphics object position
        LocalPoint playerLocal = client.getLocalPlayer().getLocalLocation();
        WorldPoint playerWorld = WorldPoint.fromLocal(client, playerLocal);

        LocalPoint objectLocal = new LocalPoint(graphicsObject.getLocation().getX(),
                graphicsObject.getLocation().getY());
        WorldPoint objectWorld = WorldPoint.fromLocal(client, objectLocal);

        if (playerWorld == null || objectWorld == null) {
            return;
        }

        // Calculate direction
        int dx = objectWorld.getX() - playerWorld.getX();
        int dy = objectWorld.getY() - playerWorld.getY();

        // Log boulder position (now only once per attack)
        log.info("Boulder position: dx=" + dx + ", dy=" + dy);

        // Clear any existing boulder safe tiles to prevent overlap issues
        boulderSafeTiles.clear();

        // Determine safe tiles based on relative position
        List<WorldPoint> safeTiles = new ArrayList<>();

        // STRAIGHT LINE ATTACK: Boulder is directly east/west/north/south of player
        if ((Math.abs(dx) > 0 && dy == 0) || (dx == 0 && Math.abs(dy) > 0)) {
            log.info("Straight line boulder attack detected");

            // Boulder is east or west of player
            if (Math.abs(dx) > 0 && dy == 0) {
                // Highlight north and south tiles
                safeTiles.add(new WorldPoint(playerWorld.getX(), playerWorld.getY() + 1, playerWorld.getPlane()));
                safeTiles.add(new WorldPoint(playerWorld.getX(), playerWorld.getY() - 1, playerWorld.getPlane()));
                log.info("Boulder is " + (dx > 0 ? "east" : "west") + " of player, highlighting north/south tiles");
            }
            // Boulder is north or south of player
            else {
                // Highlight east and west tiles
                safeTiles.add(new WorldPoint(playerWorld.getX() + 1, playerWorld.getY(), playerWorld.getPlane()));
                safeTiles.add(new WorldPoint(playerWorld.getX() - 1, playerWorld.getY(), playerWorld.getPlane()));
                log.info("Boulder is " + (dy > 0 ? "north" : "south") + " of player, highlighting east/west tiles");
            }
        }
        // DIAGONAL ATTACK: Boulder is diagonally positioned from player
        else if (dx != 0 && dy != 0) {
            log.info("Diagonal boulder attack detected");

            // Boulder is SE of player
            if (dx > 0 && dy < 0) {
                // Highlight NE and SW tiles
                safeTiles.add(new WorldPoint(playerWorld.getX() + 1, playerWorld.getY() + 1, playerWorld.getPlane())); // NE
                safeTiles.add(new WorldPoint(playerWorld.getX() - 1, playerWorld.getY() - 1, playerWorld.getPlane())); // SW
                log.info("Boulder is southeast of player, highlighting NE/SW tiles");
            }
            // Boulder is SW of player
            else if (dx < 0 && dy < 0) {
                // Highlight NW and SE tiles
                safeTiles.add(new WorldPoint(playerWorld.getX() - 1, playerWorld.getY() + 1, playerWorld.getPlane())); // NW
                safeTiles.add(new WorldPoint(playerWorld.getX() + 1, playerWorld.getY() - 1, playerWorld.getPlane())); // SE
                log.info("Boulder is southwest of player, highlighting NW/SE tiles");
            }
            // Boulder is NE of player
            else if (dx > 0 && dy > 0) {
                // Highlight SE and NW tiles
                safeTiles.add(new WorldPoint(playerWorld.getX() + 1, playerWorld.getY() - 1, playerWorld.getPlane())); // SE
                safeTiles.add(new WorldPoint(playerWorld.getX() - 1, playerWorld.getY() + 1, playerWorld.getPlane())); // NW
                log.info("Boulder is northeast of player, highlighting SE/NW tiles");
            }
            // Boulder is NW of player
            else if (dx < 0 && dy > 0) {
                // Highlight NE and SW tiles
                safeTiles.add(new WorldPoint(playerWorld.getX() + 1, playerWorld.getY() + 1, playerWorld.getPlane())); // NE
                safeTiles.add(new WorldPoint(playerWorld.getX() - 1, playerWorld.getY() - 1, playerWorld.getPlane())); // SW
                log.info("Boulder is northwest of player, highlighting NE/SW tiles");
            }
        }

        // Add safe tiles to tracking map (with 3 ticks duration)
        for (WorldPoint safeTile : safeTiles) {
            boulderSafeTiles.put(safeTile, 3);
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

                // Log animation IDs for Yama only when they change
                int animationId = npc.getAnimation();
                if (animationId != -1) {
                    Integer lastLogged = lastLoggedAnimations.get(index);
                    if (lastLogged == null || lastLogged != animationId) {
                        log.info("Yama (index " + index + ") animation: animationId=" + animationId);
                        lastLoggedAnimations.put(index, animationId);
                    }

                    // Reset timer to 8 ticks when Yama attacks, but only if not in cooldown
                    // Check for attack animation regardless of whether it changed
                    if (isAttackAnimation(animationId)) {
                        int currentTick = client.getTickCount();
                        Integer cooldownExpiry = attackCooldowns.get(index);

                        // Only reset timer if we're not in cooldown or cooldown has expired
                        if (cooldownExpiry == null || currentTick >= cooldownExpiry) {
                            yamaAttackTimers.put(index, ATTACK_CYCLE_TICKS);
                            newlyInitializedTimers.add(index);
                            // Set cooldown to expire in 6 ticks
                            attackCooldowns.put(index, currentTick + ATTACK_COOLDOWN_TICKS);
                            log.info("Yama (index " + index + ") attack detected, timer reset to "
                                    + ATTACK_CYCLE_TICKS +
                                    " (cooldown until tick " + (currentTick + ATTACK_COOLDOWN_TICKS) + ")");
                        } else {
                            log.info("Yama (index " + index + ") attack ignored - in cooldown until tick "
                                    + cooldownExpiry);
                        }
                    }

                    // Update phase based on animation if available (handle melee attacks here)
                    if (animationId == ANIMATION_MELEE) {
                        yamaPhases.put(index, YamaPhase.MELEE);
                    }
                }

                // Initialize with UNKNOWN if we haven't seen this Yama before
                if (!yamaPhases.containsKey(index)) {
                    yamaPhases.put(index, YamaPhase.UNKNOWN);
                }

                // Initialize timer if not present (start at 8, not 7)
                if (!yamaAttackTimers.containsKey(index)) {
                    yamaAttackTimers.put(index, ATTACK_CYCLE_TICKS);
                    newlyInitializedTimers.add(index);
                    log.info("Yama (index " + index + ") timer initialized to " + ATTACK_CYCLE_TICKS);
                }
            }
        }

        // If no Yama exists, clear all highlights and phases
        if (!yamaPresent) {
            yamaPhases.clear();
            specialObjectHighlights.clear();
            yamaAttackTimers.clear();
            attackCooldowns.clear();
            return;
        }

        // Update attack timers for all Yamas
        for (Map.Entry<Integer, Integer> entry : yamaAttackTimers.entrySet()) {
            int yamaIndex = entry.getKey();
            int currentTicks = entry.getValue();

            // Skip countdown for newly initialized timers this tick
            if (newlyInitializedTimers.contains(yamaIndex)) {
                log.info("Yama (index " + yamaIndex + ") timer just initialized, showing: " + currentTicks + " ticks");
                continue;
            }

            // Only decrement if the timer is greater than 1
            if (currentTicks > 1) {
                // Countdown the timer
                int newTicks = currentTicks - 1;
                yamaAttackTimers.put(yamaIndex, newTicks);
                log.info("Yama (index " + yamaIndex + ") attack timer: " + newTicks + " ticks remaining");
            } else {
                // Timer at 1, next tick should be an attack
                log.info("Yama (index " + yamaIndex + ") attack timer: 1 tick remaining - attack incoming!");
                // Keep timer at 1 until attack is detected
            }
        }

        // Clear the newly initialized timers set for next tick
        newlyInitializedTimers.clear();

        // Update special object highlights
        Iterator<Map.Entry<WorldPoint, SpecialObjectHighlight>> it = specialObjectHighlights.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WorldPoint, SpecialObjectHighlight> entry = it.next();
            if (!entry.getValue().updateTicks()) {
                it.remove();
            }
        }

        // Update boulder safe tile highlights
        Iterator<Map.Entry<WorldPoint, Integer>> boulderIterator = boulderSafeTiles.entrySet().iterator();
        while (boulderIterator.hasNext()) {
            Map.Entry<WorldPoint, Integer> entry = boulderIterator.next();
            int ticksLeft = entry.getValue() - 1;
            if (ticksLeft <= 0) {
                boulderIterator.remove();
            } else {
                entry.setValue(ticksLeft);
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

        // Log every graphic change event, including when graphics are cleared
        log.info("Yama (index " + index + ") attack graphic: graphicId=" + graphicId);

        // Reset timer when graphic-based attacks are detected, but only if not in
        // cooldown
        if (isAttackGraphic(graphicId)) {
            int currentTick = client.getTickCount();
            Integer cooldownExpiry = attackCooldowns.get(index);

            // Only reset timer if we're not in cooldown or cooldown has expired
            if (cooldownExpiry == null || currentTick >= cooldownExpiry) {
                yamaAttackTimers.put(index, ATTACK_CYCLE_TICKS);
                newlyInitializedTimers.add(index);
                // Set cooldown to expire in 6 ticks
                attackCooldowns.put(index, currentTick + ATTACK_COOLDOWN_TICKS);
                log.info("Yama (index " + index + ") graphic attack detected, timer reset to " + ATTACK_CYCLE_TICKS +
                        " (cooldown until tick " + (currentTick + ATTACK_COOLDOWN_TICKS) + ")");
            } else {
                log.info(
                        "Yama (index " + index + ") graphic attack ignored - in cooldown until tick " + cooldownExpiry);
            }
        }

        if (graphicId == GRAPHIC_MAGE) {
            yamaPhases.put(index, YamaPhase.MAGE);
        } else if (graphicId == GRAPHIC_RANGE) {
            yamaPhases.put(index, YamaPhase.RANGE);
        } else if (graphicId == GRAPHIC_GLYPH_ATTACK) {
            yamaPhases.put(index, YamaPhase.FIRE_SPECIAL); // Assuming glyph is a fire special attack
        }
    }

    // Helper method to check if an animation ID represents an attack
    private boolean isAttackAnimation(int animationId) {
        return animationId == ANIMATION_MELEE ||
                animationId == ANIMATION_ORB_ATTACK ||
                animationId == ANIMATION_MAGE;
    }

    // Helper method to check if a graphic ID represents an attack
    private boolean isAttackGraphic(int graphicId) {
        return graphicId == GRAPHIC_MAGE ||
                graphicId == GRAPHIC_RANGE ||
                graphicId == GRAPHIC_GLYPH_ATTACK;
    }

    // Get boulder safe tiles
    public Map<WorldPoint, Integer> getBoulderSafeTiles() {
        return boulderSafeTiles;
    }

    private void findSpecialObjects() {
        // Special attack object highlighting is disabled in this simplified version
        // that only tracks melee, mage, and range attacks
    }

    // Get the current phase for a specific Yama
    public YamaPhase getYamaPhase(int npcIndex) {
        return yamaPhases.getOrDefault(npcIndex, YamaPhase.UNKNOWN);
    }

    // Get the attack timer for a specific Yama
    public int getYamaAttackTimer(int npcIndex) {
        return yamaAttackTimers.getOrDefault(npcIndex, ATTACK_CYCLE_TICKS);
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
        MELEE(new Color(240, 100, 100, 120)), // Soft red
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

                // Display attack timer over the center of Yama
                int attackTimer = plugin.getYamaAttackTimer(npc.getIndex());
                if (attackTimer > 0) {
                    // Get center point of the NPC for text positioning
                    LocalPoint center = npc.getLocalLocation();
                    if (center != null) {
                        net.runelite.api.Point textPoint = Perspective.localToCanvas(client, center, 0);
                        if (textPoint != null) {
                            // Set text properties - bold teal 32px font
                            String timerText = String.valueOf(attackTimer);
                            java.awt.Font font = new java.awt.Font("Arial", java.awt.Font.BOLD, 32);
                            graphics.setFont(font);

                            java.awt.FontMetrics metrics = graphics.getFontMetrics();
                            int textWidth = metrics.stringWidth(timerText);
                            int textHeight = metrics.getHeight();
                            int textX = textPoint.getX() - (textWidth / 2);
                            int textY = textPoint.getY() + (textHeight / 4); // Center vertically

                            // Draw outline for visibility
                            graphics.setColor(Color.BLACK);
                            graphics.drawString(timerText, textX - 2, textY - 2);
                            graphics.drawString(timerText, textX + 2, textY - 2);
                            graphics.drawString(timerText, textX - 2, textY + 2);
                            graphics.drawString(timerText, textX + 2, textY + 2);
                            graphics.drawString(timerText, textX - 2, textY);
                            graphics.drawString(timerText, textX + 2, textY);
                            graphics.drawString(timerText, textX, textY - 2);
                            graphics.drawString(timerText, textX, textY + 2);

                            // Draw main text in bold teal
                            Color tealColor = new Color(0, 128, 128); // Teal color
                            graphics.setColor(tealColor);
                            graphics.drawString(timerText, textX, textY);
                        }
                    }
                }
            }

            // Render fixed tile highlights
            renderFixedTileHighlights(graphics);

            // Render special object highlights
            renderSpecialObjectHighlights(graphics);

            // Render boulder safe tiles
            renderBoulderSafeTiles(graphics);

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

        // Method to render boulder safe tiles
        private void renderBoulderSafeTiles(Graphics2D graphics) {
            Map<WorldPoint, Integer> safeTiles = plugin.getBoulderSafeTiles();

            for (WorldPoint worldPoint : safeTiles.keySet()) {
                // Skip if on different plane
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
                graphics.setColor(BOULDER_SAFE_TILE_COLOR);
                graphics.fill(tilePoly);

                // Draw tile border with more solid color
                graphics.setColor(new Color(
                        BOULDER_SAFE_TILE_COLOR.getRed(),
                        BOULDER_SAFE_TILE_COLOR.getGreen(),
                        BOULDER_SAFE_TILE_COLOR.getBlue(),
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