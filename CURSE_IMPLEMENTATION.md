# Phosani's Nightmare Curse Implementation

## Overview
The curse mechanic has been successfully implemented for Phosani's Nightmare, providing intelligent prayer shuffling support.

## Curse Mechanics

### Detection
- **Animation ID**: 8599 (curse special attack)
- **Duration**: 5 attacks from Phosani
- **Auto-tracking**: Automatically decrements with each attack during curse

### Prayer Shuffling Logic
During curse, protection prayers are shuffled:
- **Protect from Magic** → activates **Protect from Missiles** (Range)
- **Protect from Missiles** → activates **Protect from Melee**
- **Protect from Melee** → activates **Protect from Magic**

### Smart Overlay Colors
The overlay intelligently shows the prayer you should **click** (not the attack type):

| Attack Type | Normal Color | Cursed Color | Reason |
|-------------|--------------|--------------|---------|
| Magic | Blue | Green | Click Protect from Magic → activates Range protection |
| Range | Green | Red | Click Protect from Missiles → activates Melee protection |
| Melee | Red | Blue | Click Protect from Melee → activates Magic protection |

## Implementation Details

### PhosaniHandler.java Changes
1. **Added Constants**:
   - `ANIMATION_CURSE = 8599`
   - `CURSE_DURATION_ATTACKS = 5`
   - Updated: `ATTACK_CYCLE_TICKS = 6` (consistent throughout fight)

2. **New Fields**:
   - `Map<Integer, Integer> phosaniCurseAttacks` - tracks remaining curse attacks per NPC

3. **New Methods**:
   - `isPhosaniCursed(int npcIndex)` - checks if NPC is cursed
   - `getPhosaniCurseAttacksRemaining(int npcIndex)` - gets remaining curse attacks
   - `getEffectivePhase(int npcIndex)` - returns correct phase accounting for curse

4. **Enhanced Logic**:
   - Curse detection on animation 8599
   - Curse counter decrementation on each attack
   - Automatic curse removal after 5 attacks
   - Consistent 6-tick attack timing throughout fight

### PhosaniOverlay.java Changes
1. **Updated Phase Display**:
   - Uses `getEffectivePhase()` instead of `getPhasaniPhase()`
   - Accounts for prayer shuffling automatically

2. **New Visual Elements**:
   - `renderCurseIndicator()` method
   - Purple "CURSE: X" text display above boss
   - Configurable curse indicator visibility

### PvmKitsConfig.java Changes
1. **New Configuration Option**:
   - `showPhosaniCurseIndicator()` - enable/disable curse status display

## User Experience

### Visual Feedback
- **Overlay Color**: Shows the prayer button to click (accounting for shuffling)
- **Curse Counter**: Bright purple "CURSE: X" text above Phosani
- **Duration Tracking**: Automatically counts down from 5 to 0

### Configuration
- **Toggle**: Can enable/disable curse indicator in config
- **Integration**: Works seamlessly with existing attack timer and phase highlighting

## Testing Notes

### What to Verify
1. **Curse Detection**: Animation 8599 triggers curse mode
2. **Color Mapping**: Overlay colors show correct prayer to click
3. **Counter Logic**: Decrements properly with each attack (5 → 4 → 3 → 2 → 1 → 0)
4. **Auto-Reset**: Curse ends automatically after 5 attacks
5. **Visual Display**: Purple curse text appears above boss

### Debug Logging
The implementation includes comprehensive logging:
- Curse activation: `"Phosani curse activated! Duration: 5 attacks"`
- Attack counting: `"Phosani curse: X attacks remaining"`
- Curse end: `"Phosani curse has ended"`

## Build Status
✅ **Compilation**: Successful  
✅ **Integration**: Complete  
✅ **Configuration**: Added  
✅ **Documentation**: Updated  
✅ **Ready for Testing**: Yes

The curse implementation is complete and ready for field testing with Phosani's Nightmare encounters.
