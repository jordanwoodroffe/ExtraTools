package com.example;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.api.Perspective;

import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Polygon;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;

import java.util.Map;
import java.util.Set;

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
    private static final int ENRAGE_ATTACK_CYCLE_TICKS = 7;

    // Graphic for phase transition
    private static final int GRAPHIC_PHASE_TRANSITION = 3276;

    // Track last logged animation for each Yama to prevent duplicate logging
    private Map<Integer, Integer> lastLoggedAnimations = new HashMap<>();

    // Track attack timers for each Yama (NPC index -> ticks until next attack)
    private Map<Integer, Integer> yamaAttackTimers = new HashMap<>();

    // Track phase transition graphic occurrences for each Yama (NPC index -> count)
    private Map<Integer, Integer> phaseTransitionCounts = new HashMap<>();

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
        lastLoggedAnimations.clear();
        yamaAttackTimers.clear();
        phaseTransitionCounts.clear();
        newlyInitializedTimers.clear();
        attackCooldowns.clear();
        overlayManager.add(overlay);
    }

    @Override
    protected void shutDown() throws Exception {
        yamaPhases.clear();

        lastLoggedAnimations.clear();
        yamaAttackTimers.clear();
        phaseTransitionCounts.clear();
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

    @Subscribe
    public void onGameTick(GameTick event) {
        if (client.getGameState().getState() < 30) {
            return;
        }

        boolean yamaPresent = false;
        // Track all visible Yamas in the scene
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
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

                    // Reset timer to appropriate ticks when Yama attacks, but only if not in
                    // cooldown
                    // Check for attack animation regardless of whether it changed
                    if (isAttackAnimation(animationId)) {
                        int currentTick = client.getTickCount();
                        Integer cooldownExpiry = attackCooldowns.get(index);

                        // Only reset timer if we're not in cooldown or cooldown has expired
                        if (cooldownExpiry == null || currentTick >= cooldownExpiry) {
                            int attackTicks = getAttackCycleTicks(index);
                            yamaAttackTimers.put(index, attackTicks);
                            newlyInitializedTimers.add(index);
                            // Set cooldown to expire in 6 ticks
                            attackCooldowns.put(index, currentTick + ATTACK_COOLDOWN_TICKS);
                            log.info("Yama (index " + index + ") attack detected, timer reset to " + attackTicks +
                                    " (cooldown until tick " + (currentTick + ATTACK_COOLDOWN_TICKS) + ")" +
                                    (isYamaInEnragePhase(index) ? " [ENRAGE PHASE]" : ""));
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

                // Initialize timer if not present (start with appropriate timing)
                if (!yamaAttackTimers.containsKey(index)) {
                    int attackTicks = getAttackCycleTicks(index);
                    yamaAttackTimers.put(index, attackTicks);
                    newlyInitializedTimers.add(index);
                    log.info("Yama (index " + index + ") timer initialized to " + attackTicks +
                            (isYamaInEnragePhase(index) ? " [ENRAGE PHASE]" : ""));
                }
            }
        }

        // If no Yama exists, clear all highlights and phases
        if (!yamaPresent) {
            yamaPhases.clear();
            yamaAttackTimers.clear();
            phaseTransitionCounts.clear();
            attackCooldowns.clear();
            return;
        }

        // Update attack timers for all Yamas
        for (Map.Entry<Integer, Integer> entry : yamaAttackTimers.entrySet()) {
            int yamaIndex = entry.getKey();
            int currentTicks = entry.getValue();

            // Skip countdown for newly initialized timers this tick
            if (newlyInitializedTimers.contains(yamaIndex)) {

                continue;
            }

            // Only decrement if the timer is greater than 1
            if (currentTicks > 1) {
                // Countdown the timer
                int newTicks = currentTicks - 1;
                yamaAttackTimers.put(yamaIndex, newTicks);

            } else {
                // Timer at 1, next tick should be an attack

                // Keep timer at 1 until attack is detected
            }
        }

        // Clear the newly initialized timers set for next tick
        newlyInitializedTimers.clear();
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

        // Track phase transition graphics (attack graphic 3276)
        if (graphicId == GRAPHIC_PHASE_TRANSITION) {
            int currentCount = phaseTransitionCounts.getOrDefault(index, 0);
            phaseTransitionCounts.put(index, currentCount + 1);
            log.info("Yama (index " + index + ") phase transition detected. Count: " + (currentCount + 1) +
                    (currentCount + 1 >= 2 ? " - ENRAGE PHASE ACTIVATED" : ""));
        }

        // Reset timer when graphic-based attacks are detected, but only if not in
        // cooldown
        if (isAttackGraphic(graphicId)) {
            int currentTick = client.getTickCount();
            Integer cooldownExpiry = attackCooldowns.get(index);

            // Only reset timer if we're not in cooldown or cooldown has expired
            if (cooldownExpiry == null || currentTick >= cooldownExpiry) {
                int attackTicks = getAttackCycleTicks(index);
                yamaAttackTimers.put(index, attackTicks);
                newlyInitializedTimers.add(index);
                // Set cooldown to expire in 6 ticks
                attackCooldowns.put(index, currentTick + ATTACK_COOLDOWN_TICKS);
                log.info("Yama (index " + index + ") graphic attack detected, timer reset to " + attackTicks +
                        " (cooldown until tick " + (currentTick + ATTACK_COOLDOWN_TICKS) + ")" +
                        (isYamaInEnragePhase(index) ? " [ENRAGE PHASE]" : ""));
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

    // Helper method to check if Yama is in enrage phase (3rd phase after 2
    // transition graphics)
    public boolean isYamaInEnragePhase(int npcIndex) {
        Integer transitionCount = phaseTransitionCounts.get(npcIndex);
        return transitionCount != null && transitionCount >= 2;
    }

    // Helper method to get the appropriate attack cycle ticks based on enrage
    // status
    private int getAttackCycleTicks(int npcIndex) {
        return isYamaInEnragePhase(npcIndex) ? ENRAGE_ATTACK_CYCLE_TICKS : ATTACK_CYCLE_TICKS;
    }

    // Get the current phase for a specific Yama
    public YamaPhase getYamaPhase(int npcIndex) {
        return yamaPhases.getOrDefault(npcIndex, YamaPhase.UNKNOWN);
    }

    // Get the attack timer for a specific Yama
    public int getYamaAttackTimer(int npcIndex) {
        return yamaAttackTimers.getOrDefault(npcIndex, ATTACK_CYCLE_TICKS);
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
            setLayer(OverlayLayer.ABOVE_SCENE);
        }

        @Override
        public Dimension render(Graphics2D graphics) {
            // First, render existing Yama highlights
            for (NPC npc : client.getTopLevelWorldView().npcs()) {
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
                            // Set text properties - bold bright teal 48px font
                            String timerText = String.valueOf(attackTimer);
                            java.awt.Font font = new java.awt.Font("Arial", java.awt.Font.BOLD, 36);
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

                            // Draw main text - bright red for '1', bright teal for others
                            Color textColor;
                            if (attackTimer == 1) {
                                textColor = new Color(255, 0, 0); // Bright red for '1'
                            } else {
                                textColor = new Color(0, 255, 255); // Bright teal for other numbers
                            }
                            graphics.setColor(textColor);
                            graphics.drawString(timerText, textX, textY);
                        }
                    }
                }
            }

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
    }
}