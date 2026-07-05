package com.pvmkits.bosses.tob;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.core.BossHandler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ProjectileMoved;

import javax.inject.Inject;
import java.awt.Color;
import java.util.*;

/**
 * Handler for the Theatre of Blood. The whole raid is treated as a single boss;
 * each room contributes its own overlay cues:
 * <ul>
 * <li>Maiden - attack timer</li>
 * <li>Bloat - sleep / wake timer</li>
 * <li>Nylocas - role-based spider highlighting</li>
 * <li>Sotetseg - attack timer</li>
 * <li>Xarpus - nothing</li>
 * <li>Verzik P1/P2/P3 - attack timer (per-phase speed) + P3 attack-style
 * colour</li>
 * </ul>
 * NPC/animation IDs are confirmed normal-mode values from RuneLite's NpcID. The
 * boss attack animations are logged so they can be verified live and refined.
 */
@Slf4j
public class TheatreHandler implements BossHandler {

    @Inject
    private Client client;

    @Inject
    private PvmKitsConfig config;

    // --- Room NPC IDs (normal mode) ---
    private static final Set<Integer> MAIDEN_IDS = Set.of(8360, 8361, 8362, 8363, 8364, 8365);
    private static final int BLOAT_ID = 8359;
    private static final Set<Integer> SOTETSEG_IDS = Set.of(8387, 8388);
    private static final Set<Integer> XARPUS_IDS = Set.of(8338, 8339, 8340, 8341);
    private static final Set<Integer> VASILIAS_IDS = Set.of(8354, 8355, 8356, 8357);
    private static final Set<Integer> VERZIK_P1_IDS = Set.of(8369, 8370, 8371);
    private static final Set<Integer> VERZIK_P2_IDS = Set.of(8372, 8373);
    private static final Set<Integer> VERZIK_P3_IDS = Set.of(8374, 8375);

    // Small Nylocas by attack style. Grey/melee = Ischyros, green/range =
    // Toxobolos, blue/mage = Hagios.
    static final Set<Integer> NYLO_MELEE_IDS = Set.of(8263, 8342, 8345, 8348, 8351, 8381);
    static final Set<Integer> NYLO_RANGE_IDS = Set.of(8264, 8343, 8346, 8349, 8352, 8382);
    static final Set<Integer> NYLO_MAGE_IDS = Set.of(8344, 8347, 8350, 8353, 8383);

    // Any ToB boss NPC, used to detect that we're in the raid.
    private static final Set<Integer> ALL_TOB_IDS = new HashSet<>();
    static {
        ALL_TOB_IDS.addAll(MAIDEN_IDS);
        ALL_TOB_IDS.add(BLOAT_ID);
        ALL_TOB_IDS.addAll(SOTETSEG_IDS);
        ALL_TOB_IDS.addAll(XARPUS_IDS);
        ALL_TOB_IDS.addAll(VASILIAS_IDS);
        ALL_TOB_IDS.addAll(VERZIK_P1_IDS);
        ALL_TOB_IDS.addAll(VERZIK_P2_IDS);
        ALL_TOB_IDS.addAll(VERZIK_P3_IDS);
    }

    // --- Attack cycle lengths (game ticks) ---
    private static final int SOTETSEG_CYCLE = 5;
    private static final int VERZIK_P1_CYCLE = 10;
    private static final int VERZIK_P2_CYCLE = 4;
    private static final int VERZIK_P3_CYCLE = 7; // starting cadence; speeds up, re-synced on each attack

    public enum Room {
        NONE, MAIDEN, BLOAT, NYLOCAS, SOTETSEG, XARPUS, VERZIK
    }

    public enum NyloRole {
        OFF, MELEE, RANGE, MAGE
    }

    // Verzik P3 attack style, mirrors the Phosani style colours.
    public enum VerzikStyle {
        UNKNOWN(Color.GRAY), MELEE(new Color(240, 100, 100, 120)), RANGE(new Color(144, 238, 144)), MAGE(
                new Color(100, 149, 237));

        private final Color color;

        VerzikStyle(Color color) {
            this.color = color;
        }

        public Color getColor() {
            return color;
        }
    }

    private Room currentRoom = Room.NONE;
    private int verzikPhase = 0; // 1, 2 or 3 when in the Verzik room

    // Single boss attack countdown shown over the current room's boss.
    private int bossAttackTimer = -1;
    // Phosani-style sync: hold at 1 until the attack lands, then reset. Tracks the
    // current boss's last animation to detect that attack, with a backstop so a
    // missed anim still re-arms the timer.
    private int lastBossAttackAnim = -1;
    private int bossTimerHeldTicks = 0;
    // Verzik P3 speeds up (enrage) near the end of the kill, so her cadence is
    // measured live from the interval between attacks rather than fixed at 7.
    private int verzikP3Cycle = VERZIK_P3_CYCLE;
    private int lastBossAttackTick = -1;

