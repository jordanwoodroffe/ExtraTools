package com.pvmkits.bosses.nightmare;

import com.pvmkits.core.BossHandler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.api.events.AreaSoundEffectPlayed;

import javax.inject.Inject;
import java.awt.Color;
import java.util.*;

@Slf4j
public class NightmareHandler implements BossHandler {

    @Inject
    private Client client;

    // Track current Nightmare phases by NPC index
    private Map<Integer, NightmarePhase> nightmarePhases = new HashMap<>();

    // The Nightmare NPC IDs. Her index/id changes as the fight progresses through
    // her phases, so the full combat-form range is included.
    private static final Set<Integer> NIGHTMARE_IDS = Set.of(9425, 9426, 9427, 9428, 9429, 9430, 9431, 9432, 9433);

    // Spore danger zone tracking
    private static final int SPORE_GAME_OBJECT_ID = 37739;
    private Set<WorldPoint> sporeDangerZones = new HashSet<>();

    // Surge ("charge forward") danger zone tracking. During the surge special the
    // Nightmare teleports to a room edge and flies in a straight line to the
    // opposite side, dealing heavy damage to anything in her path. These are the
    // animations played as she lifts off / dashes across.
    private static final Set<Integer> SURGE_ANIMATION_IDS = Set.of(8607, 8609);
    // How many tiles ahead of her footprint the surge path is marked when we can't
    // resolve the room edge.
    private static final int SURGE_PATH_LENGTH = 10;
    // Keep the surge path highlighted for a few ticks after the animation so it
    // stays visible for the whole dash; it is recomputed live each tick from her
    // current position/orientation so the corridor tracks her as she flies.
    private static final int SURGE_PERSIST_TICKS = 5;
    private int surgeActiveUntilTick = -1;
    private Set<WorldPoint> surgeDangerZone = new HashSet<>();

    // Shadow phase "undead hand" safe tile tracking
    private static final int SHADOW_HAND_GRAPHIC_ID = 1767;
    private static final int SPORE_RADIUS = 1; // spore danger zone is 3x3 (1 tile radius)
    private static final int SAFE_TILE_SEARCH_RADIUS = 2; // primary search radius
    private static final int SAFE_TILE_EXPANDED_RADIUS = 3; // fallback search radius if primary yields nothing
    // Client cycles (20ms) per game tick (600ms); used to age claws by their start cycle.
    private static final int CYCLES_PER_GAME_TICK = Constants.GAME_TICK_LENGTH / Constants.CLIENT_TICK_LENGTH;
    // How many game ticks a grasping-claw tile stays dangerous, measured from the
    // spotanim's own start cycle. The claw's 4-tick animation is charge-up, charge-up,
    // release (the 3rd / second-to-last tick that actually deals the damage), then
    // charge-down (the 4th tick, by which point the tile is safe to stand on). So the
    // tile is dangerous for its first 3 ticks and becomes safe on the 4th - we free it
    // the tick after the damage frame instead of waiting for the graphic to despawn.
    private static final int CLAW_DANGER_TICKS = 3;
    // Keep the computed safe tiles on screen for a few ticks after the last active claw
    // clears, so the overlay doesn't blink empty in the brief gap between the repeating
    // casts before the next set of claws charges up.
    private static final int SAFE_TILE_HOLD_TICKS = 2;
    private Set<WorldPoint> shadowHandTiles = new HashSet<>();
    // Start cycle of the most recent grasping-claw spotanim seen on each tile. A claw's
    // age is derived from this rather than from the tick we happened to poll it, so a
    // tile is freed exactly when its damage frame passes and the single-tick flicker of
    // the claw graphic can't briefly make a still-dangerous tile read as safe.
    private Map<WorldPoint, Integer> shadowHandStartCycle = new HashMap<>();
    private WorldPoint lastSafeTileOrigin;
    // Last game tick an active claw existed, used to briefly hold the safe-tile overlay
    // across the gap between repeating casts.
    private int lastActiveClawTick = -1;
    // All nearby tiles that are clear of grasping claws and spores, so the player can
    // pick a safe spot themselves rather than being directed to a single tile.
    private Set<WorldPoint> safeTiles = new HashSet<>();

    // The Nightmare animation IDs (melee/mage/range confirmed in-game)
    private static final int ANIMATION_MELEE = 8594; // Confirmed - melee attack
    private static final int ANIMATION_MAGE = 8595; // Confirmed - magic attack
    private static final int ANIMATION_RANGE = 8596; // Confirmed - ranged attack
    private static final int ANIMATION_SPECIAL = 8597; // Placeholder - needs verification
    private static final int ANIMATION_CURSE = 8604; // Curse special attack (needs final verification)

    // Nightmare graphic IDs (these will need to be determined through testing)
    private static final int GRAPHIC_MAGE = 1767; // Placeholder - needs verification
    private static final int GRAPHIC_RANGE = 1768; // Placeholder - needs verification
    private static final int GRAPHIC_SPECIAL = 1769; // Placeholder - needs verification

    // Attack cycle constants
    private static final int ATTACK_CYCLE_TICKS = 6; // Consistent 6-tick cycle throughout fight

    // Track last logged animation for each Nightmare to prevent duplicate logging
    private Map<Integer, Integer> lastLoggedAnimations = new HashMap<>();

    // Track attack timers for each Nightmare (NPC index -> ticks until next attack)
    private Map<Integer, Integer> nightmareAttackTimers = new HashMap<>();

    // Track which timers were just initialized this tick to prevent immediate
    // countdown
    private Set<Integer> newlyInitializedTimers = new HashSet<>();

    // Track attack cooldowns to prevent multiple timer resets from duplicate
    // animations
    // Maps NPC index to the tick when the cooldown expires
    private Map<Integer, Integer> attackCooldowns = new HashMap<>();

    // Cooldown duration in ticks after detecting an attack
    private static final int ATTACK_COOLDOWN_TICKS = 6;

