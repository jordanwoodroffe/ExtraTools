package com.pvmkits;

import com.pvmkits.bosses.tob.TheatreHandler;
import net.runelite.client.config.*;
import java.awt.Color;

@ConfigGroup("pvmkits")
public interface PvmKitsConfig extends Config {

    @ConfigSection(name = "General", description = "General PVM Kits settings", position = 0, closedByDefault = true)
    String general = "general";

    @ConfigSection(name = "Yama", description = "Yama boss mechanics assistance", position = 1, closedByDefault = true)
    String yama = "yama";

    @ConfigSection(name = "Phosani's & The Nightmare", description = "Phosani's Nightmare and The Nightmare boss mechanics assistance", position = 2, closedByDefault = true)
    String phosani = "phosani";

    @ConfigSection(name = "Theatre of Blood", description = "Theatre of Blood raid mechanics assistance", position = 3, closedByDefault = true)
    String tob = "tob";

    @ConfigSection(name = "Maggot King", description = "Maggot King boss discovery and mechanics logging", position = 4, closedByDefault = false)
    String maggotKing = "maggotKing";

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

    // Phosani's Nightmare & The Nightmare Settings
    @ConfigItem(keyName = "highlightPhosani", name = "Highlight Boss", description = "Highlight the boss based on their combat phase", section = phosani, position = 0)
    default boolean highlightPhosani() {
        return true;
    }

    @ConfigItem(keyName = "phosaniMeleeColor", name = "Melee Phase Color", description = "Color to highlight Phosani during melee phase", section = phosani, position = 11)
    default Color phosaniMeleeColor() {
        return new Color(240, 100, 100, 120); // Soft red
    }

    @ConfigItem(keyName = "phosaniRangedColor", name = "Ranged Phase Color", description = "Color to highlight Phosani during ranged phase", section = phosani, position = 12)
    default Color phosaniRangedColor() {
        return new Color(144, 238, 144); // Soft green
    }

    @ConfigItem(keyName = "phosaniMagicColor", name = "Magic Phase Color", description = "Color to highlight Phosani during magic phase", section = phosani, position = 13)
    default Color phosaniMagicColor() {
        return new Color(100, 149, 237); // Soft blue
    }

    @ConfigItem(keyName = "showPhosaniAttackTimers", name = "Show Attack Timers", description = "Display attack countdown timers on the boss", section = phosani, position = 1)
    default boolean showPhosaniAttackTimers() {
        return true;
    }

    @ConfigItem(keyName = "phosaniTimerTextSize", name = "Timer Text Size", description = "Size of the attack timer text", section = phosani, position = 2)
    default int phosaniTimerTextSize() {
        return 36;
    }

    @ConfigItem(keyName = "phosaniWarningColor", name = "Warning Color", description = "Color for timer when attack is imminent (1 tick remaining)", section = phosani, position = 3)
    default Color phosaniWarningColor() {
        return new Color(255, 0, 0); // Bright red
    }

    @ConfigItem(keyName = "phosaniNormalTimerColor", name = "Normal Timer Color", description = "Color for timer during normal countdown", section = phosani, position = 4)
    default Color phosaniNormalTimerColor() {
        return new Color(0, 255, 255); // Bright teal
    }

    @ConfigItem(keyName = "phosaniTransparency", name = "Highlight Transparency", description = "Transparency level for tiles, sleepwalkers, husks, and spore highlighting (0-255)", section = phosani, position = 5)
    default int phosaniTransparency() {
        return 50;
    }

    @ConfigItem(keyName = "highlightPhosaniParasiteOutline", name = "Highlight Parasite Player Outline", description = "Draw a red outline around your player while you are infected by a parasite", section = phosani, position = 6)
    default boolean highlightPhosaniParasiteOutline() {
        return true;
    }

    @ConfigItem(keyName = "highlightSporeDangerZones", name = "Highlight Spore Danger Zones", description = "Show red borders around dangerous 3x3 spore areas", section = phosani, position = 14)
    default boolean highlightSporeDangerZones() {
        return true;
    }

    @ConfigItem(keyName = "highlightSleepwalkers", name = "Highlight Sleepwalkers & Husks", description = "Highlight sleepwalkers and husks in soft red", section = phosani, position = 15)
    default boolean highlightSleepwalkers() {
        return true;
    }

    @ConfigItem(keyName = "showPhosaniSafeTile", name = "Show Shadow Phase Safe Tile", description = "Highlight a safe tile to stand on when undead hands spawn during the shadow phase", section = phosani, position = 7)
    default boolean showPhosaniSafeTile() {
        return true;
    }

    @ConfigItem(keyName = "phosaniSafeTileColor", name = "Safe Tile Color", description = "Color used to highlight the shadow phase safe tile", section = phosani, position = 8)
    default Color phosaniSafeTileColor() {
        return new Color(0, 255, 0); // Green
    }