    // Bloat sleep tracking: true while down/asleep. Timer counts the room's known
    // state (ticks until wake while asleep, ticks until sleep while awake).
    private boolean bloatAsleep = false;
    private int bloatStateTimer = -1;
    // Bloat is down ~9.6s (16 ticks) and walks ~9 ticks between naps (refined live
    // off observed transitions).
    private static final int BLOAT_DOWN_TICKS = 16;
    private static final int BLOAT_UP_TICKS = 9;

    private VerzikStyle verzikStyle = VerzikStyle.UNKNOWN;

    // Verzik P3 attack-style projectiles (best-effort; logged for confirmation).
    private static final int VERZIK_P3_RANGE_PROJECTILE = 1583;
    private static final int VERZIK_P3_MAGE_PROJECTILE = 1585;

    // Animation logging dedupe per boss index.
    private final Map<Integer, Integer> lastLoggedAnimations = new HashMap<>();
    private int lastBloatAnimation = -1;

    // Bloat safe-tile assist. Tiles hidden from Bloat's line of sight (behind the
    // pillar) with no falling hand are safe to stand on while he is awake.
    private static final int BLOAT_LOS_RADIUS = 9;
    private final List<WorldPoint> bloatSafeTiles = new ArrayList<>();
    private final List<WorldPoint> bloatHandTiles = new ArrayList<>();

    @Override
    public String getBossName() {
        return "Theatre of Blood";
    }

    @Override
    public boolean isInBossArea(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && ALL_TOB_IDS.contains(npc.getId())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        // Animation polling handled in onGameTick.
    }

    @Override
    public void onGraphicChanged(GraphicChanged event) {
        // Not used; style detection uses projectiles + animation.
    }

    @Override
    public void onProjectileMoved(ProjectileMoved event) {
        if (currentRoom != Room.VERZIK || verzikPhase != 3) {
            return;
        }
        int id = event.getProjectile().getId();
        if (id == VERZIK_P3_RANGE_PROJECTILE) {
            setVerzikStyle(VerzikStyle.RANGE, "projectile " + id);
        } else if (id == VERZIK_P3_MAGE_PROJECTILE) {
            setVerzikStyle(VerzikStyle.MAGE, "projectile " + id);
        }
    }

    private void setVerzikStyle(VerzikStyle style, String source) {
        if (verzikStyle != style) {
            verzikStyle = style;
            log.info("Verzik P3 style -> " + style + " via " + source);
        }
    }

    @Override
    public void onGameTick(GameTick event) {
        if (client.getGameState().getState() < 30) {
            return;
        }

        Room room = detectRoom();
        if (room != currentRoom) {
            log.info("ToB room: {} -> {}", currentRoom, room);
            currentRoom = room;
            bossAttackTimer = -1;
            bossTimerHeldTicks = 0;
            lastBossAttackAnim = -1;
            lastBossAttackTick = -1;
            verzikP3Cycle = VERZIK_P3_CYCLE;
            bloatAsleep = false;
            bloatStateTimer = -1;
            verzikStyle = VerzikStyle.UNKNOWN;
            bloatSafeTiles.clear();
            bloatHandTiles.clear();
        }

        switch (room) {
            case MAIDEN:
                bossAttackTimer = -1;
                break;
            case SOTETSEG:
                tickBossTimer(SOTETSEG_CYCLE);
                break;
            case NYLOCAS:
                bossAttackTimer = -1;
                break;
            case VERZIK:
                updateVerzik();
                break;
            case BLOAT:
                updateBloat();
                break;
            default:
                bossAttackTimer = -1;
                break;
        }
    }

    private Room detectRoom() {
        if (findBoss(MAIDEN_IDS) != null) {
            return Room.MAIDEN;
        }
        if (findBoss(Set.of(BLOAT_ID)) != null) {
            return Room.BLOAT;
        }
        if (findBoss(VASILIAS_IDS) != null || findAnyNylo() != null) {
            return Room.NYLOCAS;
        }
        if (findBoss(SOTETSEG_IDS) != null) {
            return Room.SOTETSEG;
        }
        if (findBoss(XARPUS_IDS) != null) {
            return Room.XARPUS;
        }
        if (findBoss(VERZIK_P1_IDS) != null || findBoss(VERZIK_P2_IDS) != null || findBoss(VERZIK_P3_IDS) != null) {
            return Room.VERZIK;
        }
        return Room.NONE;
    }

