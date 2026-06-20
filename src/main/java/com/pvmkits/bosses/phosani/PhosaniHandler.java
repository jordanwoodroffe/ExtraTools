package com.pvmkits.bosses.phosani;

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
public class PhosaniHandler implements BossHandler {

    @Inject
    private Client client;

    // Track current Phosani phases by NPC index
    private Map<Integer, PhosaniPhase> phosaniPhases = new HashMap<>();

    // Phosani's Nightmare NPC IDs
    private static final Set<Integer> PHOSANI_IDS = Set.of(9416, 9417, 9418, 9419, 9420, 9421, 9422, 9423, 9424, 11153,
            11154, 11155, 377);

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
    // Grasping-claw graphics flicker off for a tick between animation frames, and
    // individual claws resolve on slightly different ticks. Keep a tile dangerous for
    // this many ticks after a claw was last seen there so the safe-tile set neither
    // flickers green on a still-dangerous tile nor stays stale after a claw clears.
    private static final int CLAW_DANGER_PERSIST_TICKS = 1;
    private Set<WorldPoint> shadowHandTiles = new HashSet<>();
    // Last game tick a grasping-claw graphic was seen on each tile, used to smooth the
    // single-tick flicker of the claw graphics and to promptly free resolved claws.
    private Map<WorldPoint, Integer> shadowHandLastSeen = new HashMap<>();
    private WorldPoint lastSafeTileOrigin;
    // All nearby tiles that are clear of grasping claws and spores, so the player can
    // pick a safe spot themselves rather than being directed to a single tile.
    private Set<WorldPoint> safeTiles = new HashSet<>();

    // Phosani animation IDs (these will need to be determined through testing)
    private static final int ANIMATION_MELEE = 8594; // Placeholder - needs verification
    private static final int ANIMATION_MAGE = 8595; // Placeholder - needs verification
    private static final int ANIMATION_RANGE = 8596; // Placeholder - needs verification
    private static final int ANIMATION_SPECIAL = 8597; // Placeholder - needs verification
    private static final int ANIMATION_CURSE = 8599; // Curse special attack

    // Phosani graphic IDs (these will need to be determined through testing)
    private static final int GRAPHIC_MAGE = 1767; // Placeholder - needs verification
    private static final int GRAPHIC_RANGE = 1768; // Placeholder - needs verification
    private static final int GRAPHIC_SPECIAL = 1769; // Placeholder - needs verification

    // Attack cycle constants
    private static final int ATTACK_CYCLE_TICKS = 6; // Consistent 6-tick cycle throughout fight

    // Track last logged animation for each Phosani to prevent duplicate logging
    private Map<Integer, Integer> lastLoggedAnimations = new HashMap<>();

    // Track attack timers for each Phosani (NPC index -> ticks until next attack)
    private Map<Integer, Integer> phosaniAttackTimers = new HashMap<>();

    // Track which timers were just initialized this tick to prevent immediate
    // countdown
    private Set<Integer> newlyInitializedTimers = new HashSet<>();

    // Track attack cooldowns to prevent multiple timer resets from duplicate
    // animations
    // Maps NPC index to the tick when the cooldown expires
    private Map<Integer, Integer> attackCooldowns = new HashMap<>();

    // Cooldown duration in ticks after detecting an attack
    private static final int ATTACK_COOLDOWN_TICKS = 6;

    // Track curse state for each Phosani (NPC index -> remaining curse attacks).
    // A value of 0 means "curse still applies for this tick's launched attack, then
    // expire at the start of the next tick" to avoid an early overlay flip.
    private Map<Integer, Integer> phosaniCurseAttacks = new HashMap<>();

    // Hard tick-based backstop for each curse (NPC index -> game tick the curse must
    // expire by, regardless of how many attacks we've counted). The attack counter is
    // the primary mechanism, but during phase 3 her special attacks (spores, grasping
    // claws) aren't counted as attacks, so the counter can stall and drift far longer
    // than the real 5-attack curse. This deadline guarantees the overlay can never keep
    // shuffling prayers after the real curse has worn off.
    private Map<Integer, Integer> phosaniCurseDeadlineTick = new HashMap<>();

