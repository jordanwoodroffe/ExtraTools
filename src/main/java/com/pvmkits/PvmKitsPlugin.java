package com.pvmkits;

import com.google.inject.Provides;
import com.pvmkits.bosses.yama.YamaHandler;
import com.pvmkits.bosses.yama.YamaOverlay;
import com.pvmkits.bosses.verzik.VerzikHandler;
import com.pvmkits.bosses.verzik.VerzikOverlay;
import com.pvmkits.core.BossHandler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.ProjectileMoved;
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
        "combat", "boss", "pvm", "mechanics", "yama", "verzik", "tob" }, enabledByDefault = false)
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
    private VerzikHandler verzikHandler;

    @Inject
    private VerzikOverlay verzikOverlay;

    // List of all boss handlers - Yama, Verzik...
    private List<BossHandler> bossHandlers;

    // Current active boss handler
    private BossHandler activeBossHandler;

    @Override
    protected void startUp() throws Exception {
        // Initialize boss handlers list
        bossHandlers = new ArrayList<>();
        bossHandlers.add(yamaHandler);
        bossHandlers.add(verzikHandler);

        // TODO: Add other boss handlers here when implemented
        // bossHandlers.add(nyloHandler);
        // etc.

        activeBossHandler = null;
        overlayManager.add(yamaOverlay);
        overlayManager.add(verzikOverlay);

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
        overlayManager.remove(verzikOverlay);

        log.info("PVM Kits plugin stopped!");
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        // Determine which boss area we're in (if any)
        BossHandler newActiveBoss = null;
        for (BossHandler handler : bossHandlers) {
            if (handler.isInBossArea(client)) {
                newActiveBoss = handler;
                break; // Use first matching boss handler
            }
        }

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
            activeBossHandler.onGameTick(event);
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

    // Getters for access by overlays and other components
    public BossHandler getActiveBossHandler() {
        return activeBossHandler;
    }

    public YamaHandler getYamaHandler() {
        return yamaHandler;
    }

    public VerzikHandler getVerzikHandler() {
        return verzikHandler;
    }

    @Provides
    PvmKitsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(PvmKitsConfig.class);
    }
}