    // Generic free-running attack countdown that re-arms each cycle. Logs the
    // boss's animations so attack timings can be verified live.
    // Phosani-style attack countdown: count down to 1, hold at 1 (the warning
    // tick), and reset to the cycle the moment the boss attacks - so the hit lands
    // on the reset number (4/5/6 depending on speed). A backstop reset keeps it
    // moving if the attack animation is missed.
    private void tickBossTimer(int cycle) {
        NPC boss = getCurrentBoss();
        boolean attacked = false;
        if (boss != null) {
            logAnim(boss);
            int anim = boss.getAnimation();
            if (anim != -1 && anim != lastBossAttackAnim) {
                attacked = true; // animation changed to an attack pose -> re-sync
            }
            lastBossAttackAnim = anim;
        }

        if (attacked) {
            // Measure Verzik P3's live cadence so the enrage speed-up is tracked.
            if (currentRoom == Room.VERZIK && verzikPhase == 3 && lastBossAttackTick > 0) {
                int interval = client.getTickCount() - lastBossAttackTick;
                if (interval >= 3 && interval <= VERZIK_P3_CYCLE) {
                    if (interval != verzikP3Cycle) {
                        log.info("Verzik P3 cadence -> {}t", interval);
                    }
                    verzikP3Cycle = interval;
                }
            }
            lastBossAttackTick = client.getTickCount();
        }

        if (bossAttackTimer <= 0) {
            bossAttackTimer = cycle;
            bossTimerHeldTicks = 0;
        } else if (attacked) {
            bossAttackTimer = cycle; // attack landed; re-arm on the cycle number
            bossTimerHeldTicks = 0;
        } else if (bossAttackTimer > 1) {
            bossAttackTimer--;
        } else {
            // Hold at 1 (red warning) until the attack lands; back off after a full
            // cycle in case the animation was missed.
            if (++bossTimerHeldTicks >= cycle) {
                bossAttackTimer = cycle;
                bossTimerHeldTicks = 0;
            }
        }
    }

    private void updateVerzik() {
        int newPhase = 3;
        if (findBoss(VERZIK_P1_IDS) != null) {
            newPhase = 1;
        } else if (findBoss(VERZIK_P2_IDS) != null) {
            newPhase = 2;
        }
        if (newPhase != verzikPhase) {
            verzikPhase = newPhase;
            bossAttackTimer = -1;
            bossTimerHeldTicks = 0;
            lastBossAttackAnim = -1;
            lastBossAttackTick = -1;
            verzikP3Cycle = VERZIK_P3_CYCLE;
            verzikStyle = VerzikStyle.UNKNOWN;
            log.info("Verzik phase -> P{}", verzikPhase);
        }

        int cycle = verzikPhase == 1 ? VERZIK_P1_CYCLE : (verzikPhase == 2 ? VERZIK_P2_CYCLE : verzikP3Cycle);
        tickBossTimer(cycle);
    }

    private void updateBloat() {
        NPC bloat = findBoss(Set.of(BLOAT_ID));
        if (bloat == null) {
            return;
        }
        int anim = bloat.getAnimation();
        if (anim != lastBloatAnimation) {
            log.info("Bloat animation: {}", anim);
            lastBloatAnimation = anim;
        }
        // Bloat is "asleep" (vulnerable, no walk animation) when idle; awake while
        // walking. -1 idle is treated as down.
        boolean asleepNow = anim == -1;
        if (asleepNow != bloatAsleep) {
            bloatAsleep = asleepNow;
            bloatStateTimer = bloatAsleep ? BLOAT_DOWN_TICKS : BLOAT_UP_TICKS;
        } else if (bloatStateTimer > 0) {
            bloatStateTimer--;
        } else {
            bloatStateTimer = bloatAsleep ? BLOAT_DOWN_TICKS : BLOAT_UP_TICKS;
        }

        computeBloatSafeTiles(bloat);
    }

    // Falling-hand danger tiles are tracked as graphics objects on the floor.
    private void collectBloatHandTiles() {
        bloatHandTiles.clear();
        for (GraphicsObject go : client.getGraphicsObjects()) {
            if (go == null) {
                continue;
            }
            LocalPoint lp = go.getLocation();
            if (lp == null) {
                continue;
            }
            bloatHandTiles.add(WorldPoint.fromLocal(client, lp));
        }
    }

