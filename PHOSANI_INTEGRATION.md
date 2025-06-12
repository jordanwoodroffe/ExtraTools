# Phosani's Nightmare Integration

This document describes the newly added Phosani's Nightmare support in the PVM Kits plugin.

## Features

### Attack Timer
- Displays countdown timer showing when the next attack will occur
- Updates based on detected attack animations and graphics
- Consistent 6-tick attack cycle throughout the entire fight
- No enrage phase - timing remains constant

### Attack Style Overlay
- Highlights Phosani's Nightmare with different colors based on combat phase:
  - **Melee**: Soft red (240, 100, 100, 120)
  - **Magic**: Soft blue (100, 149, 237)
  - **Range**: Soft green (144, 238, 144)
  - **Special**: Orange (255, 165, 0, 100)
  - **Unknown**: Gray (when phase cannot be determined)

### Curse Mechanic Support
- **Detection**: Automatically detects curse special attack (animation ID 8599)
- **Prayer Shuffling**: During curse, overlay shows the prayer you should CLICK:
  - Magic attack → shows Range color (click Protect from Magic → activates Protect from Missiles)
  - Range attack → shows Melee color (click Protect from Missiles → activates Protect from Melee)
  - Melee attack → shows Magic color (click Protect from Melee → activates Protect from Magic)
- **Duration Tracking**: Displays remaining curse attacks (5 total)
- **Visual Indicator**: Bright purple "CURSE: X" text above the boss

## Supported NPC IDs
The handler detects any of the following Phosani's Nightmare NPC IDs:
- 9416, 9417, 9418, 9419, 9420, 9421, 9422, 9423, 9424
- 11153, 11154, 11155, 377

## Configuration Options
All options are available in the "Phosani's Nightmare" section of the plugin config:

### Visual Settings
- **Highlight Phosani**: Enable/disable attack style overlay
- **Show Attack Timers**: Enable/disable timer display
- **Show Curse Indicator**: Enable/disable curse status display
- **Highlight Transparency**: Adjust transparency of the overlay (0-255)

### Color Customization
- **Melee Phase Color**: Color for melee attacks
- **Magic Phase Color**: Color for magic attacks  
- **Ranged Phase Color**: Color for ranged attacks
- **Special Attack Color**: Color for special attacks

### Timer Settings
- **Timer Text Size**: Size of the countdown text (default: 36)
- **Warning Color**: Color when 1 tick remains (default: red)
- **Normal Timer Color**: Color during normal countdown (default: teal)

### Technical Notes

### Animation & Graphic Detection
The handler monitors:
- Animation changes to detect attack types and reset timers
- Graphic changes for additional attack detection
- Curse animation (8599) to activate prayer shuffling logic

### Curse Logic
- **Prayer Mapping**: When cursed, the overlay color represents the prayer button to click
- **Attack Counting**: Each attack during curse decrements the counter
- **Auto-Reset**: Curse automatically ends after 5 attacks

### Attack Timing
- **Attack Cycle**: 6-tick attack cycle (consistent throughout fight)
- **No Enrage Phase**: Timing remains constant regardless of HP
- **Cooldown System**: Prevents duplicate timer resets (6-tick cooldown after each detected attack)

### Known Limitations
- Animation and graphic IDs are placeholders and need to be verified through actual gameplay
- Phase detection may need refinement based on actual Phosani behavior
- The 5x5 tile overlay size may need adjustment if Phosani has a different footprint

## Future Improvements
- Verify and update animation/graphic IDs through testing
- Add projectile detection for ranged attacks
- Implement special attack warnings
- Add sound notifications for imminent attacks
- Fine-tune enrage phase detection logic
