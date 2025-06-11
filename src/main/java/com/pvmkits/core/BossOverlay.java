package com.pvmkits.core;

import net.runelite.client.ui.overlay.Overlay;

/**
 * Base interface for boss-specific overlays in PVM Kits
 */
public interface BossOverlay {

    /**
     * Get the overlay instance for rendering
     */
    Overlay getOverlay();

    /**
     * Check if this overlay should be rendered
     */
    boolean shouldRender();

    /**
     * Update overlay state
     */
    void update();
}
