package com.pvmkits;

import com.google.inject.Provides;
import com.pvmkits.bosses.yama.YamaHandler;
import com.pvmkits.bosses.yama.YamaOverlay;
import com.pvmkits.bosses.phosani.PhosaniHandler;
import com.pvmkits.bosses.phosani.PhosaniOverlay;
import com.pvmkits.bosses.nightmare.NightmareHandler;
import com.pvmkits.bosses.nightmare.NightmareOverlay;
import com.pvmkits.bosses.maggotking.MaggotKingHandler;
import com.pvmkits.bosses.maggotking.MaggotKingOverlay;
import com.pvmkits.bosses.tob.TheatreHandler;
import com.pvmkits.bosses.tob.TheatreOverlay;
import com.pvmkits.bosses.tob.TheatreTickEatOverlay;
import com.pvmkits.bosses.cox.OlmHandler;
import com.pvmkits.bosses.cox.OlmOverlay;
import com.pvmkits.bosses.cox.ShamanHandler;
import com.pvmkits.bosses.cox.ShamanOverlay;
import com.pvmkits.bosses.cox.TektonHandler;
import com.pvmkits.bosses.cox.TektonOverlay;
import com.pvmkits.bosses.cox.VasaHandler;
import com.pvmkits.bosses.cox.VasaOverlay;
import com.pvmkits.bosses.mokhaiotl.MokhaiotlHandler;
import com.pvmkits.bosses.mokhaiotl.MokhaiotlOverlay;
import com.pvmkits.bosses.mokhaiotl.MokhaiotlPrayerOverlay;

import com.pvmkits.core.BossHandler;
import com.pvmkits.core.LocatorOrbOverlay;
import com.pvmkits.core.PrayerFlickOverlay;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Renderable;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.callback.Hooks;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@PluginDescriptor(name = "PVM Kits", description = "Multi-boss PVM assistance toolkit with mechanics overlays and timers", tags = {
        "combat", "boss", "pvm", "mechanics", "yama", "phosani", "nightmare", "verzik",
    "tob", "maggot" }, enabledByDefault = false)
