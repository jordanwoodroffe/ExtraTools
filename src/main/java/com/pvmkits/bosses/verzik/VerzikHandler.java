package com.pvmkits.bosses.verzik;

import com.pvmkits.core.BossHandler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ProjectileMoved;

import javax.inject.Inject;
import java.util.*;

@Slf4j
public class VerzikHandler implements BossHandler {

    @Inject
    private Client client;

    // Verzik NPC ID
    private static final int VERZIK_P3_ID = 8374;

    // Attack cycle constants
    private static final int NORMAL_ATTACK_CYCLE_TICKS = 7;
    private static final int ENRAGED_ATTACK_CYCLE_TICKS = 5;

    // Projectile IDs for P3 Verzik
    private static final int PROJECTILE_MAGE = 1594;
    private static final int PROJECTILE_RANGE = 1593;

    // Enraged graphic ID
    private static final int GRAPHIC_ENRAGED = 560;

    // Track attack timers for each Verzik (NPC index -> ticks until next attack)
    private Map<Integer, Integer> verzikAttackTimers = new HashMap<>();

    // Track enraged state for each Verzik (NPC index -> isEnraged)
    private Map<Integer, Boolean> verzikEnragedStates = new HashMap<>();

    // Track which timers were just initialized this tick to prevent immediate
    // countdown
    private Set<Integer> newlyInitializedTimers = new HashSet<>();

    // Track attack cooldowns to prevent multiple timer resets from duplicate
    // projectiles
    // Maps NPC index to the tick when the cooldown expires
    private Map<Integer, Integer> attackCooldowns = new HashMap<>();

    // Cooldown duration in ticks after detecting an attack
    private static final int ATTACK_COOLDOWN_TICKS = 3;

    @Override
    public String getBossName() {
        return "Verzik";
    }

    @Override
    public boolean isInBossArea(Client client) {
        // Check if any Verzik P3 NPCs are present
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == VERZIK_P3_ID) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        // Verzik P3 uses projectiles for attack detection, not animations
        // This method is left empty but could be used for other mechanics if needed
    }

    @Override
    public void onGraphicChanged(GraphicChanged event) {
        Actor actor = event.getActor();

        if (!(actor instanceof NPC)) {
            return;
        }

        NPC npc = (NPC) actor;
        if (npc.getId() != VERZIK_P3_ID) {
            return;
        }

        int index = npc.getIndex();
        int graphicId = actor.getGraphic();

        log.info("Verzik (index " + index + ") graphic: graphicId=" + graphicId);

        // Track enraged state
        if (graphicId == GRAPHIC_ENRAGED) {
            verzikEnragedStates.put(index, true);
            log.info("Verzik (index " + index + ") entered ENRAGED phase - switching to 5-tick cycle");

            // Reset timer with new cycle length if we have an active timer
            if (verzikAttackTimers.containsKey(index)) {
                int attackTicks = getAttackCycleTicks(index);
                verzikAttackTimers.put(index, attackTicks);
                newlyInitializedTimers.add(index);
                log.info("Verzik (index " + index + ") timer adjusted to " + attackTicks + " for enrage phase");
            }
        }
    }

    @Override
    public void onProjectileMoved(ProjectileMoved event) {
        Projectile projectile = event.getProjectile();
        if (projectile == null) {
            return;
        }

        int projectileId = projectile.getId();

        // Only handle Verzik projectiles
        if (projectileId != PROJECTILE_MAGE && projectileId != PROJECTILE_RANGE) {
            return;
        }

        // Find the Verzik that fired this projectile
        Actor interacting = projectile.getInteracting();
        if (!(interacting instanceof NPC)) {
            return;
        }

        NPC verzik = (NPC) interacting;
        if (verzik.getId() != VERZIK_P3_ID) {
            return;
        }

        int index = verzik.getIndex();
        int currentTick = client.getTickCount();

        log.info("Verzik (index " + index + ") projectile detected: projectileId=" + projectileId);

        // Check cooldown to prevent duplicate detections
        Integer cooldownExpiry = attackCooldowns.get(index);
        if (cooldownExpiry != null && currentTick < cooldownExpiry) {
            log.info("Verzik (index " + index + ") projectile ignored - in cooldown until tick " + cooldownExpiry);
            return;
        }

        // Set timer to start countdown from when projectile spawns
        // Normal: 5 ticks countdown (5→4→3→2→1)
        // Enraged: 3 ticks countdown (3→2→1)
        int attackTicks = isVerzikEnraged(index) ? 3 : 5;
        verzikAttackTimers.put(index, attackTicks);
        newlyInitializedTimers.add(index);

        // Set cooldown
        attackCooldowns.put(index, currentTick + ATTACK_COOLDOWN_TICKS);

        String attackType = (projectileId == PROJECTILE_MAGE) ? "MAGE" : "RANGE";
        log.info("Verzik (index " + index + ") projectile detected: " + attackType +
                ", timer started at " + attackTicks +
                " (cooldown until tick " + (currentTick + ATTACK_COOLDOWN_TICKS) + ")" +
                (isVerzikEnraged(index) ? " [ENRAGED]" : ""));
    }

    @Override
    public void onGameTick(GameTick event) {
        if (client.getGameState().getState() < 30) {
            return;
        }

        boolean verzikPresent = false;
        // Track all visible Verziks in the scene
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == VERZIK_P3_ID) {
                verzikPresent = true;
                int index = npc.getIndex();

                // Initialize enraged state if not present
                if (!verzikEnragedStates.containsKey(index)) {
                    verzikEnragedStates.put(index, false);
                }

                // Don't initialize timer until we detect first projectile
                // We need the projectile to deduce when the actual attack timing is
            }
        }

        // If no Verzik exists, clear all data
        if (!verzikPresent) {
            reset();
            return;
        }

        // Update attack timers for all Verziks
        for (Map.Entry<Integer, Integer> entry : verzikAttackTimers.entrySet()) {
            int verzikIndex = entry.getKey();
            int currentTicks = entry.getValue();

            // Skip countdown for newly initialized timers this tick
            if (newlyInitializedTimers.contains(verzikIndex)) {
                continue;
            }

            // Only decrement if the timer is greater than 1
            if (currentTicks > 1) {
                // Countdown the timer
                int newTicks = currentTicks - 1;
                verzikAttackTimers.put(verzikIndex, newTicks);
            } else {
                // Timer reached 1, remove it - it will reappear when next projectile spawns
                verzikAttackTimers.remove(verzikIndex);
            }
        }

        // Clear the newly initialized timers set for next tick
        newlyInitializedTimers.clear();
    }

    @Override
    public Actor getBossActor(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == VERZIK_P3_ID) {
                return npc;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        verzikAttackTimers.clear();
        verzikEnragedStates.clear();
        newlyInitializedTimers.clear();
        attackCooldowns.clear();
    }

    // Helper methods
    public boolean isVerzikEnraged(int npcIndex) {
        return verzikEnragedStates.getOrDefault(npcIndex, false);
    }

    private int getAttackCycleTicks(int npcIndex) {
        return isVerzikEnraged(npcIndex) ? ENRAGED_ATTACK_CYCLE_TICKS : NORMAL_ATTACK_CYCLE_TICKS;
    }

    public int getVerzikAttackTimer(int npcIndex) {
        return verzikAttackTimers.getOrDefault(npcIndex, 0); // Return 0 if no timer active
    }

    public List<NPC> getVerzikNpcs() {
        List<NPC> verziks = new ArrayList<>();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == VERZIK_P3_ID) {
                verziks.add(npc);
            }
        }
        return verziks;
    }
}