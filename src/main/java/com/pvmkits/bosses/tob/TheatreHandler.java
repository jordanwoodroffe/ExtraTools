package com.pvmkits.bosses.tob;

import com.pvmkits.core.BossHandler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.WorldView;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.coords.LocalPoint;

import javax.inject.Inject;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Theatre of Blood helper, covering several rooms.
 *
 * <p>
 * <b>Verzik phase 3.</b> Mirrors the Phosani handler: a per-attack style that
 * drives a coloured overlay on the boss, plus an attack countdown that holds on
 * 1 until the attack lands. In P3 Verzik uses all three combat styles as autos -
 * barbs (range), a blue bolt (magic), and a claw swipe on anything adjacent
 * (melee) - so the style has to be read off each attack rather than off the
 * phase. Only range and magic are ever displayed: the melee swipe is instant and
 * unprayable, so it leaves the previous range/magic colour up instead of
 * flipping the overlay.
 * </p>
 *
 * <p>
 * <b>Bloat's falling hands.</b> The chunks of flesh that rain from the ceiling
 * while he walks are highlighted on the tiles they are coming down on, in normal
 * and hard mode alike.
 * </p>
 *
 * <p>
 * <b>Sotetseg's death ball.</b> A countdown to the tick his big red ball lands,
 * for teams that tick eat it rather than stacking to split the damage. The two
 * rooms share a handler because they never share a scene, so their state cannot
 * collide.
 * </p>
 */
@Slf4j
public class TheatreHandler implements BossHandler {

    @Inject
    private Client client;

    // Verzik's P3 ("true form") combat NPC ids, one per raid mode: normal 8374,
    // entry 10835, hard 10852. Her death-bat ids (8375/10836/10853) are excluded on
    // purpose - she does not attack while fleeing, so the overlay clears itself.
    private static final Set<Integer> VERZIK_P3_IDS = Set.of(8374, 10835, 10852);

    // Verzik's room. Used alongside the NPC check so the handler stays active for
    // the whole fight instead of flapping as she changes NPC id between phases.
    private static final int VERZIK_REGION = 12611;

    // P3 auto-attack animations. These are the primary style signal. Melee is
    // recognised only so it can be explicitly ignored rather than falling through
    // with her specials.
    private static final int ANIM_P3_MELEE = 8123;
    private static final int ANIM_P3_MAGE = 8124;
    private static final int ANIM_P3_RANGE = 8125;

    // P3 attack projectiles, launched on the same tick as the animation above.
    // These are the authoritative "she attacked" signal: polling the animation
    // alone misses back-to-back attacks in the same style, because the animation
    // never returns to idle between them for the countdown to see a fresh start.
    private static final int PROJECTILE_P3_RANGE = 1593;
    private static final int PROJECTILE_P3_MAGE = 1594;

    // Her attack cadence, in game ticks. At 20% health she enrages: her attacks
    // speed up and she summons a tornado per player.
    private static final int P3_CYCLE = 7;
    private static final int P3_ENRAGE_CYCLE = 5;
    private static final int ENRAGE_HEALTH_PERCENT = 20;

    // The enrage tornado, internally a "creeper" - it shares its swirling model with
    // Sotetseg's chasing vortex. One spawns per player only once she is enraged, so
    // it is a clean enrage cue that does not need her health bar on screen. One id
    // per raid mode: normal 8386, hard 10863.
    private static final Set<Integer> TORNADO_NPC_IDS = Set.of(8386, 10863);

    // The only animations that re-arm the countdown. Her specials - webs (8127)
    // and yellow pools (8126) - and her transform and death animations all start
    // mid-cycle, so counting any animation start as an attack re-synced the
    // countdown to something that was not an attack.
    private static final Set<Integer> P3_AUTO_ANIMATIONS = Set.of(ANIM_P3_MELEE, ANIM_P3_MAGE, ANIM_P3_RANGE);

    // How much sooner than her cadence a second detection can arrive and still be
    // taken as a new attack. Everything inside that window is an echo of the attack
    // just handled: the other detection path seeing it a tick later, or another of
    // the barbs a ranged volley puts in the air. Her fastest cadence is 5 ticks, so
    // one tick of slack never drops a real attack.
    private static final int ATTACK_ECHO_SLACK_TICKS = 1;

