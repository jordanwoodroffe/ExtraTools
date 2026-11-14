package com.pvmkits.bosses.archive;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.core.BossHandler;
import com.pvmkits.core.SoundPlayer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.GroundObject;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Projectile;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.util.Text;
import org.slf4j.helpers.MessageFormatter;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Fractured Archive (raid 4, released 2026-10-20). Discovery stage: nothing
 * about the raid is known yet beyond its boss names, so this handler only logs.
 * Every line is prefixed {@code [Archive t<tick> r<room tick>] <TAG>} so a fight
 * can be grepped out of client.log and its rotation read off by tick.
 *
 * Deduplication follows one rule. Discrete events (animations, spotanims, NPC
 * spawns, chat) log once per real event - a repeated identical attack is a new
 * line, because the gap between repeats is what an attack timer is built from -
 * and only exact duplicates within the same tick are dropped. Anything polled or
 * re-reported (ground graphics, projectile moves, object spawns, chatty varbits)
 * logs once per instance and is grouped per tick by id.
 */
@Slf4j
public class FracturedArchiveHandler implements BossHandler {

    @Inject
    private Client client;

    @Inject
    private PvmKitsConfig config;

    static final int CYCLES_PER_GAME_TICK = 30;

    // Template (pre-instancing) region ids the raid's rooms are copied from, read
    // off the ROOM lines. Detection by region does not depend on the NPC names below.
    static final int GUARDIAN_REGION_ID = 16471;
    static final int EZEKIEL_REGION_ID = 16214;
    static final int MOIA_REGION_ID = 16216;
    static final Set<Integer> KNOWN_REGION_IDS = Set.of(GUARDIAN_REGION_ID, EZEKIEL_REGION_ID, MOIA_REGION_ID);

    // ---- Moia (boss 3, the alternative to Dreadnoth) ----
    // Moia (16667, 3x3) floats outside a 9x9 arena and teleports between its
    // sides; her Undead binders (16670, 1x1) stand inside it in formations and
    // shuffle between them. The wiki: binders "attack anyone within two tiles of
    // them with melee", which the logs bear out (hits from binders 1 and 2 tiles
    // away, on both axes). The arena is template x 4059..4067, y 5659..5667: the
    // first 8 binders spawned exactly filling that box in both logged attempts
    // (2026-10-23 and 2026-11-06), from different instances.
    static final int MOIA_ID = 16667;
    static final int UNDEAD_BINDER_ID = 16670;
    static final int MOIA_ARENA_TEMPLATE_SW_X = 4059;
    static final int MOIA_ARENA_TEMPLATE_SW_Y = 5659;
    static final int MOIA_ARENA_SIZE = 9;
    // A binder hits anyone within two tiles of it, diagonals included: every
    // logged binder hit came from 2 tiles away on the longer axis, straight or
    // diagonal (2026-11-07 and 2026-11-10). So in her enrage a tile is safe only
    // when it is at least MOIA_BINDER_REACH + 1 tiles from every live binder.
    static final int MOIA_BINDER_REACH = 2;
    // Every ring step is 2 tiles walked on 2 consecutive ticks (every logged
    // step, 2026-11-10 to 2026-11-12). When the binders start a step, the green
    // they are leaving fades out over this long, reaching nothing just as they
    // stop and the green for where they stopped shows in full. A binder that has
    // just spawned is standing on its spawn tile, so it never holds the green
    // back: on "Again!" the new binders spawn (with their 2421 smoke) on the
    // tick of the line (all 6 logged "Again!"s), and the green shows that tick.
    static final int MOIA_BINDER_STEP_TICKS = 2;
    // Her lines with each charge anim (14787): "Useless corpses... Give me your
    // strength!" the first time, "Again!" after. The enrage green only starts on
    // the first: from "Cease this futile resistance!" until then she is gone and
    // the binders walk into their ring, and the green there was only noise
    // (2026-11-12; three binder hits on the north-west corner in that window).
    // Either the line or the anim counts, whichever comes first.
    static final String MOIA_FIRST_CHARGE_TEXT = "Useless corpses";
    static final String MOIA_AGAIN_TEXT = "Again!";
    // A line and an anim this close together are the same charge (they share a
    // tick in every log; charges are 33+ ticks apart).
    static final int MOIA_CHARGE_CUE_WINDOW_TICKS = 3;
    // Moia's line as her enrage starts. She leaves, comes back on the arena's
    // centre 3x3, and her binders stand on a ring around her and step one place
    // round it every 12 ticks: anticlockwise first, then clockwise after her
    // "Again!" (the same in both logged enrages). Two steps in, a binder on the
    // ring's north-west place has the north-west corner in reach, and that is
    // where the 2026-11-10 attempt took the hits it died to.
    static final String MOIA_ENRAGE_TEXT = "Cease this futile resistance";
    // Moia attacks every 5 ticks with anim 14785 whichever style she uses. Her
    // range auto is projectile 4214 (a 1-tick flight); the thrown axe, her mage
    // attack, is projectile 4213 (a 6-tick flight, 7 in her enrage). The anim is
    // shared, so the axe is only known once its projectile appears, a tick after
    // the anim.
    static final int MOIA_AXE_PROJECTILE_ID = 4213;
    // Prayer timing around the axe, with A the tick the axe projectile first
    // shows. From 28 axes in four fights (2026-11-06 and 2026-11-07):
    // - The axe's hitsplat lands the tick before the projectile reaches you, and
    //   only the prayer on that tick counts: Magic then blocked it 10 of 10 times,
    //   even with Missiles on the tick before. Magic a tick early but back to
    //   Missiles on it took the full axe 6 of 6 times.
    // - Her next attack is always a ranged shot, its anim 2 ticks before the axe
    //   lands, and its damage is rolled on that anim tick (the wiki's "ranged
    //   damage is calculated 2 ticks before the magic hit lands"). Only the prayer
    //   on then counts: Missiles blocked it 10 of 10 times, even with Magic on the
    //   tick after.
    // The flight sets the Magic tick. Outside her enrage the axe flies 6 ticks
    // and lands on A+5 (71 of 71 logged axes). In her enrage, thrown from the
    // arena's centre, it flies 7 and lands on A+6, with the ranged anim on A+4
    // (2 of 2, 2026-11-07 and 2026-11-10; a fixed A+5 had Magic on a tick early
    // and the axe hit for 29). So the Magic tick is A + flight - 1, the flight
    // read off the projectile as it appears. An overlay frame drawn after
    // GameTick N reads N+1, the tick a click lands on, so the highlight shows
    // from the GameTick before the Magic tick to the Magic tick's own. Everything
    // is whole ticks.
    static final int MOIA_RANGED_ROLL_BEFORE_HIT_TICKS = 2;
    // Her cough (anim 14786) throws this smoke projectile at you; it poisons
    // you, spreads to the binders, and makes them say "*Cough*". In all 29
    // logged coughs (anim on C) the smoke showed with a 4-tick flight and
    // poisoned you as it arrived: "You have been poisoned!" and your "*Cough*"
    // on C+4, the first poison hitsplat a tick later. The cough itself
    // cannot be called ahead: it opens the group after her second axe, and that
    // axe's group runs 4 or 5 attacks with nothing logged telling which (95
    // groups: 53 of 4, 42 of 5, unrelated to her side, the binders spawning,
    // dying or their number, or the group before). So the countdown runs from
    // the smoke's launch to its landing, and is right every time.
    static final int MOIA_COUGH_PROJECTILE_ID = 4218;
    // The axe countdown over Protect from Magic: 5-4-3-2, then 1 on the Magic
    // tick. (The cough's countdown runs the smoke's whole flight instead.)
    static final int MOIA_AXE_COUNTDOWN_START = 5;
    // Looped while an axe is in the air: the same bundled chime as the Doom of
    // Mokhaiotl's melee-punish alert (MokhaiotlHandler.MELEE_PUNISH_SOUND).
    private static final String MOIA_AXE_SOUND = "/com/pvmkits/sounds/melee_punish.wav";
    // Her enrage burst. "Useless corpses... Give me your strength!" and each
    // "Again!" come with anim 14787: the binders get spotanim 4224 and then 4223
    // as she drains them, and a bar under her health bar drains to empty. As it
    // empties, every binder still alive gets 4222 and you get 4225, and the
    // burst's damage lands on you the tick after (13 to 50 in the logs). From 5
    // charges (2026-11-07, plus two fights on 2026-11-10): the damage lands 37
    // ticks after her first charge anim (3 of 3), then 2 ticks sooner after
    // each "Again!" (35, then 33; one fight reached a third charge), and the
    // next "Again!" comes 4 ticks after the damage. Charges beyond the third
    // assume the same 2-tick step. RuneLite only exposes one health bar per NPC,
    // so the charge bar is not read; the MBAR lines log what that one bar shows
    // in her enrage, in case it turns out to be the charge bar.
    static final int MOIA_CHARGE_ANIMATION_ID = 14787;
    static final int MOIA_BURST_FIRST_DELAY_TICKS = 37;
    static final int MOIA_BURST_DELAY_STEP_TICKS = 2;
    // Shown on the tick before the burst's damage, and only then (5 of 5): 4225
    // on you and 4222 on each binder still alive. Either one corrects the
    // predicted damage tick if the prediction was off. The burst is only
    // logged: a tick-eat timer for it was removed on 2026-11-11 after the user
    // died eating on its 0, so it is treated as not tick-eatable.
    static final int MOIA_BURST_PLAYER_SPOTANIM_ID = 4225;
    static final int MOIA_BURST_BINDER_SPOTANIM_ID = 4222;

    // ---- Archive Guardian (first encounter, every run) ----
    // Confirmed from live logs. It attacks every 5 ticks and flies, so melee cannot
    // reach it ("It's flying too high to hit using melee."). 16660 is its downed
    // form (5x5, 35 ticks, vines rise meanwhile): it swaps in place (NpcChanged)
    // and back, and is the melee window. It spawns as the dormant 16657 and wakes
    // into 16659 when the challenge starts.
    static final int GUARDIAN_ID = 16659;
    static final int GUARDIAN_DOWNED_ID = 16660;
    static final Set<Integer> GUARDIAN_IDS = Set.of(GUARDIAN_ID, GUARDIAN_DOWNED_ID);
    // Its dormant pre-fight form, "Inert Guardian" (size 5), is in the room from
    // the moment it loads; the four corner Inert Guardians are other ids.
    static final int GUARDIAN_DORMANT_ID = 16657;
    static final Set<Integer> ALL_GUARDIAN_FORM_IDS = Set.of(GUARDIAN_DORMANT_ID, GUARDIAN_ID, GUARDIAN_DOWNED_ID);

