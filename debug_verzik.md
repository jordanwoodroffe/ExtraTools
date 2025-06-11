<!-- filepath: /Users/jordanwoodroffe/Documents/Projects/ExtraTools/debug_verzik.md -->
# VerzikOverlay Debug Checklist

## ✅ COMPLETED IMPLEMENTATION STATUS:

### Core Logic ✅
- **VerzikHandler**: Targets NPC ID 8374 only, 7-tick normal/5-tick enraged cycles
- **Attack Detection**: Animation -1 triggers, defaults to RANGE, switches to MAGE on graphic 1581  
- **Timer System**: 6-tick cooldown prevents duplicates, auto-resets on attack
- **Enraged Mode**: Graphic 560 switches from 7-tick to 5-tick cycle
- **Build Status**: ✅ Compiles successfully with expected deprecation warnings

### Overlay Rendering ✅  
- **Polygon Approach**: Uses corner tiles (same as YamaOverlay) instead of broken getCanvasTileAreaPoly()
- **UNKNOWN Handling**: Light gray color for pre-attack state so overlay always renders
- **Debug Logging**: Console output for NPC detection, attack style, timer status
- **Timer Display**: Shows countdown with color changes (white normal, red ≤2)

## 🧪 READY FOR TESTING:

### Prerequisites:
1. **RuneLite client** with plugin loaded
2. **Theatre of Blood** P3 Verzik encounter (NPC ID 8374)
3. **Console access** to view debug output

## ✅ Issues Fixed:

### 1. Overlay Rendering Approach
- **Problem**: VerzikOverlay was using `Perspective.getCanvasTileAreaPoly()` which may not work correctly
- **Solution**: Changed to use same polygon construction approach as YamaOverlay (corner tiles + manual polygon)
- **Result**: Now uses corner tile polygons and `addPointsToPolygon()` helper method

### 2. UNKNOWN Attack Style Handling  
- **Problem**: When attack style was UNKNOWN, `getAttackStyleColor()` returned `null` and overlay returned early
- **Solution**: Added case for UNKNOWN attack style to return light gray color
- **Result**: Overlay will now render even when no attack has been detected yet

### 3. Debug Logging
- **Added**: Debug logging in render method to track NPC detection, attack style, timer status
- **Output**: Will print to console when near NPC ID 8374

## 🧪 Testing Steps:

### Test 1: Basic Overlay Rendering
1. **Go near NPC ID 8374** (P3 Verzik)
2. **Check console output** - Should see: `"Verzik Overlay Debug - NPC: 8374, AttackStyle: UNKNOWN, TimerActive: true, Timer: X"`
3. **Check visual overlay** - Should see light gray 7x7 tile highlighting around Verzik

### Test 2: Attack Detection
1. **Wait for Verzik to attack** (animation -1)
2. **Check console** - Should see attack style change to RANGE
3. **Check overlay color** - Should change from gray to green/purple (range color)
4. **If graphic 1581 appears** - Attack style should change to MAGE

### Test 3: Timer Display
1. **Check timer countdown** - Should show numbers counting down from 7 to 1
2. **After attack** - Timer should reset to 7 (or 5 if enraged)
3. **Timer color** - Should be white normally, red when ≤ 2

### Test 4: Enraged Phase
1. **Wait for graphic 560** - Should trigger enraged mode
2. **Check timer cycle** - Should change from 7 ticks to 5 ticks
3. **Console should show** timer values changing accordingly

## 🎯 Expected Behavior:

### Visual Elements:
- **7x7 tile area** around Verzik highlighted with semi-transparent color
- **Attack timer** text displayed above Verzik
- **Color changes** based on attack style (gray → range/mage colors)

### Debug Output:
```
Verzik Overlay Debug - NPC: 8374, AttackStyle: UNKNOWN, TimerActive: true, Timer: 7
Verzik Overlay Debug - NPC: 8374, AttackStyle: RANGE, TimerActive: true, Timer: 6
Verzik Overlay Debug - NPC: 8374, AttackStyle: RANGE, TimerActive: true, Timer: 5
...
```

## 🔧 Configuration:
- **Enable Verzik**: ✅ Default enabled
- **Show Timer**: ✅ Default enabled  
- **Colors**: Range (green), Mage (blue), UNKNOWN (light gray)

## 📝 Key Changes Made:

1. ✅ Fixed polygon rendering approach (same as YamaOverlay)
2. ✅ Added UNKNOWN attack style color support
3. ✅ Added debug logging for troubleshooting
4. ✅ Ensured NPC filtering only targets 8374
5. ✅ Used proper 7x7 tile size for P3 Verzik
