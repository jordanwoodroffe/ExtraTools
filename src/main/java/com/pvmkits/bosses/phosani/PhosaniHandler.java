package com.pvmkits.bosses.phosani;

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
public class PhosaniHandler implements BossHandler {

    @Inject
    private Client client;

    // Track current Phosani phases by NPC index
    private Map<Integer, PhosaniPhase> phosaniPhases = new HashMap<>();

    // Phosani's Nightmare NPC IDs
    private static final Set<Integer> PHOSANI_IDS = Set.of(9416, 9417, 9418, 9419, 9420, 9421, 9422, 9423, 9424, 11153,
            11154, 11155, 377);

    // Phosani animation IDs (these will need to be determined through testing)
    private static final int ANIMATION_MELEE = 8594; // Placeholder - needs verification
    private static final int ANIMATION_MAGE = 8595; // Placeholder - needs verification
    private static final int ANIMATION_RANGE = 8596; // Placeholder - needs verification
    private static final int ANIMATION_SPECIAL = 8597; // Placeholder - needs verification

    // Phosani graphic IDs (these will need to be determined through testing)
    private static final int GRAPHIC_MAGE = 1767; // Placeholder - needs verification
    private static final int GRAPHIC_RANGE = 1768; // Placeholder - needs verification
    private static final int GRAPHIC_SPECIAL = 1769; // Placeholder - needs verification

    // Attack cycle constants
    private static final int ATTACK_CYCLE_TICKS = 8;
    private static final int ENRAGE_ATTACK_CYCLE_TICKS = 6; // Faster attacks when low HP

    // Track last logged animation for each Phosani to prevent duplicate logging
    private Map<Integer, Integer> lastLoggedAnimations = new HashMap<>();

    // Track attack timers for each Phosani (NPC index -> ticks until next attack)
    private Map<Integer, Integer> phosaniAttackTimers = new HashMap<>();

    // Track which timers were just initialized this tick to prevent immediate
    // countdown
    private Set<Integer> newlyInitializedTimers = new HashSet<>();

    // Track attack cooldowns to prevent multiple timer resets from duplicate
    // animations
    // Maps NPC index to the tick when the cooldown expires
    private Map<Integer, Integer> attackCooldowns = new HashMap<>();

    // Cooldown duration in ticks after detecting an attack
    private static final int ATTACK_COOLDOWN_TICKS = 6;

    // Track HP percentages to determine enrage phase
    private Map<Integer, Integer> phosaniHpPercentages = new HashMap<>();

    @Override
    public String getBossName() {
        return "Phosani's Nightmare";
    }

