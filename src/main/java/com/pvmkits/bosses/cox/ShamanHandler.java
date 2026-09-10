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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tracks the Lizardman Shaman's acid spit in the Chambers of Xeric shaman room.
 * The shaman spits acid (LIZARDSHAMAN_SPIT_ACID projectile) at a ground tile,
 * which splashes on landing and hits a 5x5 area. The landing tile is read from
 * the projectile so the overlay can telegraph the blast a few ticks early.
 * Detection is by NPC name so it works in both normal and challenge mode.
 */
public class ShamanHandler implements BossHandler {

    @Inject
    private Client client;

    private static final String SHAMAN_NAME_KEYWORD = "lizardman shaman";

    // The shaman's acid spit projectile (LIZARDSHAMAN_SPIT_ACID). It targets a
    // tile, splashes on impact (LIZARDSHAMAN_ACID_SPLASH), and damages a 5x5 area.
    private static final int SPIT_PROJECTILE_ID = 1293;

    // Keep the blast highlighted for a beat past the splash so it stays visible on
    // the landing tick.
    private static final int SPIT_BLAST_HOLD_TICKS = 1;
    private static final int CYCLES_PER_GAME_TICK = 30;

    // Landing tile -> game tick the blast highlight should expire on.
    private final Map<WorldPoint, Integer> spitBlasts = new HashMap<>();

    @Override
    public String getBossName() {
        return "Lizardman Shaman";
    }

    private boolean isShamanNpc(NPC npc) {
        if (npc == null) {
            return false;
        }
        String name = npc.getName();
        return name != null && name.toLowerCase(Locale.ROOT).contains(SHAMAN_NAME_KEYWORD);
    }

    @Override
    public boolean isInBossArea(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (isShamanNpc(npc)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        // Not used; the blast is driven by the spit projectile.
    }

    @Override
    public void onGraphicChanged(GraphicChanged event) {
        // Not used; the blast is driven by the spit projectile.
    }

    @Override
    public void onProjectileMoved(ProjectileMoved event) {
        Projectile projectile = event.getProjectile();
        if (projectile == null || projectile.getId() != SPIT_PROJECTILE_ID) {
            return;
        }

        WorldPoint landingTile = projectile.getTargetPoint();
        if (landingTile == null) {
            return;
        }

        // Recomputed on every move event; keying on the landing tile means repeated
        // events and simultaneous spits from both shamans coalesce naturally.
        int ticksToImpact = Math.max(0,
                Math.round(projectile.getRemainingCycles() / (float) CYCLES_PER_GAME_TICK));
        int expiryTick = client.getTickCount() + ticksToImpact + SPIT_BLAST_HOLD_TICKS;
        spitBlasts.merge(landingTile, expiryTick, Math::max);
    }

    @Override
    public void onGameTick(GameTick event) {
        // Drop spit blasts once their splash window has passed.
        int currentTick = client.getTickCount();
        spitBlasts.entrySet().removeIf(entry -> currentTick > entry.getValue());
    }

    @Override
    public Actor getBossActor(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (isShamanNpc(npc)) {
                return npc;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        spitBlasts.clear();
    }

    // Landing tiles of acid spits whose blast is still a threat.
    public Set<WorldPoint> getSpitBlastTiles() {
        return spitBlasts.keySet();
    }
}