    // Track curse state for each Nightmare (NPC index -> remaining curse attacks).
    // A value of 0 means "curse still applies for this tick's launched attack, then
    // expire at the start of the next tick" to avoid an early overlay flip.
    private Map<Integer, Integer> nightmareCurseAttacks = new HashMap<>();

    // Hard tick-based backstop for each curse (NPC index -> game tick the curse must
    // expire by, regardless of how many attacks we've counted). The attack counter is
    // the primary mechanism, but during phase 3 her special attacks (spores, grasping
    // claws) aren't counted as attacks, so the counter can stall and drift far longer
    // than the real 5-attack curse. This deadline guarantees the overlay can never keep
    // shuffling prayers after the real curse has worn off.
    private Map<Integer, Integer> nightmareCurseDeadlineTick = new HashMap<>();

    // Slot anchor for each curse (NPC index -> game tick of the most recently counted
    // attack slot). The curse is counted by Nightmare's 6-tick attack RHYTHM rather than
    // by classifying individual attack animations: every 6-tick cycle is one attack
    // toward the 5-attack curse, whether or not that attack used a standard animation.
    // A tick-driven slot clock (onGameTick) counts cycles that pass without a detected
    // standard attack - this captures her special-animation attacks (e.g. 8606) that
    // were previously uncounted and made the overlay's curse outlast the real one - and
    // each detected standard attack re-anchors this tick so any rhythm offset self-
    // corrects against her actual attacks.
    private Map<Integer, Integer> nightmareCurseLastSlotTick = new HashMap<>();

    // Curse duration constants
    // The Nightmare's curse is lifted after 5 of her attacks (per game mechanics).
    private static final int CURSE_DURATION_ATTACKS = 5;
    // Hard upper bound on curse lifetime in ticks. She attacks on a 6-tick cycle.
    // The curse spans 5 cursed attacks and is only lifted when the following (6th)
    // attack starts, so a legitimate curse can last ~36 ticks from activation; two
    // extra cycles of slack cover a delayed/undetected attack without cutting the
    // real curse - including that final following attack - short.
    private static final int CURSE_MAX_DURATION_TICKS = (CURSE_DURATION_ATTACKS + 2) * NightmareHandler.ATTACK_CYCLE_TICKS;

    // --- Debug tracking (helps diagnose curse/phase desync across phase
    // transitions, e.g. totem phases where the boss NPC index may change) ---
    // The set of Nightmare NPC indices seen on the previous tick, used to detect when
    // the boss NPC is swapped for a new index (which abandons old curse state).
    private Set<Integer> lastKnownNightmareIndices = new HashSet<>();
    // Last effective (overlay) phase logged per index, so we only log when the
    // colour the player actually sees changes.
    private Map<Integer, NightmarePhase> lastLoggedEffectivePhase = new HashMap<>();

    // --- TEMPORARY totem-ID discovery debug ---
    // Known "empty" (needs-charging) totem NPC ids, confirmed in-game. Used to learn
    // each corner's location so we can log whatever NPC id replaces it when charged.
    private static final Set<Integer> TOTEM_EMPTY_IDS = Set.of(9434, 9440, 9437, 9443);
    // World locations where an empty totem has been seen this fight (the totem's
    // corner). The charged totem spawns at the same tile with a different id.
    private Set<WorldPoint> knownTotemLocations = new HashSet<>();
    // NPC ids already logged near a totem corner, so each id is only reported once.
    private Set<Integer> loggedTotemCandidateIds = new HashSet<>();

    // All known totem NPC ids (empty + full states) used to detect when the totem
    // phase is active. Full ids are inferred (empty + 2, matching the confirmed SW
    // pair 9434/9436) and should be corrected once verified in-game.
    private static final Set<Integer> ALL_TOTEM_IDS = Set.of(9434, 9436, 9440, 9442, 9437, 9439, 9443, 9445);
    // Whether any totem NPC was present on the previous tick, so we only log the
    // start/end of the totem phase rather than every tick.
    private boolean totemPhaseActive = false;

    @Override
    public String getBossName() {
        return "The Nightmare";
    }