    // Slot anchor for each curse (NPC index -> game tick of the most recently counted
    // attack slot). The curse is counted by Phosani's 6-tick attack RHYTHM rather than
    // by classifying individual attack animations: every 6-tick cycle is one attack
    // toward the 5-attack curse, whether or not that attack used a standard animation.
    // A tick-driven slot clock (onGameTick) counts cycles that pass without a detected
    // standard attack - this captures her special-animation attacks (e.g. 8606) that
    // were previously uncounted and made the overlay's curse outlast the real one - and
    // each detected standard attack re-anchors this tick so any rhythm offset self-
    // corrects against her actual attacks.
    private Map<Integer, Integer> phosaniCurseLastSlotTick = new HashMap<>();

    // Curse duration constants
    // The Nightmare's curse is lifted after 5 of her attacks (per game mechanics).
    private static final int CURSE_DURATION_ATTACKS = 5;
    // Hard upper bound on curse lifetime in ticks. She attacks on a 6-tick cycle.
    // The curse spans 5 cursed attacks and is only lifted when the following (6th)
    // attack starts, so a legitimate curse can last ~36 ticks from activation; two
    // extra cycles of slack cover a delayed/undetected attack without cutting the
    // real curse - including that final following attack - short.
    private static final int CURSE_MAX_DURATION_TICKS = (CURSE_DURATION_ATTACKS + 2) * PhosaniHandler.ATTACK_CYCLE_TICKS;

