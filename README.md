# PVM Kits - RuneLite Plugin

Multi-boss PVM assistance toolkit with mechanics overlays and timers for Old School RuneScape.

## Supported Bosses

### 1. Yama
- **Phase Detection**: Melee, Range, Magic, Fire Special, Shadow Special
- **Attack Timing**: 8-tick normal attacks, 7-tick enrage phase with countdown
- **Enrage Detection**: Automatic detection via transition graphics
- **Visual Features**: 5x5 tile area highlighting, configurable phase colors
- **Configuration**: Full customization in "Yama" tab

### 2. Phosani's Nightmare
- **Phase Detection**: Melee, Range, Magic, Special attacks
- **Attack Timing**: 8-tick normal attacks, 6-tick enrage phase (≤25% HP)
- **Enrage Detection**: Automatic detection via HP percentage monitoring
- **Visual Features**: 5x5 tile area highlighting, configurable phase colors
- **NPC Support**: All Phosani variants (IDs: 9416-9424, 11153-11155, 377)
- **Configuration**: Full customization in "Phosani's Nightmare" tab

### 3. Verzik (Normal Mode ToB)
- **Phase Detection**: P1 Dawnbringer/Pillars (14 ticks), P2 Flying/Lightning (4 ticks), P3 Spider Hybrid (7 ticks, 5 when enraged at 20% HP)
- **Attack Styles**: Web Throw, Melee, Range, Mage, Yellow Pool
- **Attack Timing**: P1: 14 ticks (hide behind pillars), P2: 4 ticks (step back), P3: 7 ticks (5 when enraged)
- **Visual Features**: Tile coloring based on attack style (like Yama), attack timers
- **Configuration**: Attack style colors match Yama colors, timer settings in "Verzik" tab

### 4. Sotetseg (Normal Mode ToB)
- **Phase Detection**: Overworld, Shadow Realm
- **Attack Styles**: Melee, Mage, Range, Death Ball, Shadow Teleport
- **Attack Timing**: 4-tick cycle for both phases
- **Special Features**: Death ball warning with countdown
- **Visual Features**: Phase highlighting, attack style indicators, special warnings
- **Configuration**: Full customization in "Sotetseg" tab

## Features

- **Automatic Context Switching**: Plugin automatically detects which boss you're fighting
- **Configurable Overlays**: Customize colors, text sizes, and visibility options
- **Attack Timers**: Visual countdown timers for boss attacks
- **Phase Highlighting**: Different colors for each boss phase
- **Special Attack Warnings**: Alerts for dangerous special attacks
- **Debug Information**: Optional debug output for troubleshooting

## Installation

1. Build the plugin: `./gradlew build`
2. Copy the JAR from `build/libs/` to your RuneLite plugins folder
3. Enable "PVM Kits" in the RuneLite plugin manager
4. Configure settings in the plugin configuration panel

## Configuration

The plugin provides separate configuration tabs for each boss:

- **General**: Debug information and global settings
- **Yama**: All Yama-specific overlays and colors
- **Phosani's Nightmare**: All Phosani-specific overlays and colors
- **Verzik**: All Verzik-specific overlays and colors
- **Sotetseg**: All Sotetseg-specific overlays and colors

## Technical Details

- **Architecture**: Extensible multi-boss handler system
- **Boss Detection**: Uses NPC IDs, animation IDs, and graphic IDs
- **Attack Tracking**: Cooldown handling to prevent duplicate detection
- **Overlay System**: Dynamic overlays with proper lifecycle management
- **Configuration**: Centralized config with section-based organization

## Adding New Bosses

To add a new boss:

1. Create handler class implementing `BossHandler`
2. Create overlay class extending `Overlay`
3. Add configuration options to `PvmKitsConfig`
4. Register in `PvmKitsPlugin.startUp()`
5. Add overlay to overlay manager

## Tags

`combat`, `boss`, `pvm`, `mechanics`, `yama`, `phosani`, `nightmare`, `verzik`, `sotetseg`, `tob`