    @Override
    public boolean isInBossArea(Client client) {
        // Check if any Nightmare NPCs are present
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && NIGHTMARE_IDS.contains(npc.getId())) {
                log.info("NightmareHandler.isInBossArea: Found Nightmare NPC with ID " + npc.getId() + " and index "
                        + npc.getIndex());
                return true;
            }
        }
        log.debug("NightmareHandler.isInBossArea: No Nightmare NPCs found, checking all NPCs...");

        // Debug: Log all NPC IDs to help identify if Nightmare ID is wrong
        int npcCount = 0;
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null) {
                npcCount++;
                if (npcCount <= 5) { // Only log first 5 NPCs to avoid spam
                    log.debug(
                            "NightmareHandler.isInBossArea: Found NPC ID " + npc.getId() + " at index " + npc.getIndex());
                }
            }
        }
        log.debug("NightmareHandler.isInBossArea: Total NPCs found: " + npcCount);

        return false;
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        // Animation detection moved to onGameTick to match working example
    }

    // TEMPORARY DEBUG: log every sound effect heard while at the Nightmare so we can
    // identify the sound id(s) that play when the totem (charging) phase starts and
    // finishes. Remove once the totem-phase cue id is known.
    public void onSoundEffectPlayed(SoundEffectPlayed event) {
        Actor source = event.getSource();
        String sourceName = source != null ? source.getName() : "world";
        log.info("SOUND DEBUG: SoundEffectPlayed id=" + event.getSoundId() + " source=" + sourceName);
    }

    // TEMPORARY DEBUG: area sounds carry a world location, which is useful for the
    // totem cue since it likely originates from a totem corner. Remove once known.
    public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event) {
        Actor source = event.getSource();
        String sourceName = source != null ? source.getName() : "world";
        log.info("SOUND DEBUG: AreaSoundEffectPlayed id=" + event.getSoundId()
                + " at (" + event.getSceneX() + "," + event.getSceneY() + ") source=" + sourceName);
    }

    @Override
    @SuppressWarnings("deprecation") // getGraphic() is deprecated but still functional
    public void onGraphicChanged(GraphicChanged event) {
        Actor actor = event.getActor();

        if (!(actor instanceof NPC)) {
            return;
        }

        NPC npc = (NPC) actor;
        if (!NIGHTMARE_IDS.contains(npc.getId())) {
            return;
        }

        int index = npc.getIndex();
        int graphicId = npc.getGraphic();

        // Log every graphic change event, including when graphics are cleared
        log.info("Nightmare (index " + index + ") attack graphic: graphicId=" + graphicId);
     
        // Reset timer when graphic-based attacks are detected, but only if not in
        // cooldown
        if (isAttackGraphic(graphicId)) {
            registerNightmareAttack(index, "graphic");
        }

        // Update phase based on graphics
        if (graphicId == GRAPHIC_MAGE) {
            nightmarePhases.put(index, NightmarePhase.MAGE);
        } else if (graphicId == GRAPHIC_RANGE) {
            nightmarePhases.put(index, NightmarePhase.RANGE);
        } else if (graphicId == GRAPHIC_SPECIAL) {
            nightmarePhases.put(index, NightmarePhase.SPECIAL);
        }
    }

    @Override
    public void onGameTick(GameTick event) {
        if (client.getGameState().getState() < 30) {
            return;
        }

        // Advance the curse by Nightmare's attack RHYTHM. She attacks on a strict 6-tick
        // cycle and the curse lifts after 5 attacks regardless of animation, so we count
        // attack SLOTS instead of classifying animations. Here we catch up any slot that
        // elapsed WITHOUT a detected standard attack - that slot was one of her special-
        // animation attacks (e.g. 8606), which must still count toward the curse. Using a
        // strict "> last + cycle" threshold (one tick of slack) guarantees a real standard
        // attack landing on its slot is counted by registerNightmareAttack instead, so a slot
        // is never decremented twice. Once the curse is held at the 0 sentinel, the next
        // elapsed slot is the FOLLOWING attack and ends the curse - this lifts it on time
        // even when that following attack is itself a special with no standard animation.
        // The hard tick deadline remains a final backstop against a total rhythm stall.
        int currentTickForCurse = client.getTickCount();
        for (Integer curseIndex : new ArrayList<>(nightmareCurseAttacks.keySet())) {
            while (nightmareCurseAttacks.containsKey(curseIndex)) {
                Integer lastSlot = nightmareCurseLastSlotTick.get(curseIndex);
                if (lastSlot == null || currentTickForCurse <= lastSlot + ATTACK_CYCLE_TICKS) {
                    break;
                }
                nightmareCurseLastSlotTick.put(curseIndex, lastSlot + ATTACK_CYCLE_TICKS);
                applyCurseSlots(curseIndex, 1, "special attack slot");
            }
            Integer deadline = nightmareCurseDeadlineTick.get(curseIndex);
            if (deadline != null && currentTickForCurse >= deadline && nightmareCurseAttacks.containsKey(curseIndex)) {
                log.info("Nightmare (index " + curseIndex
                        + ") curse has ended (tick deadline reached - rhythm stalled)");
                nightmareCurseAttacks.remove(curseIndex);
                nightmareCurseDeadlineTick.remove(curseIndex);
                nightmareCurseLastSlotTick.remove(curseIndex);
            }
        }

        log.debug("NightmareHandler.onGameTick: Called, GameState=" + client.getGameState());

        boolean nightmarePresent = false;
        Set<Integer> currentNightmareIndices = new HashSet<>();
        // Track all visible Nightmares in the scene
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && NIGHTMARE_IDS.contains(npc.getId())) {
                nightmarePresent = true;
                int index = npc.getIndex();
                currentNightmareIndices.add(index);
                log.debug("NightmareHandler.onGameTick: Processing Nightmare with index " + index);

                // Log animation IDs for Nightmare only when they change
                int animationId = npc.getAnimation();
                if (animationId != -1) {
                    Integer lastLogged = lastLoggedAnimations.get(index);
                    boolean animationChanged = lastLogged == null || lastLogged != animationId;
                    if (lastLogged == null || lastLogged != animationId) {
                        log.info("Nightmare (index " + index + ") animation: animationId=" + animationId);
                        lastLoggedAnimations.put(index, animationId);
                    }

                    // Reset timer when Nightmare attacks, but only if not in cooldown
                    if (isAttackAnimation(animationId)) {
                        registerNightmareAttack(index, "animation");
                    }

                    // Detect the surge ("charge forward") special. Arm the danger-zone
                    // window so her flight path is highlighted for the next few ticks.
                    if (SURGE_ANIMATION_IDS.contains(animationId)) {
                        surgeActiveUntilTick = client.getTickCount() + SURGE_PERSIST_TICKS;
                        log.info("Nightmare (index " + index + ") SURGE detected (animationId=" + animationId
                                + ") - marking flight path");
                    }

                    // Check for curse animation only when it starts, not while the same
                    // animation frame persists across multiple ticks.
                    if (animationId == ANIMATION_CURSE && animationChanged) {
                        Integer existing = nightmareCurseAttacks.get(index);
                        if (existing != null && existing > 0) {
                            log.info("Nightmare (index " + index + ") curse RE-APPLIED while already active (was "
                                    + existing + " remaining) - resetting to " + CURSE_DURATION_ATTACKS);
                        }
                        nightmareCurseAttacks.put(index, CURSE_DURATION_ATTACKS);
                        nightmareCurseDeadlineTick.put(index, client.getTickCount() + CURSE_MAX_DURATION_TICKS);
                        // Anchor the slot clock to the cast. The cast occupies an attack
                        // slot, so the first cursed attack is one 6-tick cycle later; the
                        // slot clock and registerNightmareAttack both measure from here.
                        nightmareCurseLastSlotTick.put(index, client.getTickCount());
                        log.info("Nightmare (index " + index + ") curse activated! Duration: " + CURSE_DURATION_ATTACKS
                                + " attacks");
                    }

                    // Update phase based on animation if available
                    if (animationId == ANIMATION_MELEE) {
                        nightmarePhases.put(index, NightmarePhase.MELEE);
                    } else if (animationId == ANIMATION_MAGE) {
                        nightmarePhases.put(index, NightmarePhase.MAGE);
                    } else if (animationId == ANIMATION_RANGE) {
                        nightmarePhases.put(index, NightmarePhase.RANGE);
                    } else if (animationId == ANIMATION_SPECIAL) {
                        nightmarePhases.put(index, NightmarePhase.SPECIAL);
                    }
                }

                // Initialize with UNKNOWN if we haven't seen this Nightmare before
                if (!nightmarePhases.containsKey(index)) {
                    nightmarePhases.put(index, NightmarePhase.UNKNOWN);
                    log.info("NightmareHandler.onGameTick: Initialized phase to UNKNOWN for Nightmare index " + index);
                }

                // Initialize timer if not present
                if (!nightmareAttackTimers.containsKey(index)) {
                    int attackTicks = getAttackCycleTicks(index);
                    nightmareAttackTimers.put(index, attackTicks);
                    newlyInitializedTimers.add(index);
                    log.info("Nightmare (index " + index + ") timer initialized to " + attackTicks);
                } else {
                    // Debug: Log current timer state every 10 ticks to avoid spam
                    if (client.getTickCount() % 10 == 0) {
                        int currentTimer = nightmareAttackTimers.get(index);
                        log.debug("Nightmare (index " + index + ") current timer value: " + currentTimer);
                    }
                }

                // Debug: log whenever the overlay's effective (displayed) phase changes,
                // including the curse state that drives the prayer-shuffle. This makes it
                // easy to confirm the colour shown to the player matches the boss's real
                // attack while cursed vs. un-cursed.
                NightmarePhase actualPhase = getNightmarePhase(index);
                NightmarePhase effectivePhase = getEffectivePhase(index);
                NightmarePhase previousEffective = lastLoggedEffectivePhase.get(index);
                if (previousEffective == null || previousEffective != effectivePhase) {
                    boolean cursed = isNightmareCursed(index);
                    log.info("Nightmare (index " + index + ") overlay phase -> " + effectivePhase
                            + " (actual=" + actualPhase + ", cursed=" + cursed
                            + (cursed ? ", curseRemaining=" + getNightmareCurseAttacksRemaining(index) : "")
                            + ")");
                    lastLoggedEffectivePhase.put(index, effectivePhase);
                }
            }
        }

        // If no Nightmare exists, clear all data
        if (!nightmarePresent) {
            if (!nightmarePhases.isEmpty() || !nightmareAttackTimers.isEmpty()) {
                log.info("NightmareHandler.onGameTick: No Nightmare present, clearing all data");
            }
            nightmarePhases.clear();
            nightmareAttackTimers.clear();
            attackCooldowns.clear();
            nightmareCurseAttacks.clear();
            nightmareCurseDeadlineTick.clear();
            nightmareCurseLastSlotTick.clear();
            shadowHandTiles.clear();
            shadowHandStartCycle.clear();
            lastActiveClawTick = -1;
            safeTiles.clear();
            surgeDangerZone.clear();
            surgeActiveUntilTick = -1;
            lastKnownNightmareIndices.clear();
            lastLoggedEffectivePhase.clear();
            knownTotemLocations.clear();
            loggedTotemCandidateIds.clear();
            totemPhaseActive = false;
            return;
        }

        // Debug: detect when the boss NPC index set changes between ticks. A new index
        // appearing (e.g. after a totem phase / boss respawn) means any curse state keyed
        // to the old index is abandoned, and a vanished index means stale state lingers.
        if (!currentNightmareIndices.equals(lastKnownNightmareIndices)) {
            Set<Integer> appeared = new HashSet<>(currentNightmareIndices);
            appeared.removeAll(lastKnownNightmareIndices);
            Set<Integer> vanished = new HashSet<>(lastKnownNightmareIndices);
            vanished.removeAll(currentNightmareIndices);
            if (!lastKnownNightmareIndices.isEmpty() && (!appeared.isEmpty() || !vanished.isEmpty())) {
                log.info("Nightmare NPC index set changed: appeared=" + appeared + ", vanished=" + vanished
                        + ", activeCurses=" + nightmareCurseAttacks);
            }
            // Drop stale curse/phase debug state for indices that no longer exist so the
            // overlay never keeps shuffling prayers based on a departed NPC.
            for (Integer goneIndex : vanished) {
                if (nightmareCurseAttacks.remove(goneIndex) != null) {
                    log.info("Nightmare (index " + goneIndex + ") removed - clearing its lingering curse state");
                }
                nightmareCurseDeadlineTick.remove(goneIndex);
                nightmareCurseLastSlotTick.remove(goneIndex);
                lastLoggedEffectivePhase.remove(goneIndex);
            }
            lastKnownNightmareIndices = new HashSet<>(currentNightmareIndices);
        }

        // Track shadow phase undead hands / grasping claws and recompute the safe tiles
        updateShadowHands();

        // Recompute the surge flight-path danger zone while a surge is active
        updateSurgeDangerZone();

        // TEMPORARY: discover charged-totem NPC ids by watching the totem corners
        logTotemCandidateIds();

        // Log when the totem (charging) phase starts and ends so we can see whether
        // the totem NPCs are only present during that phase or for the whole fight.
        updateTotemPhaseState();

        // Update attack timers for all Nightmares
        log.debug("NightmareHandler.onGameTick: Updating timers for " + nightmareAttackTimers.size() + " Nightmares");
        for (Map.Entry<Integer, Integer> entry : nightmareAttackTimers.entrySet()) {
            int nightmareIndex = entry.getKey();
            int currentTicks = entry.getValue();

            // Skip countdown for newly initialized timers this tick
            if (newlyInitializedTimers.contains(nightmareIndex)) {
                log.debug("Nightmare (index " + nightmareIndex + ") timer skip countdown (newly initialized): "
                        + currentTicks);
                continue;
            }

            // Only decrement if the timer is greater than 1
            if (currentTicks > 1) {
                // Countdown the timer
                int newTicks = currentTicks - 1;
                nightmareAttackTimers.put(nightmareIndex, newTicks);
                log.debug("Nightmare (index " + nightmareIndex + ") timer countdown: " + currentTicks + " -> " + newTicks);

            } else if (currentTicks == 1) {
                // Timer at 1, next tick should be an attack
                log.debug("Nightmare (index " + nightmareIndex + ") timer at 1, waiting for attack");
                // Keep timer at 1 until attack is detected
            } else if (currentTicks <= 0) {
                // Timer went below 1, reset it
                int attackTicks = getAttackCycleTicks(nightmareIndex);
                nightmareAttackTimers.put(nightmareIndex, attackTicks);
                log.info(
                        "Nightmare (index " + nightmareIndex + ") timer reset from " + currentTicks + " to " + attackTicks);
            }
        }

        // Clear the newly initialized timers set for next tick
        if (!newlyInitializedTimers.isEmpty()) {
            log.debug("NightmareHandler.onGameTick: Clearing newly initialized timers: " + newlyInitializedTimers);
        }
        newlyInitializedTimers.clear();
    }

    @Override
    public Actor getBossActor(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && NIGHTMARE_IDS.contains(npc.getId())) {
                return npc;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        nightmarePhases.clear();
        nightmareAttackTimers.clear();
        attackCooldowns.clear();
        lastLoggedAnimations.clear();
        newlyInitializedTimers.clear();
        nightmareCurseAttacks.clear();
        nightmareCurseDeadlineTick.clear();
        nightmareCurseLastSlotTick.clear();
        sporeDangerZones.clear();
        shadowHandTiles.clear();
        shadowHandStartCycle.clear();
        lastActiveClawTick = -1;
        lastSafeTileOrigin = null;
        safeTiles.clear();
        surgeDangerZone.clear();
        surgeActiveUntilTick = -1;
        lastKnownNightmareIndices.clear();
        lastLoggedEffectivePhase.clear();
        knownTotemLocations.clear();
        loggedTotemCandidateIds.clear();
        totemPhaseActive = false;
        log.info("NightmareHandler reset - all state cleared");
    }

    // Handle spore danger zone creation
    public void onGameObjectSpawned(GameObjectSpawned event) {
        GameObject gameObject = event.getGameObject();
        if (gameObject.getId() == SPORE_GAME_OBJECT_ID) {
            WorldPoint location = gameObject.getWorldLocation();
            sporeDangerZones.add(location);
            log.info("Spore danger zone activated at " + location);
        }
    }

    // Handle spore danger zone removal
    public void onGameObjectDespawned(GameObjectDespawned event) {
        GameObject gameObject = event.getGameObject();
        if (gameObject.getId() == SPORE_GAME_OBJECT_ID) {
            WorldPoint location = gameObject.getWorldLocation();
            sporeDangerZones.remove(location);
            log.info("Spore danger zone deactivated at " + location);
        }
    }

    // Poll active graphics objects for shadow phase grasping claws. Each claw's age is
    // taken from its spotanim start cycle, so its tile is marked dangerous only through
    // the release/damage frame and freed on the following (charge-down) tick - the point
    // at which it is safe to stand there again.
    private void updateShadowHands() {
        int currentCycle = client.getGameCycle();

        // Record (or re-arm) the start cycle of every claw graphic currently in the
        // scene. A newer cast on the same tile has a later start cycle and resets that
        // tile's danger window.
        for (GraphicsObject go : client.getGraphicsObjects()) {
            if (go == null || go.getId() != SHADOW_HAND_GRAPHIC_ID) {
                continue;
            }
            LocalPoint local = go.getLocation();
            if (local == null) {
                continue;
            }
            WorldPoint worldPoint = WorldPoint.fromLocal(client, local);
            if (worldPoint == null) {
                continue;
            }
            Integer existing = shadowHandStartCycle.get(worldPoint);
            if (existing == null || go.getStartCycle() > existing) {
                shadowHandStartCycle.put(worldPoint, go.getStartCycle());
            }
        }

        // Build the danger set from claws still within their damage window. Once
        // CLAW_DANGER_TICKS have elapsed since a claw started, its release/damage frame
        // has passed and the tile is the safe charge-down tile, so it is dropped
        // immediately rather than lingering until the graphic despawns. Deriving the age
        // from the start cycle also survives the single-tick flicker of the claw graphic.
        Set<WorldPoint> activeHands = new HashSet<>();
        int dangerCycles = CLAW_DANGER_TICKS * CYCLES_PER_GAME_TICK;
        Iterator<Map.Entry<WorldPoint, Integer>> it = shadowHandStartCycle.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WorldPoint, Integer> entry = it.next();
            int elapsed = currentCycle - entry.getValue();
            if (elapsed >= dangerCycles) {
                it.remove();
            } else {
                activeHands.add(entry.getKey());
            }
        }

        Player localPlayer = client.getLocalPlayer();
        WorldPoint playerLocation = localPlayer != null ? localPlayer.getWorldLocation() : null;
        updateSafeTilesForShadowHands(activeHands, playerLocation);
    }

    // While the surge special is active, mark the straight-line path the Nightmare
    // flies along. The corridor is her 5x5 footprint widened across her travel and
    // extended SURGE_PATH_LENGTH tiles forward in the direction she is facing. It is
    // recomputed every tick from her live position/orientation so it tracks her as
    // she dashes across the room.
    private void updateSurgeDangerZone() {
        if (client.getTickCount() > surgeActiveUntilTick) {
            if (!surgeDangerZone.isEmpty()) {
                surgeDangerZone.clear();
            }
            return;
        }

        NPC boss = null;
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && NIGHTMARE_IDS.contains(npc.getId())) {
                boss = npc;
                break;
            }
        }
        if (boss == null) {
            surgeDangerZone.clear();
            return;
        }

        surgeDangerZone = computeSurgeDangerZone(boss);
    }

    // Build the set of tiles covered by the surge flight path. Nightmare always surges
    // edge-to-edge in a cardinal direction, so her orientation is snapped to N/S/E/W.
    private Set<WorldPoint> computeSurgeDangerZone(NPC boss) {
        Set<WorldPoint> path = new HashSet<>();

        net.runelite.api.coords.WorldArea area = boss.getWorldArea();
        if (area == null) {
            return path;
        }

        int ax = area.getX();
        int ay = area.getY();
        int w = area.getWidth();
        int h = area.getHeight();
        int plane = boss.getWorldLocation().getPlane();

        // Orientation: 0 = south, 512 = west, 1024 = north, 1536 = east. Snap to the
        // nearest cardinal direction (0=S, 1=W, 2=N, 3=E).
        int dir = ((int) Math.round(boss.getOrientation() / 512.0)) & 3;
        int dx = 0;
        int dy = 0;
        switch (dir) {
            case 0: // facing south
                dy = -1;
                break;
            case 1: // facing west
                dx = -1;
                break;
            case 2: // facing north
                dy = 1;
                break;
            case 3: // facing east
                dx = 1;
                break;
        }

        // Always include her footprint so the band is continuous through her.
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                path.add(new WorldPoint(ax + x, ay + y, plane));
            }
        }

        // Extend a 5-wide (her model width) strip forward up to SURGE_PATH_LENGTH tiles.
        if (dy != 0) {
            // Travelling north/south: the strip spans her width along X.
            int startY = dy > 0 ? ay + h : ay - 1;
            for (int step = 0; step < SURGE_PATH_LENGTH; step++) {
                int y = startY + dy * step;
                for (int x = 0; x < w; x++) {
                    path.add(new WorldPoint(ax + x, y, plane));
                }
            }
        } else if (dx != 0) {
            // Travelling east/west: the strip spans her width along Y.
            int startX = dx > 0 ? ax + w : ax - 1;
            for (int step = 0; step < SURGE_PATH_LENGTH; step++) {
                int x = startX + dx * step;
                for (int y = 0; y < h; y++) {
                    path.add(new WorldPoint(x, ay + y, plane));
                }
            }
        }

        return path;
    }

    // TEMPORARY DEBUG: learn the totem corners from confirmed "empty" totem ids, then
    // log any other NPC id that appears on/adjacent to those corners (the charged
    // totem, and possibly intermediate charge states). Each id is logged only once.
    // Remove this method and its call once the full-state totem ids are confirmed.
    private void logTotemCandidateIds() {
        // Record the world location of every empty totem currently visible
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && TOTEM_EMPTY_IDS.contains(npc.getId())) {
                WorldPoint loc = npc.getWorldLocation();
                if (loc != null) {
                    knownTotemLocations.add(loc);
                }
            }
        }

        if (knownTotemLocations.isEmpty()) {
            return;
        }

        // Report any non-empty-totem NPC sitting on/next to a known totem corner
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc == null) {
                continue;
            }
            int id = npc.getId();
            if (TOTEM_EMPTY_IDS.contains(id) || loggedTotemCandidateIds.contains(id)) {
                continue;
            }
            WorldPoint loc = npc.getWorldLocation();
            if (loc == null) {
                continue;
            }
            for (WorldPoint totemLoc : knownTotemLocations) {
                if (totemLoc.getPlane() == loc.getPlane()
                        && Math.abs(totemLoc.getX() - loc.getX()) <= 1
                        && Math.abs(totemLoc.getY() - loc.getY()) <= 1) {
                    loggedTotemCandidateIds.add(id);
                    log.info("TOTEM DEBUG: NPC id " + id + " (\"" + npc.getName() + "\", index " + npc.getIndex()
                            + ") at " + loc + " is near totem corner " + totemLoc
                            + " - candidate totem state id");
                    break;
                }
            }
        }
    }

    // Detect and log totem (charging) phase transitions based on totem NPC presence.
    // The Nightmare's totems only become attackable after her shield is depleted, so
    // logging when these NPCs appear/disappear tells us whether the overlay should be
    // gated to this window rather than highlighting whenever the NPCs exist.
    private void updateTotemPhaseState() {
        int totemCount = 0;
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && ALL_TOTEM_IDS.contains(npc.getId())) {
                totemCount++;
            }
        }

        boolean totemsPresent = totemCount > 0;
        if (totemsPresent && !totemPhaseActive) {
            totemPhaseActive = true;
            log.info("TOTEM PHASE STARTED: " + totemCount + " totem NPC(s) detected");
        } else if (!totemsPresent && totemPhaseActive) {
            totemPhaseActive = false;
            log.info("TOTEM PHASE ENDED: no totem NPCs remain");
        }
    }

    void updateSafeTilesForShadowHands(Set<WorldPoint> currentHands, WorldPoint playerLocation) {
        int currentTick = client.getTickCount();

        // No claws active this tick. Hold the last safe-tile set for a few ticks so the
        // overlay doesn't blink empty during the brief gap between the repeating casts,
        // then clear once the phase has clearly ended (no claws for the whole hold
        // window). The held tiles were the gaps between the previous claws and so are
        // still genuinely safe while nothing is erupting.
        if (currentHands.isEmpty()) {
            boolean withinHold = lastActiveClawTick >= 0
                    && (currentTick - lastActiveClawTick) <= SAFE_TILE_HOLD_TICKS;
            if (!withinHold && (!shadowHandTiles.isEmpty() || !safeTiles.isEmpty())) {
                shadowHandTiles.clear();
                lastSafeTileOrigin = null;
                safeTiles.clear();
            }
            return;
        }

        lastActiveClawTick = currentTick;

        // A new cast is any tick where hands appeared that weren't tracked before
        boolean newCast = shadowHandTiles.isEmpty() || !shadowHandTiles.containsAll(currentHands);
        // The danger set changing in EITHER direction (claws added or removed) must
        // trigger a recompute. Using a full equality check rather than containsAll
        // ensures tiles freed by a resolved claw are re-highlighted as safe instead of
        // staying stale until the player moves or the next cast begins.
        boolean handsChanged = !shadowHandTiles.equals(currentHands);
        shadowHandTiles = currentHands;

        // Recompute on a danger-set change, when we have no safe tiles yet, or when the
        // player has moved and the local 2-tile search window should shift with them.
        if (handsChanged || safeTiles.isEmpty() || !Objects.equals(playerLocation, lastSafeTileOrigin)) {
            safeTiles = computeSafeTiles(playerLocation);
            lastSafeTileOrigin = playerLocation;
            if (newCast) {
                log.info("Nightmare safe tiles recomputed: " + safeTiles.size() + " options");
            }
        }
    }

    // Find every tile near the player that is clear of grasping claws and spores, so
    // the player can move to whichever safe spot is most convenient. First searches within
    // SAFE_TILE_SEARCH_RADIUS (2); if none found, expands to SAFE_TILE_EXPANDED_RADIUS (3).
    // Tiles underneath the boss are never returned.
    private Set<WorldPoint> computeSafeTiles(WorldPoint playerLocation) {
        if (playerLocation == null) {
            return new HashSet<>();
        }

        // First pass: search radius 2 for clear tiles (excluding under-boss)
        Set<WorldPoint> clearTiles = searchSafeTiles(playerLocation, SAFE_TILE_SEARCH_RADIUS);
        if (!clearTiles.isEmpty()) {
            return clearTiles;
        }

        // Second pass: if radius 2 has nothing, expand to radius 3
        return searchSafeTiles(playerLocation, SAFE_TILE_EXPANDED_RADIUS);
    }

    // Helper to search for safe tiles within a given radius, excluding under-boss tiles.
    private Set<WorldPoint> searchSafeTiles(WorldPoint playerLocation, int radius) {
        Set<WorldPoint> safeTiles = new HashSet<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                WorldPoint candidate = new WorldPoint(
                        playerLocation.getX() + dx,
                        playerLocation.getY() + dy,
                        playerLocation.getPlane());

                // Skip claws, spores, and any tiles under the boss
                if (shadowHandTiles.contains(candidate) || isInSporeDangerZone(candidate)
                        || isUnderBoss(candidate)) {
                    continue;
                }

                safeTiles.add(candidate);
            }
        }

        return safeTiles;
    }

    private boolean isUnderBoss(WorldPoint tile) {
        if (client == null || client.getTopLevelWorldView() == null) {
            return false;
        }

        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && NIGHTMARE_IDS.contains(npc.getId())) {
                if (npc.getWorldArea() != null && npc.getWorldArea().contains(tile)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInSporeDangerZone(WorldPoint tile) {
        for (WorldPoint spore : sporeDangerZones) {
            if (spore.getPlane() == tile.getPlane()
                    && Math.abs(spore.getX() - tile.getX()) <= SPORE_RADIUS
                    && Math.abs(spore.getY() - tile.getY()) <= SPORE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    // Register a detected Nightmare attack from either detection path (animation or
    // graphic). A shared cooldown ensures each real attack is only counted once,
    // and the curse counter is decremented here so it stays in sync with the boss's
    // actual attacks regardless of which path detected them.
    private void registerNightmareAttack(int index, String source) {
        int currentTick = client.getTickCount();
        Integer cooldownExpiry = attackCooldowns.get(index);

        // Ignore if we're still within the cooldown window from a prior detection
        if (cooldownExpiry != null && currentTick < cooldownExpiry) {
            log.debug("Nightmare (index " + index + ") " + source
                    + " attack ignored - in cooldown until tick " + cooldownExpiry);
            return;
        }

        int attackTicks = getAttackCycleTicks(index);
        nightmareAttackTimers.put(index, attackTicks);
        newlyInitializedTimers.add(index);
        attackCooldowns.put(index, currentTick + ATTACK_COOLDOWN_TICKS);
        log.info("Nightmare (index " + index + ") " + source + " attack detected, timer reset to "
                + attackTicks + " (cooldown until tick " + (currentTick + ATTACK_COOLDOWN_TICKS) + ")");

        // Count this attack toward the curse duration. We measure by attack SLOTS
        // (6-tick cycles) elapsed since the last counted slot rather than always
        // subtracting one, so any special-animation attack that slipped between two
        // detected standard attacks - and would otherwise go uncounted - is still
        // counted here. Rounding to the nearest whole cycle tolerates a +/-2 tick
        // detection offset, and re-anchoring the slot clock to this real attack keeps
        // the rhythm aligned to her actual attacks.
        if (isNightmareCursed(index)) {
            Integer lastSlot = nightmareCurseLastSlotTick.get(index);
            int slots = 1;
            if (lastSlot != null) {
                slots = Math.max(1, Math.round((currentTick - lastSlot) / (float) ATTACK_CYCLE_TICKS));
            }
            nightmareCurseLastSlotTick.put(index, currentTick);
            applyCurseSlots(index, slots, source + " attack");
        }
    }

    // Apply a number of elapsed attack slots to an active curse. Counting by slots (not
    // a fixed -1) lets a single call account for special-animation attacks that occupied
    // intervening 6-tick cycles without a detected standard animation. The curse is held
    // at the 0 sentinel for the 5th (final) cursed attack so it stays displayed as cursed,
    // and only ends when a further slot - the FOLLOWING attack - elapses.
    private void applyCurseSlots(int index, int slots, String source) {
        Integer current = nightmareCurseAttacks.get(index);
        if (current == null) {
            return;
        }
        int remaining = current - slots;
        if (remaining > 0) {
            nightmareCurseAttacks.put(index, remaining);
            log.info("Nightmare (index " + index + ") curse: " + remaining
                    + " attacks remaining (" + source + ")");
        } else if (remaining == 0) {
            // The 5th (final) cursed attack has now been counted. Hold at the 0 sentinel
            // so this attack is still displayed as cursed until the following attack.
            nightmareCurseAttacks.put(index, 0);
            log.info("Nightmare (index " + index
                    + ") curse final attack counted - holding until following attack (" + source + ")");
        } else {
            // The following attack (past the 5th cursed attack) has started, so the curse
            // is now lifted and this attack is uncursed. Expire it so the overlay switches
            // back to the normal (un-shuffled) phase as the next attack begins.
            nightmareCurseAttacks.remove(index);
            nightmareCurseDeadlineTick.remove(index);
            nightmareCurseLastSlotTick.remove(index);
            log.info("Nightmare (index " + index
                    + ") curse has ended (following attack started - overlay back to normal, " + source + ")");
        }
    }

    // Helper methods
    private boolean isAttackAnimation(int animationId) {
        return animationId == ANIMATION_MELEE ||
                animationId == ANIMATION_MAGE ||
                animationId == ANIMATION_RANGE;
        // ANIMATION_SPECIAL (8597) is NOT counted as an attack
    }

    private boolean isAttackGraphic(int graphicId) {
        return graphicId == GRAPHIC_MAGE ||
                graphicId == GRAPHIC_RANGE ||
                graphicId == GRAPHIC_SPECIAL;
    }

    public boolean isNightmareInEnragePhase(int npcIndex) {
        return false; // Nightmare has no enrage phase - consistent 6-tick cycle throughout
    }

    public boolean isNightmareCursed(int npcIndex) {
        return nightmareCurseAttacks.containsKey(npcIndex);
    }

    public int getNightmareCurseAttacksRemaining(int npcIndex) {
        return nightmareCurseAttacks.getOrDefault(npcIndex, 0);
    }

    private int getAttackCycleTicks(int npcIndex) {
        return ATTACK_CYCLE_TICKS; // Always 6 ticks - no enrage phase
    }

    public NightmarePhase getNightmarePhase(int npcIndex) {
        return nightmarePhases.getOrDefault(npcIndex, NightmarePhase.UNKNOWN);
    }

    /**
     * Get the effective phase for overlay display, accounting for curse prayer
     * shuffling.
     * During curse, prayers are shuffled to the left:
     * - Protect from Magic activates Protect from Missiles (Range)
     * - Protect from Missiles activates Protect from Melee
     * - Protect from Melee activates Protect from Magic
     * 
     * The overlay should show the prayer the player should CLICK to get the correct
     * protection.
     * Since the shuffling happens after clicking, we show the same color as the
     * attack type.
     */
    public NightmarePhase getEffectivePhase(int npcIndex) {
        NightmarePhase actualPhase = getNightmarePhase(npcIndex);

        // If not cursed, return normal phase
        if (!isNightmareCursed(npcIndex)) {
            return actualPhase;
        }

        // During curse, show the prayer button to click to get the needed protection
        switch (actualPhase) {
            case MAGE:
                // Magic attack -> need Magic protection -> click Melee (because Melee activates
                // Magic)
                return NightmarePhase.MELEE;
            case RANGE:
                // Range attack -> need Range protection -> click Magic (because Magic activates
                // Range)
                return NightmarePhase.MAGE;
            case MELEE:
                // Melee attack -> need Melee protection -> click Range (because Range activates
                // Melee)
                return NightmarePhase.RANGE;
            case SPECIAL:
            case UNKNOWN:
            default:
                // For special attacks or unknown, keep the same
                return actualPhase;
        }
    }

    public int getNightmareAttackTimer(int npcIndex) {
        return nightmareAttackTimers.getOrDefault(npcIndex, ATTACK_CYCLE_TICKS);
    }

    public List<NPC> getNightmareNpcs() {
        List<NPC> nightmares = new ArrayList<>();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && NIGHTMARE_IDS.contains(npc.getId())) {
                nightmares.add(npc);
            }
        }
        return nightmares;
    }

    public Set<WorldPoint> getSporeDangerZones() {
        return new HashSet<>(sporeDangerZones);
    }

    // True while spores are on the floor. Nightmare does not attack during the spore
    // phase, so callers can use this to suppress attack-related overlays.
    public boolean isSporePhaseActive() {
        return !sporeDangerZones.isEmpty();
    }

    public Set<WorldPoint> getSurgeDangerZone() {
        return new HashSet<>(surgeDangerZone);
    }

    public Set<WorldPoint> getSafeTiles() {
        return new HashSet<>(safeTiles);
    }

    // Nightmare combat phases
    public enum NightmarePhase {
        MAGE(new Color(100, 149, 237)), // Soft blue
        RANGE(new Color(144, 238, 144)), // Soft green
        MELEE(new Color(240, 100, 100, 120)), // Soft red
        SPECIAL(new Color(255, 165, 0, 100)), // Orange for special attacks
        UNKNOWN(Color.GRAY);

        private final Color color;

        NightmarePhase(Color color) {
            this.color = color;
        }

        public Color getColor() {
            return color;
        }
    }
}
