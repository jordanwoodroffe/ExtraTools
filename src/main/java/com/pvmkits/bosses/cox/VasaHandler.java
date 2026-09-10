package com.pvmkits.bosses.cox;

import com.pvmkits.core.BossHandler;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.ProjectileMoved;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tracks Vasa Nistirio's standard ranged boulder attack in the Chambers of Xeric
 * Vasa room. Vasa throws boulders that arc to a ground tile and hit an area on
 * landing. The landing tile is read from the projectile so the overlay can
 * telegraph the blast a few ticks early. Detection is by NPC id so it works in
 * both normal and challenge mode.
 */
public class VasaHandler implements BossHandler {

    @Inject
    private Client client;

    // Vasa's NPC ids (RuneLite gameval NpcID): dormant rock pile, walking (active) and
    // healing at a crystal. Excludes the glowing crystal (7568) and the pet.
    private static final Set<Integer> VASA_NPC_IDS = Set.of(7565, 7566, 7567);

    // Vasa's thrown boulder (RuneLite gameval SpotanimID RAIDS_VASANISTIRIO_RANGE_TRAVEL):
    // the ranged boulder auto that arcs to a tile and hits an area. The magic spotanim
    // 1327 is her teleport/proximity special blast, not a thrown boulder, so it is
    // excluded. The impact spotanims 1328/1330 are the on-landing splash, not tracked.
    private static final int RANGE_BOULDER_PROJECTILE_ID = 1329;

    // Keep the blast highlighted for a beat past impact so it stays visible on the
    // landing tick.
    private static final int BOULDER_BLAST_HOLD_TICKS = 1;
    private static final int CYCLES_PER_GAME_TICK = 30;

    // Landing tile -> game tick the blast highlight should expire on.
    private final Map<WorldPoint, Integer> boulderBlasts = new HashMap<>();

    @Override
    public String getBossName() {
        return "Vasa Nistirio";
    }

    private boolean isVasaNpc(NPC npc) {
        return npc != null && VASA_NPC_IDS.contains(npc.getId());
    }

    @Override
    public boolean isInBossArea(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (isVasaNpc(npc)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        // Not used; the blast is driven by the boulder projectiles.
    }

    @Override
    public void onGraphicChanged(GraphicChanged event) {
        // Not used; the blast is driven by the boulder projectiles.
    }

    @Override
    public void onProjectileMoved(ProjectileMoved event) {
        Projectile projectile = event.getProjectile();
        if (projectile == null || projectile.getId() != RANGE_BOULDER_PROJECTILE_ID) {
            return;
        }

        WorldPoint landingTile = projectile.getTargetPoint();
        if (landingTile == null) {
            return;
        }

        // Recomputed on every move event; keying on the landing tile means repeated
        // events and simultaneous boulders coalesce naturally.
        int ticksToImpact = Math.max(0,
                Math.round(projectile.getRemainingCycles() / (float) CYCLES_PER_GAME_TICK));
        int expiryTick = client.getTickCount() + ticksToImpact + BOULDER_BLAST_HOLD_TICKS;
        boulderBlasts.merge(landingTile, expiryTick, Math::max);
    }

    @Override
    public void onGameTick(GameTick event) {
        // Drop boulder blasts once their impact window has passed.
        int currentTick = client.getTickCount();
        boulderBlasts.entrySet().removeIf(entry -> currentTick > entry.getValue());
    }

    @Override
    public Actor getBossActor(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (isVasaNpc(npc)) {
                return npc;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        boulderBlasts.clear();
    }

    // Landing tiles of boulders whose blast is still a threat.
    public Set<WorldPoint> getBoulderBlastTiles() {
        return boulderBlasts.keySet();
    }
}