    // --- Debug tracking (helps diagnose curse/phase desync across phase
    // transitions, e.g. totem phases where the boss NPC index may change) ---
    // The set of Phosani NPC indices seen on the previous tick, used to detect when
    // the boss NPC is swapped for a new index (which abandons old curse state).
    private Set<Integer> lastKnownPhosaniIndices = new HashSet<>();
    // Last effective (overlay) phase logged per index, so we only log when the
    // colour the player actually sees changes.
    private Map<Integer, PhosaniPhase> lastLoggedEffectivePhase = new HashMap<>();

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
        return "Phosani's Nightmare";
    }

    @Override
    public boolean isInBossArea(Client client) {
        // Check if any Phosani NPCs are present
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && PHOSANI_IDS.contains(npc.getId())) {
                log.info("PhosaniHandler.isInBossArea: Found Phosani NPC with ID " + npc.getId() + " and index "
                        + npc.getIndex());
                return true;
            }
        }
        log.debug("PhosaniHandler.isInBossArea: No Phosani NPCs found, checking all NPCs...");

        // Debug: Log all NPC IDs to help identify if Phosani ID is wrong
        int npcCount = 0;
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null) {
                npcCount++;
                if (npcCount <= 5) { // Only log first 5 NPCs to avoid spam
                    log.debug(
                            "PhosaniHandler.isInBossArea: Found NPC ID " + npc.getId() + " at index " + npc.getIndex());
                }
            }
        }
        log.debug("PhosaniHandler.isInBossArea: Total NPCs found: " + npcCount);

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
        if (!PHOSANI_IDS.contains(npc.getId())) {
            return;
        }

        int index = npc.getIndex();
        int graphicId = npc.getGraphic();

        // Log every graphic change event, including when graphics are cleared
        log.info("Phosani (index " + index + ") attack graphic: graphicId=" + graphicId);

        // Reset timer when graphic-based attacks are detected, but only if not in
        // cooldown
        if (isAttackGraphic(graphicId)) {
            registerPhosaniAttack(index, "graphic");
        }

        // Update phase based on graphics
        if (graphicId == GRAPHIC_MAGE) {
            phosaniPhases.put(index, PhosaniPhase.MAGE);
        } else if (graphicId == GRAPHIC_RANGE) {
            phosaniPhases.put(index, PhosaniPhase.RANGE);
        } else if (graphicId == GRAPHIC_SPECIAL) {
            phosaniPhases.put(index, PhosaniPhase.SPECIAL);
        }
    }

    @Override
    public void onGameTick(GameTick event) {
        if (client.getGameState().getState() < 30) {
            return;
        }

        // Advance the curse by Phosani's attack RHYTHM. She attacks on a strict 6-tick
        // cycle and the curse lifts after 5 attacks regardless of animation, so we count
        // attack SLOTS instead of classifying animations. Here we catch up any slot that
        // elapsed WITHOUT a detected standard attack - that slot was one of her special-
        // animation attacks (e.g. 8606), which must still count toward the curse. Using a
        // strict "> last + cycle" threshold (one tick of slack) guarantees a real standard
        // attack landing on its slot is counted by registerPhosaniAttack instead, so a slot
        // is never decremented twice. Once the curse is held at the 0 sentinel, the next
        // elapsed slot is the FOLLOWING attack and ends the curse - this lifts it on time
        // even when that following attack is itself a special with no standard animation.
        // The hard tick deadline remains a final backstop against a total rhythm stall.
        int currentTickForCurse = client.getTickCount();
        for (Integer curseIndex : new ArrayList<>(phosaniCurseAttacks.keySet())) {
            while (phosaniCurseAttacks.containsKey(curseIndex)) {
                Integer lastSlot = phosaniCurseLastSlotTick.get(curseIndex);
                if (lastSlot == null || currentTickForCurse <= lastSlot + ATTACK_CYCLE_TICKS) {
                    break;
                }
                phosaniCurseLastSlotTick.put(curseIndex, lastSlot + ATTACK_CYCLE_TICKS);
                applyCurseSlots(curseIndex, 1, "special attack slot");
            }
            Integer deadline = phosaniCurseDeadlineTick.get(curseIndex);
            if (deadline != null && currentTickForCurse >= deadline && phosaniCurseAttacks.containsKey(curseIndex)) {
                log.info("Phosani (index " + curseIndex
                        + ") curse has ended (tick deadline reached - rhythm stalled)");
                phosaniCurseAttacks.remove(curseIndex);
                phosaniCurseDeadlineTick.remove(curseIndex);
                phosaniCurseLastSlotTick.remove(curseIndex);
            }
        }

        log.debug("PhosaniHandler.onGameTick: Called, GameState=" + client.getGameState());

        boolean phosaniPresent = false;
        Set<Integer> currentPhosaniIndices = new HashSet<>();
        // Track all visible Phosanis in the scene
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && PHOSANI_IDS.contains(npc.getId())) {
                phosaniPresent = true;
                int index = npc.getIndex();
                currentPhosaniIndices.add(index);
                log.debug("PhosaniHandler.onGameTick: Processing Phosani with index " + index);

                // Log animation IDs for Phosani only when they change
                int animationId = npc.getAnimation();
                if (animationId != -1) {
                    Integer lastLogged = lastLoggedAnimations.get(index);
                    boolean animationChanged = lastLogged == null || lastLogged != animationId;
                    if (lastLogged == null || lastLogged != animationId) {
                        log.info("Phosani (index " + index + ") animation: animationId=" + animationId);
                        lastLoggedAnimations.put(index, animationId);
                    }

                    // Reset timer when Phosani attacks, but only if not in cooldown
                    if (isAttackAnimation(animationId)) {
                        registerPhosaniAttack(index, "animation");
                    }

                    // Detect the surge ("charge forward") special. Arm the danger-zone
                    // window so her flight path is highlighted for the next few ticks.
                    if (SURGE_ANIMATION_IDS.contains(animationId)) {
                        surgeActiveUntilTick = client.getTickCount() + SURGE_PERSIST_TICKS;
                        log.info("Phosani (index " + index + ") SURGE detected (animationId=" + animationId
                                + ") - marking flight path");
                    }

                    // Check for curse animation only when it starts, not while the same
                    // animation frame persists across multiple ticks.
                    if (animationId == ANIMATION_CURSE && animationChanged) {
                        Integer existing = phosaniCurseAttacks.get(index);
                        if (existing != null && existing > 0) {
                            log.info("Phosani (index " + index + ") curse RE-APPLIED while already active (was "
                                    + existing + " remaining) - resetting to " + CURSE_DURATION_ATTACKS);
                        }
                        phosaniCurseAttacks.put(index, CURSE_DURATION_ATTACKS);
                        phosaniCurseDeadlineTick.put(index, client.getTickCount() + CURSE_MAX_DURATION_TICKS);
                        // Anchor the slot clock to the cast. The cast occupies an attack
                        // slot, so the first cursed attack is one 6-tick cycle later; the
                        // slot clock and registerPhosaniAttack both measure from here.
                        phosaniCurseLastSlotTick.put(index, client.getTickCount());
                        log.info("Phosani (index " + index + ") curse activated! Duration: " + CURSE_DURATION_ATTACKS
                                + " attacks");
                    }

                    // Update phase based on animation if available
                    if (animationId == ANIMATION_MELEE) {
                        phosaniPhases.put(index, PhosaniPhase.MELEE);
                    } else if (animationId == ANIMATION_MAGE) {
                        phosaniPhases.put(index, PhosaniPhase.MAGE);
                    } else if (animationId == ANIMATION_RANGE) {
                        phosaniPhases.put(index, PhosaniPhase.RANGE);
                    } else if (animationId == ANIMATION_SPECIAL) {
                        phosaniPhases.put(index, PhosaniPhase.SPECIAL);
                    }
                }

                // Initialize with UNKNOWN if we haven't seen this Phosani before
                if (!phosaniPhases.containsKey(index)) {
                    phosaniPhases.put(index, PhosaniPhase.UNKNOWN);
                    log.info("PhosaniHandler.onGameTick: Initialized phase to UNKNOWN for Phosani index " + index);
                }

                // Initialize timer if not present
                if (!phosaniAttackTimers.containsKey(index)) {
                    int attackTicks = getAttackCycleTicks(index);
                    phosaniAttackTimers.put(index, attackTicks);
                    newlyInitializedTimers.add(index);
                    log.info("Phosani (index " + index + ") timer initialized to " + attackTicks);
                } else {
                    // Debug: Log current timer state every 10 ticks to avoid spam
                    if (client.getTickCount() % 10 == 0) {
                        int currentTimer = phosaniAttackTimers.get(index);
                        log.debug("Phosani (index " + index + ") current timer value: " + currentTimer);
                    }
                }

                // Debug: log whenever the overlay's effective (displayed) phase changes,
                // including the curse state that drives the prayer-shuffle. This makes it
                // easy to confirm the colour shown to the player matches the boss's real
                // attack while cursed vs. un-cursed.
                PhosaniPhase actualPhase = getPhosaniPhase(index);
                PhosaniPhase effectivePhase = getEffectivePhase(index);
                PhosaniPhase previousEffective = lastLoggedEffectivePhase.get(index);
                if (previousEffective == null || previousEffective != effectivePhase) {
                    boolean cursed = isPhosaniCursed(index);
                    log.info("Phosani (index " + index + ") overlay phase -> " + effectivePhase
                            + " (actual=" + actualPhase + ", cursed=" + cursed
                            + (cursed ? ", curseRemaining=" + getPhosaniCurseAttacksRemaining(index) : "")
                            + ")");
                    lastLoggedEffectivePhase.put(index, effectivePhase);
                }
            }
        }

        // If no Phosani exists, clear all data
        if (!phosaniPresent) {
            if (!phosaniPhases.isEmpty() || !phosaniAttackTimers.isEmpty()) {
                log.info("PhosaniHandler.onGameTick: No Phosani present, clearing all data");
            }
            phosaniPhases.clear();
            phosaniAttackTimers.clear();
            attackCooldowns.clear();
            phosaniCurseAttacks.clear();
            phosaniCurseDeadlineTick.clear();
            phosaniCurseLastSlotTick.clear();
            shadowHandTiles.clear();
            shadowHandLastSeen.clear();
            safeTiles.clear();
            surgeDangerZone.clear();
            surgeActiveUntilTick = -1;
            lastKnownPhosaniIndices.clear();
            lastLoggedEffectivePhase.clear();
            knownTotemLocations.clear();
            loggedTotemCandidateIds.clear();
            totemPhaseActive = false;
            return;
        }

        // Debug: detect when the boss NPC index set changes between ticks. A new index
        // appearing (e.g. after a totem phase / boss respawn) means any curse state keyed
        // to the old index is abandoned, and a vanished index means stale state lingers.
        if (!currentPhosaniIndices.equals(lastKnownPhosaniIndices)) {
            Set<Integer> appeared = new HashSet<>(currentPhosaniIndices);
            appeared.removeAll(lastKnownPhosaniIndices);
            Set<Integer> vanished = new HashSet<>(lastKnownPhosaniIndices);
            vanished.removeAll(currentPhosaniIndices);
            if (!lastKnownPhosaniIndices.isEmpty() && (!appeared.isEmpty() || !vanished.isEmpty())) {
                log.info("Phosani NPC index set changed: appeared=" + appeared + ", vanished=" + vanished
                        + ", activeCurses=" + phosaniCurseAttacks);
            }
            // Drop stale curse/phase debug state for indices that no longer exist so the
            // overlay never keeps shuffling prayers based on a departed NPC.
            for (Integer goneIndex : vanished) {
                if (phosaniCurseAttacks.remove(goneIndex) != null) {
                    log.info("Phosani (index " + goneIndex + ") removed - clearing its lingering curse state");
                }
                phosaniCurseDeadlineTick.remove(goneIndex);
                phosaniCurseLastSlotTick.remove(goneIndex);
                lastLoggedEffectivePhase.remove(goneIndex);
            }
            lastKnownPhosaniIndices = new HashSet<>(currentPhosaniIndices);
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

        // Update attack timers for all Phosanis
        log.debug("PhosaniHandler.onGameTick: Updating timers for " + phosaniAttackTimers.size() + " Phosanis");
        for (Map.Entry<Integer, Integer> entry : phosaniAttackTimers.entrySet()) {
            int phosaniIndex = entry.getKey();
            int currentTicks = entry.getValue();

            // Skip countdown for newly initialized timers this tick
            if (newlyInitializedTimers.contains(phosaniIndex)) {
                log.debug("Phosani (index " + phosaniIndex + ") timer skip countdown (newly initialized): "
                        + currentTicks);
                continue;
            }

            // Only decrement if the timer is greater than 1
            if (currentTicks > 1) {
                // Countdown the timer
                int newTicks = currentTicks - 1;
                phosaniAttackTimers.put(phosaniIndex, newTicks);
                log.debug("Phosani (index " + phosaniIndex + ") timer countdown: " + currentTicks + " -> " + newTicks);

            } else if (currentTicks == 1) {
                // Timer at 1, next tick should be an attack
                log.debug("Phosani (index " + phosaniIndex + ") timer at 1, waiting for attack");
                // Keep timer at 1 until attack is detected
            } else if (currentTicks <= 0) {
                // Timer went below 1, reset it
                int attackTicks = getAttackCycleTicks(phosaniIndex);
                phosaniAttackTimers.put(phosaniIndex, attackTicks);
                log.info(
                        "Phosani (index " + phosaniIndex + ") timer reset from " + currentTicks + " to " + attackTicks);
            }
        }

        // Clear the newly initialized timers set for next tick
        if (!newlyInitializedTimers.isEmpty()) {
            log.debug("PhosaniHandler.onGameTick: Clearing newly initialized timers: " + newlyInitializedTimers);
        }
        newlyInitializedTimers.clear();
    }

    @Override
    public Actor getBossActor(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && PHOSANI_IDS.contains(npc.getId())) {
                return npc;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        phosaniPhases.clear();
        phosaniAttackTimers.clear();
        attackCooldowns.clear();
        lastLoggedAnimations.clear();
        newlyInitializedTimers.clear();
        phosaniCurseAttacks.clear();
        phosaniCurseDeadlineTick.clear();
        phosaniCurseLastSlotTick.clear();
        sporeDangerZones.clear();
        shadowHandTiles.clear();
        shadowHandLastSeen.clear();
        lastSafeTileOrigin = null;
        safeTiles.clear();
        surgeDangerZone.clear();
        surgeActiveUntilTick = -1;
        lastKnownPhosaniIndices.clear();
        lastLoggedEffectivePhase.clear();
        knownTotemLocations.clear();
        loggedTotemCandidateIds.clear();
        totemPhaseActive = false;
        log.info("PhosaniHandler reset - all state cleared");
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

    // Poll active graphics objects for shadow phase undead hands. When a new hand
    // cast is detected, recompute the safe tiles relative to the player.
    private void updateShadowHands() {
        Set<WorldPoint> detectedHands = new HashSet<>();
        for (GraphicsObject go : client.getGraphicsObjects()) {
            if (go == null || go.getId() != SHADOW_HAND_GRAPHIC_ID) {
                continue;
            }
            LocalPoint local = go.getLocation();
            if (local == null) {
                continue;
            }
            WorldPoint worldPoint = WorldPoint.fromLocal(client, local);
            if (worldPoint != null) {
                detectedHands.add(worldPoint);
            }
        }

        int currentTick = client.getTickCount();

        // Refresh the "last seen" tick for every claw detected this tick.
        for (WorldPoint hand : detectedHands) {
            shadowHandLastSeen.put(hand, currentTick);
        }

        // Drop claws not seen within the persistence window and build the effective
        // danger set from those still within it. This smooths a single-tick flicker of
        // the claw graphics while promptly freeing tiles once a claw has resolved, so
        // recently-cleared tiles get re-highlighted as safe instead of staying stale.
        Set<WorldPoint> activeHands = new HashSet<>();
        Iterator<Map.Entry<WorldPoint, Integer>> it = shadowHandLastSeen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WorldPoint, Integer> entry = it.next();
            if (currentTick - entry.getValue() > CLAW_DANGER_PERSIST_TICKS) {
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
            if (npc != null && PHOSANI_IDS.contains(npc.getId())) {
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

    // Build the set of tiles covered by the surge flight path. Phosani always surges
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
        // No hands active - clear the safe tiles
        if (currentHands.isEmpty()) {
            if (!shadowHandTiles.isEmpty() || !safeTiles.isEmpty()) {
                shadowHandTiles.clear();
                lastSafeTileOrigin = null;
                safeTiles.clear();
            }
            return;
        }

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
                log.info("Phosani safe tiles recomputed: " + safeTiles.size() + " options");
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
            if (npc != null && PHOSANI_IDS.contains(npc.getId())) {
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

    // Register a detected Phosani attack from either detection path (animation or
    // graphic). A shared cooldown ensures each real attack is only counted once,
    // and the curse counter is decremented here so it stays in sync with the boss's
    // actual attacks regardless of which path detected them.
    private void registerPhosaniAttack(int index, String source) {
        int currentTick = client.getTickCount();
        Integer cooldownExpiry = attackCooldowns.get(index);

        // Ignore if we're still within the cooldown window from a prior detection
        if (cooldownExpiry != null && currentTick < cooldownExpiry) {
            log.debug("Phosani (index " + index + ") " + source
                    + " attack ignored - in cooldown until tick " + cooldownExpiry);
            return;
        }

        int attackTicks = getAttackCycleTicks(index);
        phosaniAttackTimers.put(index, attackTicks);
        newlyInitializedTimers.add(index);
        attackCooldowns.put(index, currentTick + ATTACK_COOLDOWN_TICKS);
        log.info("Phosani (index " + index + ") " + source + " attack detected, timer reset to "
                + attackTicks + " (cooldown until tick " + (currentTick + ATTACK_COOLDOWN_TICKS) + ")");

        // Count this attack toward the curse duration. We measure by attack SLOTS
        // (6-tick cycles) elapsed since the last counted slot rather than always
        // subtracting one, so any special-animation attack that slipped between two
        // detected standard attacks - and would otherwise go uncounted - is still
        // counted here. Rounding to the nearest whole cycle tolerates a +/-2 tick
        // detection offset, and re-anchoring the slot clock to this real attack keeps
        // the rhythm aligned to her actual attacks.
        if (isPhosaniCursed(index)) {
            Integer lastSlot = phosaniCurseLastSlotTick.get(index);
            int slots = 1;
            if (lastSlot != null) {
                slots = Math.max(1, Math.round((currentTick - lastSlot) / (float) ATTACK_CYCLE_TICKS));
            }
            phosaniCurseLastSlotTick.put(index, currentTick);
            applyCurseSlots(index, slots, source + " attack");
        }
    }

    // Apply a number of elapsed attack slots to an active curse. Counting by slots (not
    // a fixed -1) lets a single call account for special-animation attacks that occupied
    // intervening 6-tick cycles without a detected standard animation. The curse is held
    // at the 0 sentinel for the 5th (final) cursed attack so it stays displayed as cursed,
    // and only ends when a further slot - the FOLLOWING attack - elapses.
    private void applyCurseSlots(int index, int slots, String source) {
        Integer current = phosaniCurseAttacks.get(index);
        if (current == null) {
            return;
        }
        int remaining = current - slots;
        if (remaining > 0) {
            phosaniCurseAttacks.put(index, remaining);
            log.info("Phosani (index " + index + ") curse: " + remaining
                    + " attacks remaining (" + source + ")");
        } else if (remaining == 0) {
            // The 5th (final) cursed attack has now been counted. Hold at the 0 sentinel
            // so this attack is still displayed as cursed until the following attack.
            phosaniCurseAttacks.put(index, 0);
            log.info("Phosani (index " + index
                    + ") curse final attack counted - holding until following attack (" + source + ")");
        } else {
            // The following attack (past the 5th cursed attack) has started, so the curse
            // is now lifted and this attack is uncursed. Expire it so the overlay switches
            // back to the normal (un-shuffled) phase as the next attack begins.
            phosaniCurseAttacks.remove(index);
            phosaniCurseDeadlineTick.remove(index);
            phosaniCurseLastSlotTick.remove(index);
            log.info("Phosani (index " + index
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

    public boolean isPhosaniInEnragePhase(int npcIndex) {
        return false; // Phosani has no enrage phase - consistent 6-tick cycle throughout
    }

    public boolean isPhosaniCursed(int npcIndex) {
        return phosaniCurseAttacks.containsKey(npcIndex);
    }

    public int getPhosaniCurseAttacksRemaining(int npcIndex) {
        return phosaniCurseAttacks.getOrDefault(npcIndex, 0);
    }

    private int getAttackCycleTicks(int npcIndex) {
        return ATTACK_CYCLE_TICKS; // Always 6 ticks - no enrage phase
    }

    public PhosaniPhase getPhosaniPhase(int npcIndex) {
        return phosaniPhases.getOrDefault(npcIndex, PhosaniPhase.UNKNOWN);
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
    public PhosaniPhase getEffectivePhase(int npcIndex) {
        PhosaniPhase actualPhase = getPhosaniPhase(npcIndex);

        // If not cursed, return normal phase
        if (!isPhosaniCursed(npcIndex)) {
            return actualPhase;
        }

        // During curse, show the prayer button to click to get the needed protection
        switch (actualPhase) {
            case MAGE:
                // Magic attack -> need Magic protection -> click Melee (because Melee activates
                // Magic)
                return PhosaniPhase.MELEE;
            case RANGE:
                // Range attack -> need Range protection -> click Magic (because Magic activates
                // Range)
                return PhosaniPhase.MAGE;
            case MELEE:
                // Melee attack -> need Melee protection -> click Range (because Range activates
                // Melee)
                return PhosaniPhase.RANGE;
            case SPECIAL:
            case UNKNOWN:
            default:
                // For special attacks or unknown, keep the same
                return actualPhase;
        }
    }

    public int getPhosaniAttackTimer(int npcIndex) {
        return phosaniAttackTimers.getOrDefault(npcIndex, ATTACK_CYCLE_TICKS);
    }

    public List<NPC> getPhosaniNpcs() {
        List<NPC> phosanis = new ArrayList<>();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && PHOSANI_IDS.contains(npc.getId())) {
                phosanis.add(npc);
            }
        }
        return phosanis;
    }

    public Set<WorldPoint> getSporeDangerZones() {
        return new HashSet<>(sporeDangerZones);
    }

    public Set<WorldPoint> getSurgeDangerZone() {
        return new HashSet<>(surgeDangerZone);
    }

    public Set<WorldPoint> getSafeTiles() {
        return new HashSet<>(safeTiles);
    }

    // Phosani combat phases
    public enum PhosaniPhase {
        MAGE(new Color(100, 149, 237)), // Soft blue
        RANGE(new Color(144, 238, 144)), // Soft green
        MELEE(new Color(240, 100, 100, 120)), // Soft red
        SPECIAL(new Color(255, 165, 0, 100)), // Orange for special attacks
        UNKNOWN(Color.GRAY);

        private final Color color;

        PhosaniPhase(Color color) {
            this.color = color;
        }

        public Color getColor() {
            return color;
        }
    }
}