public class PvmKitsPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private PvmKitsConfig config;

    @Inject
    private Hooks hooks;

    @Inject
    private YamaHandler yamaHandler;

    @Inject
    private YamaOverlay yamaOverlay;

    @Inject
    private PhosaniHandler phosaniHandler;

    @Inject
    private PhosaniOverlay phosaniOverlay;

    @Inject
    private NightmareHandler nightmareHandler;

    @Inject
    private NightmareOverlay nightmareOverlay;

    @Inject
    private TheatreHandler theatreHandler;

    @Inject
    private TheatreOverlay theatreOverlay;

    @Inject
    private TheatreTickEatOverlay theatreTickEatOverlay;

    @Inject
    private MaggotKingHandler maggotKingHandler;

    @Inject
    private MaggotKingOverlay maggotKingOverlay;

    @Inject
    private OlmHandler olmHandler;

    @Inject
    private OlmOverlay olmOverlay;

    @Inject
    private TektonHandler tektonHandler;

    @Inject
    private TektonOverlay tektonOverlay;

    @Inject
    private ShamanHandler shamanHandler;

    @Inject
    private ShamanOverlay shamanOverlay;

    @Inject
    private VasaHandler vasaHandler;

    @Inject
    private VasaOverlay vasaOverlay;

    @Inject
    private MokhaiotlHandler mokhaiotlHandler;

    @Inject
    private MokhaiotlOverlay mokhaiotlOverlay;

    @Inject
    private MokhaiotlPrayerOverlay mokhaiotlPrayerOverlay;

    @Inject
    private PrayerFlickOverlay prayerFlickOverlay;

    @Inject
    private LocatorOrbOverlay locatorOrbOverlay;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private PvmKitsPanel panel;

    private NavigationButton navButton;

    // List of all boss handlers - Yama, Verzik...
    private List<BossHandler> bossHandlers;

    // Current active boss handler
    private BossHandler activeBossHandler;

    // Render hook used to hide the Doom's earthen shield ("orb") 3D model while its
    // true tile is shown by the overlay. Any listener returning false drops the
    // entity for that frame, so this does not conflict with other hide plugins.
    private final Hooks.RenderableDrawListener orbDrawListener = this::shouldDrawEntity;

    // Wall-clock time of the most recent game tick, used by the prayer flick
    // heartbeat overlay as a liveness check (no recent tick = draw nothing).
    private long lastGameTickMillis;

    // Smoothed phase reference for the prayer flick heartbeat. Real game ticks
    // jitter around 600ms (network/server), so a metronome pinned to each raw tick
    // jitters too. This anchor free-runs on a 600ms grid and is nudged only a
    // fraction of the way toward each real tick, low-pass filtering that jitter so
    // the flash cadence stays even while still tracking the server rhythm. A large
    // gap (lag spike / relog) snaps a fresh anchor.
    private long tickPhaseAnchorMillis;
    private static final long NOMINAL_TICK_MILLIS = 600L;
    private static final double TICK_PHASE_CORRECTION_GAIN = 0.2;
    private static final long TICK_PHASE_RESYNC_MILLIS = 1000L;

    @Override
    protected void startUp() throws Exception {
        // Initialize boss handlers list
        bossHandlers = new ArrayList<>();
        bossHandlers.add(yamaHandler);
        bossHandlers.add(phosaniHandler);
        bossHandlers.add(nightmareHandler);
        bossHandlers.add(theatreHandler);
        bossHandlers.add(maggotKingHandler);
        bossHandlers.add(olmHandler);
        bossHandlers.add(tektonHandler);
        bossHandlers.add(shamanHandler);
        bossHandlers.add(vasaHandler);
        bossHandlers.add(mokhaiotlHandler);

        // TODO: Add other boss handlers here when implemented
        // bossHandlers.add(nyloHandler);
        // etc.

        activeBossHandler = null;
        overlayManager.add(yamaOverlay);
        overlayManager.add(phosaniOverlay);
        overlayManager.add(nightmareOverlay);
        overlayManager.add(theatreOverlay);
        overlayManager.add(theatreTickEatOverlay);
        overlayManager.add(maggotKingOverlay);
        overlayManager.add(tektonOverlay);
        overlayManager.add(olmOverlay);
        overlayManager.add(shamanOverlay);
        overlayManager.add(vasaOverlay);
        overlayManager.add(mokhaiotlOverlay);
        overlayManager.add(mokhaiotlPrayerOverlay);
        overlayManager.add(prayerFlickOverlay);
        overlayManager.add(locatorOrbOverlay);

        hooks.registerRenderableDrawListener(orbDrawListener);

        final BufferedImage icon = createPanelIcon();
        navButton = NavigationButton.builder()
            .tooltip("PVM Kits")
            .icon(icon)
            .priority(7)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

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
        overlayManager.remove(nightmareOverlay);
        overlayManager.remove(theatreOverlay);
        overlayManager.remove(theatreTickEatOverlay);
        overlayManager.remove(maggotKingOverlay);
        overlayManager.remove(tektonOverlay);
        overlayManager.remove(olmOverlay);
        overlayManager.remove(shamanOverlay);
        overlayManager.remove(vasaOverlay);
        overlayManager.remove(mokhaiotlOverlay);
        overlayManager.remove(mokhaiotlPrayerOverlay);
        overlayManager.remove(prayerFlickOverlay);
        overlayManager.remove(locatorOrbOverlay);

        hooks.unregisterRenderableDrawListener(orbDrawListener);

        clientToolbar.removeNavigation(navButton);

        log.info("PVM Kits plugin stopped!");
    }

    private static BufferedImage createPanelIcon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(200, 83, 0));
        g.fillRoundRect(0, 0, 16, 16, 5, 5);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Dialog", Font.BOLD, 9));
        g.drawString("PK", 2, 11);
        g.dispose();
        return img;
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        // Timestamp the tick so the prayer flick heartbeat can interpolate its pulse
        // between ticks and stay locked to the server rhythm.
        updateTickPhaseAnchor(System.currentTimeMillis());

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

    // Advances the smoothed heartbeat phase anchor. Snaps to the tick on the first
    // tick or after a gap (lag/relog); otherwise nudges the free-running 600ms grid
    // a fraction of the way toward the real tick so per-tick jitter is filtered out.
    private void updateTickPhaseAnchor(long now) {
        long sinceLast = now - lastGameTickMillis;
        if (tickPhaseAnchorMillis == 0L || sinceLast < 0 || sinceLast > TICK_PHASE_RESYNC_MILLIS) {
            tickPhaseAnchorMillis = now;
        } else {
            long steps = Math.round((now - tickPhaseAnchorMillis) / (double) NOMINAL_TICK_MILLIS);
            long predicted = tickPhaseAnchorMillis + steps * NOMINAL_TICK_MILLIS;
            long error = now - predicted;
            tickPhaseAnchorMillis += Math.round(error * TICK_PHASE_CORRECTION_GAIN);
        }
        lastGameTickMillis = now;
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
        // Forward to Phosani handler for spore tracking and Yama handler for glyphs
        if (phosaniHandler != null) {
            phosaniHandler.onGameObjectSpawned(event);
        }
        if (nightmareHandler != null) {
            nightmareHandler.onGameObjectSpawned(event);
        }
        if (yamaHandler != null) {
            yamaHandler.onGameObjectSpawned(event);
        }
        if (maggotKingHandler != null) {
            maggotKingHandler.onGameObjectSpawned(event);
        }
        if (mokhaiotlHandler != null) {
            mokhaiotlHandler.onGameObjectSpawned(event);
        }
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event) {
        // Forward to Phosani handler for spore tracking and Yama handler for glyphs
        if (phosaniHandler != null) {
            phosaniHandler.onGameObjectDespawned(event);
        }
        if (nightmareHandler != null) {
            nightmareHandler.onGameObjectDespawned(event);
        }
        if (yamaHandler != null) {
            yamaHandler.onGameObjectDespawned(event);
        }
        if (maggotKingHandler != null) {
            maggotKingHandler.onGameObjectDespawned(event);
        }
        if (mokhaiotlHandler != null) {
            mokhaiotlHandler.onGameObjectDespawned(event);
        }
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        if (activeBossHandler != null) {
            activeBossHandler.onNpcSpawned(event);
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event) {
        if (activeBossHandler != null) {
            activeBossHandler.onNpcDespawned(event);
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        if (activeBossHandler != null) {
            activeBossHandler.onHitsplatApplied(event);
        }
    }

    // Give demonic larvae Attack priority so they can be clicked while stacked under
    // the Doom. Runs once per menu build (after the client sorts entries) and moves
    // larva entries to the end of the array (the default left-click / top of menu).
    @Subscribe
    public void onPostMenuSort(PostMenuSort event) {
        if (mokhaiotlHandler == null || !config.mokhaiotlLarvaeMenuSwap()
                || activeBossHandler != mokhaiotlHandler) {
            return;
        }

        MenuEntry[] entries = client.getMenuEntries();
        if (entries.length < 2) {
            return;
        }

        boolean hasLarva = false;
        for (MenuEntry entry : entries) {
            if (MokhaiotlHandler.isLarva(entry.getNpc())) {
                hasLarva = true;
                break;
            }
        }
        if (!hasLarva) {
            return;
        }

        MenuEntry[] reordered = new MenuEntry[entries.length];
        int idx = 0;
        for (MenuEntry entry : entries) {
            if (!MokhaiotlHandler.isLarva(entry.getNpc())) {
                reordered[idx++] = entry;
            }
        }
        for (MenuEntry entry : entries) {
            if (MokhaiotlHandler.isLarva(entry.getNpc())) {
                reordered[idx++] = entry;
            }
        }
        client.setMenuEntries(reordered);
    }

    // Hides the Doom's earthen shield ("orb") model during the Mokhaiotl fight so it
    // does not obscure the arena; its true tile is still drawn by the overlay.
    private boolean shouldDrawEntity(Renderable renderable, boolean drawingUI) {
        if (renderable instanceof NPC && MokhaiotlHandler.isEarthenShield((NPC) renderable)
                && config.hideMokhaiotlOrbModel() && activeBossHandler == mokhaiotlHandler) {
            return false;
        }
        return true;
    }

    // Getters for access by overlays and other components
    public BossHandler getActiveBossHandler() {
        return activeBossHandler;
    }

    // Wall-clock time (ms) of the most recent game tick (heartbeat liveness check).
    public long getLastGameTickMillis() {
        return lastGameTickMillis;
    }

    // Smoothed 600ms-grid phase anchor (ms) for the prayer flick heartbeat, so its
    // flash cadence stays evenly spaced despite per-tick server jitter.
    public long getTickPhaseAnchorMillis() {
        return tickPhaseAnchorMillis;
    }

    public YamaHandler getYamaHandler() {
        return yamaHandler;
    }

    public PhosaniHandler getPhosaniHandler() {
        return phosaniHandler;
    }

    public NightmareHandler getNightmareHandler() {
        return nightmareHandler;
    }

    public TheatreHandler getTheatreHandler() {
        return theatreHandler;
    }

    public MaggotKingHandler getMaggotKingHandler() {
        return maggotKingHandler;
    }

    public TektonHandler getTektonHandler() {
        return tektonHandler;
    }

    public OlmHandler getOlmHandler() {
        return olmHandler;
    }

    public ShamanHandler getShamanHandler() {
        return shamanHandler;
    }

    public VasaHandler getVasaHandler() {
        return vasaHandler;
    }

    public MokhaiotlHandler getMokhaiotlHandler() {
        return mokhaiotlHandler;
    }

    @Provides
    PvmKitsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(PvmKitsConfig.class);
    }
}
