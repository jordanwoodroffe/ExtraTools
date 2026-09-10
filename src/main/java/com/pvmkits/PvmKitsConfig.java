package com.pvmkits;

import net.runelite.client.config.*;
import java.awt.Color;

@ConfigGroup("pvmkits")
public interface PvmKitsConfig extends Config {

    @ConfigSection(name = "Yama", description = "Yama boss mechanics assistance", position = 1, closedByDefault = true)
    String yama = "yama";

    @ConfigSection(name = "Phosani's & The Nightmare", description = "Phosani's Nightmare and The Nightmare boss mechanics assistance", position = 2, closedByDefault = true)
    String phosani = "phosani";

    @ConfigSection(name = "Theatre of Blood", description = "Verzik phase 3 attack style highlighting, Verzik phase 2 / phase 3 and Xarpus attack timers, and Sotetseg's death ball tick eat timer", position = 3, closedByDefault = true)
    String tob = "tob";

    @ConfigSection(name = "Maggot King", description = "Maggot King boss discovery and mechanics logging", position = 4, closedByDefault = false)
    String maggotKing = "maggotKing";

    @ConfigSection(name = "Chambers of Xeric", description = "Great Olm attack-style overlay and Tekton flinch timer", position = 5, closedByDefault = false)
    String cox = "cox";

    @ConfigSection(name = "Doom of Mokhaiotl", description = "Doom of Mokhaiotl (delve boss) mechanics: prayer-book outline for incoming attacks, larvae highlighting and attack priority, boulder shatter tiles, shield / melee-punish boss tiles and the car (dash) phase path and safe tiles. Enable verbose logging to capture unknown projectile/object IDs from the fight to client.log.", position = 6, closedByDefault = false)
    String mokhaiotl = "mokhaiotl";

    @ConfigSection(name = "Prayer Flicking", description = "Tick-synced heartbeat cue above the prayer orb for prayer flicking, plus a locator orb Redemption click counter", position = 7, closedByDefault = false)
    String prayerFlick = "prayerFlick";

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

    @ConfigItem(keyName = "attackTimerColor", name = "Attack Timer Color", description = "Color for the attack countdown timer", section = yama, position = 8)
    default Color attackTimerColor() {
        return new Color(255, 0, 0); // Bright red
    }

    @ConfigItem(keyName = "highlightBoulders", name = "Highlight Glyphs", description = "Highlight glyph objects that spawn on the floor during the Yama fight (fire glyphs on fire attacks, shadow glyphs on shadow attacks)", section = yama, position = 10)
    default boolean highlightBoulders() {
        return true;
    }

    @ConfigItem(keyName = "showYamaFireballSafeTiles", name = "Show Fireball Safe Tiles", description = "Highlight the two safe tiles for each 3-fireball line special attack (horizontal, vertical or NW-SE diagonal) during Yama's final enrage phase", section = yama, position = 11)
    default boolean showYamaFireballSafeTiles() {
        return true;
    }

    @ConfigItem(keyName = "yamaFireballSafeTileColor", name = "Fireball Safe Tile Color", description = "Color used to highlight the safe tiles during the 3-fireball line special attack", section = yama, position = 12)
    default Color yamaFireballSafeTileColor() {
        return new Color(0, 255, 0); // Green
    }

