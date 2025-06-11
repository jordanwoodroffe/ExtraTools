package com.pvmkits.bosses.verzik;

import com.pvmkits.core.BossHandler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GameTick;

import javax.inject.Inject;
import java.awt.Color;

@Slf4j
public class VerzikHandler implements BossHandler {

    @Inject
    private Client client;

    // Verzik NPC ID - Only show overlay for this NPC
    private static final int VERZIK_NPC_ID = 8374; // P3 Verzik

    // Verzik animation IDs
    private static final int VERZIK_ATTACK_ANIMATION = -1; // Both mage and range attacks

    // Verzik graphic IDs
    private static final int VERZIK_MAGE_GRAPHIC = 1581; // Mage attack graphic on player
    private static final int VERZIK_ENRAGED_GRAPHIC = 560; // Enraged phase signal

    // Attack timers (in ticks)
    private static final int NORMAL_CYCLE = 7; // Normal attack cycle
    private static final int ENRAGED_CYCLE = 5; // Enraged attack cycle
    private static final int ATTACK_COOLDOWN_TICKS = 6; // Cooldown between attacks (1 tick less than normal cycle)

    // Track current attack style and state
    private VerzikAttackStyle currentAttackStyle = VerzikAttackStyle.UNKNOWN;
    private boolean isEnraged = false; // Track if P3 is enraged

    // Track attack timer (following Yama pattern - no timerActive flag needed)
    private int attackTimer = 0;
    private boolean newlyInitializedTimer = false;

    // Track last animations to prevent duplicates
    private int lastAnimationId = -1;

    // Attack cooldown tracking (following Yama pattern)
    private int attackCooldownExpiry = 0;

    @Override
    public String getBossName() {
        return "Verzik";
    }

    @Override
    public boolean isInBossArea(Client client) {
        // Check if Verzik P3 NPC is present
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == VERZIK_NPC_ID) {
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
        if (npc.getId() != VERZIK_NPC_ID) {
            return;
        }

        int animationId = npc.getAnimation();

        // DEBUG: Log all Verzik animations for ID discovery
        log.info("VERZIK DEBUG - NPC ID: {}, Animation ID: {}, LastAnimationId: {}", npc.getId(), animationId,
                lastAnimationId);

        // For attack animations, we need to check cooldown instead of just duplicate
        // detection
        // because Verzik can trigger the same attack animation multiple times
        if (animationId == VERZIK_ATTACK_ANIMATION) {
            // Check if we're in attack cooldown for attack animations
            int currentTick = client.getTickCount();
            log.info("VERZIK DEBUG - Attack animation detected, checking cooldown: current tick {}, cooldown expiry {}",
                    currentTick, attackCooldownExpiry);
            if (attackCooldownExpiry > currentTick) {
                log.info(
                        "VERZIK DEBUG - Attack in cooldown, ignoring attack animation. Current tick: {}, cooldown expiry: {}",
                        currentTick, attackCooldownExpiry);
                return;
            }

            log.info("VERZIK DEBUG - Attack animation passed cooldown check, proceeding to process attack");

            // Default to range, will be corrected by graphic if it's mage
            currentAttackStyle = VerzikAttackStyle.RANGE;

            // Reset attack timer (following Yama pattern)
            resetAttackTimer();
            newlyInitializedTimer = true;
            attackCooldownExpiry = currentTick + ATTACK_COOLDOWN_TICKS;

            log.info("Verzik attack detected: {} (Timer reset to {}, Cooldown expiry: {})",
                    currentAttackStyle, attackTimer, attackCooldownExpiry);

            lastAnimationId = animationId;
            return;
        }

        // For non-attack animations, use normal duplicate detection
        if (animationId == lastAnimationId) {
            log.info("VERZIK DEBUG - Skipping duplicate non-attack animation: {}", animationId);
            return; // Skip duplicate animations
        }

        lastAnimationId = animationId;
        log.info("VERZIK DEBUG - Non-attack animation processed: {}", animationId);
    }

    @Override
    public void onGraphicChanged(GraphicChanged event) {
        Actor actor = event.getActor();

        // Note: Using deprecated getGraphic() method - should be updated when API
        // changes
        @SuppressWarnings("deprecation")
        int graphicId = actor.getGraphic();

        // DEBUG: Log all graphics for discovery
        if (graphicId > 0) {
            log.info("GRAPHIC DEBUG - Actor: {}, Graphic ID: {}",
                    actor.getClass().getSimpleName(), graphicId);
        }

        if (!(actor instanceof Player)) {
            // Check for enraged graphic on NPC
            if (actor instanceof NPC && ((NPC) actor).getId() == VERZIK_NPC_ID) {
                if (graphicId == VERZIK_ENRAGED_GRAPHIC) {
                    isEnraged = true;
                    log.info("Verzik entered enraged phase");
                }
            }
            return;
        }

        // Handle verzik projectile graphics on players
        if (graphicId == VERZIK_MAGE_GRAPHIC) {
            // Set mage attack style when graphic is detected
            currentAttackStyle = VerzikAttackStyle.MAGE;
            log.info("Verzik mage attack detected (graphic 1581) - Attack style updated to MAGE");
        }
    }

    @Override
    public void onGameTick(GameTick event) {
        if (client.getGameState().getState() < 30) {
            return;
        }

        // Check if Verzik is still present
        boolean verzikPresent = false;
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == VERZIK_NPC_ID) {
                verzikPresent = true;

                // Initialize timer if not present (following Yama pattern)
                if (attackTimer == 0) {
                    resetAttackTimer();
                    newlyInitializedTimer = true;
                    log.info("Verzik timer initialized to {} ({})", attackTimer,
                            (isEnraged ? "[ENRAGE PHASE]" : "[NORMAL PHASE]"));
                }
                break;
            }
        }

        if (!verzikPresent) {
            reset();
            return;
        }

        // Update attack timer (following Yama pattern)
        if (attackTimer > 0) {
            // Skip countdown for newly initialized timer this tick
            if (newlyInitializedTimer) {
                newlyInitializedTimer = false;
                return;
            }

            // Only decrement if the timer is greater than 1
            if (attackTimer > 1) {
                attackTimer--;
            } else {
                // Timer at 1, next tick should be an attack
                // Keep timer at 1 until attack is detected (like Yama)
            }
        }
    }

    @Override
    public Actor getBossActor(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == VERZIK_NPC_ID) {
                return npc;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        currentAttackStyle = VerzikAttackStyle.UNKNOWN;
        attackTimer = 0;
        newlyInitializedTimer = false;
        lastAnimationId = -1;
        attackCooldownExpiry = 0;
        isEnraged = false;
    }

    // Helper methods
    private void resetAttackTimer() {
        attackTimer = isEnraged ? ENRAGED_CYCLE : NORMAL_CYCLE;
    }

    // Public getters for overlay access
    public VerzikAttackStyle getCurrentAttackStyle() {
        return currentAttackStyle;
    }

    public int getAttackTimer() {
        return attackTimer;
    }

    public boolean isTimerActive() {
        return attackTimer > 0;
    }

    public boolean isEnraged() {
        return isEnraged;
    }

    // Verzik attack styles
    public enum VerzikAttackStyle {
        RANGE(new Color(0, 255, 0, 150)), // Green - Range projectiles
        MAGE(new Color(0, 0, 255, 150)), // Blue - Mage projectiles
        UNKNOWN(new Color(128, 128, 128, 80)); // Light gray for initial state

        private final Color color;

        VerzikAttackStyle(Color color) {
            this.color = color;
        }

        public Color getColor() {
            return color;
        }
    }
}
