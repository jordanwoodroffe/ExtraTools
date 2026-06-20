package com.pvmkits;

import net.runelite.client.config.*;
import java.awt.Color;

@ConfigGroup("pvmkits")
public interface PvmKitsConfig extends Config {

    @ConfigSection(name = "General", description = "General PVM Kits settings", position = 0, closedByDefault = true)
    String general = "general";

    @ConfigSection(name = "Yama", description = "Yama boss mechanics assistance", position = 1, closedByDefault = true)
    String yama = "yama";

    @ConfigSection(name = "Phosani's Nightmare", description = "Phosani's Nightmare boss mechanics assistance", position = 2, closedByDefault = true)
    String phosani = "phosani";

    // General Settings
    @ConfigItem(keyName = "showDebugInfo", name = "Show Debug Info", description = "Display debug information in chat", section = general, position = 0)
    default boolean showDebugInfo() {
        return false;
    }

    // Yama Settings
    @ConfigItem(keyName = "highlightYama", name = "Highlight Yama", description = "Highlight Yama NPCs based on their combat phase", section = yama, position = 0)
    default boolean highlightYama() {
        return true;
    }

    @ConfigItem(keyName = "meleeColor", name = "Melee Phase Color", description = "Color to highlight Yama during melee phase", section = yama, position = 1)
    default Color meleeColor() {
        return new Color(240, 100, 100, 120); // Soft red
    }

    @ConfigItem(keyName = "rangedColor", name = "Ranged Phase Color", description = "Color to highlight Yama during ranged phase", section = yama, position = 2)
    default Color rangedColor() {
        return new Color(144, 238, 144); // Soft green
    }

    @ConfigItem(keyName = "magicColor", name = "Magic Phase Color", description = "Color to highlight Yama during magic phase", section = yama, position = 3)
    default Color magicColor() {
        return new Color(100, 149, 237); // Soft blue
    }

    @ConfigItem(keyName = "fireSpecialColor", name = "Fire Special Color", description = "Color to highlight Yama during fire special attacks", section = yama, position = 4)
    default Color fireSpecialColor() {
        return new Color(255, 200, 100, 50); // Lighter soft orange
    }

    @ConfigItem(keyName = "shadowSpecialColor", name = "Shadow Special Color", description = "Color to highlight Yama during shadow special attacks", section = yama, position = 5)
    default Color shadowSpecialColor() {
        return new Color(180, 150, 240, 50); // Lighter soft purple
    }

    @ConfigItem(keyName = "showAttackTimers", name = "Show Attack Timers", description = "Display attack countdown timers on Yama", section = yama, position = 6)
    default boolean showAttackTimers() {
        return true;
    }

    @ConfigItem(keyName = "timerTextSize", name = "Timer Text Size", description = "Size of the attack timer text", section = yama, position = 7)
    default int timerTextSize() {
        return 36;
    }

    @ConfigItem(keyName = "warningColor", name = "Warning Color", description = "Color for timer when attack is imminent (1 tick remaining)", section = yama, position = 8)
    default Color warningColor() {
        return new Color(255, 0, 0); // Bright red
    }

    @ConfigItem(keyName = "normalTimerColor", name = "Normal Timer Color", description = "Color for timer during normal countdown", section = yama, position = 9)
    default Color normalTimerColor() {
        return new Color(0, 255, 255); // Bright teal
    }

    @ConfigItem(keyName = "highlightBoulders", name = "Highlight Glyphs", description = "Highlight glyph objects that spawn on the floor during the Yama fight (fire glyphs on fire attacks, shadow glyphs on shadow attacks)", section = yama, position = 10)
    default boolean highlightBoulders() {
        return true;
    }

    @ConfigItem(keyName = "fireGlyphColor", name = "Fire Glyph Color", description = "Color used to highlight fire glyphs during Yama's fire elemental attack", section = yama, position = 11)
    default Color fireGlyphColor() {
        return new Color(255, 100, 0); // Orange
    }

    @ConfigItem(keyName = "shadowGlyphColor", name = "Shadow Glyph Color", description = "Color used to highlight shadow glyphs during Yama's shadow elemental attack", section = yama, position = 12)
    default Color shadowGlyphColor() {
        return new Color(150, 80, 220); // Purple
    }

    @ConfigItem(keyName = "glyphTransparency", name = "Glyph Highlight Transparency", description = "Transparency level for glyph highlighting (0-255)", section = yama, position = 13)
    default int glyphTransparency() {
        return 80;
    }

    @ConfigItem(keyName = "boulderTransparency", name = "Yama Area Highlight Transparency", description = "Transparency level for Yama's 5x5 area highlighting (0-255)", section = yama, position = 14)
    default int boulderTransparency() {
        return 50;
    }

    @ConfigItem(keyName = "showBorderOnly", name = "Show Border Only", description = "Only show the border of Yama's area instead of filling it", section = yama, position = 15)
    default boolean showBorderOnly() {
        return false;
    }

    // Phosani's Nightmare Settings
    @ConfigItem(keyName = "highlightPhosani", name = "Highlight Phosani", description = "Highlight Phosani's Nightmare based on their combat phase", section = phosani, position = 0)
    default boolean highlightPhosani() {
        return true;
    }