    // Sotetseg's NPC ids: a non-combat and a combat id he swaps between as players
    // are pulled into the shadow realm, so both are accepted for the countdown to
    // survive a maze. Two per raid mode: normal 8387/8388, hard 10867/10868.
    private static final Set<Integer> SOTETSEG_IDS = Set.of(8387, 8388, 10867, 10868);

    // Sotetseg's "death ball" - the big red orb he throws at one player after his
    // tenth projectile. The team either stacks on the target to split it across a
    // 3x3, or the target tick eats it.
    private static final int PROJECTILE_SOTETSEG_BALL = 1604;

    // Client cycles per game tick (600ms / 20ms). Converts the ball's remaining
    // flight into the game tick it lands on.
    private static final int CYCLES_PER_GAME_TICK = 30;

    // Ball flight length in ticks: launched on tick 0, lands on tick 10. Only a
    // fallback - the countdown is normally read from the projectile's own end
    // cycle, which is authoritative and is logged on every launch. Jagex made the
    // flight time distance-independent (the projectile's speed scales to
    // compensate), so a fixed number is a safe backstop.
    private static final int BALL_FLIGHT_TICKS_FALLBACK = 10;

    // Verzik's phase 2 combat NPC ids, one per raid mode: normal 8372, story 10833,
    // hard 10850. Her phase-transition ids are excluded - she does not attack on a
    // cadence while morphing between phases.
    private static final Set<Integer> VERZIK_P2_IDS = Set.of(8372, 10833, 10850);

    // Verzik's phase 2 mage attack: the blood spell she casts below 35% health, whose
    // damage lands on the cast animation (pray Magic). Her ranged urnbombs deal their
    // damage on landing instead, so they have no attack animation and are tracked by
    // projectile below; her body slam is an off-cadence proximity punish, left out.
    private static final int ANIM_P2_ATTACK_MAGIC = 8114;
    private static final Set<Integer> P2_ATTACK_ANIMATIONS = Set.of(ANIM_P2_ATTACK_MAGIC);

    // Verzik's phase 2 urnbomb - her ranged auto through the whole phase (pray
    // Missiles). Aimed at each player's tile and dealing its damage on landing, it
    // has no attack animation to poll, so its projectile is what re-arms the timer.
    private static final Set<Integer> VERZIK_P2_ATTACK_PROJECTILE_IDS = Set.of(1583);

    // Verzik's phase 2 attack cadence, in game ticks.
    private static final int P2_CYCLE = 4;

    // The raw countdown value (ticks until her attack lands) on the tick players
    // step back for Verzik's P2 attack. The display is rotated so this value reads
    // 1 - the highlighted tick - so the countdown reaches 1 on the step back rather
    // than one tick before the hit. Two ticks before the attack lands.
    private static final int P2_STEP_BACK_TICK = 2;

    // Xarpus's combat NPC ids - his poison-spit phase, one per raid mode: normal
    // 8340, story 10768, hard 10772. His earlier static/feeding ids only heal off
    // the exhumes and never attack, so they are excluded.
    private static final Set<Integer> XARPUS_COMBAT_IDS = Set.of(8340, 10768, 10772);

    // Xarpus's poison-spit animation, his only attack on the cadence.
    private static final int ANIM_XARPUS_SPIT = 8059;
    private static final Set<Integer> XARPUS_ATTACK_ANIMATIONS = Set.of(ANIM_XARPUS_SPIT);

    // Xarpus's attack cadence, in game ticks.
    private static final int XARPUS_CYCLE = 4;

    // Bloat's NPC ids, one per raid mode: normal 8359, story 10812, hard 10813. He
    // keeps the same id asleep and awake, so his presence alone marks his room.
    private static final Set<Integer> BLOAT_IDS = Set.of(8359, 10812, 10813);

    // The falling flesh - the "hands" that rain from the ceiling while Bloat walks.
    // They are spot animations played on the tile they land on, with no NPC or game
    // object behind them, so the graphic itself is what gets highlighted. Four ids,
    // one per chunk model, and the same four in every raid mode.
    private static final Set<Integer> BLOAT_HAND_GRAPHIC_IDS = Set.of(1570, 1571, 1572, 1573);

