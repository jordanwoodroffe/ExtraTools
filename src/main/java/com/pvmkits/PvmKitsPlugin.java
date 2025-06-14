package com.pvmkits;

import com.google.inject.Provides;
import com.pvmkits.bosses.yama.YamaHandler;
import com.pvmkits.bosses.yama.YamaOverlay;
import com.pvmkits.bosses.phosani.PhosaniHandler;
import com.pvmkits.bosses.phosani.PhosaniOverlay;

import com.pvmkits.core.BossHandler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@PluginDescriptor(name = "PVM Kits", description = "Multi-boss PVM assistance toolkit with mechanics overlays and timers", tags = {
        "combat", "boss", "pvm", "mechanics", "yama", "phosani", "nightmare", "verzik",
        "tob" }, enabledByDefault = false)
public class PvmKitsPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private PvmKitsConfig config;

    @Inject
    private YamaHandler yamaHandler;

    @Inject
    private YamaOverlay yamaOverlay;

    @Inject
    private PhosaniHandler phosaniHandler;

    @Inject
    private PhosaniOverlay phosaniOverlay;

    // List of all boss handlers - Yama, Verzik...
    private List<BossHandler> bossHandlers;

    // Current active boss handler
    private BossHandler activeBossHandler;

    @Override
    protected void startUp() throws Exception {
        // Initialize boss handlers list
        bossHandlers = new ArrayList<>();
        bossHandlers.add(yamaHandler);
        bossHandlers.add(phosaniHandler);

        // TODO: Add other boss handlers here when implemented
        // bossHandlers.add(nyloHandler);
        // etc.

        activeBossHandler = null;
        overlayManager.add(yamaOverlay);
        overlayManager.add(phosaniOverlay);

        log.info("PVM Kits plugin started!");
    }

    @Override
    protected void shutDown() throws Exception {
        // Reset all boss handlers
        for (BossHandler handler : bossHandlers) {
            handler.reset();
        }

        activeBossHandler = null;
        overlayManager.remove(yamaOverlay);
        overlayManager.remove(phosaniOverlay);

        log.info("PVM Kits plugin stopped!");
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        log.debug("PvmKitsPlugin.onGameTick: Called with " + bossHandlers.size() + " handlers");

        // Determine which boss area we're in (if any)
        BossHandler newActiveBoss = null;
        for (BossHandler handler : bossHandlers) {
            log.debug("PvmKitsPlugin.onGameTick: Checking " + handler.getBossName() + " boss area");
            if (handler.isInBossArea(client)) {
                newActiveBoss = handler;
                log.debug("PvmKitsPlugin.onGameTick: Found active boss: " + handler.getBossName());
                break; // Use first matching boss handler
            }
        }

        log.debug("PvmKitsPlugin.onGameTick: Active boss = " +
                (newActiveBoss != null ? newActiveBoss.getBossName() : "null"));

        // If we switched boss areas, reset the previous handler
        if (activeBossHandler != newActiveBoss) {
            if (activeBossHandler != null) {
                activeBossHandler.reset();
                log.info("Left {} area", activeBossHandler.getBossName());
            }

            activeBossHandler = newActiveBoss;

            if (activeBossHandler != null) {
                log.info("Entered {} area", activeBossHandler.getBossName());
            }
        }

        // Forward event to active boss handler
        if (activeBossHandler != null) {
            log.debug("PvmKitsPlugin.onGameTick: Forwarding to " + activeBossHandler.getBossName());
            activeBossHandler.onGameTick(event);
        } else {
            log.debug("PvmKitsPlugin.onGameTick: No active boss handler");
        }
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        // Forward to active boss handler
        if (activeBossHandler != null) {
            activeBossHandler.onAnimationChanged(event);
        }
    }

    @Subscribe
    public void onGraphicChanged(GraphicChanged event) {
        // Forward to active boss handler
        if (activeBossHandler != null) {
            activeBossHandler.onGraphicChanged(event);
        }
    }

    @Subscribe
    public void onProjectileMoved(ProjectileMoved event) {
        // Forward to active boss handler
        if (activeBossHandler != null) {
            activeBossHandler.onProjectileMoved(event);
        }
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event) {
        // Forward to Phosani handler specifically for spore tracking
        if (phosaniHandler != null) {
            phosaniHandler.onGameObjectSpawned(event);
        }
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event) {
        // Forward to Phosani handler specifically for spore tracking
        if (phosaniHandler != null) {
            phosaniHandler.onGameObjectDespawned(event);
        }
    }

    // Getters for access by overlays and other components
    public BossHandler getActiveBossHandler() {
        return activeBossHandler;
    }

    public YamaHandler getYamaHandler() {
        return yamaHandler;
    }

    public PhosaniHandler getPhosaniHandler() {
        return phosaniHandler;
    }

    @Provides
    PvmKitsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(PvmKitsConfig.class);
    }
}
