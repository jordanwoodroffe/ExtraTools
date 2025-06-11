package com.yamahelper;

import net.runelite.client.config.*;
import java.awt.Color;

@ConfigGroup("yamahelper")
public interface YamaHelperConfig extends Config {

    @ConfigSection(name = "Attack Style Overlay", description = "Options for highlighting Yama based on attack style", position = 0)
    String attackStyleOverlay = "attackStyleOverlay";

    @ConfigSection(name = "Attack Timer", description = "Options for attack timing displays", position = 1)
    String attackTimer = "attackTimer";

    @ConfigSection(name = "Boulder Highlights", description = "Options for boulder/tile highlighting", position = 2)
    String boulderHighlights = "boulderHighlights";

    // Attack Style Overlay Options
    @ConfigItem(keyName = "highlightYama", name = "Highlight Yama", description = "Highlight Yama NPCs based on their combat phase", section = attackStyleOverlay, position = 0)
    default boolean highlightYama() {
        return true;
    }

    @ConfigItem(keyName = "meleeColor", name = "Melee Phase Color", description = "Color to highlight Yama during melee phase", section = attackStyleOverlay, position = 1)
    default Color meleeColor() {
        return new Color(240, 100, 100, 120); // Soft red
    }

    @ConfigItem(keyName = "rangedColor", name = "Ranged Phase Color", description = "Color to highlight Yama during ranged phase", section = attackStyleOverlay, position = 2)
    default Color rangedColor() {
        return new Color(144, 238, 144); // Soft green
    }

    @ConfigItem(keyName = "magicColor", name = "Magic Phase Color", description = "Color to highlight Yama during magic phase", section = attackStyleOverlay, position = 3)
    default Color magicColor() {
        return new Color(100, 149, 237); // Soft blue
    }

    @ConfigItem(keyName = "fireSpecialColor", name = "Fire Special Color", description = "Color to highlight Yama during fire special attacks", section = attackStyleOverlay, position = 4)
    default Color fireSpecialColor() {
        return new Color(255, 200, 100, 50); // Lighter soft orange
    }

    @ConfigItem(keyName = "shadowSpecialColor", name = "Shadow Special Color", description = "Color to highlight Yama during shadow special attacks", section = attackStyleOverlay, position = 5)
    default Color shadowSpecialColor() {
        return new Color(180, 150, 240, 50); // Lighter soft purple
    }

    // Attack Timer Options
    @ConfigItem(keyName = "showAttackTimers", name = "Show Attack Timers", description = "Display attack countdown timers on Yama", section = attackTimer, position = 0)
    default boolean showAttackTimers() {
        return true;
    }

    @ConfigItem(keyName = "timerTextSize", name = "Timer Text Size", description = "Size of the attack timer text", section = attackTimer, position = 1)
    default int timerTextSize() {
        return 36;
    }

    @ConfigItem(keyName = "warningColor", name = "Warning Color", description = "Color for timer when attack is imminent (1 tick remaining)", section = attackTimer, position = 2)
    default Color warningColor() {
        return new Color(255, 0, 0); // Bright red
    }

    @ConfigItem(keyName = "normalTimerColor", name = "Normal Timer Color", description = "Color for timer during normal countdown", section = attackTimer, position = 3)
    default Color normalTimerColor() {
        return new Color(0, 255, 255); // Bright teal
    }

    // Boulder Highlights Options
    @ConfigItem(keyName = "highlightBoulders", name = "Highlight Boulder Areas", description = "Highlight Yama's 5x5 tile area for positioning reference", section = boulderHighlights, position = 0)
    default boolean highlightBoulders() {
        return true;
    }

    @ConfigItem(keyName = "boulderTransparency", name = "Boulder Highlight Transparency", description = "Transparency level for boulder area highlighting (0-255)", section = boulderHighlights, position = 1)
    default int boulderTransparency() {
        return 50;
    }

    @ConfigItem(keyName = "showBorderOnly", name = "Show Border Only", description = "Only show the border of Yama's area instead of filling it", section = boulderHighlights, position = 2)
    default boolean showBorderOnly() {
        return false;
    }
}