    // A safe tile is reachable, hidden from Bloat's line of sight (pillar-blocked)
    // and free of a falling hand. Only tiles directly bordering the pillar are
    // kept - that wall-hugging row is where you hide as he circles. Bloat is awake
    // while walking, so only assist then; while he is down the whole room is fair
    // game to attack.
    private void computeBloatSafeTiles(NPC bloat) {
        collectBloatHandTiles();
        bloatSafeTiles.clear();
        if (bloatAsleep) {
            return;
        }
        WorldView wv = client.getTopLevelWorldView();
        WorldArea bloatArea = bloat.getWorldArea();
        WorldPoint base = bloat.getWorldLocation();
        if (bloatArea == null || base == null) {
            return;
        }
        Set<WorldPoint> hands = new HashSet<>(bloatHandTiles);
        for (int dx = -BLOAT_LOS_RADIUS; dx <= BLOAT_LOS_RADIUS; dx++) {
            for (int dy = -BLOAT_LOS_RADIUS; dy <= BLOAT_LOS_RADIUS; dy++) {
                WorldPoint wp = new WorldPoint(base.getX() + dx, base.getY() + dy, base.getPlane());
                if (isBlockedTile(wv, wp) || hands.contains(wp)) {
                    continue;
                }
                // Keep only tiles hugging the pillar wall, never open-floor tiles.
                if (!bordersPillar(wv, wp)) {
                    continue;
                }
                if (!bloatArea.hasLineOfSightTo(wv, new WorldArea(wp, 1, 1))) {
                    bloatSafeTiles.add(wp);
                }
            }
        }
    }

    // True if any of the 8 neighbours is a blocked pillar tile.
    private boolean bordersPillar(WorldView wv, WorldPoint wp) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                if (isBlockedTile(wv, new WorldPoint(wp.getX() + dx, wp.getY() + dy, wp.getPlane()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isBlockedTile(WorldView wv, WorldPoint wp) {
        CollisionData[] maps = wv.getCollisionMaps();
        if (maps == null) {
            return false;
        }
        int plane = wp.getPlane();
        if (plane < 0 || plane >= maps.length || maps[plane] == null) {
            return false;
        }
        int sceneX = wp.getX() - wv.getBaseX();
        int sceneY = wp.getY() - wv.getBaseY();
        if (sceneX < 0 || sceneY < 0 || sceneX >= 104 || sceneY >= 104) {
            return true;
        }
        int flag = maps[plane].getFlags()[sceneX][sceneY];
        return (flag & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0;
    }

    private void logAnim(NPC npc) {
        int anim = npc.getAnimation();
        if (anim != -1) {
            Integer last = lastLoggedAnimations.get(npc.getIndex());
            if (last == null || last != anim) {
                log.info("ToB boss {} (idx {}) animation: {}", npc.getId(), npc.getIndex(), anim);
                lastLoggedAnimations.put(npc.getIndex(), anim);
            }
        }
    }

    private NPC findBoss(Set<Integer> ids) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && ids.contains(npc.getId())) {
                return npc;
            }
        }
        return null;
    }

    private NPC findAnyNylo() {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && (NYLO_MELEE_IDS.contains(npc.getId()) || NYLO_RANGE_IDS.contains(npc.getId())
                    || NYLO_MAGE_IDS.contains(npc.getId()))) {
                return npc;
            }
        }
        return null;
    }

    private NPC getCurrentBoss() {
        switch (currentRoom) {
            case MAIDEN:
                return findBoss(MAIDEN_IDS);
            case SOTETSEG:
                return findBoss(SOTETSEG_IDS);
            case NYLOCAS:
                return findBoss(VASILIAS_IDS);
            case VERZIK:
                NPC v = findBoss(VERZIK_P1_IDS);
                if (v == null) {
                    v = findBoss(VERZIK_P2_IDS);
                }
                if (v == null) {
                    v = findBoss(VERZIK_P3_IDS);
                }
                return v;
            default:
                return null;
        }
    }

    @Override
    public Actor getBossActor(Client client) {
        return getCurrentBoss();
    }

    @Override
    public void reset() {
        currentRoom = Room.NONE;
        verzikPhase = 0;
        bossAttackTimer = -1;
        bossTimerHeldTicks = 0;
        lastBossAttackAnim = -1;
        lastBossAttackTick = -1;
        verzikP3Cycle = VERZIK_P3_CYCLE;
        bloatAsleep = false;
        bloatStateTimer = -1;
        verzikStyle = VerzikStyle.UNKNOWN;
        lastLoggedAnimations.clear();
        lastBloatAnimation = -1;
        bloatSafeTiles.clear();
        bloatHandTiles.clear();
    }

    // --- Accessors for the overlay ---
    public Room getCurrentRoom() {
        return currentRoom;
    }

    public int getVerzikPhase() {
        return verzikPhase;
    }

    public int getBossAttackTimer() {
        return bossAttackTimer;
    }

    public boolean isBloatAsleep() {
        return bloatAsleep;
    }

    public int getBloatStateTimer() {
        return bloatStateTimer;
    }

    public List<WorldPoint> getBloatSafeTiles() {
        return bloatSafeTiles;
    }

    public VerzikStyle getVerzikStyle() {
        return verzikStyle;
    }

    public NyloRole getNyloRole() {
        return config.tobNyloRole();
    }

    public NPC getBoss() {
        return getCurrentBoss();
    }
}
