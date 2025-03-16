package com.example;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NpcID;
import net.runelite.api.events.AnimationChanged;
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
    private static final int MAIDEN_ID = NpcID.THE_MAIDEN_OF_SUGADINTI; 
    private static final int MAIDEN_ATTACK_INTERVAL_TICKS = 10;

    // Sotetseg attack animation IDs
    private static final int[] SOTESEG_ATTACK_ANIMATION_IDS = {10864, 10865, 10867, 10868};

    // Maiden attack animation IDs
    private static final int[] MAIDEN_ATTACK_ANIMATION_IDS = {8091, 8092};

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

    private boolean maidenResetThisTick = false;

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
        maidenResetThisTick = false;
        log.info("STOPPED!");
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event)
    {
        if (event.getNpc().getId() == MAIDEN_ID)
        {
            maiden = event.getNpc();
            maidenCountdown = -1; // Timer will not start until the first attack animation
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
    public void onAnimationChanged(AnimationChanged event)
    {
        // Log Maiden's animations and reset countdown for specific animations
        if (event.getActor() == maiden)
        {
            int animationId = event.getActor().getAnimation();

            // Check if the animation ID matches any of Maiden's attack animations
            for (int attackAnimationId : MAIDEN_ATTACK_ANIMATION_IDS)
            {
                if (animationId == attackAnimationId)
                {
                    if (maidenCountdown == -1)
                    {
                        log.info("Maiden's first attack animation detected. Starting countdown.");
                    }
                    maidenCountdown = MAIDEN_ATTACK_INTERVAL_TICKS; // Start or reset countdown when attack animation is detected
                    maidenResetThisTick = true; // Mark that the countdown was reset this tick
                    log.info("Maiden attack animation detected: {}", animationId);
                    break;
                }
            }

            // Log all Maiden animations for debugging
            log.info("Maiden animation ID: {}", animationId);
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (maiden != null && maidenCountdown > 0)
        {
            if (!maidenResetThisTick)
            {
                maidenCountdown--;
            }
        }
        maidenResetThisTick = false; // Reset the flag for the next tick
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