    // The raw countdown value (ticks until his spit lands) on the tick players step
    // back for Xarpus's poison spit. The display is rotated so this value reads 1 -
    // the highlighted tick - so the countdown reaches 1 on the step back rather than
    // one tick before the hit. Three ticks before the spit lands.
    private static final int XARPUS_STEP_BACK_TICK = 3;

    /**
     * Verzik's P3 prayable attack style. There is deliberately no melee value -
     * her claw swipe is instant and cannot be prayed against, so it never becomes
     * a displayed style.
     */
    public enum VerzikStyle {
        UNKNOWN(Color.GRAY),
        RANGE(new Color(144, 238, 144)),
        MAGE(new Color(100, 149, 237));

        private final Color color;

        VerzikStyle(Color color) {
            this.color = color;
        }

        public Color getColor() {
            return color;
        }
    }

    private boolean verzikP3Active = false;
    private VerzikStyle verzikStyle = VerzikStyle.UNKNOWN;

    // Latched once she enrages, so a tornado despawning or her health bar dropping
    // out of view cannot flip the cadence back to the slower one mid-fight.
    private boolean enraged = false;

    // Attack countdown. Counts down to 1, holds there until the attack actually
    // lands, then re-arms on the cycle length - so the hit lands on the reset
    // number. A backstop re-arms it if an attack is ever missed entirely.
    private int attackTimer = -1;
    private int timerHeldTicks = 0;
    private int lastAnimation = -1;
    private int lastAttackTick = -1;

    // Set by an attack projectile launched this tick and consumed by the next
    // onGameTick. Deduped on start cycle: one attack fires a projectile per player
    // in the room, and each stays in flight for several ticks.
    private boolean projectileAttackPending = false;

    // Highest start cycle already counted as an attack - not merely the last one
    // seen. See onProjectileMoved.
    private int lastProjectileStartCycle = -1;

    // Her NPC as of the last game tick. Held so the projectile path can check a
    // projectile is hers without rescanning the NPC list, which it would otherwise
    // do once per client cycle per projectile in flight.
    private NPC verzikNpc = null;

    // Ids seen in her room that this handler does not recognise, logged once each
    // per fight. A wrong or missing id then shows up in client.log as a line here,
    // rather than as a countdown that quietly mistimes.
    private final Set<Integer> loggedUnknownAnimations = new HashSet<>();
    private final Set<Integer> loggedUnknownProjectiles = new HashSet<>();

    // Absolute game tick Sotetseg's death ball lands on, which is also the tick to
    // eat on. Held as an absolute tick rather than a decrementing counter so the
    // countdown cannot drift if a tick is ever missed. -1 when no ball is airborne.
    private int ballImpactTick = -1;
    private int lastBallStartCycle = -1;

    // Flight length of the airborne ball in ticks, captured at launch. Used as the
    // full value of the inventory pie timer so the wheel drains from full to empty.
    // -1 when no ball is airborne.
    private int ballFlightTicks = -1;

    // Client game cycle of the last game tick, for getGameTickFraction() (smooth pie).
    private int lastGameTickCycle = -1;

    // Verzik phase 2 and Xarpus attack countdowns. Both are fixed-cadence attackers
    // read off a single auto animation, so they share the simple countdown below
    // rather than the fuller P3 machinery (projectiles, enrage, attack style).
    private final AttackCountdown verzikP2Countdown = new AttackCountdown("Verzik P2", P2_ATTACK_ANIMATIONS,
            VERZIK_P2_ATTACK_PROJECTILE_IDS, P2_CYCLE, P2_STEP_BACK_TICK);
    private final AttackCountdown xarpusCountdown = new AttackCountdown("Xarpus", XARPUS_ATTACK_ANIMATIONS,
            Set.of(), XARPUS_CYCLE, XARPUS_STEP_BACK_TICK);

    // Whether Bloat is in the scene, refreshed once a tick. The hand highlight is
    // rebuilt every frame, so this keeps that path off the NPC list.
    private boolean bloatActive = false;

    @Override
    public String getBossName() {
        return "Theatre of Blood";
    }

