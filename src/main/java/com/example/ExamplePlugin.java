package com.example;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NpcID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
    name = "Example"
)
public class ExamplePlugin extends Plugin
{
    private static final int MAIDEN_ID = NpcID.THE_MAIDEN_OF_SUGADINTI; // Replace with the correct ID if needed
    private static final int MAIDEN_ATTACK_INTERVAL_TICKS = 10;

    @Inject
    private Client client;

    @Inject
    private ExampleConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private MaidenOverlay maidenOverlay;

    private NPC maiden;
    private int maidenCountdown = -1;

    @Override
    protected void startUp() throws Exception
    {
        overlayManager.add(maidenOverlay);
        log.info("STARTED!");
    }

    @Override
    protected void shutDown() throws Exception
    {
        overlayManager.remove(maidenOverlay);
        maiden = null;
        maidenCountdown = -1;
        log.info("STOPPED!");
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event)
    {
     if (event.getNpc().getId() == MAIDEN_ID)
        {
            maiden = event.getNpc();
            maidenCountdown = MAIDEN_ATTACK_INTERVAL_TICKS; 
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event)
    {
        if (event.getNpc() == maiden)
        {
            maiden = null;
            maidenCountdown = -1;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (maiden != null && maidenCountdown > 0)
        {
            maidenCountdown--;
            if (maidenCountdown == 0)
            {
                maidenCountdown = MAIDEN_ATTACK_INTERVAL_TICKS; // Reset to 10 after attack
            }
        }
    }

    public NPC getMaiden()
    {
        return maiden;
    }

    public int getMaidenCountdown()
    {
        return maidenCountdown;
    }

    @Provides
    ExampleConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ExampleConfig.class);
    }
}