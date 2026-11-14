package com.pvmkits;

import net.runelite.client.config.*;
import java.awt.Color;

@ConfigGroup("pvmkits")
public interface PvmKitsConfig extends Config {

    @ConfigSection(name = "Yama", description = "Yama boss mechanics assistance", position = 1, closedByDefault = true)
    String yama = "yama";

    @ConfigSection(name = "Phosani's & The Nightmare", description = "Phosani's Nightmare and The Nightmare boss mechanics assistance", position = 2, closedByDefault = true)
    String phosani = "phosani";

    @ConfigSection(name = "Theatre of Blood", description = "Verzik phase 3 attack style highlighting, Verzik phase 2 / phase 3 and Xarpus attack timers, Bloat falling hand tile highlighting, and Sotetseg's death ball tick eat timer", position = 3, closedByDefault = true)
    String tob = "tob";

    @ConfigSection(name = "Maggot King", description = "Maggot King boss discovery and mechanics logging", position = 4, closedByDefault = false)
    String maggotKing = "maggotKing";

    @ConfigSection(name = "Chambers of Xeric", description = "Great Olm attack-style overlay and Tekton flinch timer", position = 5, closedByDefault = false)
    String cox = "cox";

    @ConfigSection(name = "Doom of Mokhaiotl", description = "Doom of Mokhaiotl (delve boss) mechanics: prayer-book outline for incoming attacks, larvae highlighting and attack priority, boulder shatter tiles, shield / melee-punish boss tiles and the car (dash) phase path and safe tiles.", position = 6, closedByDefault = false)
    String mokhaiotl = "mokhaiotl";

    @ConfigSection(name = "Fractured Archive", description = "The Fractured Archive (raid 4) mechanics discovery logging: NPC animations, spotanims, projectiles, ground graphics, object spawns, chat cues and varbits, written to the RuneLite client log", position = 7, closedByDefault = false)
    String fracturedArchive = "fracturedArchive";

    @ConfigSection(name = "Prayer Flicking", description = "Tick-synced heartbeat cue above the prayer orb for prayer flicking, plus a locator orb Redemption click counter and a tea flask servings counter", position = 8, closedByDefault = false)
    String prayerFlick = "prayerFlick";

    @ConfigSection(name = "Prayer & Health Bars", description = "Restyled health and prayer bars over your character: rounded green (health) and blue (prayer) bars with a dark border, joined into one split bar or kept separate, with the numbers shown beside them, plus quiet hit numbers beside your character in place of its hitsplats", position = 9, closedByDefault = false)
    String prayerBar = "prayerBar";

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

    @ConfigItem(keyName = "tobTransparency", name = "Highlight Transparency", description = "Transparency level for the Verzik attack style and Bloat falling hand highlights (0-255)", section = tob, position = 5)
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

    // Bloat Settings
    @ConfigItem(keyName = "tobBloatHandHighlight", name = "Highlight Bloat's Falling Hands", description = "Highlight the tiles Bloat's falling hands are coming down on while he walks, in normal and hard mode", section = tob, position = 10)
    default boolean tobBloatHandHighlight() {
        return true;
    }

