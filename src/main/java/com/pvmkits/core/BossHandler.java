package com.pvmkits.core;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GameTick;
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