    @ConfigItem(keyName = "phosaniMeleeColor", name = "Melee Phase Color", description = "Color to highlight Phosani during melee phase", section = phosani, position = 1)
    default Color phosaniMeleeColor() {
        return new Color(240, 100, 100, 120); // Soft red
    }

    @ConfigItem(keyName = "phosaniRangedColor", name = "Ranged Phase Color", description = "Color to highlight Phosani during ranged phase", section = phosani, position = 2)
    default Color phosaniRangedColor() {
        return new Color(144, 238, 144); // Soft green
    }

    @ConfigItem(keyName = "phosaniMagicColor", name = "Magic Phase Color", description = "Color to highlight Phosani during magic phase", section = phosani, position = 3)
    default Color phosaniMagicColor() {
        return new Color(100, 149, 237); // Soft blue
    }

    @ConfigItem(keyName = "phosaniSpecialColor", name = "Special Attack Color", description = "Color to highlight Phosani during special attacks", section = phosani, position = 4)
    default Color phosaniSpecialColor() {
        return new Color(255, 165, 0, 100); // Orange
    }

    @ConfigItem(keyName = "showPhosaniAttackTimers", name = "Show Attack Timers", description = "Display attack countdown timers on Phosani's Nightmare", section = phosani, position = 5)
    default boolean showPhosaniAttackTimers() {
        return true;
    }

    @ConfigItem(keyName = "phosaniTimerTextSize", name = "Timer Text Size", description = "Size of the attack timer text", section = phosani, position = 6)
    default int phosaniTimerTextSize() {
        return 36;
    }

    @ConfigItem(keyName = "phosaniWarningColor", name = "Warning Color", description = "Color for timer when attack is imminent (1 tick remaining)", section = phosani, position = 7)
    default Color phosaniWarningColor() {
        return new Color(255, 0, 0); // Bright red
    }

    @ConfigItem(keyName = "phosaniNormalTimerColor", name = "Normal Timer Color", description = "Color for timer during normal countdown", section = phosani, position = 8)
    default Color phosaniNormalTimerColor() {
        return new Color(0, 255, 255); // Bright teal
    }

    @ConfigItem(keyName = "phosaniTransparency", name = "Highlight Transparency", description = "Transparency level for Phosani tiles, sleepwalkers, husks, and spore highlighting (0-255)", section = phosani, position = 9)
    default int phosaniTransparency() {
        return 50;
    }

    @ConfigItem(keyName = "highlightPhosaniParasiteOutline", name = "Highlight Parasite Player Outline", description = "Draw a red outline around your player while you are infected by Phosani's parasite", section = phosani, position = 10)
    default boolean highlightPhosaniParasiteOutline() {
        return true;
    }

    @ConfigItem(keyName = "highlightSporeDangerZones", name = "Highlight Spore Danger Zones", description = "Show red borders around dangerous 3x3 spore areas", section = phosani, position = 11)
    default boolean highlightSporeDangerZones() {
        return true;
    }

    @ConfigItem(keyName = "highlightSleepwalkers", name = "Highlight Sleepwalkers & Husks", description = "Highlight sleepwalkers and husks in soft red", section = phosani, position = 12)
    default boolean highlightSleepwalkers() {
        return true;
    }

    @ConfigItem(keyName = "showPhosaniSafeTile", name = "Show Shadow Phase Safe Tile", description = "Highlight a safe tile to stand on when undead hands spawn during the shadow phase", section = phosani, position = 13)
    default boolean showPhosaniSafeTile() {
        return true;
    }

    @ConfigItem(keyName = "phosaniSafeTileColor", name = "Safe Tile Color", description = "Color used to highlight the shadow phase safe tile", section = phosani, position = 14)
    default Color phosaniSafeTileColor() {
        return new Color(0, 255, 0); // Green
    }

    @ConfigItem(keyName = "highlightPhosaniSurge", name = "Highlight Surge Path", description = "Highlight the straight-line danger zone when Phosani surges (charges) across the room", section = phosani, position = 15)
    default boolean highlightPhosaniSurge() {
        return true;
    }

    @ConfigItem(keyName = "phosaniSurgeColor", name = "Surge Path Color", description = "Color used to highlight Phosani's surge (charge) flight path", section = phosani, position = 16)
    default Color phosaniSurgeColor() {
        return new Color(255, 0, 0); // Red
    }

    @ConfigItem(keyName = "highlightPhosaniTotems", name = "Highlight Totems", description = "Highlight the four totem NPCs during the totem charging phase, coloured by whether they still need charging or are full", section = phosani, position = 17)
    default boolean highlightPhosaniTotems() {
        return true;
    }

    @ConfigItem(keyName = "phosaniTotemEmptyColor", name = "Totem (Needs Charging) Color", description = "Color used to highlight totems that still need to be charged", section = phosani, position = 18)
    default Color phosaniTotemEmptyColor() {
        return new Color(255, 140, 0); // Orange
    }

    @ConfigItem(keyName = "phosaniTotemFullColor", name = "Totem (Charged) Color", description = "Color used to highlight totems that are fully charged", section = phosani, position = 19)
    default Color phosaniTotemFullColor() {
        return new Color(0, 255, 0); // Green
    }

    // TODO: Add other boss config sections here when new bosses are implemented
    // Example:
    // @ConfigSection(name = "Verzik Settings", description = "Verzik boss
    // mechanics", position = 4)
    // String verzikSettings = "verzikSettings";
}