    // ---- Step-under timer ----
    // Standing under the Guardian on the tick before it attacks makes it slam the
    // ground instead (wiki; marked "not consistent"). Its attacks keep a strict
    // 5-tick rhythm: 14751 is the ranged attack, 14752 the slam and 14753 the
    // mushroom summon, each taking one slot. Three other animations reset the beat,
    // consistently across every logged run: waking (14745) attacks 4 ticks later,
    // taking off after the downed phase (14748) 5 ticks later, and a reposition
    // (14750, +2..+4 after an attack) pushes the next attack to 3 ticks after it.
    // Value: ticks from the animation to the Guardian's next attack.
    static final Map<Integer, Integer> GUARDIAN_NEXT_ATTACK_LEAD = Map.of(
            14751, 5,
            14752, 5,
            14753, 5,
            14745, 4,
            14748, 5,
            14750, 3);
    static final int GUARDIAN_ATTACK_CYCLE_TICKS = 5;

    // ---- Shield break warning ----
    // The boss HUD bar: 6099 is its current value and 6100 its max. While the
    // Guardian flies the bar is its shield (max 550 in the logs). It reaches 0
    // about 3 ticks before the Guardian crashes down as 16660, when the max
    // switches to 1100. The max reads 0 or 1 while the HUD is cleared.
    static final int HUD_BAR_CURRENT_VARBIT = 6099;
    static final int HUD_BAR_MAX_VARBIT = 6100;
    // The last stretch of the shield, during which players near the boss should
    // move away before the shield breaks.
    static final double SHIELD_WARNING_FRACTION = 0.12;

    // Step-under timer: free-run through up to two missed attacks (a slam, a
    // dodge) before hiding.
    private static final int STEP_TIMER_WINDOW_TICKS = 2 * GUARDIAN_ATTACK_CYCLE_TICKS + 1;

    // ---- Beam tick-eat timer ----
    // The low-HP beam: anim 14754 (with spotanim 4204 on the boss) starts it, and
    // its hit lands exactly 10 ticks later - 18 of 18 beams in the logs. The wiki
    // notes it can be tick eaten, so it is counted down over food like
    // Sotetseg's death ball. It
    // comes every 20 ticks, with the mushroom summon 15 ticks after each beam, and
    // carries on after the Guardian drops to the ground as 16660. The beam also
    // charges whichever Inert Guardian is behind its target, one tick after the hit.
    static final int GUARDIAN_BEAM_ANIMATION_ID = 14754;
    static final int BEAM_IMPACT_DELAY_TICKS = 10;

    // Mushrooms (3x3) summoned by anim 14753 / spotanim 4205, two at a time:
    // 62578 is the sprouting mushroom, which becomes the grown 62579 three ticks
    // later. Setting one off swaps it for 62580 and poisons you; its leftover is
    // the generic 29733, which is not a hazard.
    static final Set<Integer> MUSHROOM_OBJECT_IDS = Set.of(62578, 62579);
    static final int MUSHROOM_SIZE = 3;

    // Vines that rise during the downed phase: emerge 14771, lash 14768 every
    // 3 ticks, retract 14769, then despawn.
    static final int VINE_ID = 16664;

    // Names announced for the raid's encounters. Bork is left out on purpose: he
    // also lives in an instance in the Chaos Tunnels, while his Smorks are unique
    // to the Archive. The Archive Guardian opens every run, so "archive" alone
    // latches the logger on the first room even if the rest are named differently.
    static final List<String> NAME_KEYWORDS = List.of(
            "archive",
            "smork",
            "ezekiel",
            "dreadnoth",
            "moia",
            "xarth",
            "dagon'hai",
            "hooded figure",
            "ocellus");

    // A var that is still changing after this many updates in one raid is a counter
    // or timer rather than a state flag, so it is muted to keep the log readable.
    private static final int VAR_MUTE_AFTER_CHANGES = 25;

    // Tiles printed per grouped ground-graphic / object line before truncating
    // (the bounding box always covers the full group). Ezekiel's 21-tile slam
    // telegraph lost 9 tiles at the old limit of 12.
    static final int MAX_TILES_PER_LINE = 64;

    private boolean latched;
    private int latchTick;
    private int currentRegion = -1;
    private int roomStartTick;

    // Object spawns during a scene load are the room's scenery, not mechanics. They
    // are skipped as they arrive and summarised by a scene scan once loading ends.
    private boolean sceneLoading;
    private boolean rescanPending;

    // ---- Mechanics state read by FracturedArchiveOverlay ----
    // Tracked whether or not logging is on.

    // Live mushrooms, object hash -> centre tile.
    private final Map<Long, WorldPoint> mushrooms = new HashMap<>();
    // Tick the Guardian's current 5-tick attack cycle began (its last attack, or
    // the slot implied by a wake, takeoff or reposition), or -1 when there is no
    // live rhythm (before it wakes, and while it is downed).
    private int lastGuardianAttackTick = -1;
    // Tick the Guardian last started its beam, or -1 if it has not this fight.
    private int lastBeamTick = -1;
    // Client game cycle of the last game tick, for the smooth inventory pie.
    private int lastGameTickCycle = -1;
    // Ezekiel's special attacks (second boss). It logs through this handler.
    private final EzekielTracker ezekiel = new EzekielTracker(this::emit);
    // Dreadnoth (third boss, the alternative to Moia): his logging and the state
    // behind his highlights. It logs through this handler.
    private final DreadnothTracker dreadnoth = new DreadnothTracker(this::emit);

    // The green tiles in Moia's room: the arena's north-west corner until her
    // enrage, then (from her first charge) every arena tile out of all the
    // binders' reach, recomputed each tick as they circle her; empty outside
    // her room.
    private Set<WorldPoint> moiaSafeTiles = Set.of();
    // The enrage green the binders are walking away from, fading out over their
    // step from moiaFadeStartCycle (the client cycle of the tick they set off);
    // empty when nothing is fading.
    private Set<WorldPoint> moiaFadingSafeTiles = Set.of();
    private int moiaFadeStartCycle = -1;
    // Set on her enrage line, or whenever she stands on the arena centre in case
    // the line was missed. Cleared when a challenge starts and on leaving her room.
    private boolean moiaEnraged;
    // The binder on the arena's centre tile, the one to aim the Venator bow
    // at, recomputed every tick; null when no binder stands there or the binders
    // are moving.
    private NPC venatorBinder = null;
    // Moia herself while her 3x3 covers the arena's centre tile, which happens
    // only in her enrage: she leaves and comes back in the middle of the arena.
    // A fallback sign of the enrage; no longer highlighted (2026-11-10).
    // Recomputed every tick; null otherwise.
    private NPC moiaOnCentre = null;
    // Each live binder's server tile as of the previous tick, so a binder that
    // stepped this tick counts as still moving.
    private final Map<NPC, WorldPoint> lastBinderTiles = new IdentityHashMap<>();
    // Whether every live binder stood still this tick (one that has just
    // spawned counts as standing); the enrage green is only shown in full then.
    // Worked out with the Venator binder each GameTick.
    private boolean moiaBindersSettled = true;
    // Client game cycle the latest axe lands on; Moia shows as maging until then.
    private int moiaAxeEndCycle = -1;
    // Tick the latest axe projectile first showed (A in the timing notes), or -1.
    private int moiaAxeTick = -1;
    // Tick that axe's damage lands on, the one tick to be on Magic, or -1.
    private int moiaAxeHitTick = -1;
    // Whether this handler last started the axe alert loop, so it only ever stops
    // a loop it started.
    private boolean moiaAxeSoundPlaying;
    // Tick the latest cough's smoke lands and poisons you, or -1.
    private int moiaCoughLandTick = -1;
    // Charges (anim 14787) this enrage, 1 for "Useless corpses...", and the
    // tick the latest one started, or -1.
    private int moiaChargeCount;
    private int moiaChargeTick = -1;
    // Tick the latest charge's burst damage lands on, or -1.
    private int moiaBurstTick = -1;
    // Moia's health bar as last logged on an MBAR line, so only changes are logged.
    private int lastMoiaBarRatio = Integer.MIN_VALUE;
    private int lastMoiaBarScale = Integer.MIN_VALUE;

    // Polled state, remembered by identity so each instance is logged exactly once.
    private final Set<GraphicsObject> seenGraphicsObjects = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Projectile> seenProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Integer> seenSceneObjectIds = new HashSet<>();

    // Object spawns/despawns collected through the tick and flushed as one line per id.
    private final Map<Integer, List<WorldPoint>> pendingObjectSpawns = new TreeMap<>();
    private final Map<Integer, List<WorldPoint>> pendingObjectDespawns = new TreeMap<>();
    private final Map<Integer, List<WorldPoint>> pendingGroundSpawns = new TreeMap<>();
    private final Map<Integer, List<WorldPoint>> pendingGroundDespawns = new TreeMap<>();

    // Timing context for the ANIM / PROJ lines: when each NPC (by index) last
    // animated at all, when it last played each particular animation, and when each
    // projectile id was last fired.
    private final Map<Integer, Integer> lastAnimTick = new HashMap<>();
    private final Map<Long, Integer> lastSameAnimTick = new HashMap<>();
    private final Map<Integer, Integer> lastProjectileTick = new HashMap<>();
    // Actor key -> "ids@tick" of the last spotanim line, to drop same-tick repeats.
    private final Map<String, String> lastSpotAnims = new HashMap<>();

    // Varbits keyed by id, varps by -(id + 1), so the two never collide.
    private final Map<Integer, Integer> varValues = new HashMap<>();
    private final Map<Integer, Integer> varChangeCounts = new HashMap<>();

