package com.pvmkits.core;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ProjectileMoved;

/**
 * Base interface for boss-specific handlers in PVM Kits
 */
public interface BossHandler {

    /**
     * Get the display name of this boss
     */
    String getBossName();

    /**
     * Check if the current area/context matches this boss
     */
    boolean isInBossArea(Client client);

    /**
     * Handle animation changes for this boss
     */
    void onAnimationChanged(AnimationChanged event);

    /**
     * Handle graphic changes for this boss
     */
    void onGraphicChanged(GraphicChanged event);

    /**
     * Handle projectile events for this boss
     */
    default void onProjectileMoved(ProjectileMoved event) {
        // Default empty implementation for bosses that don't use projectiles
    }

    /**
     * Handle an NPC entering the scene. Used for diagnostics (spotting unrecognised
     * NPC IDs) and for handlers that track adds.
     */
    default void onNpcSpawned(NpcSpawned event) {
        // Default empty implementation
    }

    /**
     * Handle an NPC leaving the scene.
     */
    default void onNpcDespawned(NpcDespawned event) {
        // Default empty implementation
    }

    /**
     * Handle a hitsplat. Damage taken is the ground truth for when a boss attack
     * actually landed, which is what attack timers are validated against.
     */
    default void onHitsplatApplied(HitsplatApplied event) {
        // Default empty implementation
    }

    /**
     * Handle game tick updates for this boss
     */
    void onGameTick(GameTick event);

    /**
     * Get the current boss actor if available
     */
    Actor getBossActor(Client client);

    /**
     * Reset the handler state
     */
    void reset();
}