    @Override
    public boolean isInBossArea(Client client) {
        return inVerzikRegion() || findVerzikP3() != null || findXarpus() != null || findSotetseg() != null
                || findBloat() != null;
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        // Animations are polled in onGameTick so the attack timer and the style
        // update on the same tick boundary.
    }

    @Override
    public void onGraphicChanged(GraphicChanged event) {
        // Verzik's P3 style is carried by her animation and projectiles.
    }

    /**
     * Primary attack detection. Every range/magic auto launches one of these, so
     * unlike the animation poll it also catches consecutive attacks in the same
     * style - which is what the countdown needs to stay in sync once she enrages.
     */
    @Override
    public void onProjectileMoved(ProjectileMoved event) {
        Projectile projectile = event.getProjectile();
        if (projectile == null) {
            return;
        }

        // Sotetseg's ball is checked ahead of the Verzik guard - it is the one
        // mechanic this handler follows outside her room.
        if (projectile.getId() == PROJECTILE_SOTETSEG_BALL) {
            onDeathBallLaunched(projectile);
            return;
        }

        // Verzik's P2 urnbomb re-arms her P2 countdown. It lands for damage and has no
        // attack animation, so its projectile is the signal.
        if (verzikP2Countdown.onProjectile(projectile)) {
            return;
        }

        if (!verzikP3Active) {
            return;
        }

        // The event re-fires every client cycle a projectile is airborne, so only a
        // launch from this tick can be a new attack. Her barbs cross the room over
        // several ticks, which is long enough for an old one to keep arriving here
        // as though it had just been fired.
        if (client.getGameCycle() - projectile.getStartCycle() > CYCLES_PER_GAME_TICK) {
            return;
        }

        // Start cycles only ever increase, so this is a watermark check rather than
        // an equality check: anything at or below the newest cycle already counted
        // belongs to an attack that has been handled. Comparing against the last
        // cycle *seen* is what desynced her ranged cycle - a volley puts several
        // barbs in the air at once, and two with different start cycles each looked
        // new to the other on every client cycle, leaving an attack permanently
        // pending and re-arming the countdown partway through the cycle. Her magic
        // attack is a single bolt, so it had nothing to alternate with and stayed in
        // sync.
        if (projectile.getStartCycle() <= lastProjectileStartCycle) {
            return;
        }

        // Her nylocas spawns share the room and shoot too. Projectiles the client
        // does not attribute to anyone are still accepted - dropping those would
        // cost this path the back-to-back attacks it exists to catch.
        Actor sourceActor = projectile.getSourceActor();
        if (sourceActor != null && verzikNpc != null && sourceActor != verzikNpc) {
            return;
        }

        VerzikStyle style;
        if (projectile.getId() == PROJECTILE_P3_RANGE) {
            style = VerzikStyle.RANGE;
        } else if (projectile.getId() == PROJECTILE_P3_MAGE) {
            style = VerzikStyle.MAGE;
        } else {
            logUnknownId(loggedUnknownProjectiles, projectile.getId(), "unrecognised projectile");
            return;
        }

        lastProjectileStartCycle = projectile.getStartCycle();
        projectileAttackPending = true;
        setStyle(style, "projectile");
    }

    /**
     * Logs an id seen during P3 that the countdown deliberately ignores, once per id
     * per fight. Her specials show up here as expected; anything unexpected is an id
     * that needs correcting, which is otherwise only visible as a countdown that
     * quietly mistimes.
     */
    private void logUnknownId(Set<Integer> alreadyLogged, int id, String kind) {
        if (alreadyLogged.add(id)) {
            log.info("Verzik P3 {} id {} not counted as an attack", kind, id);
        }
    }

