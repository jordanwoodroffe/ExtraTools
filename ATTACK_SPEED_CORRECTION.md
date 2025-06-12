# Phosani Attack Speed Correction

## Updated Attack Timing

The Phosani's Nightmare attack speed has been corrected based on accurate information:

### Previous (Incorrect) Timing:
- **Normal Phase**: 8 ticks
- **Enrage Phase**: 6 ticks

### Updated (Correct) Timing:
- **Normal Phase**: 6 ticks  
- **Enrage Phase**: 4 ticks (≤25% HP)

## Changes Made

### Code Changes:
1. **PhosaniHandler.java**
   - `ATTACK_CYCLE_TICKS`: 8 → 6
   - `ENRAGE_ATTACK_CYCLE_TICKS`: 6 → 4

### Documentation Updates:
1. **PHOSANI_INTEGRATION.md** - Updated attack timing descriptions
2. **README.md** - Updated Phosani section with correct timing  
3. **IMPLEMENTATION_SUMMARY.md** - Updated feature descriptions

## Impact

This change makes the attack timer more accurate for actual Phosani's Nightmare encounters:
- **Faster Response**: Players get more accurate timing for prayer switching/positioning
- **Better Enrage Detection**: 4-tick timing in enrage phase provides proper warning for the increased attack speed
- **Improved Accuracy**: Timer now matches the actual boss mechanics

## Verification
✅ **Build Status**: Successful compilation
✅ **Documentation**: All files updated consistently  
✅ **Functionality**: Timer logic remains intact with new constants

The plugin is now ready for testing with the correct 6-tick attack cycle for Phosani's Nightmare.