    @Override
    public boolean isInBossArea(Client client) {
        // Check if any Phosani NPCs are present
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && PHOSANI_IDS.contains(npc.getId())) {
                log.info("PhosaniHandler.isInBossArea: Found Phosani NPC with ID " + npc.getId() + " and index "
                        + npc.getIndex());
                return true;
            }
        }
        log.debug("PhosaniHandler.isInBossArea: No Phosani NPCs found, checking all NPCs...");

        // Debug: Log all NPC IDs to help identify if Phosani ID is wrong
        int npcCount = 0;
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null) {
                npcCount++;
                if (npcCount <= 5) { // Only log first 5 NPCs to avoid spam
                    log.debug(
                            "PhosaniHandler.isInBossArea: Found NPC ID " + npc.getId() + " at index " + npc.getIndex());
                }
            }
        }
        log.debug("PhosaniHandler.isInBossArea: Total NPCs found: " + npcCount);

        return false;
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        // Animation detection moved to onGameTick to match working example
    }

    @Override
    @SuppressWarnings("deprecation") // getGraphic() is deprecated but still functional
    public void onGraphicChanged(GraphicChanged event) {
        Actor actor = event.getActor();

        if (!(actor instanceof NPC)) {
            return;
        }

        NPC npc = (NPC) actor;
        if (!PHOSANI_IDS.contains(npc.getId())) {
            return;
        }

        int index = npc.getIndex();
        int graphicId = npc.getGraphic();

        // Log every graphic change event, including when graphics are cleared
        log.info("Phosani (index " + index + ") attack graphic: graphicId=" + graphicId);

        // Reset timer when graphic-based attacks are detected, but only if not in
        // cooldown
        if (isAttackGraphic(graphicId)) {
            int currentTick = client.getTickCount();
            Integer cooldownExpiry = attackCooldowns.get(index);

            // Only reset timer if we're not in cooldown or cooldown has expired
            if (cooldownExpiry == null || currentTick >= cooldownExpiry) {
                int attackTicks = getAttackCycleTicks(index);
                phosaniAttackTimers.put(index, attackTicks);
                newlyInitializedTimers.add(index);
                // Set cooldown to expire in 6 ticks
                attackCooldowns.put(index, currentTick + ATTACK_COOLDOWN_TICKS);
                log.info("Phosani (index " + index + ") graphic attack detected, timer reset to " + attackTicks +
                        " (cooldown until tick " + (currentTick + ATTACK_COOLDOWN_TICKS) + ")" +
                        (isPhosaniInEnragePhase(index) ? " [ENRAGE PHASE]" : ""));
            } else {
                log.info("Phosani (index " + index + ") graphic attack ignored - in cooldown until tick "
                        + cooldownExpiry);
            }
        }

        // Update phase based on graphics
        if (graphicId == GRAPHIC_MAGE) {
            phosaniPhases.put(index, PhosaniPhase.MAGE);
        } else if (graphicId == GRAPHIC_RANGE) {
            phosaniPhases.put(index, PhosaniPhase.RANGE);
        } else if (graphicId == GRAPHIC_SPECIAL) {
            phosaniPhases.put(index, PhosaniPhase.SPECIAL);
        }
    }

    @Override
    public void onGameTick(GameTick event) {
        if (client.getGameState().getState() < 30) {
            return;
        }

        log.debug("PhosaniHandler.onGameTick: Called, GameState=" + client.getGameState());

        boolean phosaniPresent = false;
        // Track all visible Phosanis in the scene
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && PHOSANI_IDS.contains(npc.getId())) {
                phosaniPresent = true;
                int index = npc.getIndex();
                log.debug("PhosaniHandler.onGameTick: Processing Phosani with index " + index);

                // Update HP percentage for enrage detection
                if (npc.getHealthRatio() != -1) {
                    int hpPercentage = (npc.getHealthRatio() * 100) / npc.getHealthScale();
                    phosaniHpPercentages.put(index, hpPercentage);
                }

                // Log animation IDs for Phosani only when they change
                int animationId = npc.getAnimation();
                if (animationId != -1) {
                    Integer lastLogged = lastLoggedAnimations.get(index);
                    if (lastLogged == null || lastLogged != animationId) {
                        log.info("Phosani (index " + index + ") animation: animationId=" + animationId);
                        lastLoggedAnimations.put(index, animationId);
                    }

                    // Reset timer when Phosani attacks, but only if not in cooldown
                    if (isAttackAnimation(animationId)) {
                        int currentTick = client.getTickCount();
                        Integer cooldownExpiry = attackCooldowns.get(index);

                        // Only reset timer if we're not in cooldown or cooldown has expired
                        if (cooldownExpiry == null || currentTick >= cooldownExpiry) {
                            int attackTicks = getAttackCycleTicks(index);
                            phosaniAttackTimers.put(index, attackTicks);
                            newlyInitializedTimers.add(index);
                            // Set cooldown to expire in 6 ticks
                            attackCooldowns.put(index, currentTick + ATTACK_COOLDOWN_TICKS);
                            log.info("Phosani (index " + index + ") attack detected, timer reset to " + attackTicks +
                                    " (cooldown until tick " + (currentTick + ATTACK_COOLDOWN_TICKS) + ")" +
                                    (isPhosaniInEnragePhase(index) ? " [ENRAGE PHASE]" : ""));
                        } else {
                            log.debug("Phosani (index " + index + ") attack ignored - in cooldown until tick "
                                    + cooldownExpiry);
                        }
                    }

                    // Update phase based on animation if available
                    if (animationId == ANIMATION_MELEE) {
                        phosaniPhases.put(index, PhosaniPhase.MELEE);
                    } else if (animationId == ANIMATION_MAGE) {
                        phosaniPhases.put(index, PhosaniPhase.MAGE);
                    } else if (animationId == ANIMATION_RANGE) {
                        phosaniPhases.put(index, PhosaniPhase.RANGE);
                    } else if (animationId == ANIMATION_SPECIAL) {
                        phosaniPhases.put(index, PhosaniPhase.SPECIAL);
                    }
                }

                // Initialize with UNKNOWN if we haven't seen this Phosani before
                if (!phosaniPhases.containsKey(index)) {
                    phosaniPhases.put(index, PhosaniPhase.UNKNOWN);
                    log.info("PhosaniHandler.onGameTick: Initialized phase to UNKNOWN for Phosani index " + index);
                }

                // Initialize timer if not present
                if (!phosaniAttackTimers.containsKey(index)) {
                    int attackTicks = getAttackCycleTicks(index);
                    phosaniAttackTimers.put(index, attackTicks);
                    newlyInitializedTimers.add(index);
                    log.info("Phosani (index " + index + ") timer initialized to " + attackTicks +
                            (isPhosaniInEnragePhase(index) ? " [ENRAGE PHASE]" : ""));
                } else {
                    // Debug: Log current timer state every 10 ticks to avoid spam
                    if (client.getTickCount() % 10 == 0) {
                        int currentTimer = phosaniAttackTimers.get(index);
                        log.debug("Phosani (index " + index + ") current timer value: " + currentTimer);
                    }
                }
            }
        }

        // If no Phosani exists, clear all data
        if (!phosaniPresent) {
            if (!phosaniPhases.isEmpty() || !phosaniAttackTimers.isEmpty()) {
                log.info("PhosaniHandler.onGameTick: No Phosani present, clearing all data");
            }
            phosaniPhases.clear();
            phosaniAttackTimers.clear();
            phosaniHpPercentages.clear();
            attackCooldowns.clear();
            return;
        }

        // Update attack timers for all Phosanis
        log.debug("PhosaniHandler.onGameTick: Updating timers for " + phosaniAttackTimers.size() + " Phosanis");
        for (Map.Entry<Integer, Integer> entry : phosaniAttackTimers.entrySet()) {
            int phosaniIndex = entry.getKey();
            int currentTicks = entry.getValue();

            // Skip countdown for newly initialized timers this tick
            if (newlyInitializedTimers.contains(phosaniIndex)) {
                log.debug("Phosani (index " + phosaniIndex + ") timer skip countdown (newly initialized): "
                        + currentTicks);
                continue;
            }

            // Only decrement if the timer is greater than 1
            if (currentTicks > 1) {
                // Countdown the timer
                int newTicks = currentTicks - 1;
                phosaniAttackTimers.put(phosaniIndex, newTicks);
                log.debug("Phosani (index " + phosaniIndex + ") timer countdown: " + currentTicks + " -> " + newTicks);

            } else if (currentTicks == 1) {
                // Timer at 1, next tick should be an attack
                log.debug("Phosani (index " + phosaniIndex + ") timer at 1, waiting for attack");
                // Keep timer at 1 until attack is detected
            } else if (currentTicks <= 0) {
                // Timer went below 1, reset it
                int attackTicks = getAttackCycleTicks(phosaniIndex);
                phosaniAttackTimers.put(phosaniIndex, attackTicks);
                log.info(
                        "Phosani (index " + phosaniIndex + ") timer reset from " + currentTicks + " to " + attackTicks);
            }
        }

        // Clear the newly initialized timers set for next tick
        if (!newlyInitializedTimers.isEmpty()) {
            log.debug("PhosaniHandler.onGameTick: Clearing newly initialized timers: " + newlyInitializedTimers);
        }
        newlyInitializedTimers.clear();
    }

    @Override
    public Actor getBossActor(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && PHOSANI_IDS.contains(npc.getId())) {
                return npc;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        phosaniPhases.clear();
        phosaniAttackTimers.clear();
        phosaniHpPercentages.clear();
        attackCooldowns.clear();
        lastLoggedAnimations.clear();
        newlyInitializedTimers.clear();
    }

    // Helper methods
    private boolean isAttackAnimation(int animationId) {
        return animationId == ANIMATION_MELEE ||
                animationId == ANIMATION_MAGE ||
                animationId == ANIMATION_RANGE ||
                animationId == ANIMATION_SPECIAL;
    }

    private boolean isAttackGraphic(int graphicId) {
        return graphicId == GRAPHIC_MAGE ||
                graphicId == GRAPHIC_RANGE ||
                graphicId == GRAPHIC_SPECIAL;
    }

    public boolean isPhosaniInEnragePhase(int npcIndex) {
        Integer hpPercentage = phosaniHpPercentages.get(npcIndex);
        return hpPercentage != null && hpPercentage <= 25; // Enrage at 25% HP
    }

    private int getAttackCycleTicks(int npcIndex) {
        return isPhosaniInEnragePhase(npcIndex) ? ENRAGE_ATTACK_CYCLE_TICKS : ATTACK_CYCLE_TICKS;
    }

    public PhosaniPhase getPhosaniPhase(int npcIndex) {
        return phosaniPhases.getOrDefault(npcIndex, PhosaniPhase.UNKNOWN);
    }

    public int getPhosaniAttackTimer(int npcIndex) {
        return phosaniAttackTimers.getOrDefault(npcIndex, ATTACK_CYCLE_TICKS);
    }

    public List<NPC> getPhosaniNpcs() {
        List<NPC> phosanis = new ArrayList<>();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && PHOSANI_IDS.contains(npc.getId())) {
                phosanis.add(npc);
            }
        }
        return phosanis;
    }

    // Phosani combat phases
    public enum PhosaniPhase {
        MAGE(new Color(100, 149, 237)), // Soft blue
        RANGE(new Color(144, 238, 144)), // Soft green
        MELEE(new Color(240, 100, 100, 120)), // Soft red
        SPECIAL(new Color(255, 165, 0, 100)), // Orange for special attacks
        UNKNOWN(Color.GRAY);

        private final Color color;

        PhosaniPhase(Color color) {
            this.color = color;
        }

        public Color getColor() {
            return color;
        }
    }
}