    /**
     * Starts the death ball countdown. The tick it lands on is taken from the
     * projectile's own end cycle rather than a hardcoded flight length, so the
     * count is exact even if the timing is ever changed.
     */
    private void onDeathBallLaunched(Projectile projectile) {
        // Re-fires every client cycle the ball is airborne, and hard mode throws
        // two at once - the launch cycle they share identifies the volley exactly
        // once.
        if (projectile.getStartCycle() == lastBallStartCycle) {
            return;
        }
        lastBallStartCycle = projectile.getStartCycle();

        int remainingCycles = projectile.getRemainingCycles();
        // The flight is not a whole number of ticks (e.g. 457 cycles = 15.23), so
        // round to the nearest tick - ceil landed the count a tick late.
        int flightTicks = remainingCycles > 0
                ? (int) Math.round(remainingCycles / (double) CYCLES_PER_GAME_TICK)
                : BALL_FLIGHT_TICKS_FALLBACK;

        // Both terms are read at the same instant, so this lands on the right tick
        // whether the projectile event arrived before or after this tick's counter
        // increment - which a plain "count down from N" would not.
        int impactTick = client.getTickCount() + flightTicks;

        // Hard mode's second ball is launched on the same tick and lands on the
        // same tick; if that ever stopped holding, the earlier one is the one worth
        // counting down to.
        if (ballImpactTick >= 0 && ballImpactTick <= impactTick) {
            return;
        }
        ballImpactTick = impactTick;
        ballFlightTicks = flightTicks;
        log.info("Sotetseg death ball launched - lands in {} ticks ({} cycles), eat on 0",
                flightTicks, remainingCycles);
    }

    /**
     * Clears the death ball countdown once it has been on 0 for its tick, or if
     * Sotetseg leaves the scene mid-flight, so it cannot freeze on screen.
     */
    private void updateDeathBallTimer() {
        if (ballImpactTick < 0) {
            return;
        }
        if (client.getTickCount() > ballImpactTick || findSotetseg() == null) {
            ballImpactTick = -1;
            ballFlightTicks = -1;
        }
    }

    @Override
    public void onGameTick(GameTick event) {
        if (client.getGameState().getState() < 30) {
            return;
        }

        lastGameTickCycle = client.getGameCycle();
        updateDeathBallTimer();
        updateVerzik();
        verzikP2Countdown.update(findVerzikP2());
        xarpusCountdown.update(findXarpus());
        updateBloat();
    }

    private void updateVerzik() {
        NPC verzik = findVerzikP3();
        verzikNpc = verzik;
        if (verzik == null) {
            if (verzikP3Active) {
                verzikP3Active = false;
                log.info("Verzik P3 ended");
                resetFightState();
            }
            return;
        }
        if (!verzikP3Active) {
            verzikP3Active = true;
            log.info("Verzik P3 started");
            resetFightState();
        }

        updateEnrage(verzik);
        int cycle = enraged ? P3_ENRAGE_CYCLE : P3_CYCLE;

        // An animation start is the only signal available for her melee swipe,
        // which fires no projectile. lastAnimation tracks the idle (-1) frames too,
        // so a repeat of the same animation still reads as a fresh start.
        int animation = verzik.getAnimation();
        boolean animationStarted = animation != -1 && animation != lastAnimation;
        lastAnimation = animation;
        boolean autoStarted = false;
        if (animationStarted) {
            updateStyle(animation);
            autoStarted = P3_AUTO_ANIMATIONS.contains(animation);
            if (!autoStarted) {
                logUnknownId(loggedUnknownAnimations, animation, "non-auto animation");
            }
        }

        // Only her autos re-arm the countdown. A special starting mid-cycle is not
        // an attack on the cycle, so it must not re-sync it.
        boolean attacked = autoStarted || projectileAttackPending;
        String source = autoStarted ? "animation " + animation : "projectile";
        projectileAttackPending = false;

        // A detection inside her cadence is an echo of the attack just handled
        // rather than a new one: the two paths see the same attack up to a tick
        // apart, and a ranged volley launches more than one barb. Measuring against
        // the cadence rather than a flat three ticks is what keeps those echoes from
        // re-arming the countdown before she has actually attacked again.
        int tick = client.getTickCount();
        int sinceLastAttack = lastAttackTick > 0 ? tick - lastAttackTick : -1;
        if (attacked && sinceLastAttack >= 0 && sinceLastAttack < cycle - ATTACK_ECHO_SLACK_TICKS) {
            log.info("Verzik P3 attack ignored ({}) - {}t since the last one, cadence is {}t",
                    source, sinceLastAttack, cycle);
            attacked = false;
        }
        if (attacked) {
            log.info("Verzik P3 attack: {}, style {}, {}t since her last - countdown -> {}",
                    source, verzikStyle, sinceLastAttack, cycle);
            lastAttackTick = tick;
        }

        if (attackTimer <= 0 || attacked) {
            attackTimer = cycle;
            timerHeldTicks = 0;
        } else if (attackTimer > cycle) {
            // Cadence just shortened under a running countdown - drop straight to
            // the new cycle rather than counting down from the old, longer one.
            attackTimer = cycle;
        } else if (attackTimer > 1) {
            attackTimer--;
        } else if (++timerHeldTicks >= cycle) {
            // Held on 1 for a full cycle with no attack detected - re-arm so the
            // countdown keeps running instead of sticking on the warning tick.
            attackTimer = cycle;
            timerHeldTicks = 0;
        }
    }