    @ConfigItem(keyName = "highlightPhosaniSurge", name = "Highlight Surge Path", description = "Highlight the straight-line danger zone when the boss surges (charges) across the room", section = phosani, position = 9)
    default boolean highlightPhosaniSurge() {
        return true;
    }

    @ConfigItem(keyName = "phosaniSurgeColor", name = "Surge Path Color", description = "Color used to highlight the boss's surge (charge) flight path", section = phosani, position = 10)
    default Color phosaniSurgeColor() {
        return new Color(255, 0, 0); // Red
    }

    @ConfigItem(keyName = "highlightPhosaniTotems", name = "Highlight Totems", description = "Highlight the four totem NPCs during the totem charging phase, coloured by whether they still need charging or are full", section = phosani, position = 16)
    default boolean highlightPhosaniTotems() {
        return true;
    }

    @ConfigItem(keyName = "phosaniTotemEmptyColor", name = "Totem (Needs Charging) Color", description = "Color used to highlight totems that still need to be charged", section = phosani, position = 17)
    default Color phosaniTotemEmptyColor() {
        return new Color(255, 140, 0); // Orange
    }

    @ConfigItem(keyName = "phosaniTotemFullColor", name = "Totem (Charged) Color", description = "Color used to highlight totems that are fully charged", section = phosani, position = 18)
    default Color phosaniTotemFullColor() {
        return new Color(0, 255, 0); // Green
    }

    // Theatre of Blood Settings
    @ConfigItem(keyName = "tobShowBloatTimer", name = "Bloat Sleep/Wake Timer", description = "Show how long until the Pestilent Bloat falls asleep and how long until he wakes", section = tob, position = 1)
    default boolean tobShowBloatTimer() {
        return true;
    }

    @ConfigItem(keyName = "tobShowBloatSafeTiles", name = "Bloat Safe Tiles", description = "Highlight tiles hidden from Bloat's line of sight (behind the pillar) with no falling hand, so you can hide as he circles", section = tob, position = 17)
    default boolean tobShowBloatSafeTiles() {
        return true;
    }

    @ConfigItem(keyName = "tobBloatSafeColor", name = "Bloat Safe Tile Color", description = "Color for tiles safe from Bloat's line of sight", section = tob, position = 18)
    default Color tobBloatSafeColor() {
        return new Color(0, 255, 0); // Green
    }


    @ConfigItem(keyName = "tobNyloRole", name = "Nylocas Role", description = "Highlight the Nylocas spiders matching your role: Melee=grey, Range=green, Mage=blue", section = tob, position = 2)
    default TheatreHandler.NyloRole tobNyloRole() {
        return TheatreHandler.NyloRole.OFF;
    }

    @ConfigItem(keyName = "tobNyloMeleeColor", name = "Nylo Melee Color", description = "Highlight color for melee (grey) Nylocas", section = tob, position = 3)
    default Color tobNyloMeleeColor() {
        return new Color(200, 200, 200); // Grey
    }

    @ConfigItem(keyName = "tobNyloRangeColor", name = "Nylo Range Color", description = "Highlight color for range (green) Nylocas", section = tob, position = 4)
    default Color tobNyloRangeColor() {
        return new Color(0, 220, 0); // Green
    }

    @ConfigItem(keyName = "tobNyloMageColor", name = "Nylo Mage Color", description = "Highlight color for mage (blue) Nylocas", section = tob, position = 5)
    default Color tobNyloMageColor() {
        return new Color(0, 130, 255); // Blue
    }

    @ConfigItem(keyName = "tobShowSotetsegTimer", name = "Sotetseg Attack Timer", description = "Show Sotetseg's attack countdown timer", section = tob, position = 7)
    default boolean tobShowSotetsegTimer() {
        return true;
    }

    @ConfigItem(keyName = "tobShowVerzikTimer", name = "Verzik Attack Timer", description = "Show Verzik's attack countdown timer across P1/P2/P3", section = tob, position = 8)
    default boolean tobShowVerzikTimer() {
        return true;
    }

    @ConfigItem(keyName = "tobVerzikP3StyleOverlay", name = "Verzik P3 Attack Style Overlay", description = "Highlight Verzik in P3 with a colour matching her current attack style", section = tob, position = 9)
    default boolean tobVerzikP3StyleOverlay() {
        return true;
    }

    @ConfigItem(keyName = "tobVerzikMeleeColor", name = "Verzik Melee Color", description = "Color to highlight Verzik P3 during melee attacks", section = tob, position = 10)
    default Color tobVerzikMeleeColor() {
        return new Color(240, 100, 100, 120); // Soft red
    }

