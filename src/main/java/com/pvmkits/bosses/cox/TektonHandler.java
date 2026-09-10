package com.pvmkits.bosses.cox;

import com.pvmkits.core.BossHandler;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tekton (Chambers of Xeric) flinch timer. Tekton attacks every 3 ticks
 * (unchanged by enrage), cycling three swing animations (7482/7483/7484; enraged
 * 7492/7493/7494). The timer is a free-running metronome counting 3 -> 2 -> 1:
 * the swing lands on "2" and the player clicks into melee on "1", so they can
 * melee Tekton while stepping out of his hits. Detection is by NPC name so it
 * works in normal and challenge mode.
 */
public class TektonHandler implements BossHandler {

    @Inject
    private Client client;

    private static final String TEKTON_NAME_KEYWORD = "tekton";

    // Attack swings: normal 7482/7483/7484, enraged 7492/7493/7494 (+10 offset).
    private static final Set<Integer> ATTACK_ANIMATION_IDS =
            Set.of(7482, 7483, 7484, 7492, 7493, 7494);

    // Animations that mean Tekton is at the anvil / not meleeing, so the timer hides.
    private static final Set<Integer> ANVIL_ANIMATION_IDS = Set.of(7474, 7475, 7487);

    private static final int ATTACK_CYCLE_TICKS = 3;

    // The swing lands on "2" and the click-into-melee tick is "1", so a detected
    // swing snaps the display to 2, not the top of the countdown.
    private static final int ATTACK_TICK_VALUE = ATTACK_CYCLE_TICKS - 1;

    // Two swing detections closer than this are the same swing, not a new attack.
    private static final int MIN_ATTACK_GAP_TICKS = 2;

    // Hide the timer if no swing has been seen for this long.
    private static final int DISPLAY_WINDOW_TICKS = 9;

    private final Map<Integer, Integer> attackTimers = new HashMap<>();
    private final Map<Integer, Integer> lastAttackTick = new HashMap<>();
    private final Map<Integer, Integer> lastRawAnimation = new HashMap<>();
    private final Set<Integer> atAnvil = new HashSet<>();

    @Override
    public String getBossName() {
        return "Tekton";
    }

    private boolean isTektonNpc(NPC npc) {
        if (npc == null) {
            return false;
        }
        String name = npc.getName();
        return name != null && name.toLowerCase(Locale.ROOT).contains(TEKTON_NAME_KEYWORD);
    }

    @Override
    public boolean isInBossArea(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (isTektonNpc(npc)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        // Animation detection is polled in onGameTick.
    }

    @Override
    public void onGraphicChanged(GraphicChanged event) {
        // Not used.
    }

    @Override
    public void onGameTick(GameTick event) {
        if (client.getGameState().getState() < 30) {
            return;
        }

        int currentTick = client.getTickCount();
        boolean tektonPresent = false;
        Set<Integer> attackedThisTick = new HashSet<>();

        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (!isTektonNpc(npc)) {
                continue;
            }

            tektonPresent = true;
            int index = npc.getIndex();
            int animationId = npc.getAnimation();

            // Track whether Tekton is at the anvil (timer hides there).
            if (ANVIL_ANIMATION_IDS.contains(animationId)) {
                atAnvil.add(index);
            } else if (ATTACK_ANIMATION_IDS.contains(animationId)) {
                atAnvil.remove(index);
            }

            // A swing is any transition INTO an attack animation. Tekton chains the
            // three swing ids back-to-back without returning to idle, so fire on
            // every change into an attack id, not just from a non-attack one.
            Integer prevRaw = lastRawAnimation.get(index);
            boolean changedIntoAttack = ATTACK_ANIMATION_IDS.contains(animationId)
                    && (prevRaw == null || prevRaw != animationId);
            if (changedIntoAttack && registerAttack(index, currentTick)) {
                attackedThisTick.add(index);
            }
            lastRawAnimation.put(index, animationId);
        }

        if (!tektonPresent) {
            clearTimerState();
            return;
        }

        // Advance the free-running metronome for any Tekton that didn't just swing:
        // 2 -> 1 -> 3 -> 2 ..., predicting the next attack even across a dodged hit.
        for (Map.Entry<Integer, Integer> entry : attackTimers.entrySet()) {
            int index = entry.getKey();
            if (attackedThisTick.contains(index)) {
                continue;
            }
            int ticks = entry.getValue();
            attackTimers.put(index, ticks <= 1 ? ATTACK_CYCLE_TICKS : ticks - 1);
        }
    }

    // Snaps the metronome to the swing tick (2). Returns false when this transition
    // is the same swing as the last (coalesced) rather than a new attack.
    private boolean registerAttack(int index, int currentTick) {
        Integer last = lastAttackTick.get(index);
        int since = last == null ? -1 : currentTick - last;
        if (since >= 0 && since < MIN_ATTACK_GAP_TICKS) {
            return false;
        }

        lastAttackTick.put(index, currentTick);
        attackTimers.put(index, ATTACK_TICK_VALUE);
        return true;
    }

    private void clearTimerState() {
        attackTimers.clear();
        lastAttackTick.clear();
        lastRawAnimation.clear();
        atAnvil.clear();
    }

    @Override
    public Actor getBossActor(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (isTektonNpc(npc)) {
                return npc;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        clearTimerState();
    }

    // The countdown for the overlay, or 0 when Tekton isn't actively meleeing (at
    // the anvil, or no swing seen recently) so the number hides.
    public int getTektonAttackTimer(int npcIndex) {
        if (atAnvil.contains(npcIndex)) {
            return 0;
        }
        Integer last = lastAttackTick.get(npcIndex);
        if (last == null || client.getTickCount() - last > DISPLAY_WINDOW_TICKS) {
            return 0;
        }
        return attackTimers.getOrDefault(npcIndex, 0);
    }

    public List<NPC> getTektonNpcs() {
        List<NPC> list = new ArrayList<>();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (isTektonNpc(npc)) {
                list.add(npc);
            }
        }
        return list;
    }
}