    @ConfigItem(keyName = "yamaTransparency", name = "Highlight Transparency", description = "Transparency level applied to all Yama highlight fills (0-255)", section = yama, position = 13)
    default int yamaTransparency() {
        return 70;
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

    @ConfigItem(keyName = "phosaniAttackTimerColor", name = "Attack Timer Color", description = "Color for the attack countdown timer", section = phosani, position = 3)
    default Color phosaniAttackTimerColor() {
        return new Color(255, 0, 0); // Bright red
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

    // Verzik P3 Settings
    @ConfigItem(keyName = "tobVerzikP3StyleOverlay", name = "Highlight P3 Verzik Attacks", description = "Highlight Verzik in P3 with a color matching her current attack style", section = tob, position = 0)
    default boolean tobVerzikP3StyleOverlay() {
        return true;
    }

    @ConfigItem(keyName = "tobShowVerzikTimer", name = "Show Verzik P3 Attack Timer", description = "Show Verzik's P3 attack countdown timer", section = tob, position = 1)
    default boolean tobShowVerzikTimer() {
        return true;
    }

    @ConfigItem(keyName = "tobShowVerzikP2Timer", name = "Show Verzik P2 Step Back Timer", description = "Show Verzik's P2 step-back countdown - a 4-tick attacker (counting 4-3-2-1) covering her ranged urnbombs and her magic blood spells below 35% health, with the 1 landing on the tick to step back", section = tob, position = 8)
    default boolean tobShowVerzikP2Timer() {
        return true;
    }

    @ConfigItem(keyName = "tobShowXarpusTimer", name = "Show Xarpus Step Back Timer", description = "Show Xarpus's step-back countdown during his poison-spit phase (a 4-tick attacker, counting 4-3-2-1 with the 1 landing on the tick to step back)", section = tob, position = 9)
    default boolean tobShowXarpusTimer() {
        return true;
    }

    @ConfigItem(keyName = "tobTimerTextSize", name = "Timer Text Size", description = "Size of the attack timer text", section = tob, position = 2)
    default int tobTimerTextSize() {
        return 36;
    }

    @ConfigItem(keyName = "tobAttackTimerColor", name = "Attack Timer Color", description = "Color for the attack countdown timer", section = tob, position = 3)
    default Color tobAttackTimerColor() {
        return new Color(255, 0, 0); // Bright red
    }

    @ConfigItem(keyName = "tobAttackTimerOneTickColor", name = "Attack Timer '1' Tick Color", description = "Color of the attack timer on the '1' tick - the step back tick for Verzik P2 and Xarpus, or the tick before the attack for Verzik P3", section = tob, position = 4)
    default Color tobAttackTimerOneTickColor() {
        return new Color(115, 200, 115); // #73C873
    }

    @ConfigItem(keyName = "tobTransparency", name = "Highlight Transparency", description = "Transparency level for the Verzik attack style highlight (0-255)", section = tob, position = 5)
    default int tobTransparency() {
        return 50;
    }

    @ConfigItem(keyName = "tobVerzikRangeColor", name = "Range Color", description = "Color to highlight Verzik P3 during ranged attacks", section = tob, position = 6)
    default Color tobVerzikRangeColor() {
        return new Color(144, 238, 144); // Soft green
    }

    @ConfigItem(keyName = "tobVerzikMageColor", name = "Mage Color", description = "Color to highlight Verzik P3 during magic attacks", section = tob, position = 7)
    default Color tobVerzikMageColor() {
        return new Color(100, 149, 237); // Soft blue
    }

    // Sotetseg Settings
    @ConfigItem(keyName = "tobSotetsegTickEatTimer", name = "Sotetseg Death Ball Tick Eat Timer", description = "Count down the ticks until Sotetseg's death ball lands, shown over food in your inventory (shark and Saradomin brew) with a draining pie. Counts down in red and turns green on 0 - the tick to eat on to tick eat the ball.", section = tob, position = 13)
    default boolean tobSotetsegTickEatTimer() {
        return true;
    }

    // Maggot King Settings
    @ConfigItem(keyName = "showMaggotKingAttackStyleOverlay", name = "Show Attack Style Overlay", description = "Highlight the Maggot King with a colored overlay for the current attack style (Range: green, Mage: blue)", section = maggotKing, position = 0)
    default boolean showMaggotKingAttackStyleOverlay() {
        return true;
    }

    @ConfigItem(keyName = "highlightMaggotKingLarvae", name = "Highlight Larvae", description = "Highlight the maggot larvae with a hull and tile marker, like Phosani's sleepwalkers", section = maggotKing, position = 1)
    default boolean highlightMaggotKingLarvae() {
        return true;
    }

    @ConfigItem(keyName = "showMaggotKingScreechWarning", name = "Screech Prayer Warning", description = "Outline yourself while the Maggot King screeches until all overhead prayers are turned off", section = maggotKing, position = 2)
    default boolean showMaggotKingScreechWarning() {
        return true;
    }

    @ConfigItem(keyName = "maggotKingRangeStyleColor", name = "Range Attack Style Color", description = "Color to highlight the Maggot King during ranged attacks", section = maggotKing, position = 3)
    default Color maggotKingRangeStyleColor() {
        return new Color(0, 255, 0, 160);
    }

    @ConfigItem(keyName = "maggotKingMageStyleColor", name = "Mage Attack Style Color", description = "Color to highlight the Maggot King during magic attacks", section = maggotKing, position = 4)
    default Color maggotKingMageStyleColor() {
        return new Color(0, 100, 255, 160);
    }

    @ConfigItem(keyName = "maggotKingScreechStyleColor", name = "Screech Style Color", description = "Colour shown on the prayer/attack-style overlay while the Maggot King is screeching (all overhead prayers off), alongside the outline warning", section = maggotKing, position = 6)
    default Color maggotKingScreechStyleColor() {
        return new Color(255, 255, 0, 160);
    }

    @ConfigItem(keyName = "maggotKingTransparency", name = "Overlay Transparency", description = "Transparency level for Maggot King overlays (0-255)", section = maggotKing, position = 7)
    default int maggotKingTransparency() {
        return 85;
    }

    @ConfigItem(keyName = "hideDriedAcid", name = "Hide Dried Acid", description = "Hide the dried acid (safe) game objects so they are invisible", section = maggotKing, position = 8)
    default boolean hideDriedAcid() {
        return false;
    }

    // Chambers of Xeric Settings
    @ConfigItem(keyName = "showTektonAttackTimer", name = "Show Tekton Flinch Timer", description = "Display Tekton's flinch countdown timer over him (counts 3-2-1; click into melee on 1)", section = cox, position = 0)
    default boolean showTektonAttackTimer() {
        return true;
    }

    @ConfigItem(keyName = "tektonTimerTextSize", name = "Flinch Timer Text Size", description = "Size of the Tekton flinch timer text", section = cox, position = 1)
    default int tektonTimerTextSize() {
        return 36;
    }

    @ConfigItem(keyName = "tektonAttackTimerColor", name = "Flinch Timer Color", description = "Color for the Tekton flinch timer on ticks other than 1", section = cox, position = 2)
    default Color tektonAttackTimerColor() {
        return new Color(255, 0, 0); // Bright red
    }

    @ConfigItem(keyName = "tektonStepTickColor", name = "Flinch Step-In (Tick 1) Color", description = "Color of the flinch timer on the '1' tick - the tick to click into melee distance", section = cox, position = 3)
    default Color tektonStepTickColor() {
        return new Color(0, 255, 0); // Green = click into melee this tick
    }

    @ConfigItem(keyName = "showOlmAttackStyleOverlay", name = "Show Olm Attack Style", description = "Highlight the Great Olm's head with a colour matching the attack style (mage/range) aimed at you, so you can pray correctly", section = cox, position = 4)
    default boolean showOlmAttackStyleOverlay() {
        return true;
    }

    @ConfigItem(keyName = "olmMageColor", name = "Olm Magic Color", description = "Color shown over Olm's head when his incoming attack on you is magic", section = cox, position = 5)
    default Color olmMageColor() {
        return new Color(100, 149, 237); // Soft blue
    }

    @ConfigItem(keyName = "olmRangeColor", name = "Olm Ranged Color", description = "Color shown over Olm's head when his incoming attack on you is ranged", section = cox, position = 6)
    default Color olmRangeColor() {
        return new Color(144, 238, 144); // Soft green
    }

    @ConfigItem(keyName = "showOlmCrystalBomb", name = "Show Olm Crystal Bomb Blast", description = "Highlight the 5x5 blast area where Olm's crystal bomb will explode so you can move out", section = cox, position = 8)
    default boolean showOlmCrystalBomb() {
        return true;
    }

    @ConfigItem(keyName = "olmCrystalBombColor", name = "Olm Crystal Bomb Color", description = "Color of the Olm crystal bomb blast area highlight", section = cox, position = 9)
    default Color olmCrystalBombColor() {
        return new Color(255, 90, 0); // Orange-red danger
    }

    @ConfigItem(keyName = "showShamanSpitBlast", name = "Show Shaman Spit Blast", description = "Highlight the 5x5 hit area of a Lizardman Shaman's acid spit so you can move out", section = cox, position = 10)
    default boolean showShamanSpitBlast() {
        return true;
    }

    @ConfigItem(keyName = "shamanSpitBlastColor", name = "Shaman Spit Blast Color", description = "Color of the Lizardman Shaman acid spit blast area highlight", section = cox, position = 11)
    default Color shamanSpitBlastColor() {
        return new Color(200, 0, 200); // Magenta, distinct from prayer colours
    }

    @ConfigItem(keyName = "showVasaBoulderBlast", name = "Show Vasa Boulder Blast", description = "Highlight the 3x3 landing area of Vasa Nistirio's thrown boulders so you can move out", section = cox, position = 12)
    default boolean showVasaBoulderBlast() {
        return true;
    }

    @ConfigItem(keyName = "vasaBoulderBlastColor", name = "Vasa Boulder Blast Color", description = "Color of the Vasa Nistirio boulder blast area highlight", section = cox, position = 13)
    default Color vasaBoulderBlastColor() {
        return new Color(144, 238, 144); // Soft green, matches the ranged prayer cue
    }

    // Section-wide transparency; keyName stays "olmTransparency" to keep saved values.
    @ConfigItem(keyName = "olmTransparency", name = "Highlight Transparency", description = "Transparency of all Chambers of Xeric highlight fills (0-255): Olm attack style, crystal bomb, Shaman spit blast and Vasa boulder blast", section = cox, position = 14)
    default int olmTransparency() {
        return 50;
    }

    // Prayer Flicking Settings
    @ConfigItem(keyName = "showPrayerFlickHeartbeat", name = "Show Flick Heartbeat", description = "Flash a red heartbeat dot above the prayer orb once per game tick, peaking just before the middle of each tick, to keep the rhythm for prayer flicking (unlimited prayer)", section = prayerFlick, position = 0)
    default boolean showPrayerFlickHeartbeat() {
        return true;
    }

    @ConfigItem(keyName = "prayerFlickHeartbeatColor", name = "Heartbeat Color", description = "Colour of the prayer flick heartbeat dot", section = prayerFlick, position = 1)
    default Color prayerFlickHeartbeatColor() {
        return new Color(255, 0, 0); // Bright red
    }

    @ConfigItem(keyName = "prayerFlickHeartbeatSize", name = "Heartbeat Size", description = "Radius of the heartbeat dot in pixels", section = prayerFlick, position = 2)
    default int prayerFlickHeartbeatSize() {
        return 5;
    }

    @ConfigItem(keyName = "prayerFlickHeartbeatVerticalOffset", name = "Vertical Offset", description = "Lower the heartbeat dot by this many pixels (higher = further down, towards or onto the prayer orb)", section = prayerFlick, position = 3)
    @Range(min = -200, max = 200)
    default int prayerFlickHeartbeatVerticalOffset() {
        return 0;
    }

    @ConfigItem(keyName = "prayerFlickHeartbeatHorizontalOffset", name = "Horizontal Offset", description = "Shift the heartbeat dot sideways by this many pixels (positive = right, negative = left)", section = prayerFlick, position = 4)
    @Range(min = -200, max = 200)
    default int prayerFlickHeartbeatHorizontalOffset() {
        return 0;
    }

    @ConfigItem(keyName = "showPrayerFlickPrayerOutline", name = "Highlight Active Prayer", description = "Fill the icon of each active offensive prayer (Piety/Rigour/Augury, etc.) in the open prayer book with a pulsing transparent red (same rhythm as the heartbeat)", section = prayerFlick, position = 5)
    default boolean showPrayerFlickPrayerOutline() {
        return true;
    }

    // Locator Orb Settings (within Prayer Flicking)
    @ConfigItem(keyName = "showLocatorOrbRedemptionCounter", name = "Show Redemption Counter", description = "Show a countdown over the locator orb in the inventory: the number of clicks (10 damage each) needed to drop to the Redemption threshold (10% of maximum hitpoints). It reads ...3, 2, 1 (armed - the next click procs) then 0, and resets once Redemption heals you", section = prayerFlick, position = 6)
    default boolean showLocatorOrbRedemptionCounter() {
        return true;
    }

    @ConfigItem(keyName = "locatorOrbCounterColor", name = "Counter Color", description = "Colour of the counter while still counting down (2 or more clicks from proccing)", section = prayerFlick, position = 7)
    default Color locatorOrbCounterColor() {
        return new Color(255, 255, 0); // Yellow
    }

    @ConfigItem(keyName = "locatorOrbArmedColor", name = "Armed Color", description = "Colour of the counter when armed - 1 click from proccing Redemption, or already at or below the threshold (0)", section = prayerFlick, position = 8)
    default Color locatorOrbArmedColor() {
        return new Color(0, 255, 0); // Green
    }

    // Doom of Mokhaiotl Settings
    @ConfigItem(keyName = "showMokhaiotlPrayerOutline", name = "Show Prayer Highlight", description = "Fill the required protection prayer icon in the prayer book with a transparent colour, shown on the next incoming attack to pray until you pray it. Covers standard orbs, car-phase attacks and the boulder shatter sequence", section = mokhaiotl, position = 1)
    default boolean showMokhaiotlPrayerOutline() {
        return true;
    }

    @ConfigItem(keyName = "mokhaiotlPrayerGreenTransparency", name = "Prayer Highlight Transparency", description = "Opacity of the prayer-book highlight fill (0-255), so the prayer icon still shows through", section = mokhaiotl, position = 3)
    @Range(min = 0, max = 255)
    default int mokhaiotlPrayerGreenTransparency() {
        return 120;
    }

    @ConfigItem(keyName = "highlightMokhaiotlLarvae", name = "Highlight Larvae", description = "Highlight demonic larvae true tiles in the colour of the combat style needed to kill them (green range, blue magic, red melee)", section = mokhaiotl, position = 5)
    default boolean highlightMokhaiotlLarvae() {
        return true;
    }

    @ConfigItem(keyName = "mokhaiotlLarvaeMenuSwap", name = "Prioritise Attack Larvae", description = "Make Attack the default left-click option on demonic larvae so they can be clicked even while stacked under the boss", section = mokhaiotl, position = 6)
    default boolean mokhaiotlLarvaeMenuSwap() {
        return true;
    }

    @ConfigItem(keyName = "showMokhaiotlBoulderTiles", name = "Show Boulder Shatter Tiles", description = "Highlight the tiles a rock throw's boulder will shatter onto so you can move out of them", section = mokhaiotl, position = 9)
    default boolean showMokhaiotlBoulderTiles() {
        return true;
    }

    @ConfigItem(keyName = "showMokhaiotlShieldTile", name = "Show Shield Phase Tile", description = "Highlight the boss's 5x5 true tile in blue during its shield phase", section = mokhaiotl, position = 11)
    default boolean showMokhaiotlShieldTile() {
        return true;
    }

    @ConfigItem(keyName = "showMokhaiotlDashPath", name = "Show Car Phase Dash Path", description = "Highlight the straight-line path the burrowed boss dashes along towards its eye tile during the car (dash) phase", section = mokhaiotl, position = 13)
    default boolean showMokhaiotlDashPath() {
        return true;
    }

    @ConfigItem(keyName = "showMokhaiotlCarSafeTiles", name = "Show Car Phase Safe Tiles", description = "Highlight tiles shielded from the dash by an arena boulder (line-of-sight blocked from the boss)", section = mokhaiotl, position = 15)
    default boolean showMokhaiotlCarSafeTiles() {
        return true;
    }

    @ConfigItem(keyName = "showMokhaiotlMeleePunishTile", name = "Show Melee Punish Tile", description = "Highlight the boss's 5x5 true tile in red while it charges its beam and must be melee-punished", section = mokhaiotl, position = 17)
    default boolean showMokhaiotlMeleePunishTile() {
        return true;
    }

    @ConfigItem(keyName = "mokhaiotlMeleePunishSound", name = "Melee Punish Sound", description = "Play an audio alert (bundled two-tone chime, played outside the game's own audio) the moment the boss starts its beam charge-up and must be melee-punished", section = mokhaiotl, position = 18)
    default boolean mokhaiotlMeleePunishSound() {
        return false;
    }

    @Range(min = 0, max = 100)
    @ConfigItem(keyName = "mokhaiotlMeleePunishSoundVolume", name = "Melee Punish Sound Volume", description = "Volume of the melee-punish audio alert (0-100)", section = mokhaiotl, position = 19)
    default int mokhaiotlMeleePunishSoundVolume() {
        return 70;
    }

    @ConfigItem(keyName = "showMokhaiotlStatues", name = "Show Shockwave Statues", description = "Highlight the nearest usable pair of volatile earth statues (in line, 14+ tiles apart): the far one green then red once attacked, the near one dormant grey until its turn", section = mokhaiotl, position = 20)
    default boolean showMokhaiotlStatues() {
        return true;
    }

    @ConfigItem(keyName = "showMokhaiotlShockwaveTimer", name = "Show Shockwave Timer", description = "Display a countdown over the highlighted statues showing ticks until the boss's shockwave attack", section = mokhaiotl, position = 23)
    default boolean showMokhaiotlShockwaveTimer() {
        return true;
    }

    @ConfigItem(keyName = "mokhaiotlTimerTextSize", name = "Timer Text Size", description = "Size of the shockwave countdown timer text", section = mokhaiotl, position = 25)
    default int mokhaiotlTimerTextSize() {
        return 36;
    }

    @ConfigItem(keyName = "hideMokhaiotlOrbModel", name = "Hide Orb Model", description = "Hide the 3x3 earthen shield orb model so it does not obscure the arena, while keeping its true tile highlighted", section = mokhaiotl, position = 26)
    default boolean hideMokhaiotlOrbModel() {
        return true;
    }

    @ConfigItem(keyName = "showMokhaiotlOrbTile", name = "Show Orb True Tile", description = "Highlight the earthen shield orb's 3x3 true tile so you can stand under it as it moves between statues", section = mokhaiotl, position = 27)
    default boolean showMokhaiotlOrbTile() {
        return true;
    }

    @ConfigItem(keyName = "mokhaiotlTransparency", name = "Highlight Transparency", description = "Transparency level applied to all Doom of Mokhaiotl tile fills (0-255)", section = mokhaiotl, position = 29)
    default int mokhaiotlTransparency() {
        return 70;
    }

    @ConfigItem(keyName = "mokhaiotlVerboseLogging", name = "Verbose Logging", description = "Log unknown projectile/object IDs from the Doom of Mokhaiotl fight to client.log for development purposes", section = mokhaiotl, position = 31)
    default boolean mokhaiotlVerboseLogging() {
        return false;
    }
}