    @ConfigItem(keyName = "tobVerzikRangeColor", name = "Verzik Range Color", description = "Color to highlight Verzik P3 during ranged attacks", section = tob, position = 11)
    default Color tobVerzikRangeColor() {
        return new Color(144, 238, 144); // Soft green
    }

    @ConfigItem(keyName = "tobVerzikMageColor", name = "Verzik Mage Color", description = "Color to highlight Verzik P3 during magic attacks", section = tob, position = 12)
    default Color tobVerzikMageColor() {
        return new Color(100, 149, 237); // Soft blue
    }

    @ConfigItem(keyName = "tobTimerTextSize", name = "Timer Text Size", description = "Size of the Theatre of Blood attack timer text", section = tob, position = 13)
    default int tobTimerTextSize() {
        return 36;
    }

    @ConfigItem(keyName = "tobWarningColor", name = "Warning Color", description = "Color for timer when an attack is imminent (1 tick remaining)", section = tob, position = 14)
    default Color tobWarningColor() {
        return new Color(255, 0, 0); // Bright red
    }

    @ConfigItem(keyName = "tobNormalTimerColor", name = "Normal Timer Color", description = "Color for timer during normal countdown", section = tob, position = 15)
    default Color tobNormalTimerColor() {
        return new Color(0, 255, 255); // Bright teal
    }

    @ConfigItem(keyName = "tobTransparency", name = "Highlight Transparency", description = "Transparency level for Theatre of Blood highlighting (0-255)", section = tob, position = 16)
    default int tobTransparency() {
        return 70;
    }

    // Maggot King Settings
    @ConfigItem(keyName = "showMaggotKingAttackStyleOverlay", name = "Show Attack Style Overlay", description = "Highlight the Maggot King with a colored overlay for the current attack style (Range: green, Mage: blue)", section = maggotKing, position = 0)
    default boolean showMaggotKingAttackStyleOverlay() {
        return true;
    }

    @ConfigItem(keyName = "highlightMaggotKingLarvae", name = "Highlight Larvae", description = "Highlight the maggot larvae with a hull and tile marker, like Phosani's sleepwalkers", section = maggotKing, position = 2)
    default boolean highlightMaggotKingLarvae() {
        return true;
    }

    @ConfigItem(keyName = "showMaggotKingSafeTile", name = "Show Safe Tile", description = "Highlight nearby safe tiles to step to while acid, split projectiles or the melee slam are active", section = maggotKing, position = 3)
    default boolean showMaggotKingSafeTile() {
        return true;
    }

    @ConfigItem(keyName = "showMaggotKingScreechWarning", name = "Screech Prayer Warning", description = "Outline yourself while the Maggot King screeches until all overhead prayers are turned off", section = maggotKing, position = 4)
    default boolean showMaggotKingScreechWarning() {
        return true;
    }

    @ConfigItem(keyName = "maggotKingRangeStyleColor", name = "Range Attack Style Color", description = "Color to highlight the Maggot King during ranged attacks", section = maggotKing, position = 5)
    default Color maggotKingRangeStyleColor() {
        return new Color(0, 255, 0, 160);
    }

    @ConfigItem(keyName = "maggotKingMageStyleColor", name = "Mage Attack Style Color", description = "Color to highlight the Maggot King during magic attacks", section = maggotKing, position = 6)
    default Color maggotKingMageStyleColor() {
        return new Color(0, 100, 255, 160);
    }

    @ConfigItem(keyName = "maggotKingSafeTileColor", name = "Safe Tile Color", description = "Color used to highlight the best safe tile", section = maggotKing, position = 9)
    default Color maggotKingSafeTileColor() {
        return new Color(0, 255, 0, 150);
    }

    @ConfigItem(keyName = "maggotKingScreechColor", name = "Screech Outline Color", description = "Colour of the outline drawn around you while the Maggot King is screeching until you turn off all overhead prayers", section = maggotKing, position = 10)
    default Color maggotKingScreechColor() {
        return new Color(255, 0, 0);
    }

    @ConfigItem(keyName = "maggotKingScreechStyleColor", name = "Screech Style Color", description = "Colour shown on the prayer/attack-style overlay while the Maggot King is screeching (all overhead prayers off), alongside the outline warning", section = maggotKing, position = 11)
    default Color maggotKingScreechStyleColor() {
        return new Color(255, 255, 0, 160);
    }

    @ConfigItem(keyName = "maggotKingTransparency", name = "Overlay Transparency", description = "Transparency level for Maggot King overlays (0-255)", section = maggotKing, position = 12)
    default int maggotKingTransparency() {
        return 85;
    }

    @ConfigItem(keyName = "maggotKingVerboseLogging", name = "Verbose Event Logging", description = "Log extra graphics, projectile, sound and object events to help decode mechanics on release", section = maggotKing, position = 13)
    default boolean maggotKingVerboseLogging() {
        return true;
    }
}