    @ConfigItem(keyName = "tobBloatHandColor", name = "Bloat Falling Hand Color", description = "Color to highlight the tiles Bloat's falling hands land on", section = tob, position = 11)
    default Color tobBloatHandColor() {
        return new Color(255, 0, 0); // Bright red
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

    // Fractured Archive Settings
    @ConfigItem(keyName = "showArchiveGuardianTrueTiles", name = "Guardian True Tiles", description = "Outline the Archive Guardian's 5x5 true tiles in a faint dormant white (the same as the Doom of Mokhaiotl's waiting statue) for the whole fight - while it flies, its model floats above these tiles, and while it is downed they are the area to hit with melee", section = fracturedArchive, position = 1)
    default boolean showArchiveGuardianTrueTiles() {
        return true;
    }

    @ConfigItem(keyName = "showArchiveGuardianStepTimer", name = "Guardian Step-Under Timer", description = "Count down the Archive Guardian's 5-tick attack rhythm at its feet (5-4-3-2-1), styled like the Phosani attack timer (white, with 1 in Phosani's pastel range green). Step under the boss on 1, the tick before it attacks, which makes it slam the ground instead of shooting (step back out to avoid the slam). The wiki notes this does not work every time. Hidden while it is downed", section = fracturedArchive, position = 2)
    default boolean showArchiveGuardianStepTimer() {
        return true;
    }

    @ConfigItem(keyName = "archiveTimerTextSize", name = "Boss Timer Text Size", description = "Size of the timer text at the boss's feet: the Guardian step-under timer and the Moia cough timer", section = fracturedArchive, position = 3)
    default int archiveTimerTextSize() {
        return 36;
    }

    @ConfigItem(keyName = "showArchiveShieldBreakHull", name = "Shield Break Warning", description = "Fill the Archive Guardian's model hull red (Phosani's melee colour) during the last 12% of its shield, as a reminder to move away from it before the shield breaks and it crashes down. Gone once it is downed", section = fracturedArchive, position = 4)
    default boolean showArchiveShieldBreakHull() {
        return true;
    }

    @ConfigItem(keyName = "showArchiveGuardianBeamTimer", name = "Guardian Beam Timer", description = "Tick-eat countdown for the Archive Guardian's low-health beam over sharks, Saradomin brews, Tea flasks and Purple sweets in your inventory, styled like Sotetseg's death-ball timer: 10 when it starts charging, down to a green 0 on the tick it hits (the tick to eat). The step-under timer hides while a beam is charging", section = fracturedArchive, position = 5)
    default boolean showArchiveGuardianBeamTimer() {
        return true;
    }

    @ConfigItem(keyName = "highlightArchiveMushrooms", name = "Highlight Mushrooms", description = "Mark the 3x3 area of each Archive Guardian mushroom in muted red (the same style as Phosani's spore zones), from the moment it sprouts until it is set off", section = fracturedArchive, position = 6)
    default boolean highlightArchiveMushrooms() {
        return true;
    }

    @ConfigItem(keyName = "highlightArchiveVines", name = "Highlight Vines", description = "Mark the 3x3 area around each vine in the Archive Guardian's downed phase in the same muted red as the mushrooms, so it can be dodged", section = fracturedArchive, position = 7)
    default boolean highlightArchiveVines() {
        return true;
    }

    @ConfigItem(keyName = "highlightEzekielSlam", name = "Ezekiel Slam Safe Tiles", description = "While Ezekiel's ground slam telegraph is on the floor, mark in soft green (Phosani's range green) every walkable tile that touches the slam's edge, side-on or diagonally - the closest places to stand to dodge it. The tiles under Ezekiel himself are never marked, and the marks clear once the slam's second hit lands", section = fracturedArchive, position = 11)
    default boolean highlightEzekielSlam() {
        return true;
    }

    @ConfigItem(keyName = "highlightEzekielConvergingSwords", name = "Ezekiel Converging Swords", description = "When Ezekiel's swords fly in from the arena's edges to meet in one 3x3, draw a one-tile pastel blue line down the middle of the lane nearest the arena's middle (between the north and south swords or between the west and east ones), and mark in soft green the corners of that 3x3 that no sword passes through", section = fracturedArchive, position = 12)
    default boolean highlightEzekielConvergingSwords() {
        return true;
    }

    @ConfigItem(keyName = "highlightEzekielSwordSwipe", name = "Ezekiel Sword Swipe Tiles", description = "When three of Ezekiel's swords line up along an edge and charge, mark two soft green tiles in front of them once the firing order is known (from their draining bars, or from the first sword to fire): one next to the second-last sword and one next to the last. Stand on the one next to the last sword while the first two fire, then step onto the other to dodge the last. Hidden once the last sword fires", section = fracturedArchive, position = 13)
    default boolean highlightEzekielSwordSwipe() {
        return true;
    }

    @ConfigItem(keyName = "highlightEzekielFourSwords", name = "Ezekiel Four Swords Safe Zone", description = "When Ezekiel's swords spawn two on each side of the arena, fill the lanes none of them sweeps (the middle two east-west rows) in soft green, with a single outline around the whole zone, plus a pastel blue line a one-tile gap either side of it and a soft green line a one-tile gap outside each blue one. Until the slam that comes with them has landed, its tiles and the tiles under Ezekiel are left out of the zone", section = fracturedArchive, position = 14)
    default boolean highlightEzekielFourSwords() {
        return true;
    }

    @ConfigItem(keyName = "highlightEzekielEnrage", name = "Ezekiel Enrage Safe Tiles", description = "In Ezekiel's enrage, when the walls box you into a 3x3, mark in soft green the tiles each lightning strike misses (the side of the sword left dark, or the back row), shown only on the tick to click onto them - the tick before that strike's lightning shows. The three strikes come a tick apart, so each set of tiles appears as the strike before it lands", section = fracturedArchive, position = 15)
    default boolean highlightEzekielEnrage() {
        return true;
    }

    @ConfigItem(keyName = "highlightMoiaSafeTiles", name = "Moia Safe Tiles", description = "In Moia's 9x9 arena, mark the north-west corner tile in soft green (the same as Ezekiel's safe tiles) until her enrage. From her \"Cease this futile resistance!\" nothing is marked until her \"Useless corpses... Give me your strength!\"; from then on, mark instead every arena tile at least 3 tiles from every Undead binder (they hit anyone within two tiles, diagonals included), updated as the binders circle her. Binders she raises on \"Again!\" count from the tick they spawn. When the binders start walking to their next places the tiles fade out over their walk, and the tiles for where they stop show in full as they stand still. Her own 3x3 is never marked", section = fracturedArchive, position = 16)
    default boolean highlightMoiaSafeTiles() {
        return true;
    }

    @ConfigItem(keyName = "highlightMoiaAttackStyle", name = "Moia Attack Style", description = "Highlight Moia's 3x3 base like Phosani's attack-style square, in soft green. From when she throws an axe (her mage attack) until it lands, her centre tile also shows purple, like Phosani's cursed centre tile. The axe shares her normal attack animation, so the purple tile appears as the axe leaves her hand, a tick after she swings, about 6 ticks before it lands", section = fracturedArchive, position = 17)
    default boolean highlightMoiaAttackStyle() {
        return true;
    }

    @ConfigItem(keyName = "moiaAxeSound", name = "Moia Axe Sound", description = "Loop the Doom of Mokhaiotl's melee-punish chime (played outside the game's own audio) from when Moia throws an axe until it lands, the same window her centre tile shows purple. It runs straight through back-to-back axes and stops the moment the last one lands. Works whether or not Moia Attack Style is on", section = fracturedArchive, position = 17)
    default boolean moiaAxeSound() {
        return true;
    }

    @Range(min = 0, max = 100)
    @ConfigItem(keyName = "moiaAxeSoundVolume", name = "Moia Axe Sound Volume", description = "Volume of the Moia axe audio alert (0-100)", section = fracturedArchive, position = 17)
    default int moiaAxeSoundVolume() {
        return 70;
    }

    @ConfigItem(keyName = "highlightMoiaVenatorBinders", name = "Moia Venator Binders", description = "Highlight in soft red the tile and hull of the Undead binder standing on the centre tile of Moia's 9x9 arena, the one to shoot with the Venator bow. Shown once that binder has stood on the centre for a tick; other binders spawning or moving do not hide it. Uses the binders' server tiles; the tile is drawn under the model so it moves smoothly. Moia herself is never highlighted, even when she stands in the middle of the arena in her enrage", section = fracturedArchive, position = 18)
    default boolean highlightMoiaVenatorBinders() {
        return true;
    }

    @ConfigItem(keyName = "showMoiaPrayerTiming", name = "Moia Prayer Timing", description = "Like the Doom of Mokhaiotl's prayer highlight, fill the protection prayer to be on in the open prayer book in soft green, until you pray it. Protect from Missiles through Moia's fight, and Protect from Magic for the one tick her axe's damage lands, a tick before it visibly reaches you (5 ticks after it is thrown, or 6 in her enrage, where it flies a tick longer), then straight back to Missiles", section = fracturedArchive, position = 19)
    default boolean showMoiaPrayerTiming() {
        return true;
    }

    @ConfigItem(keyName = "showMoiaAxeCountdown", name = "Moia Axe Countdown", description = "While Moia's axe (her mage attack) is in the air, count down over Protect from Magic in the open prayer book: 5-4-3-2 in white, then a green 1 on the tick to switch to Magic (the same tick the prayer highlight turns green). It starts the tick after the axe appears, or a tick later in her enrage, where the axe flies a tick longer. Styled like the Archive's boss timers", section = fracturedArchive, position = 19)
    default boolean showMoiaAxeCountdown() {
        return true;
    }

    @ConfigItem(keyName = "moiaAxeCountdownTextSize", name = "Moia Axe Countdown Text Size", description = "Size of the axe countdown text drawn over the Protect from Magic icon", section = fracturedArchive, position = 19)
    default int moiaAxeCountdownTextSize() {
        return 22;
    }

    @ConfigItem(keyName = "showMoiaCoughTimer", name = "Moia Cough Timer", description = "When Moia coughs (the smoke that poisons you and spreads to the binders), count down its flight in soft pastel red at her feet: 4-3-2-1, the 4 as she coughs and the 1 on the last tick before the smoke lands and poisons you. Timed off the smoke itself, so it is never wrong; the cough cannot be called earlier, as the attacks before it vary at random. Same font and text size as the Guardian step-under timer", section = fracturedArchive, position = 20)
    default boolean showMoiaCoughTimer() {
        return true;
    }

    @ConfigItem(keyName = "hideMoiaCough", name = "Hide Binder Cough", description = "Hide the \"*Cough*\" overhead text. Moia's cough attack spreads from you to the Undead binders, and an infected binder says it every 8 ticks, often from your own tile, so it shows over your head. Hidden on any character, including you", section = fracturedArchive, position = 20)
    default boolean hideMoiaCough() {
        return true;
    }

    @ConfigItem(keyName = "showDreadnothTrueTiles", name = "Dreadnoth True Tiles", description = "Outline Dreadnoth the Jellied's 5x5 true tiles in the same faint grey as the Archive Guardian's. They are his server tiles, so while he walks (a tile every two ticks) they step ahead of his model to where he really is", section = fracturedArchive, position = 21)
    default boolean showDreadnothTrueTiles() {
        return true;
    }

    @ConfigItem(keyName = "highlightDreadnothAttackStyle", name = "Dreadnoth Attack Style", description = "Highlight Dreadnoth's 5x5 base in soft green (Phosani's range colour) from the moment he launches a projectile at a player until it lands. Nothing is drawn for his melee, his main attack, so between ranged attacks only his grey true tiles show", section = fracturedArchive, position = 22)
    default boolean highlightDreadnothAttackStyle() {
        return true;
    }

    @ConfigItem(keyName = "showDreadnothPrayerTiming", name = "Dreadnoth Prayer Timing", description = "Like the Doom of Mokhaiotl's and Moia's prayer highlights, fill the protection prayer to be on in the open prayer book in soft green, until you pray it. Protect from Melee through Dreadnoth's fight (his slams, his melee and the jellies, including any left after he dies), and Protect from Missiles only on the ticks his ranged projectiles hit you: three ticks in a row after each slam, then straight back to Melee, as his next slam hits the very next tick", section = fracturedArchive, position = 22)
    default boolean showDreadnothPrayerTiming() {
        return true;
    }

    @ConfigItem(keyName = "showDreadnothRangedCountdown", name = "Dreadnoth Ranged Countdown", description = "Like Moia's axe countdown: when Dreadnoth slams, count down over Protect from Missiles in the open prayer book to his ranged hits, 4-3-2 in white, then a green 1 on the tick to switch to Missiles (the same tick the prayer highlight turns green). It starts the tick after each slam, so between two volleys of one phase it runs through the three Melee ticks in between", section = fracturedArchive, position = 22)
    default boolean showDreadnothRangedCountdown() {
        return true;
    }

    @ConfigItem(keyName = "dreadnothRangedCountdownTextSize", name = "Dreadnoth Ranged Countdown Text Size", description = "Size of the ranged countdown text drawn over the Protect from Missiles icon", section = fracturedArchive, position = 22)
    default int dreadnothRangedCountdownTextSize() {
        return 22;
    }

    @ConfigItem(keyName = "highlightDreadnothJellies", name = "Dreadnoth Jellies", description = "Highlight each Jelly's true tile and model hull in the same soft pastel red as Dreadnoth's floor splats", section = fracturedArchive, position = 23)
    default boolean highlightDreadnothJellies() {
        return true;
    }

    @ConfigItem(keyName = "highlightDreadnothFloor", name = "Dreadnoth Floor Splats", description = "Mark in a soft pastel red, lighter than the Guardian's mushrooms, every tile in Dreadnoth's room with something on the floor (the jelly splats that hit for 6-8 a tick), so you can stick to clear tiles. Their ids are not known yet, so this marks anything that appears on a walkable floor tile after the room loads: spawned objects, floor decorations and ground graphics, except the raid's invisible blockers and any graphic that covers the whole arena", section = fracturedArchive, position = 24)
    default boolean highlightDreadnothFloor() {
        return true;
    }

    @Range(min = 0, max = 255)
    @ConfigItem(keyName = "archiveTransparency", name = "Highlight Transparency", description = "Fill opacity of the mushroom and vine zones, Moia's and Dreadnoth's attack-style squares and Dreadnoth's floor splats (0-255). Ezekiel's safe tiles and sword areas are a fixed 37% opaque", section = fracturedArchive, position = 8)
    default int archiveTransparency() {
        return 50;
    }

    @ConfigItem(keyName = "simplifyArchiveArena", name = "Simplify Arena", description = "Repaint the Archive Guardian's whole walled room a single flat colour (set by Simplified Arena Color below), with its texture stripped, so the fight's tile highlights stand out against a uniform background. The room is measured from the floor around the Guardian, walls included, and the original floor is restored as soon as you leave or turn this off", section = fracturedArchive, position = 9)
    default boolean simplifyArchiveArena() {
        return false;
    }

    @ConfigItem(keyName = "archiveArenaColor", name = "Simplified Arena Color", description = "The colour the Archive Guardian's room floor is repainted when Simplify Arena is on. Only the hue, saturation and brightness are used - the client's own lighting still shades it, and transparency has no effect", section = fracturedArchive, position = 10)
    default Color archiveArenaColor() {
        return new Color(166, 138, 108);
    }

    @ConfigItem(keyName = "fracturedArchiveLogging", name = "Mechanics Logging", description = "While in The Fractured Archive, write every boss animation, spotanim, projectile, ground graphic, object spawn, NPC spawn/transform, overhead shout, game message and hit you take to the RuneLite client log (lines start with [Archive t<tick> r<room tick>]). Repeats are kept so attack timings can be read off, but per-frame and per-tile spam is grouped. A summary of every id seen is written when you leave", section = fracturedArchive, position = 26)
    default boolean fracturedArchiveLogging() {
        return true;
    }

    @ConfigItem(keyName = "fracturedArchiveLogVars", name = "Log Varbit Changes", description = "Also log varbit and varp changes while in the Archive (raid state such as the current room, phase or door choice usually lives in these). A var that keeps changing is muted after 25 changes", section = fracturedArchive, position = 27)
    default boolean fracturedArchiveLogVars() {
        return true;
    }

    @ConfigItem(keyName = "fracturedArchiveAnyInstance", name = "Treat Any Instance As Archive", description = "Fallback for if the logger does not start on its own: the Archive is recognised by its boss names, which have not been confirmed in game yet. With this on, any instance the other bosses in this plugin do not claim is logged as the Archive. Turn it off again once the room ids are known", section = fracturedArchive, position = 28)
    default boolean fracturedArchiveAnyInstance() {
        return false;
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

    // Tea Flask Settings (within Prayer Flicking)
    @ConfigItem(keyName = "showTeaFlaskCounter", name = "Show Tea Flask Counter", description = "Show the servings of tea left in the tea flask (0-5) over the flask in the inventory. Shows ? until known - look in the flask once to sync it; after that drinking and filling keep it up to date", section = prayerFlick, position = 9)
    default boolean showTeaFlaskCounter() {
        return true;
    }

    @ConfigItem(keyName = "teaFlaskCounterColor", name = "Tea Flask Color", description = "Colour of the tea flask counter while it has tea in it", section = prayerFlick, position = 10)
    default Color teaFlaskCounterColor() {
        return new Color(255, 255, 0); // Yellow
    }

    @ConfigItem(keyName = "teaFlaskEmptyColor", name = "Tea Flask Empty Color", description = "Colour of the tea flask counter when the flask is empty (0)", section = prayerFlick, position = 11)
    default Color teaFlaskEmptyColor() {
        return new Color(255, 0, 0); // Red
    }

    // Prayer Bar Settings
    @ConfigItem(keyName = "showPrayerBar", name = "Show Prayer Bar", description = "Draw a rounded prayer bar directly under your health bar showing your current prayer points. Turn off the core Prayer plugin's own prayer bar to avoid drawing two", section = prayerBar, position = 0)
    default boolean showPrayerBar() {
        return true;
    }

    @ConfigItem(keyName = "prayerBarOnlyWhenPraying", name = "Only While Praying", description = "Only show the prayer bar while at least one prayer is active", section = prayerBar, position = 1)
    default boolean prayerBarOnlyWhenPraying() {
        return false;
    }

    @ConfigItem(keyName = "prayerBarOnlyWithHealthBar", name = "Only With Health Bar", description = "Only show the prayer bar while your own health bar is showing (i.e. in combat)", section = prayerBar, position = 2)
    default boolean prayerBarOnlyWithHealthBar() {
        return false;
    }

    @Alpha
    @ConfigItem(keyName = "prayerBarFillColor", name = "Fill Color", description = "Colour of the filled part of the prayer bar (a light-to-dark shade of it is applied automatically)", section = prayerBar, position = 3)
    default Color prayerBarFillColor() {
        return new Color(40, 140, 245); // Prayer blue
    }

    @Alpha
    @ConfigItem(keyName = "prayerBarBackgroundColor", name = "Background Color", description = "Colour of the empty part of the prayer and health bars", section = prayerBar, position = 4)
    default Color prayerBarBackgroundColor() {
        return new Color(18, 24, 36, 230); // Deep navy
    }

    @Alpha
    @ConfigItem(keyName = "prayerBarBorderColor", name = "Border Color", description = "Colour of the rounded border of the prayer and health bars", section = prayerBar, position = 5)
    default Color prayerBarBorderColor() {
        return Color.BLACK;
    }

    @ConfigItem(keyName = "prayerBarWidth", name = "Width", description = "Width of the prayer and health bars in pixels (the game's own health bar is 30)", section = prayerBar, position = 6)
    @Range(min = 10, max = 120)
    default int prayerBarWidth() {
        return 30;
    }

    @ConfigItem(keyName = "prayerBarHeight", name = "Height", description = "Height of each of the prayer and health bars in pixels", section = prayerBar, position = 7)
    @Range(min = 3, max = 16)
    default int prayerBarHeight() {
        return 6;
    }

    @ConfigItem(keyName = "prayerBarVerticalOffset", name = "Vertical Offset", description = "Move the prayer bar (and the health bar above it) down by this many pixels (negative = up)", section = prayerBar, position = 8)
    @Range(min = -100, max = 100)
    default int prayerBarVerticalOffset() {
        return 0;
    }

    @ConfigItem(keyName = "showStyledHealthBar", name = "Show Health Bar", description = "Draw a matching rounded health bar just above the prayer bar showing your current hitpoints", section = prayerBar, position = 9)
    default boolean showStyledHealthBar() {
        return true;
    }

    @ConfigItem(keyName = "hideGameHealthBar", name = "Hide Game Health Bar", description = "Hide the game's own health bar over your character (only yours - NPC health bars, hitsplats and prayer icons are untouched) so it never shows over these bars. Only applies while Show Health Bar is on", section = prayerBar, position = 10)
    default boolean hideGameHealthBar() {
        return true;
    }

    @ConfigItem(keyName = "hideOwnHitsplats", name = "Hide My Hitsplats", description = "Hide every hitsplat that lands on your character - damage, 0s, poison, venom, acid, heals, any type - so your hitpoints are read from the health bar instead. Hitsplats on NPCs and other players, your prayer icon and overhead text are untouched. Works on hitsplats that land after it is switched on", section = prayerBar, position = 10)
    default boolean hideOwnHitsplats() {
        return true;
    }

    @ConfigItem(keyName = "healthBarOnlyInCombat", name = "Health Only In Combat", description = "Only show the health bar while you are in combat (while the game would show its own health bar)", section = prayerBar, position = 11)
    default boolean healthBarOnlyInCombat() {
        return false;
    }

    @Alpha
    @ConfigItem(keyName = "healthBarFillColor", name = "Health Fill Color", description = "Colour of the filled part of the health bar (a light-to-dark shade of it is applied automatically)", section = prayerBar, position = 12)
    default Color healthBarFillColor() {
        return new Color(50, 200, 90); // Health green
    }

    @Alpha
    @ConfigItem(keyName = "healthBarPoisonedColor", name = "Health Poisoned Color", description = "Colour the health bar (and hitpoints number) switches to while you are poisoned or venomed, going back to the normal fill colour once cured", section = prayerBar, position = 13)
    default Color healthBarPoisonedColor() {
        return new Color(30, 130, 55); // Darker green
    }

    @ConfigItem(keyName = "healthBarGap", name = "Health Bar Gap", description = "Gap in pixels between the health bar and the prayer bar below it (separate bars only - joined bars share one divider line)", section = prayerBar, position = 14)
    @Range(min = 0, max = 30)
    default int healthBarGap() {
        return 1;
    }

    @ConfigItem(keyName = "joinStatusBars", name = "Join Bars", description = "Draw health and prayer as one rounded bar split by a straight divider line (health on top, prayer below) instead of two separate bars. Falls back to a single bar while only one of the two is showing", section = prayerBar, position = 15)
    default boolean joinStatusBars() {
        return true;
    }

    @ConfigItem(keyName = "showStatusBarNumbers", name = "Show Numbers", description = "Show your current hitpoints (in the health colour) stacked over your current prayer points (in the prayer colour) just to the left of the bars", section = prayerBar, position = 16)
    default boolean showStatusBarNumbers() {
        return true;
    }

    @ConfigItem(keyName = "statusBarTextSize", name = "Text Size", description = "Font size of the hitpoints and prayer numbers (the rising hit numbers are always size 14)", section = prayerBar, position = 17)
    @Range(min = 6, max = 24)
    default int statusBarTextSize() {
        return 11;
    }

    @ConfigItem(keyName = "showOwnHitNumbers", name = "Show My Hit Numbers", description = "Each hitsplat that lands on you rises out of the hitpoints number beside the bars as -12 (or +12 for a heal, 0 for a block), drifting up and away from the bars at 45 degrees in a fixed size 14 font, then fades away - a quiet replacement for the splats Hide My Hitsplats removes. Needs a bar showing", section = prayerBar, position = 18)
    default boolean showOwnHitNumbers() {
        return true;
    }

    @Alpha
    @ConfigItem(keyName = "ownHitNumbersColor", name = "Hit Number Color", description = "Colour of the rising hit numbers. Lower the alpha to make them more see-through", section = prayerBar, position = 19)
    default Color ownHitNumbersColor() {
        return new Color(80, 80, 80, 180); // Soft dark grey, semi-transparent
    }

    @Units(Units.MILLISECONDS)
    @ConfigItem(keyName = "ownHitNumbersDuration", name = "Hit Number Time", description = "How long each hit number takes to rise and fade away", section = prayerBar, position = 20)
    @Range(min = 300, max = 6000)
    default int ownHitNumbersDuration() {
        return 2000;
    }

    // Doom of Mokhaiotl Settings
    @ConfigItem(keyName = "showMokhaiotlPrayerOutline", name = "Highlight Prayer Timing", description = "Fill the required protection prayer icon in the prayer book with a transparent colour, shown on the next incoming attack to pray until you pray it. Covers standard orbs, car-phase attacks and the boulder shatter sequence", section = mokhaiotl, position = 1)
    default boolean showMokhaiotlPrayerOutline() {
        return true;
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

    @ConfigItem(keyName = "showMokhaiotlShieldTile", name = "Shield/Melee Punish Boss Highlight", description = "Highlight the boss's 5x5 true tile: blue during its shield phase, red while it charges its beam and must be melee-punished", section = mokhaiotl, position = 11)
    default boolean showMokhaiotlBossPhaseTile() {
        return true;
    }

    @ConfigItem(keyName = "showMokhaiotlDashPath", name = "Show Car Phase Dash Path", description = "Highlight in pastel red the area the burrowed boss tramples as it dashes to its eye tile during the car (dash) phase, including the 2 tiles past the eye its footprint reaches", section = mokhaiotl, position = 13)
    default boolean showMokhaiotlDashPath() {
        return true;
    }

    @ConfigItem(keyName = "showMokhaiotlCarSafeTiles", name = "Show Car Phase Safe Tiles", description = "Highlight in pastel green the tiles near you that a landed boulder puts between you and the boss, so its car phase dash cannot reach you there. Shown while no dash is telegraphed, so these and the dash path are never on screen together", section = mokhaiotl, position = 15)
    default boolean showMokhaiotlCarSafeTiles() {
        return true;
    }

    @ConfigItem(keyName = "showMokhaiotlEyeSpawnTiles", name = "Show First Dash Eye Tiles", description = "Mark the four tiles 10 north, east, south and west of where the boss spawns, which is where the delve's first car phase dash is most likely to put its eye. Marked once when the boss spawns, kept for the whole delve and re-marked on the next one, in a rotating rainbow colour. Note the dash goes diagonally about as often as it goes cardinally, so these are four of its eight possible directions", section = mokhaiotl, position = 16)
    default boolean showMokhaiotlEyeSpawnTiles() {
        return true;
    }


    @ConfigItem(keyName = "mokhaiotlMeleePunishSound", name = "Melee Punish Sound", description = "Play an audio alert (bundled two-tone chime, played outside the game's own audio) from the moment the boss starts its beam charge-up and must be melee-punished. The alert repeats for as long as the charge is up (while the red boss tile is shown) and stops as soon as you interrupt it", section = mokhaiotl, position = 18)
    default boolean mokhaiotlMeleePunishSound() {
        return false;
    }

    @Range(min = 0, max = 100)
    @ConfigItem(keyName = "mokhaiotlMeleePunishSoundVolume", name = "Melee Punish Sound Volume", description = "Volume of the melee-punish audio alert (0-100)", section = mokhaiotl, position = 19)
    default int mokhaiotlMeleePunishSoundVolume() {
        return 70;
    }

    @ConfigItem(keyName = "showMokhaiotlStatues", name = "Suggest Statue Pairs", description = "Highlight the nearest usable pair of volatile earth statues (in line, 14+ tiles apart, relaxing to a shorter or one-tile-off-axis pair when no such pair exists): the far one green then red once attacked, the near one dormant grey until its turn. Also takes the statues outside that pair, and every demonic larva, off left-click for the duration of the shockwave phase, so a misclick walks instead of attacking the wrong target - they stay on right-click, normal larva clicking resumes once the phase ends, and nothing is demoted while no pair is highlighted", section = mokhaiotl, position = 20)
    default boolean showMokhaiotlStatues() {
        return true;
    }





    @ConfigItem(keyName = "showMokhaiotlOrbTile", name = "Hide Orb Model and Show True Tile", description = "Highlight the earthen shield orb's 3x3 true tile and hide the orb model so it doesn't obscure the arena, letting you stand under it as it moves between statues", section = mokhaiotl, position = 26)
    default boolean showMokhaiotlOrbTile() {
        return true;
    }

    @ConfigItem(keyName = "simplifyMokhaiotlArena", name = "Simplify Arena", description = "Repaint the walled arena room's floor a single flat colour (set by Simplified Arena Color below), with its texture stripped, so the fight's tile highlights (dash path, slam area, boulder shatter tiles) stand out against a uniform background instead of the arena's own patterned floor. Covers the whole room - its corners, wall ring and entrance alcove - and is applied from the moment you enter rather than waiting for the boss to spawn. The original floor is restored as soon as you leave or turn this off", section = mokhaiotl, position = 28)
    default boolean simplifyMokhaiotlArena() {
        return false;
    }

    @ConfigItem(keyName = "mokhaiotlArenaColor", name = "Simplified Arena Color", description = "The colour the arena floor is repainted when Simplify Arena is on. Only the hue, saturation and brightness are used - the floor is still shaded by the client's own lighting, so it will not match this swatch exactly, and transparency has no effect. A desaturated mid tone keeps the fight's tile highlights legible on top of it", section = mokhaiotl, position = 29)
    default Color mokhaiotlArenaColor() {
        return new Color(166, 138, 108);
    }
}
