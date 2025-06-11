package com.pvmkits.bosses.yama;

import com.pvmkits.core.BossHandler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GameTick;

import javax.inject.Inject;
import java.awt.Color;
import java.util.*;

@Slf4j
public class YamaHandler implements BossHandler {

    @Inject
    private Client client;

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
    public String getBossName() {
        return "Yama";
    }

    @Override
    public boolean isInBossArea(Client client) {
        // Check if any Yama NPCs are present
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == YAMA_ID) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        Actor actor = event.getActor();
        if (!(actor instanceof NPC)) {
            return;
        }

        NPC npc = (NPC) actor;
        if (npc.getId() != YAMA_ID) {
            return;
        }

        int index = npc.getIndex();
        int animationId = npc.getAnimation();

        if (animationId != -1) {
            Integer lastLogged = lastLoggedAnimations.get(index);
            if (lastLogged == null || lastLogged != animationId) {
                log.info("Yama (index " + index + ") animation: animationId=" + animationId);
                lastLoggedAnimations.put(index, animationId);
            }

            // Reset timer to appropriate ticks when Yama attacks, but only if not in
            // cooldown
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
                    log.info("Yama (index " + index + ") attack ignored - in cooldown until tick " + cooldownExpiry);
                }
            }

            // Update phase based on animation if available (handle melee attacks here)
            if (animationId == ANIMATION_MELEE) {
                yamaPhases.put(index, YamaPhase.MELEE);
            }
        }
    }

    @Override
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
        int graphicId = actor.getGraphic();

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

    @Override
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
            reset();
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

    @Override
    public Actor getBossActor(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == YAMA_ID) {
                return npc;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        yamaPhases.clear();
        yamaAttackTimers.clear();
        phaseTransitionCounts.clear();
        attackCooldowns.clear();
        lastLoggedAnimations.clear();
        newlyInitializedTimers.clear();
    }

    // Helper methods
    private boolean isAttackAnimation(int animationId) {
        return animationId == ANIMATION_MELEE ||
                animationId == ANIMATION_ORB_ATTACK ||
                animationId == ANIMATION_MAGE;
    }

    private boolean isAttackGraphic(int graphicId) {
        return graphicId == GRAPHIC_MAGE ||
                graphicId == GRAPHIC_RANGE ||
                graphicId == GRAPHIC_GLYPH_ATTACK;
    }

    public boolean isYamaInEnragePhase(int npcIndex) {
        Integer transitionCount = phaseTransitionCounts.get(npcIndex);
        return transitionCount != null && transitionCount >= 2;
    }

    private int getAttackCycleTicks(int npcIndex) {
        return isYamaInEnragePhase(npcIndex) ? ENRAGE_ATTACK_CYCLE_TICKS : ATTACK_CYCLE_TICKS;
    }

    public YamaPhase getYamaPhase(int npcIndex) {
        return yamaPhases.getOrDefault(npcIndex, YamaPhase.UNKNOWN);
    }

    public int getYamaAttackTimer(int npcIndex) {
        return yamaAttackTimers.getOrDefault(npcIndex, ATTACK_CYCLE_TICKS);
    }

    public List<NPC> getYamaNpcs() {
        List<NPC> yamas = new ArrayList<>();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == YAMA_ID) {
                yamas.add(npc);
            }
        }
        return yamas;
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
}