    // Raid-wide tallies for the SUMMARY lines written on leaving.
    private final Map<Integer, String> npcNames = new TreeMap<>();
    private final Map<Integer, Map<Integer, Integer>> npcAnimCounts = new TreeMap<>();
    private final Map<Integer, Map<Integer, Integer>> spotAnimCounts = new TreeMap<>();
    private final Map<Integer, Integer> projectileCounts = new TreeMap<>();
    private final Map<Integer, Integer> graphicsObjectCounts = new TreeMap<>();
    private final Map<Integer, Integer> dynamicObjectCounts = new TreeMap<>();
    private final Map<Integer, Integer> groundObjectCounts = new TreeMap<>();

    // spotAnimCounts key for the local player; NPCs use their (non-negative) id.
    private static final int LOCAL_PLAYER_KEY = -1;

    @Override
    public String getBossName() {
        return "Fractured Archive";
    }

    @Override
    public boolean isInBossArea(Client client) {
        if (KNOWN_REGION_IDS.contains(templateRegion())) {
            latch("known template region " + templateRegion());
            return true;
        }
        // The raid is instanced; leaving the instance ends it, and the plugin's
        // handler switch calls reset(), which writes the summary.
        if (!client.getTopLevelWorldView().isInstance()) {
            return false;
        }
        if (latched) {
            return true;
        }
        if (config.fracturedArchiveAnyInstance()) {
            latch("Treat Any Instance As Archive");
            return true;
        }
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && isArchiveName(npc.getName())) {
                latch("NPC " + npcLabel(npc));
                return true;
            }
        }
        return false;
    }

    @Override
    public Actor getBossActor(Client client) {
        return getGuardian();
    }

    // ------------------------------------------------------------------
    // Event handlers
    // ------------------------------------------------------------------

    @Override
    public void onGameTick(GameTick event) {
        lastGameTickCycle = client.getGameCycle();
        if (!latched) {
            return;
        }

        trackRoom();
        if (rescanPending) {
            rescanPending = false;
            scanSceneObjects("scene reloaded");
        }

        flushObjects(pendingObjectSpawns, "OBJ+", "object", true);
        flushObjects(pendingObjectDespawns, "OBJ-", "object", false);
        flushObjects(pendingGroundSpawns, "GRND+", "ground", true);
        flushObjects(pendingGroundDespawns, "GRND-", "ground", false);
        scanGraphicsObjects();
        ezekiel.onGameTick(client);
        dreadnoth.onGameTick(client);
        logMoiaAxePrayer();
        logMoiaBurst();
        venatorBinder = computeVenatorBinder();
        moiaOnCentre = computeMoiaOnCentre();
        // After moiaOnCentre, which tells whether she stands on the arena centre.
        updateMoiaEnrage();
        moiaSafeTiles = computeMoiaSafeTiles();

        int gameCycle = client.getGameCycle();
        seenProjectiles.removeIf(p -> p.getEndCycle() < gameCycle);
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        if (!latched || !(event.getActor() instanceof NPC)) {
            return;
        }
        NPC npc = (NPC) event.getActor();
        int anim = npc.getAnimation();
        if (anim == -1) {
            return;
        }

        int tick = client.getTickCount();
        if (npc.getId() == GUARDIAN_ID) {
            Integer cycleStart = cycleStartTick(anim, tick);
            if (cycleStart != null) {
                lastGuardianAttackTick = cycleStart;
            }
        }
        // Both forms: it keeps beaming once it is on the ground.
        if (anim == GUARDIAN_BEAM_ANIMATION_ID && GUARDIAN_IDS.contains(npc.getId())) {
            lastBeamTick = tick;
        }
        if (npc.getId() == EzekielTracker.EZEKIEL_ID) {
            ezekiel.onEzekielAnimation(anim, tick);
        }
        if (npc.getId() == MOIA_ID && anim == MOIA_CHARGE_ANIMATION_ID) {
            onMoiaChargeCue(tick);
        }
        dreadnoth.onAnimation(npc, anim, tick);
        int index = npc.getIndex();
        long sameKey = ((long) index << 32) | (anim & 0xffffffffL);
        Integer lastSame = lastSameAnimTick.get(sameKey);
        if (lastSame != null && lastSame == tick) {
            return;
        }
        Integer lastAny = lastAnimTick.get(index);

        emit("ANIM", "{} anim {} @{} target={}{}{}",
                npcLabel(npc), anim, tile(npc.getWorldLocation()), actorLabel(npc.getInteracting()),
                since(" prev=", lastAny, tick), since(" same=", lastSame, tick));

        lastAnimTick.put(index, tick);
        lastSameAnimTick.put(sameKey, tick);
        npcAnimCounts.computeIfAbsent(npc.getId(), k -> new TreeMap<>()).merge(anim, 1, Integer::sum);
    }

    // Spotanims on NPCs and on the local player (a boss attack landing on you often
    // shows as one). Teammates are left out: their gear and specs add noise.
    @Override
    public void onGraphicChanged(GraphicChanged event) {
        if (!latched) {
            return;
        }
        Actor actor = event.getActor();
        boolean local = actor != null && actor == client.getLocalPlayer();
        if (!(actor instanceof NPC) && !local) {
            return;
        }

        Set<Integer> ids = spotAnimIds(actor);
        if (ids.isEmpty()) {
            return;
        }
        int tick = client.getTickCount();
        if (local ? ids.contains(MOIA_BURST_PLAYER_SPOTANIM_ID) : ids.contains(MOIA_BURST_BINDER_SPOTANIM_ID)) {
            onMoiaBurstGraphic(tick);
        }
        String key = local ? "you" : "npc" + ((NPC) actor).getIndex();
        // The slots go in the stamp too: a second copy of an id already showing,
        // in another slot or with another delay, is a new event the id set hides.
        String slots = spotAnimSlots(actor, client.getGameCycle());
        String stamp = ids + slots + "@" + tick;
        if (stamp.equals(lastSpotAnims.put(key, stamp))) {
            return;
        }

        emit("GFX", "{} spotanims {} @{}{}", actorLabel(actor), ids, tile(actor.getWorldLocation()),
                slots.isEmpty() ? "" : " slots " + slots);
        int countKey = local ? LOCAL_PLAYER_KEY : ((NPC) actor).getId();
        Map<Integer, Integer> counts = spotAnimCounts.computeIfAbsent(countKey, k -> new TreeMap<>());
        for (int id : ids) {
            counts.merge(id, 1, Integer::sum);
        }
    }

    // A projectile fires a move event every client frame, so each one is logged on
    // the first only. Projectiles fired by players are skipped: in a team they are
    // most of the traffic and say nothing about the boss.
    @Override
    public void onProjectileMoved(ProjectileMoved event) {
        if (!latched) {
            return;
        }
        Projectile p = event.getProjectile();
        if (p == null || !seenProjectiles.add(p)) {
            return;
        }
        int tick = client.getTickCount();
        int flightTicks = flightTicks(p.getRemainingCycles());
        if (p.getId() == MOIA_AXE_PROJECTILE_ID) {
            moiaAxeEndCycle = Math.max(moiaAxeEndCycle, p.getEndCycle());
            moiaAxeTick = tick;
            moiaAxeHitTick = moiaProjectileLandTick(tick, flightTicks);
            emit("MOIA", "axe t{}: {}t flight ({} cycles), Magic tick t{} (+{})", tick, flightTicks,
                    p.getRemainingCycles(), moiaAxeHitTick, moiaAxeHitTick - tick);
        }
        if (p.getId() == MOIA_COUGH_PROJECTILE_ID) {
            moiaCoughLandTick = moiaProjectileLandTick(tick, flightTicks);
            emit("MOIA", "cough smoke t{}: {}t flight ({} cycles), poisons you t{}", tick, flightTicks,
                    p.getRemainingCycles(), moiaCoughLandTick);
        }
        dreadnoth.onProjectile(client, p, tick, flightTicks);
        if (isPlayerProjectile(p)) {
            return;
        }

        Actor source = p.getSourceActor();
        Actor target = p.getTargetActor();
        Integer last = lastProjectileTick.put(p.getId(), tick);

        emit("PROJ", "id {} from {} to {} lands in ~{}t (impact tick {}) height {}->{}{}",
                p.getId(),
                source != null ? actorLabel(source) : tile(p.getSourcePoint()),
                target != null ? actorLabel(target) + " @" + tile(target.getWorldLocation()) : tile(p.getTargetPoint()),
                flightTicks, tick + flightTicks,
                p.getStartHeight(), p.getEndHeight(),
                since(" same=", last, tick));
        projectileCounts.merge(p.getId(), 1, Integer::sum);
    }

    @Override
    public void onNpcSpawned(NpcSpawned event) {
        if (latched) {
            logNpc("NPC+", event.getNpc());
            ezekiel.onNpcSpawned(client, event.getNpc());
        }
    }

    @Override
    public void onNpcDespawned(NpcDespawned event) {
        if (!latched) {
            return;
        }
        NPC npc = event.getNpc();
        emit("NPC-", "{} @{}", npcLabel(npc), tile(npc.getWorldLocation()));
        ezekiel.onNpcDespawned(npc);
        dreadnoth.onNpcDespawned(client, npc);
        // Indices are recycled, so a new NPC must not inherit this one's timings.
        int index = npc.getIndex();
        lastAnimTick.remove(index);
        lastSameAnimTick.keySet().removeIf(k -> (int) (k >> 32) == index);
        lastSpotAnims.remove("npc" + index);
    }

    // Boss phase changes usually swap the NPC's id in place rather than respawning it.
    public void onNpcChanged(NpcChanged event) {
        if (!latched) {
            return;
        }
        NPC npc = event.getNpc();
        // Downed, the Guardian stops attacking; the rhythm restarts from its takeoff.
        if (npc.getId() == GUARDIAN_DOWNED_ID) {
            lastGuardianAttackTick = -1;
        }
        NPCComposition old = event.getOld();
        emit("NPC~", "{} transformed from id {} ({}) @{}",
                npcLabel(npc), old != null ? old.getId() : -1, old != null ? old.getName() : "?",
                tile(npc.getWorldLocation()));
        npcNames.put(npc.getId(), String.valueOf(npc.getName()));
    }

    // Damage you take is the ground truth an attack timer is checked against, and
    // the tile you took it on is what a safe-tile rule is checked against. The
    // protection prayer on shows which one blocks an attack.
    @Override
    public void onHitsplatApplied(HitsplatApplied event) {
        if (!latched || event.getActor() == null) {
            return;
        }
        if (event.getActor() == client.getLocalPlayer()) {
            emit("HIT", "you took {} (hitsplat type {}) @{} praying {}, prayer {}{}",
                    event.getHitsplat().getAmount(), event.getHitsplat().getHitsplatType(),
                    tile(event.getActor().getWorldLocation()), activeProtection(),
                    client.getBoostedSkillLevel(Skill.PRAYER), dreadnoth.describeYou(client));
        }
        dreadnoth.onHitsplatApplied(client, event.getActor(), event.getHitsplat());
    }

    // Mushrooms are tracked even during a scene load, because a reload re-spawns the
    // ones already on the floor; only the logging skips the load's scenery.
    public void onGameObjectSpawned(GameObjectSpawned event) {
        GameObject object = event.getGameObject();
        if (!latched || object == null) {
            return;
        }
        if (MUSHROOM_OBJECT_IDS.contains(object.getId()) && object.getLocalLocation() != null) {
            // The local location of a multi-tile object is its centre tile.
            mushrooms.put(object.getHash(), WorldPoint.fromLocal(client, object.getLocalLocation()));
        }
        dreadnoth.onGameObjectSpawned(client, object, sceneLoading);
        if (sceneLoading) {
            return;
        }
        pendingObjectSpawns.computeIfAbsent(object.getId(), k -> new ArrayList<>()).add(object.getWorldLocation());
        dynamicObjectCounts.merge(object.getId(), 1, Integer::sum);
    }

    public void onGameObjectDespawned(GameObjectDespawned event) {
        GameObject object = event.getGameObject();
        if (!latched || object == null) {
            return;
        }
        mushrooms.remove(object.getHash());
        dreadnoth.onGameObjectDespawned(object);
        if (sceneLoading) {
            return;
        }
        pendingObjectDespawns.computeIfAbsent(object.getId(), k -> new ArrayList<>()).add(object.getWorldLocation());
    }

    // Ground objects (floor decorations) are a separate kind from game objects
    // and were not logged before Dreadnoth: his floor jelly could be either.
    public void onGroundObjectSpawned(GroundObjectSpawned event) {
        GroundObject object = event.getGroundObject();
        if (!latched || object == null) {
            return;
        }
        dreadnoth.onGroundObjectSpawned(client, object, sceneLoading);
        if (sceneLoading) {
            return;
        }
        pendingGroundSpawns.computeIfAbsent(object.getId(), k -> new ArrayList<>()).add(object.getWorldLocation());
        groundObjectCounts.merge(object.getId(), 1, Integer::sum);
    }

    public void onGroundObjectDespawned(GroundObjectDespawned event) {
        GroundObject object = event.getGroundObject();
        if (!latched || object == null) {
            return;
        }
        dreadnoth.onGroundObjectDespawned(object);
        if (sceneLoading) {
            return;
        }
        pendingGroundDespawns.computeIfAbsent(object.getId(), k -> new ArrayList<>()).add(object.getWorldLocation());
    }

    // Forwarded on every state change, active or not, so a load that started before
    // the latch still suppresses its scenery.
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOADING) {
            sceneLoading = true;
            // The rebuilt scene re-spawns whatever is still standing.
            mushrooms.clear();
            ezekiel.onSceneLoading();
            dreadnoth.onSceneLoading();
        } else if (event.getGameState() == GameState.LOGGED_IN && sceneLoading) {
            sceneLoading = false;
            rescanPending = true;
        }
    }

    // Game messages carry most raid cues ("The Guardian prepares to ..."); player
    // chat and filtered spam are left out.
    public void onChatMessage(ChatMessage event) {
        if (!latched) {
            return;
        }
        ChatMessageType type = event.getType();
        if (type == ChatMessageType.GAMEMESSAGE || type == ChatMessageType.ENGINE
                || type == ChatMessageType.MESBOX) {
            String message = Text.removeTags(event.getMessage());
            emit("CHAT", "[{}] {}", type, message);
            // "Challenge started: Moia." opens every attempt, so none starts enraged.
            if (message.startsWith("Challenge started")) {
                setMoiaEnraged(false, "a challenge started");
                dreadnoth.onChallengeStarted();
            }
        }
    }

    public void onOverheadTextChanged(OverheadTextChanged event) {
        if (!latched) {
            return;
        }
        Actor actor = event.getActor();
        if (actor instanceof NPC || actor == client.getLocalPlayer()) {
            emit("SAY", "{}: \"{}\"", actorLabel(actor), event.getOverheadText());
        }
        if (actor instanceof NPC && ((NPC) actor).getId() == MOIA_ID && event.getOverheadText() != null) {
            String text = Text.removeTags(event.getOverheadText());
            if (text.contains(MOIA_ENRAGE_TEXT)) {
                setMoiaEnraged(true, "she said \"" + event.getOverheadText() + "\"");
            }
            if (isMoiaChargeLine(text)) {
                onMoiaChargeCue(client.getTickCount());
            }
        }
    }

    // Raid state (room, phase, points, door choice) usually lives in varbits. Each
    // change is logged with its old value until the var proves to be a counter.
    public void onVarbitChanged(VarbitChanged event) {
        if (!latched || !config.fracturedArchiveLogVars()) {
            return;
        }
        boolean isVarbit = event.getVarbitId() != -1;
        int id = isVarbit ? event.getVarbitId() : event.getVarpId();
        int key = isVarbit ? id : -(id + 1);
        int value = event.getValue();

        Integer old = varValues.put(key, value);
        if (old != null && old == value) {
            return;
        }
        int changes = varChangeCounts.merge(key, 1, Integer::sum);
        if (changes > VAR_MUTE_AFTER_CHANGES) {
            return;
        }
        emit("VAR", "{} {}: {} -> {}{}",
                isVarbit ? "varbit" : "varp", id, old != null ? old : "?", value,
                changes == VAR_MUTE_AFTER_CHANGES ? " (changes too often, muted from here)" : "");
    }

    @Override
    public void reset() {
        if (latched) {
            emitSummary();
        }
        latched = false;
        mushrooms.clear();
        ezekiel.reset();
        dreadnoth.reset();
        moiaSafeTiles = Set.of();
        clearMoiaFade();
        moiaEnraged = false;
        venatorBinder = null;
        moiaOnCentre = null;
        lastBinderTiles.clear();
        moiaBindersSettled = true;
        moiaAxeEndCycle = -1;
        moiaAxeTick = -1;
        moiaAxeHitTick = -1;
        // Never leave the alert looping once the raid state is torn down.
        SoundPlayer.stopLoop(MOIA_AXE_SOUND);
        moiaAxeSoundPlaying = false;
        moiaCoughLandTick = -1;
        clearMoiaCharges();
        lastGuardianAttackTick = -1;
        lastBeamTick = -1;
        currentRegion = -1;
        sceneLoading = false;
        rescanPending = false;
        seenGraphicsObjects.clear();
        seenProjectiles.clear();
        seenSceneObjectIds.clear();
        pendingObjectSpawns.clear();
        pendingObjectDespawns.clear();
        pendingGroundSpawns.clear();
        pendingGroundDespawns.clear();
        lastAnimTick.clear();
        lastSameAnimTick.clear();
        lastProjectileTick.clear();
        lastSpotAnims.clear();
        varValues.clear();
        varChangeCounts.clear();
        npcNames.clear();
        npcAnimCounts.clear();
        spotAnimCounts.clear();
        projectileCounts.clear();
        graphicsObjectCounts.clear();
        dynamicObjectCounts.clear();
        groundObjectCounts.clear();
    }

    public boolean isActive() {
        return latched;
    }

    public NPC getGuardian() {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && GUARDIAN_IDS.contains(npc.getId())) {
                return npc;
            }
        }
        return null;
    }

    // The step-under countdown for the overlay, or 0 to hide it (no flying
    // Guardian, no attack seen yet, or none for a couple of cycles).
    public int getGuardianStepTimer() {
        NPC guardian = getGuardian();
        if (guardian == null || guardian.getId() != GUARDIAN_ID || lastGuardianAttackTick < 0) {
            return 0;
        }
        // Nothing else attacks while a beam is charging, so the free-running
        // count would only mislead; the beam has its own countdown on your food.
        if (getBeamTickEatTimer() >= 0) {
            return 0;
        }
        int since = client.getTickCount() - lastGuardianAttackTick;
        if (since > STEP_TIMER_WINDOW_TICKS) {
            return 0;
        }
        return stepTimerValue(since);
    }

    // A plain countdown: the attack tick shows 5, and "1" is the tick right before
    // the next attack - the tick to step under the boss. Confirmed in game: the
    // Tekton-style offset (snap to 4, "1" two ticks early) put the step a tick
    // early, landing it on the following "5". Free-running, so a skipped attack
    // keeps the beat.
    static int stepTimerValue(int ticksSinceAttack) {
        return GUARDIAN_ATTACK_CYCLE_TICKS - Math.floorMod(ticksSinceAttack, GUARDIAN_ATTACK_CYCLE_TICKS);
    }

    /**
     * Ticks until the Guardian's beam hits, or -1 when none is charging - the
     * same contract as Sotetseg's death ball timer. 10 on the tick the beam
     * starts, 0 on the tick it hits, which is the tick to eat on to tick eat it.
     */
    public int getBeamTickEatTimer() {
        if (lastBeamTick < 0 || getGuardian() == null) {
            return -1;
        }
        return beamTicksToLand(client.getTickCount() - lastBeamTick);
    }

    static int beamTicksToLand(int ticksSinceBeamStart) {
        if (ticksSinceBeamStart < 0 || ticksSinceBeamStart > BEAM_IMPACT_DELAY_TICKS) {
            return -1;
        }
        return BEAM_IMPACT_DELAY_TICKS - ticksSinceBeamStart;
    }

    // The beam's full charge in ticks, the full value of the inventory pie.
    public int getBeamChargeTicks() {
        return BEAM_IMPACT_DELAY_TICKS;
    }

    /**
     * Fraction (0..1) of the way through the current game tick, from the client
     * game cycle since the last tick, so the inventory pie drains smoothly between
     * ticks rather than stepping once per tick.
     */
    public double getGameTickFraction() {
        if (lastGameTickCycle < 0) {
            return 0.0;
        }
        double fraction = (client.getGameCycle() - lastGameTickCycle) / (double) CYCLES_PER_GAME_TICK;
        return Math.max(0.0, Math.min(1.0, fraction));
    }

    // When the Guardian's current attack cycle began, given an animation that
    // fixes when its next attack lands, or null for any other animation.
    static Integer cycleStartTick(int anim, int tick) {
        Integer lead = GUARDIAN_NEXT_ATTACK_LEAD.get(anim);
        return lead == null ? null : tick + lead - GUARDIAN_ATTACK_CYCLE_TICKS;
    }

    // True from the moment the flying Guardian's shield drops into its last 12%
    // until it crashes down (including the few ticks the bar sits on 0 first).
    public boolean isShieldBreakImminent() {
        NPC guardian = getGuardian();
        if (guardian == null || guardian.getId() != GUARDIAN_ID) {
            return false;
        }
        return isShieldLow(client.getVarbitValue(HUD_BAR_CURRENT_VARBIT),
                client.getVarbitValue(HUD_BAR_MAX_VARBIT));
    }

    static boolean isShieldLow(int current, int max) {
        return max > 1 && current <= max * SHIELD_WARNING_FRACTION;
    }
    // Centre tiles of the live (sprouting or grown) mushrooms.
    public Collection<WorldPoint> getMushroomTiles() {
        return mushrooms.values();
    }

    public DreadnothTracker getDreadnoth() {
        return dreadnoth;
    }

    public EzekielTracker getEzekiel() {
        return ezekiel;
    }

    // The arena's north-west corner, or in Moia's enrage every arena tile out of
    // all the binders' reach, in full green.
    public Set<WorldPoint> getMoiaSafeTiles() {
        return moiaSafeTiles;
    }

    // In her enrage, the green the binders are walking away from, drawn at
    // getMoiaFadeOpacity(); empty when none is fading.
    public Set<WorldPoint> getMoiaFadingSafeTiles() {
        return moiaFadingSafeTiles;
    }

    // How much of the fading green is left, 1 as the binders set off down to 0
    // as they arrive, smooth between ticks; 0 when nothing is fading.
    public double getMoiaFadeOpacity() {
        if (moiaFadingSafeTiles.isEmpty() || moiaFadeStartCycle < 0) {
            return 0.0;
        }
        return moiaFadeOpacity(client.getGameCycle() - moiaFadeStartCycle);
    }

    // Linear over the binders' 2-tick step, from cycles since the tick they set off.
    static double moiaFadeOpacity(int cyclesSinceStepStart) {
        double walked = cyclesSinceStepStart / (double) (MOIA_BINDER_STEP_TICKS * CYCLES_PER_GAME_TICK);
        return Math.max(0.0, Math.min(1.0, 1.0 - walked));
    }

    // Null while the centre binder's own model is still sliding onto the
    // centre (it can trail its server tile by a couple of client cycles).
    // Other binders' movement does not matter.
    public NPC getVenatorBinder() {
        NPC binder = venatorBinder;
        if (binder == null) {
            return null;
        }
        LocalPoint model = binder.getLocalLocation();
        LocalPoint tile = LocalPoint.fromWorld(client.getTopLevelWorldView(), binder.getWorldLocation());
        if (model == null || tile == null || model.getX() != tile.getX() || model.getY() != tile.getY()) {
            return null;
        }
        return binder;
    }

    public NPC getMoia() {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == MOIA_ID) {
                return npc;
            }
        }
        return null;
    }

    // True from the moment an axe is thrown until it lands. Moia's centre tile
    // is purple in FracturedArchiveOverlay for exactly this window. Read at
    // render time.
    public boolean isMoiaAxeInFlight() {
        return moiaAxeEndCycle != -1 && client.getGameCycle() < moiaAxeEndCycle;
    }

    // Run every client tick (20 ms) while the Archive is the active handler. Loops
    // the axe alert for exactly as long as Moia's centre tile is purple in
    // FracturedArchiveOverlay: an axe in flight while she is alive. The flight
    // window is read off the client cycle, not the game tick, so checking per
    // frame starts and stops the sound on the frame the purple tile shows and
    // clears. Back-to-back axes keep the window open (moiaAxeEndCycle takes the
    // later landing), so the loop runs straight through them, as the tile does.
    public void onClientTick() {
        boolean alert = latched && config.moiaAxeSound() && isMoiaAxeInFlight() && isMoiaAlive();
        if (alert) {
            // Only refreshes the volume while the loop is already running.
            SoundPlayer.loop(MOIA_AXE_SOUND, config.moiaAxeSoundVolume());
        } else if (moiaAxeSoundPlaying) {
            SoundPlayer.stopLoop(MOIA_AXE_SOUND);
        }
        moiaAxeSoundPlaying = alert;
    }

    private boolean isMoiaAlive() {
        NPC moia = getMoia();
        return moia != null && !moia.isDead();
    }

    // The protection prayer to be on right now while Moia is up, or null when she
    // is not (dead, or between rooms): Missiles, except Magic on each axe's one
    // Magic tick, then straight back to Missiles. Read at render time, so "now"
    // is the tick a click lands on.
    public Prayer getMoiaPrayer() {
        NPC moia = getMoia();
        if (moia == null || moia.isDead()) {
            return null;
        }
        return getMoiaAxeCountdown() == 1 ? Prayer.PROTECT_FROM_MAGIC : Prayer.PROTECT_FROM_MISSILES;
    }

    // The protection prayer to be on right now in Dreadnoth's fight, or null
    // with nothing of his up: Melee, except Missiles on each tick one of his
    // projectiles at you hits (see DreadnothTracker).
    public Prayer getDreadnothPrayer() {
        return dreadnoth.getPrayer(client);
    }

    // The number to show over Protect from Missiles right now, or 0 for none:
    // 4-3-2-1 from the tick after each slam, the 1 on the first Missiles tick.
    public int getDreadnothRangedCountdown() {
        return dreadnoth.getRangedCountdown(client);
    }

    // The number to show over Protect from Magic right now, or 0 for none.
    public int getMoiaAxeCountdown() {
        return moiaAxeHitTick == -1 ? 0 : moiaCountdownAt(moiaAxeHitTick, client.getTickCount());
    }

    // The tick one of Moia's projectiles, first seen on firstSeenTick with the
    // given flight, takes effect on. The axe's damage: A+5 for the usual 6-tick
    // flight, A+6 for the 7-tick flight in her enrage (73 of 73 logged axes).
    // The cough's poisoning ("You have been poisoned!" and your "*Cough*"): smoke
    // seen on C+1 with a 4-tick flight, so C+4 (29 of 29 logged coughs).
    static int moiaProjectileLandTick(int firstSeenTick, int flightTicks) {
        return firstSeenTick + flightTicks - 1;
    }

    // Whole ticks left in a projectile's flight, rounded up: the same reading the
    // PROJ lines log, which gave 6 for 71 of 71 axes outside the enrage and 7 for
    // both inside it.
    static int flightTicks(int remainingCycles) {
        return (int) Math.ceil(remainingCycles / (double) CYCLES_PER_GAME_TICK);
    }

    // The axe's 5-4-3-2-1 countdown on render tick renderTick to hitTick, the
    // Magic tick, which shows the 1; 0 before and after. A 6-tick flight shows
    // the 5 the tick after the axe appears, her enrage's 7-tick flight a tick
    // later.
    static int moiaCountdownAt(int hitTick, int renderTick) {
        int left = hitTick - renderTick + 1;
        return left >= 1 && left <= MOIA_AXE_COUNTDOWN_START ? left : 0;
    }

    // The cough's countdown on render tick renderTick to landTick, the tick the
    // smoke poisons you: its whole flight, so 4-3-2-1 for the usual 4 ticks,
    // the 4 as she coughs and the 1 on the last tick before it lands; 0 once it
    // has landed. landTick is only set when the smoke first shows, on the render
    // tick it shows on, so the count always opens at the flight's length (a
    // longer flight would count from its own) and nothing shows before a cough.
    static int moiaCoughCountdownAt(int landTick, int renderTick) {
        int left = landTick - renderTick + 1;
        return landTick != -1 && left >= 1 ? left : 0;
    }

    // The cough countdown for the overlay, or 0 to hide (no smoke in the air, or
    // she is gone). It starts only once she has coughed: nothing logged tells
    // the cough ahead of time (see MOIA_COUGH_PROJECTILE_ID).
    public int getMoiaCoughCountdown() {
        NPC moia = getMoia();
        if (moia == null || moia.isDead()) {
            return 0;
        }
        return moiaCoughCountdownAt(moiaCoughLandTick, client.getTickCount());
    }

    // Her charge anim (14787) or its line ("Useless corpses..." / "Again!") on
    // tick. The two come together, so whichever is seen first starts the charge
    // and the other is the same one.
    private void onMoiaChargeCue(int tick) {
        if (moiaChargeTick != -1 && tick - moiaChargeTick < MOIA_CHARGE_CUE_WINDOW_TICKS) {
            return;
        }
        startMoiaCharge(tick);
    }

    static boolean isMoiaChargeLine(String text) {
        return text.contains(MOIA_FIRST_CHARGE_TEXT) || text.trim().equals(MOIA_AGAIN_TEXT);
    }

    // A charge on tick: the burst it builds to is predicted from how many
    // charges have gone before it this enrage. The first one also starts the
    // enrage green.
    private void startMoiaCharge(int tick) {
        moiaChargeCount++;
        moiaChargeTick = tick;
        int delay = moiaBurstDelayTicks(moiaChargeCount);
        moiaBurstTick = tick + delay;
        emit("MOIA", "charge #{} t{}: burst damage predicted t{} (+{})", moiaChargeCount, tick, moiaBurstTick, delay);
        if (moiaChargeCount == 1) {
            emit("MSAFE", "her first charge: green now follows the binders");
        }
    }

    // The burst's spotanims (4225 on you, 4222 on the binders) show on graphicTick,
    // and its damage lands the tick after, whatever the prediction said. Logged
    // once per charge with how far off the prediction was.
    private void onMoiaBurstGraphic(int graphicTick) {
        int damageTick = graphicTick + 1;
        if (damageTick == moiaBurstTick) {
            return;
        }
        if (moiaBurstTick == -1) {
            emit("MOIA", "burst graphic t{} with no charge seen: damage t{}", graphicTick, damageTick);
        } else {
            emit("MOIA", "charge #{} burst graphic t{}: damage t{} (+{}), predicted t{} (off by {})",
                    moiaChargeCount, graphicTick, damageTick, damageTick - moiaChargeTick,
                    moiaBurstTick, moiaBurstTick - damageTick);
        }
        moiaBurstTick = damageTick;
    }

    // Ticks from a charge anim to its burst's damage, for the charge'th charge
    // this enrage: 37 for "Useless corpses...", 2 fewer for each "Again!" (35
    // and 33 logged; later ones follow the same step), never under one tick.
    static int moiaBurstDelayTicks(int charge) {
        int delay = MOIA_BURST_FIRST_DELAY_TICKS - MOIA_BURST_DELAY_STEP_TICKS * (Math.max(charge, 1) - 1);
        return Math.max(delay, 1);
    }

    private void clearMoiaCharges() {
        moiaChargeCount = 0;
        moiaChargeTick = -1;
        moiaBurstTick = -1;
        lastMoiaBarRatio = Integer.MIN_VALUE;
        lastMoiaBarScale = Integer.MIN_VALUE;
    }

    // In her enrage: a MOIA line on each predicted burst tick (the HIT line just
    // before it is the damage it predicted), and an MBAR line whenever the one
    // health bar RuneLite reads off her changes, to find out whether that is
    // ever the charge bar under her health bar.
    private void logMoiaBurst() {
        if (!moiaEnraged) {
            return;
        }
        int tick = client.getTickCount();
        if (tick == moiaBurstTick) {
            emit("MOIA", "charge #{} burst damage due now t{} (+{} after the charge anim)",
                    moiaChargeCount, tick, tick - moiaChargeTick);
        }
        NPC moia = getMoia();
        if (moia == null) {
            return;
        }
        int ratio = moia.getHealthRatio();
        int scale = moia.getHealthScale();
        if (ratio != lastMoiaBarRatio || scale != lastMoiaBarScale) {
            lastMoiaBarRatio = ratio;
            lastMoiaBarScale = scale;
            emit("MBAR", "Moia health bar {}/{}{}", ratio, scale, moiaChargeTick == -1 ? ""
                    : " (charge #" + moiaChargeCount + " +" + (tick - moiaChargeTick) + "t)");
        }
    }

    // Logs the protection prayer on at the axe's two deciding ticks, so a log
    // can check the timing even after the VAR lines have muted the prayer
    // varbits. GameTick N reads the prayer the server settled on tick N.
    private void logMoiaAxePrayer() {
        if (moiaAxeHitTick == -1) {
            return;
        }
        int tick = client.getTickCount();
        int since = tick - moiaAxeTick;
        if (tick == moiaAxeHitTick - MOIA_RANGED_ROLL_BEFORE_HIT_TICKS) {
            emit("MOIA", "axe t{} +{} ranged roll (needs Missiles): praying {}", moiaAxeTick, since, activeProtection());
        } else if (tick == moiaAxeHitTick) {
            emit("MOIA", "axe t{} +{} axe hit (needs Magic): praying {}", moiaAxeTick, since, activeProtection());
        }
    }

    private String activeProtection() {
        if (client.getVarbitValue(Prayer.PROTECT_FROM_MAGIC.getVarbit()) != 0) {
            return "Magic";
        }
        if (client.getVarbitValue(Prayer.PROTECT_FROM_MISSILES.getVarbit()) != 0) {
            return "Missiles";
        }
        return client.getVarbitValue(Prayer.PROTECT_FROM_MELEE.getVarbit()) != 0 ? "Melee" : "none";
    }

    // Her enrage runs from "Cease this futile resistance!" until a challenge
    // starts or you leave her room. Standing on the arena centre also counts,
    // in case the line was missed (she only ever stands there enraged).
    private void updateMoiaEnrage() {
        if (templateRegion() != MOIA_REGION_ID) {
            setMoiaEnraged(false, "left her room");
        } else if (moiaOnCentre != null) {
            setMoiaEnraged(true, "she stands on the arena centre");
        }
    }

    private void setMoiaEnraged(boolean enraged, String why) {
        if (enraged == moiaEnraged) {
            return;
        }
        moiaEnraged = enraged;
        emit("MSAFE", "enrage {} ({}): green now {}", enraged ? "on" : "off", why,
                !enraged ? "the north-west corner"
                        : moiaChargeCount > 0 ? "follows the binders"
                        : "hidden until her first charge (\"Useless corpses...\")");
        // Only on the way out: the centre fallback can switch the enrage on in
        // the GameTick after her first charge anim, and must not lose it.
        if (!enraged) {
            clearMoiaCharges();
        }
    }

    // The green tiles in Moia's room, as instance tiles; empty elsewhere.
    // Until her enrage, the arena's north-west corner, fixed. In her enrage,
    // none until her first charge ("Useless corpses..."), then every arena tile
    // out of all the live binders' reach, recomputed every tick as they step
    // round her, leaving out her own 3x3. None while no binder is alive, rather
    // than the whole arena: there is then no reach to step out of.
    // While any binder is walking, nothing new is worked out (from server
    // tiles mid-walk the green came out as odd slivers and single tiles):
    // the green they set off from fades out over their step instead
    // (moiaFadingSafeTiles), and the green for where they stop shows in full
    // the tick they stand still. Binders that have just spawned are standing,
    // so the green takes them in on their spawn tick.
    private Set<WorldPoint> computeMoiaSafeTiles() {
        if (templateRegion() != MOIA_REGION_ID) {
            clearMoiaFade();
            return Set.of();
        }
        if (!moiaEnraged) {
            clearMoiaFade();
            WorldPoint template = new WorldPoint(
                    MOIA_ARENA_TEMPLATE_SW_X, MOIA_ARENA_TEMPLATE_SW_Y + MOIA_ARENA_SIZE - 1, 0);
            return new HashSet<>(WorldPoint.toLocalInstance(client.getTopLevelWorldView(), template));
        }
        if (moiaChargeCount == 0) {
            clearMoiaFade();
            return Set.of();
        }
        List<WorldPoint> binders = new ArrayList<>();
        for (NPC npc : liveBinders()) {
            binders.add(npc.getWorldLocation());
        }
        if (binders.isEmpty()) {
            clearMoiaFade();
            return Set.of();
        }
        if (!moiaBindersSettled) {
            // Only on the step's first tick, while the full green is still
            // up: its second tick keeps the fade it started.
            if (!moiaSafeTiles.isEmpty()) {
                moiaFadingSafeTiles = moiaSafeTiles;
                moiaFadeStartCycle = lastGameTickCycle;
                emit("MSAFE", "binders moving: green fading out over {} ticks", MOIA_BINDER_STEP_TICKS);
            }
            return Set.of();
        }
        clearMoiaFade();
        NPC moia = getMoia();
        Set<WorldPoint> moiaTiles = moia != null && !moia.isDead() ? EzekielTracker.footprint(moia) : Set.of();
        Collection<WorldPoint> centres = moiaArenaCentres();
        Set<WorldPoint> safe = new HashSet<>();
        for (WorldPoint centre : centres) {
            safe.addAll(binderSafeTiles(centre, binders, moiaTiles));
        }
        if (!safe.equals(moiaSafeTiles)) {
            logMoiaSafeTiles(centres, safe, binders, moiaTiles);
        }
        return safe;
    }

    private void clearMoiaFade() {
        moiaFadingSafeTiles = Set.of();
        moiaFadeStartCycle = -1;
    }

    // The tiles of the 9x9 arena around centre that are out of every binder's
    // reach and not in blocked (Moia's own 3x3, so she is never shown as a
    // place to stand).
    static Set<WorldPoint> binderSafeTiles(WorldPoint centre, Collection<WorldPoint> binders,
                                           Set<WorldPoint> blocked) {
        int half = MOIA_ARENA_SIZE / 2;
        Set<WorldPoint> safe = new HashSet<>();
        for (int x = centre.getX() - half; x <= centre.getX() + half; x++) {
            for (int y = centre.getY() - half; y <= centre.getY() + half; y++) {
                WorldPoint tile = new WorldPoint(x, y, centre.getPlane());
                if (!blocked.contains(tile) && !inBinderReach(tile, binders)) {
                    safe.add(tile);
                }
            }
        }
        return safe;
    }

    // Within MOIA_BINDER_REACH tiles of a binder along both x and y, so a
    // binder two tiles away diagonally has you in reach too.
    static boolean inBinderReach(WorldPoint tile, Collection<WorldPoint> binders) {
        for (WorldPoint binder : binders) {
            if (binder != null && Math.max(Math.abs(binder.getX() - tile.getX()),
                    Math.abs(binder.getY() - tile.getY())) <= MOIA_BINDER_REACH) {
                return true;
            }
        }
        return false;
    }

    // The arena as a picture, north at the top, each time the green changes in
    // her enrage, so a log shows what was green against where the binders
    // stood and where you were hit.
    private void logMoiaSafeTiles(Collection<WorldPoint> centres, Set<WorldPoint> safe,
                                  Collection<WorldPoint> binders, Set<WorldPoint> moiaTiles) {
        Player local = client.getLocalPlayer();
        WorldPoint you = local != null ? local.getWorldLocation() : null;
        int half = MOIA_ARENA_SIZE / 2;
        for (WorldPoint centre : centres) {
            emit("MSAFE", "{} safe tiles{}, columns x {}..{}: # safe, . in a binder's reach, B binder, M Moia, "
                            + "@ you on a safe tile, ! you in reach",
                    safe.size(), you == null ? "" : safe.contains(you) ? ", you on one" : ", you NOT on one",
                    centre.getX() - half, centre.getX() + half);
            for (String row : moiaSafePicture(centre, safe, binders, moiaTiles, you)) {
                emit("MSAFE", "{}", row);
            }
        }
    }

    // Rows of the MSAFE picture from north to south, each led by its y.
    static List<String> moiaSafePicture(WorldPoint centre, Set<WorldPoint> safe, Collection<WorldPoint> binders,
                                        Set<WorldPoint> moiaTiles, WorldPoint you) {
        int half = MOIA_ARENA_SIZE / 2;
        List<String> rows = new ArrayList<>();
        for (int y = centre.getY() + half; y >= centre.getY() - half; y--) {
            StringBuilder row = new StringBuilder().append(y).append(" |");
            for (int x = centre.getX() - half; x <= centre.getX() + half; x++) {
                WorldPoint tile = new WorldPoint(x, y, centre.getPlane());
                if (tile.equals(you)) {
                    row.append(safe.contains(tile) ? '@' : '!');
                } else if (binders.contains(tile)) {
                    row.append('B');
                } else if (moiaTiles.contains(tile)) {
                    row.append('M');
                } else {
                    row.append(safe.contains(tile) ? '#' : '.');
                }
            }
            rows.add(row.append('|').toString());
        }
        return rows;
    }

    private List<NPC> liveBinders() {
        List<NPC> binders = new ArrayList<>();
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getId() == UNDEAD_BINDER_ID && !npc.isDead()) {
                binders.add(npc);
            }
        }
        return binders;
    }

    // The binder standing on the arena's centre tile, to shoot with the Venator
    // bow. Found by server tiles, though the overlay draws it where its model is.
    // Only that binder's own movement counts: it lights up once it has stood on
    // the centre for a whole tick, so one walking across the centre never
    // flashes. It used to hide whenever ANY binder spawned or stepped, which
    // blinked the red off for a tick or two under a binder that never moved -
    // a new binder spawns every 30 ticks and walks into the formation.
    private NPC computeVenatorBinder() {
        if (templateRegion() != MOIA_REGION_ID) {
            lastBinderTiles.clear();
            moiaBindersSettled = true;
            return null;
        }
        Collection<WorldPoint> centres = moiaArenaCentres();

        Map<NPC, WorldPoint> nowTiles = new IdentityHashMap<>();
        for (NPC npc : liveBinders()) {
            nowTiles.put(npc, npc.getWorldLocation());
        }
        logBinderSteps(nowTiles, centres);
        moiaBindersSettled = bindersSettled(nowTiles, lastBinderTiles);
        NPC picked = pickCentreBinder(nowTiles, lastBinderTiles, centres, venatorBinder);
        lastBinderTiles.clear();
        lastBinderTiles.putAll(nowTiles);
        if (picked != venatorBinder) {
            emit("VENB", "centre binder {} -> {}",
                    venatorBinder != null ? "#" + venatorBinder.getIndex() : "none",
                    picked != null ? "#" + picked.getIndex() : "none");
        }
        return picked;
    }

    // The arena's centre tile, template x 4063, y 5663, as instance tiles.
    private Collection<WorldPoint> moiaArenaCentres() {
        WorldPoint templateCentre = new WorldPoint(
                MOIA_ARENA_TEMPLATE_SW_X + MOIA_ARENA_SIZE / 2, MOIA_ARENA_TEMPLATE_SW_Y + MOIA_ARENA_SIZE / 2, 0);
        return WorldPoint.toLocalInstance(client.getTopLevelWorldView(), templateCentre);
    }

    // Moia, if her footprint covers the arena's centre tile. She normally floats
    // outside the 9x9 arena, so this only happens in her enrage. In the one
    // logged enrage (2026-11-07 18:56) she despawned on "Cease this futile
    // resistance!" and came back 10 ticks later centred exactly on the centre
    // tile, where she stayed (and again on 2026-11-10). Her whole 3x3 is checked
    // rather than her centre tile alone, in case she ever lands a tile off.
    private NPC computeMoiaOnCentre() {
        NPC moia = null;
        if (templateRegion() == MOIA_REGION_ID) {
            NPC live = getMoia();
            if (live != null && !live.isDead()) {
                NPCComposition composition = live.getComposition();
                int size = composition != null ? composition.getSize() : 3;
                if (footprintCovers(live.getWorldLocation(), size, moiaArenaCentres())) {
                    moia = live;
                }
            }
        }
        if (moia != moiaOnCentre) {
            emit("VENB", "Moia {} the arena centre{}", moia != null ? "on" : "off",
                    moia != null ? " @" + tile(moia.getWorldLocation()) : "");
        }
        return moia;
    }

    // Whether a size x size footprint with south-west tile sw covers any of the
    // given tiles (an NPC's world location is its south-west tile).
    static boolean footprintCovers(WorldPoint sw, int size, Collection<WorldPoint> tiles) {
        if (sw == null) {
            return false;
        }
        for (WorldPoint t : tiles) {
            if (t.getPlane() == sw.getPlane()
                    && t.getX() >= sw.getX() && t.getX() < sw.getX() + size
                    && t.getY() >= sw.getY() && t.getY() < sw.getY() + size) {
                return true;
            }
        }
        return false;
    }

    // Whether every binder is standing still this tick: none stepped off the
    // tile it had last tick. One with no tile last tick has just spawned and
    // stands on its spawn tile, so it counts as standing (until 2026-11-12 new
    // binders held the green back for 2 ticks; the user wants the green on the
    // spawn tick). True with no binders at all.
    static <T> boolean bindersSettled(Map<T, WorldPoint> nowTiles, Map<T, WorldPoint> lastTiles) {
        for (Map.Entry<T, WorldPoint> e : nowTiles.entrySet()) {
            WorldPoint tile = e.getValue();
            if (tile == null) {
                return false;
            }
            WorldPoint last = lastTiles.get(e.getKey());
            if (last != null && !tile.equals(last)) {
                return false;
            }
        }
        return true;
    }

    // Of the binders on a centre tile that were already on that tile last
    // tick, the one highlighted last tick if it still qualifies (so two
    // stacked on the centre never swap the highlight), else the first.
    static <T> T pickCentreBinder(Map<T, WorldPoint> nowTiles, Map<T, WorldPoint> lastTiles,
                                  Collection<WorldPoint> centres, T previous) {
        T first = null;
        for (Map.Entry<T, WorldPoint> e : nowTiles.entrySet()) {
            WorldPoint tile = e.getValue();
            if (tile == null || !centres.contains(tile) || !tile.equals(lastTiles.get(e.getKey()))) {
                continue;
            }
            if (e.getKey() == previous) {
                return previous;
            }
            if (first == null) {
                first = e.getKey();
            }
        }
        return first;
    }

    // One line per tick that any binder spawned or stepped, so the shuffle
    // pattern can be read off a log: "#index from->to", "*" marking the centre.
    private void logBinderSteps(Map<NPC, WorldPoint> nowTiles, Collection<WorldPoint> centres) {
        List<String> steps = new ArrayList<>();
        for (Map.Entry<NPC, WorldPoint> e : nowTiles.entrySet()) {
            WorldPoint last = lastBinderTiles.get(e.getKey());
            WorldPoint now = e.getValue();
            if (now != null && !now.equals(last)) {
                steps.add("#" + e.getKey().getIndex() + " " + (last == null ? "new" : binderTile(last, centres))
                        + "->" + binderTile(now, centres));
            }
        }
        if (!steps.isEmpty()) {
            emit("BIND", "{} of {} stepped: {}", steps.size(), nowTiles.size(), String.join(", ", steps));
        }
    }

    private static String binderTile(WorldPoint tile, Collection<WorldPoint> centres) {
        return tile.getX() + "," + tile.getY() + (centres.contains(tile) ? "*" : "");
    }

    // ------------------------------------------------------------------
    // Logging internals
    // ------------------------------------------------------------------

    private void latch(String reason) {
        if (latched) {
            return;
        }
        latched = true;
        latchTick = client.getTickCount();
        roomStartTick = latchTick;
        emit("START", "Fractured Archive detected via {} - logging on", reason);
        trackRoom();
        scanSceneObjects("initial scene");
        // These spawned before the latch, so they never got an NPC+ line.
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null) {
                logNpc("NPC=", npc);
            }
        }
    }

    // A new template region is a new room (or the lobby). Logs both coordinate
    // systems: template tiles are stable between raids, instance tiles are what the
    // overlays will draw on.
    private void trackRoom() {
        int region = templateRegion();
        if (region == currentRegion) {
            return;
        }
        currentRegion = region;
        roomStartTick = client.getTickCount();

        Player local = client.getLocalPlayer();
        WorldPoint world = local != null ? local.getWorldLocation() : null;
        WorldPoint template = local != null && local.getLocalLocation() != null
                ? WorldPoint.fromLocalInstance(client, local.getLocalLocation())
                : null;
        emit("ROOM", "template region {} you @{} (template) @{} (instance), instance regions {}",
                region, tile(template), tile(world),
                Arrays.toString(client.getTopLevelWorldView().getMapRegions()));
    }

    // Ground graphics are polled, so each instance is logged once and every new one
    // of an id this tick shares a line (a telegraph often paints many tiles at once).
    // The line is split by start tick: Ezekiel's enrage queues three floor strikes
    // on one tick, a tick apart, and one line per id hid which tiles each struck.
    private void scanGraphicsObjects() {
        int gameCycle = client.getGameCycle();
        Set<GraphicsObject> live = Collections.newSetFromMap(new IdentityHashMap<>());
        // id -> start offset -> tiles
        Map<Integer, Map<Integer, List<WorldPoint>>> fresh = new TreeMap<>();

        for (GraphicsObject go : client.getTopLevelWorldView().getGraphicsObjects()) {
            live.add(go);
            if (!seenGraphicsObjects.add(go)) {
                continue;
            }
            LocalPoint lp = go.getLocation();
            // Positive = the graphic is scheduled to start that many ticks from now.
            int startOffset = Math.round((go.getStartCycle() - gameCycle) / (float) CYCLES_PER_GAME_TICK);
            fresh.computeIfAbsent(go.getId(), k -> new TreeMap<>())
                    .computeIfAbsent(startOffset, k -> new ArrayList<>())
                    .add(lp != null ? WorldPoint.fromLocal(client, lp) : null);
        }
        seenGraphicsObjects.retainAll(live);

        boolean ezekielRoom = isInEzekielRoom(client);
        for (Map.Entry<Integer, Map<Integer, List<WorldPoint>>> byId : fresh.entrySet()) {
            int id = byId.getKey();
            for (Map.Entry<Integer, List<WorldPoint>> e : byId.getValue().entrySet()) {
                emit("GOBJ", "graphic {} x{} starts {}t {}",
                        id, e.getValue().size(), signed(e.getKey()),
                        describeTiles(e.getValue(), MAX_TILES_PER_LINE));
                graphicsObjectCounts.merge(id, e.getValue().size(), Integer::sum);
                if (ezekielRoom) {
                    ezekiel.logShape(client, id, e.getValue());
                }
                dreadnoth.logShape(client, "graphic", id, e.getValue());
            }
        }
    }

    // Spawns are also drawn as Dreadnoth SHAPE pictures in his room, in case his
    // splatters are objects rather than ground graphics.
    private void flushObjects(Map<Integer, List<WorldPoint>> pending, String tag, String kind, boolean drawShapes) {
        for (Map.Entry<Integer, List<WorldPoint>> e : pending.entrySet()) {
            emit(tag, "{} {} x{} {}", kind, e.getKey(), e.getValue().size(),
                    describeTiles(e.getValue(), MAX_TILES_PER_LINE));
            if (drawShapes) {
                dreadnoth.logShape(client, kind, e.getKey(), e.getValue());
            }
        }
        pending.clear();
    }

    // Lists the game object ids in the loaded scene that have not appeared in an
    // earlier scan this raid, with counts - enough to spot a room's marker objects
    // (like the Doom's arena-centre object) without dumping every rock each load.
    private void scanSceneObjects(String reason) {
        Scene scene = client.getTopLevelWorldView().getScene();
        Tile[][][] tiles = scene != null ? scene.getTiles() : null;
        if (tiles == null) {
            return;
        }

        Map<Integer, Integer> newIds = new TreeMap<>();
        Set<Long> counted = new HashSet<>();
        for (Tile[][] plane : tiles) {
            for (Tile[] column : plane) {
                for (Tile tile : column) {
                    if (tile == null) {
                        continue;
                    }
                    for (GameObject object : tile.getGameObjects()) {
                        // A multi-tile object sits on every tile it covers; count it once.
                        if (object != null && !seenSceneObjectIds.contains(object.getId())
                                && counted.add(object.getHash())) {
                            newIds.merge(object.getId(), 1, Integer::sum);
                        }
                    }
                }
            }
        }
        seenSceneObjectIds.addAll(newIds.keySet());
        emit("SCENE", "{}: {} new object ids {}", reason, newIds.size(), formatCounts(newIds));
    }

    private void logNpc(String tag, NPC npc) {
        NPCComposition composition = npc.getComposition();
        emit(tag, "{} size {} lvl {} @{}",
                npcLabel(npc), composition != null ? composition.getSize() : "?",
                npc.getCombatLevel(), tile(npc.getWorldLocation()));
        npcNames.put(npc.getId(), String.valueOf(npc.getName()));
    }

    private void emitSummary() {
        emit("SUM", "left after {} ticks - ids seen this raid:", client.getTickCount() - latchTick);
        Set<Integer> npcIds = new TreeSet<>(npcNames.keySet());
        npcIds.addAll(npcAnimCounts.keySet());
        for (int id : npcIds) {
            emit("SUM", "npc {} '{}' anims {} spotanims {}", id, npcNames.getOrDefault(id, "?"),
                    formatCounts(npcAnimCounts.getOrDefault(id, Map.of())),
                    formatCounts(spotAnimCounts.getOrDefault(id, Map.of())));
        }
        emit("SUM", "your spotanims {}", formatCounts(spotAnimCounts.getOrDefault(LOCAL_PLAYER_KEY, Map.of())));
        emit("SUM", "projectiles {}", formatCounts(projectileCounts));
        emit("SUM", "ground graphics {}", formatCounts(graphicsObjectCounts));
        emit("SUM", "spawned objects {}", formatCounts(dynamicObjectCounts));
        emit("SUM", "spawned ground objects {}", formatCounts(groundObjectCounts));
        emit("SUM", "vars changed (id -> times) {}", formatVarCounts(varChangeCounts));
    }

    private void emit(String tag, String format, Object... args) {
        if (!config.fracturedArchiveLogging()) {
            return;
        }
        int tick = client.getTickCount();
        log.info("[Archive t{} r{}] {} {}", tick, tick - roomStartTick, String.format("%-5s", tag),
                MessageFormatter.arrayFormat(format, args).getMessage());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int templateRegion() {
        return templateRegion(client);
    }

    // True while you are in the Archive Guardian's room (its instance template
    // region), whatever the logger's latch state. The arena simplifiers use this
    // to tell this room apart from the Doom of Mokhaiotl's.
    public static boolean isInGuardianRoom(Client client) {
        return client.getTopLevelWorldView().isInstance()
                && templateRegion(client) == GUARDIAN_REGION_ID;
    }

    public static boolean isInEzekielRoom(Client client) {
        return client.getTopLevelWorldView().isInstance()
                && templateRegion(client) == EZEKIEL_REGION_ID;
    }

    static int templateRegion(Client client) {
        Player local = client.getLocalPlayer();
        LocalPoint lp = local != null ? local.getLocalLocation() : null;
        WorldPoint template = lp != null ? WorldPoint.fromLocalInstance(client, lp) : null;
        return template != null ? template.getRegionID() : -1;
    }

    @SuppressWarnings("deprecation") // getGraphic() is the fallback spotanim read in this pinned version
    private static Set<Integer> spotAnimIds(Actor actor) {
        Set<Integer> ids = new TreeSet<>();
        if (actor.getSpotAnims() != null) {
            for (ActorSpotAnim spotAnim : actor.getSpotAnims()) {
                ids.add(spotAnim.getId());
            }
        }
        if (ids.isEmpty() && actor.getGraphic() != -1) {
            ids.add(actor.getGraphic());
        }
        return ids;
    }

    // Every spotanim slot on the actor with its id, delay and height, e.g.
    // "[0: 4182 +2.0t h0, 1: 4182 +5.0t h0]", or "" if it has none. Ezekiel's
    // enrage swords show the same id every cycle; which two light up each round
    // can only be in when (or how high) each copy plays. The delay is in ticks to
    // a tenth, as the server can queue a spotanim part-way into a tick.
    private static String spotAnimSlots(Actor actor, int gameCycle) {
        if (actor.getSpotAnims() == null) {
            return "";
        }
        List<String> slots = new ArrayList<>();
        for (ActorSpotAnim spotAnim : actor.getSpotAnims()) {
            float delayTicks = (spotAnim.getStartCycle() - gameCycle) / (float) CYCLES_PER_GAME_TICK;
            slots.add(String.format(Locale.ROOT, "%d: %d %+.1ft h%d",
                    spotAnim.getHash(), spotAnim.getId(), delayTicks, spotAnim.getHeight()));
        }
        return slots.isEmpty() ? "" : slots.toString();
    }

    // The pinned client often leaves a player's projectile without a source actor
    // (your own spell, 2126, logged 42 times in the first run), so a projectile
    // with no NPC source that is aimed at an NPC counts as a player's attack.
    private static boolean isPlayerProjectile(Projectile p) {
        Actor source = p.getSourceActor();
        if (source instanceof Player) {
            return true;
        }
        return source == null && p.getTargetActor() instanceof NPC;
    }

    private String actorLabel(Actor actor) {
        if (actor == null) {
            return "none";
        }
        if (actor instanceof NPC) {
            return npcLabel((NPC) actor);
        }
        return actor == client.getLocalPlayer() ? "you" : "player '" + actor.getName() + "'";
    }

    private static String npcLabel(NPC npc) {
        return "'" + npc.getName() + "'#" + npc.getIndex() + "(id " + npc.getId() + ")";
    }

    static boolean isArchiveName(String name) {
        if (name == null) {
            return false;
        }
        String lowered = Text.removeTags(name).toLowerCase(Locale.ENGLISH);
        for (String keyword : NAME_KEYWORDS) {
            if (lowered.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    static String tile(WorldPoint point) {
        return point == null ? "?" : point.getX() + "," + point.getY() + "," + point.getPlane();
    }

    // Up to `max` tiles as x,y, then the group's bounding box so a truncated or
    // large group still reads as an area (e.g. "bbox 3200..3204 x 4100..4104").
    static String describeTiles(List<WorldPoint> points, int max) {
        StringBuilder sb = new StringBuilder("[");
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int shown = 0, known = 0, plane = -1;
        for (WorldPoint p : points) {
            if (p == null) {
                continue;
            }
            known++;
            plane = p.getPlane();
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
            if (shown < max) {
                sb.append(shown > 0 ? " " : "").append(p.getX()).append(',').append(p.getY());
                shown++;
            }
        }
        if (known > shown) {
            sb.append(" +").append(known - shown).append(" more");
        }
        sb.append(']');
        if (known > 1) {
            sb.append(" bbox ").append(minX).append("..").append(maxX)
                    .append(" x ").append(minY).append("..").append(maxY);
        }
        if (known > 0) {
            sb.append(" p").append(plane);
        }
        return sb.toString();
    }

    static String formatCounts(Map<Integer, Integer> counts) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            sb.append(sb.length() > 1 ? ", " : "").append(e.getKey()).append('x').append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static String formatVarCounts(Map<Integer, Integer> counts) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(counts).entrySet()) {
            int key = e.getKey();
            sb.append(sb.length() > 1 ? ", " : "")
                    .append(key >= 0 ? "varbit " + key : "varp " + (-key - 1))
                    .append(" -> ").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static String since(String label, Integer previousTick, int tick) {
        return previousTick == null ? "" : label + "+" + (tick - previousTick) + "t";
    }

    private static String signed(Integer value) {
        if (value == null) {
            return "?";
        }
        return value >= 0 ? "+" + value : String.valueOf(value);
    }
}