    /**
     * At 20% health Verzik enrages, speeding her attacks up and summoning a
     * tornado per player. The tornadoes are the more reliable of the two signals -
     * her health bar only reports a ratio while it is on screen - so either one
     * latches the enrage for the rest of the phase.
     */
    private void updateEnrage(NPC verzik) {
        if (enraged) {
            return;
        }

        String source;
        if (tornadoesPresent()) {
            source = "tornadoes spawned";
        } else if (healthAtOrBelowPercent(verzik, ENRAGE_HEALTH_PERCENT)) {
            source = "health <= " + ENRAGE_HEALTH_PERCENT + "%";
        } else {
            return;
        }

        enraged = true;
        log.info("Verzik P3 enraged ({}) - attack cycle {} -> {} ticks",
                source, P3_CYCLE, P3_ENRAGE_CYCLE);
    }

    private boolean tornadoesPresent() {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null) {
            return false;
        }
        for (NPC npc : worldView.npcs()) {
            if (npc != null && TORNADO_NPC_IDS.contains(npc.getId())) {
                return true;
            }
        }
        return false;
    }

    private boolean healthAtOrBelowPercent(NPC verzik, int percent) {
        int ratio = verzik.getHealthRatio();
        int scale = verzik.getHealthScale();
        // Both are -1 whenever her health bar is not currently displayed.
        if (ratio < 0 || scale <= 0) {
            return false;
        }
        return (ratio * 100) / scale <= percent;
    }

    private void updateStyle(int animation) {
        switch (animation) {
            case ANIM_P3_MAGE:
                setStyle(VerzikStyle.MAGE, "animation");
                break;
            case ANIM_P3_RANGE:
                setStyle(VerzikStyle.RANGE, "animation");
                break;
            case ANIM_P3_MELEE:
                // Her melee swipe is instant and unprayable, so it needs no colour
                // of its own. Fall through and leave the last range/magic style up.
                break;
            default:
                // Her specials (webs 8127, yellows 8126) are not autos either -
                // keep showing the last real attack style rather than blanking out.
                break;
        }
    }

    private void setStyle(VerzikStyle style, String source) {
        if (verzikStyle != style) {
            log.info("Verzik P3 attack style: {} -> {} (via {})", verzikStyle, style, source);
            verzikStyle = style;
        }
    }

    private NPC findVerzikP3() {
        return findNpc(VERZIK_P3_IDS);
    }

    private NPC findSotetseg() {
        return findNpc(SOTETSEG_IDS);
    }

    private NPC findVerzikP2() {
        return findNpc(VERZIK_P2_IDS);
    }

    private NPC findXarpus() {
        return findNpc(XARPUS_COMBAT_IDS);
    }

    private NPC findBloat() {
        return findNpc(BLOAT_IDS);
    }

    // Bloat's room is entered asleep, so his presence is tracked rather than an
    // attack: the hands fall whenever he is walking, and stop when he goes down.
    private void updateBloat() {
        boolean present = findBloat() != null;
        if (present == bloatActive) {
            return;
        }
        bloatActive = present;
        log.info("Bloat {}", present ? "started" : "ended");
    }

    private NPC findNpc(Set<Integer> ids) {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null) {
            return null;
        }
        for (NPC npc : worldView.npcs()) {
            if (npc != null && ids.contains(npc.getId())) {
                return npc;
            }
        }
        return null;
    }

    private boolean inVerzikRegion() {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null) {
            return false;
        }
        int[] regions = worldView.getMapRegions();
        if (regions == null) {
            return false;
        }
        for (int region : regions) {
            if (region == VERZIK_REGION) {
                return true;
            }
        }
        return false;
    }

    private void resetFightState() {
        verzikStyle = VerzikStyle.UNKNOWN;
        enraged = false;
        attackTimer = -1;
        timerHeldTicks = 0;
        lastAnimation = -1;
        lastAttackTick = -1;
        projectileAttackPending = false;
        lastProjectileStartCycle = -1;
        loggedUnknownAnimations.clear();
        loggedUnknownProjectiles.clear();
    }

    @Override
    public Actor getBossActor(Client client) {
        return findVerzikP3();
    }

    @Override
    public void reset() {
        verzikP3Active = false;
        verzikNpc = null;
        ballImpactTick = -1;
        lastBallStartCycle = -1;
        ballFlightTicks = -1;
        verzikP2Countdown.reset();
        xarpusCountdown.reset();
        bloatActive = false;
        resetFightState();
    }

    // --- Accessors for the overlay ---
    public boolean isVerzikP3() {
        return verzikP3Active;
    }

    public VerzikStyle getVerzikStyle() {
        return verzikStyle;
    }

    public int getAttackTimer() {
        return attackTimer;
    }

    public NPC getVerzik() {
        return findVerzikP3();
    }

    public NPC getVerzikP2() {
        return findVerzikP2();
    }

    public int getVerzikP2AttackTimer() {
        return verzikP2Countdown.getDisplayTimer();
    }

    public NPC getXarpus() {
        return findXarpus();
    }

    public int getXarpusAttackTimer() {
        return xarpusCountdown.getDisplayTimer();
    }

    /**
     * The tiles Bloat's falling hands are currently coming down on, or an empty list
     * when none are in the air. Rebuilt on each call rather than once a tick: the
     * graphics are created and finish between game ticks, and the overlay reads this
     * per frame.
     */
    public List<LocalPoint> getBloatHandTiles() {
        if (!bloatActive) {
            return Collections.emptyList();
        }

        List<LocalPoint> tiles = new ArrayList<>();
        for (GraphicsObject go : client.getGraphicsObjects()) {
            if (go == null || go.finished() || !BLOAT_HAND_GRAPHIC_IDS.contains(go.getId())) {
                continue;
            }
            LocalPoint location = go.getLocation();
            if (location != null) {
                tiles.add(location);
            }
        }
        return tiles;
    }

    /**
     * Ticks until Sotetseg's death ball lands, or -1 when none is airborne. 0 is
     * the tick it hits the target, which is the tick to eat on to tick eat it.
     */
    public int getDeathBallTimer() {
        if (ballImpactTick < 0) {
            return -1;
        }
        return Math.max(-1, ballImpactTick - client.getTickCount());
    }

    /**
     * Flight length in ticks of the airborne death ball, captured at launch, or -1
     * when none is airborne. The inventory pie timer uses it as the countdown's
     * full value so the wheel drains from full to empty.
     */
    public int getDeathBallFlightTicks() {
        return ballFlightTicks;
    }

    /**
     * Fraction (0..1) of the way through the current game tick, from the client game
     * cycle (~20ms, 50Hz) since the last tick. The inventory tick-eat pie uses it to
     * drain smoothly between ticks instead of stepping once per tick.
     */
    public double getGameTickFraction() {
        if (lastGameTickCycle < 0) {
            return 0.0;
        }
        double fraction = (client.getGameCycle() - lastGameTickCycle) / (double) CYCLES_PER_GAME_TICK;
        return Math.max(0.0, Math.min(1.0, fraction));
    }

    /**
     * A Phosani-style attack countdown for a fixed-cadence boss: counts down to 1,
     * holds there until the boss attacks, then re-arms on the cycle so the hit lands
     * on the reset number. An attack is read off the boss's auto animation; a
     * detection inside the cadence is treated as an echo of the attack just handled,
     * and a backstop re-arms the countdown if an attack is ever missed entirely.
     *
     * <p>
     * The value shown to the player is rotated by {@code stepBackTick} so the
     * highlighted 1 lands on the tick to step back on rather than on the tick before
     * the hit - see {@link #getDisplayTimer()}.
     * </p>
     */
    private final class AttackCountdown {
        private final String label;
        private final Set<Integer> attackAnimations;
        private final Set<Integer> attackProjectiles;
        private final int cycle;
        private final int stepBackTick;

        private boolean active = false;
        private int timer = -1;
        private int heldTicks = 0;
        private int lastAnimation = -1;
        private int lastAttackTick = -1;
        private boolean projectilePending = false;
        private int lastProjectileStartCycle = -1;

        private AttackCountdown(String label, Set<Integer> attackAnimations, Set<Integer> attackProjectiles, int cycle,
                int stepBackTick) {
            this.label = label;
            this.attackAnimations = attackAnimations;
            this.attackProjectiles = attackProjectiles;
            this.cycle = cycle;
            this.stepBackTick = stepBackTick;
        }

        // Records an attack projectile launched this tick (Verzik P2's urnbomb has no
        // attack animation, so its projectile is the only signal). Returns true when
        // the projectile is one of this boss's attacks so the caller stops there.
        private boolean onProjectile(Projectile projectile) {
            if (!active || !attackProjectiles.contains(projectile.getId())) {
                return false;
            }
            // Re-fires every client cycle and launches one per player; the start
            // cycle identifies the volley exactly once.
            if (projectile.getStartCycle() > lastProjectileStartCycle) {
                lastProjectileStartCycle = projectile.getStartCycle();
                projectilePending = true;
            }
            return true;
        }

        private void update(NPC npc) {
            if (npc == null) {
                if (active) {
                    active = false;
                    clear();
                    log.info("{} ended", label);
                }
                return;
            }
            if (!active) {
                active = true;
                clear();
                log.info("{} started", label);
            }

            // Poll the animation each tick so a repeat of the same attack still reads
            // as a fresh start once the boss has returned to idle between attacks.
            int animation = npc.getAnimation();
            boolean animationStarted = animation != -1 && animation != lastAnimation;
            lastAnimation = animation;
            boolean animationAttack = animationStarted && attackAnimations.contains(animation);
            boolean attacked = animationAttack || projectilePending;
            String source = animationAttack ? "animation " + animation : "projectile";
            projectilePending = false;

            // A detection inside the cadence is an echo of the attack just handled -
            // the same swing seen a tick later - so it must not re-arm the countdown.
            int tick = client.getTickCount();
            int sinceLastAttack = lastAttackTick > 0 ? tick - lastAttackTick : -1;
            if (attacked && sinceLastAttack >= 0 && sinceLastAttack < cycle - ATTACK_ECHO_SLACK_TICKS) {
                attacked = false;
            }
            if (attacked) {
                log.info("{} attack: {}, {}t since the last - countdown -> {}",
                        label, source, sinceLastAttack, cycle);
                lastAttackTick = tick;
            }

            if (timer <= 0 || attacked) {
                timer = cycle;
                heldTicks = 0;
            } else if (timer > 1) {
                timer--;
            } else if (++heldTicks >= cycle) {
                // Held on 1 for a full cycle with no attack seen - re-arm so the
                // countdown keeps running instead of sticking on the warning tick.
                timer = cycle;
                heldTicks = 0;
            }
        }

        // Clears the countdown between fights, leaving the active flag to update().
        private void clear() {
            timer = -1;
            heldTicks = 0;
            lastAnimation = -1;
            lastAttackTick = -1;
            projectilePending = false;
            lastProjectileStartCycle = -1;
        }

        private void reset() {
            active = false;
            clear();
        }

        // The value shown on the overlay: the raw countdown rotated so the step-back
        // tick reads 1 (and so takes the highlight colour). The raw countdown is
        // ticks-until-attack (cycle..1); relabelling stepBackTick to 1 turns it into
        // a count down to the step back, still cycling cycle..1. Inactive (<= 0) is
        // passed through unchanged so the overlay keeps hiding the timer.
        private int getDisplayTimer() {
            if (timer <= 0) {
                return timer;
            }
            return ((timer - stepBackTick + cycle) % cycle) + 1;
        }
    }
}
