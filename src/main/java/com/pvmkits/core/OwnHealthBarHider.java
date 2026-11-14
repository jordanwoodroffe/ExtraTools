package com.pvmkits.core;

import com.pvmkits.PvmKitsConfig;
import net.runelite.api.Client;
import net.runelite.api.HealthBarConfig;
import net.runelite.api.Renderable;
import net.runelite.api.SpritePixels;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Hides the game's own overhead health bar on the local player only, so it never
 * shows over the restyled bars from {@link StatusBarsOverlay}. NPC health bars and
 * the player's hitsplats, prayer icons and chat text are left untouched.
 *
 * The client has no per-actor health bar switch, and a health bar's sprites are
 * shared by every actor using that bar type (most NPCs share the player's 30px
 * bar), so the sprites cannot simply be overridden. Instead this uses the render
 * callback the client calls right before drawing each actor's 2D elements: just
 * before the local player's are drawn, the loaded health bar sprites are blanked
 * (pixel 0 is transparent), and they are put back as soon as the client moves on
 * to any other entity (and again at the start of every frame as a safety net).
 */
@Singleton
public class OwnHealthBarHider implements RenderCallback {

    private final Client client;
    private final PvmKitsConfig config;
    private final ClientThread clientThread;
    private final RenderCallbackManager renderCallbackManager;

    // Every health bar definition the client has loaded (reported through
    // PostHealthBarConfig). Weak, so definitions the client evicts drop out.
    private final Set<HealthBarConfig> healthBarConfigs = Collections.newSetFromMap(new WeakHashMap<>());

    // Sprites currently blanked -> their original pixels, by identity.
    private final Map<SpritePixels, int[]> blanked = new IdentityHashMap<>();

    @Inject
    public OwnHealthBarHider(Client client, PvmKitsConfig config, ClientThread clientThread,
            RenderCallbackManager renderCallbackManager) {
        this.client = client;
        this.config = config;
        this.clientThread = clientThread;
        this.renderCallbackManager = renderCallbackManager;
    }

    public void startUp() {
        renderCallbackManager.register(this);
        // Definitions loaded before the plugin started never reported in; clearing
        // the cache makes the client reload them, firing PostHealthBarConfig again.
        clientThread.invokeLater(client::resetHealthBarCaches);
    }

    public void shutDown() {
        renderCallbackManager.unregister(this);
        clientThread.invoke(() -> {
            restore();
            healthBarConfigs.clear();
        });
    }

    /** Records a loaded health bar definition (from the PostHealthBarConfig event). */
    public void onHealthBarConfig(HealthBarConfig healthBarConfig) {
        if (healthBarConfig != null) {
            healthBarConfigs.add(healthBarConfig);
        }
    }

    /** Called at the start of every frame so no blanked sprite outlives a frame. */
    public void onBeforeRender() {
        restore();
    }

    @Override
    public boolean addEntity(Renderable renderable, boolean ui) {
        if (ui && renderable != null && renderable == client.getLocalPlayer() && enabled()) {
            blank();
        } else {
            restore();
        }
        // Never hides the entity itself; only the health bar sprites are touched.
        return true;
    }

    private boolean enabled() {
        return config.showStyledHealthBar() && config.hideGameHealthBar();
    }

    private void blank() {
        for (HealthBarConfig healthBar : healthBarConfigs) {
            blankSprite(healthBar.getHealthBarFrontSprite());
            blankSprite(healthBar.getHealthBarBackSprite());
        }
    }

    private void blankSprite(SpritePixels sprite) {
        if (sprite == null || blanked.containsKey(sprite)) {
            return;
        }
        int[] pixels = sprite.getPixels();
        if (pixels == null) {
            return;
        }
        blanked.put(sprite, pixels.clone());
        Arrays.fill(pixels, 0);
    }

    private void restore() {
        if (blanked.isEmpty()) {
            return;
        }
        for (Map.Entry<SpritePixels, int[]> entry : blanked.entrySet()) {
            int[] pixels = entry.getKey().getPixels();
            int[] original = entry.getValue();
            if (pixels != null) {
                System.arraycopy(original, 0, pixels, 0, Math.min(pixels.length, original.length));
            }
        }
        blanked.clear();
    }
}
