package com.pvmkits.bosses.cox;

import com.pvmkits.core.BossHandler;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
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
 * Tracks the Great Olm's incoming attack style at the local player so the overlay
 * can show which prayer to use. Each Olm auto is independently mage or range per
 * player, driven by the projectile aimed at that player, so the style is read
 * straight off the projectile. Detection is by NPC name so it works in both
 * normal and challenge mode.
 */
public class OlmHandler implements BossHandler {

    @Inject
    private Client client;

    // Matches the head ("Great Olm") and both claws while avoiding unrelated names.
    private static final String OLM_NAME_KEYWORD = "great olm";

    // Olm's auto-attack projectiles aimed at a player, per RuneLite SpotanimID. Mage =
    // 1339 (firebreath) + 1341 (weak mage); range = 1340 (generic) + 1343 (weak range).
    // The "weak" variants are the final head-phase autos, so both must be recognised or
    // the prayer overlay gets stuck when the head phase stops using the standard autos.
    private static final Set<Integer> MAGE_PROJECTILE_IDS = Set.of(1339, 1341);
    private static final Set<Integer> RANGE_PROJECTILE_IDS = Set.of(1340, 1343);

    // Crystal bomb (OLM_CRYSTAL_BOMB_TRAVEL) arcs to a ground tile and detonates a
    // few ticks after landing, hitting a 5x5 area. Highlight it from launch until
    // shortly past the explosion so players can clear the blast.
    private static final int CRYSTAL_BOMB_PROJECTILE_ID = 1352;
    private static final int CRYSTAL_BOMB_BLAST_HOLD_TICKS = 4;
    private static final int CYCLES_PER_GAME_TICK = 30;

    private OlmStyle currentStyle = OlmStyle.UNKNOWN;

    // Landing tile -> game tick the blast highlight should expire on.
    private final Map<WorldPoint, Integer> crystalBombBlasts = new HashMap<>();

    public enum OlmStyle {
        MAGE, RANGE, UNKNOWN
    }

    @Override
    public String getBossName() {
        return "Great Olm";
    }

    private boolean isOlmNpc(NPC npc) {
        if (npc == null) {
            return false;
        }
        String name = npc.getName();
        return name != null && name.toLowerCase(Locale.ROOT).contains(OLM_NAME_KEYWORD);
    }

    @Override
    public boolean isInBossArea(Client client) {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (isOlmNpc(npc)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAnimationChanged(AnimationChanged event) {
        // Not used; style is driven by projectiles.
    }

    @Override
    public void onGraphicChanged(GraphicChanged event) {
        // Not used; style is driven by projectiles.
    }

    @Override
    public void onProjectileMoved(ProjectileMoved event) {
        Projectile projectile = event.getProjectile();
        if (projectile == null) {
            return;
        }

        int id = projectile.getId();

        // The crystal bomb targets a tile rather than the player, so track its blast
        // area before the player-targeted style check below would filter it out.
        if (id == CRYSTAL_BOMB_PROJECTILE_ID) {
            trackCrystalBomb(projectile);
            return;
        }

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || projectile.getTargetActor() != localPlayer) {
            return;
        }

        if (MAGE_PROJECTILE_IDS.contains(id)) {
            currentStyle = OlmStyle.MAGE;
        } else if (RANGE_PROJECTILE_IDS.contains(id)) {
            currentStyle = OlmStyle.RANGE;
        }
    }

    // Records the tile a crystal bomb will land on and how long to keep its blast
    // highlighted. Recomputed on every move event; keying on the landing tile means
    // repeated events and simultaneous bombs coalesce naturally.
    private void trackCrystalBomb(Projectile projectile) {
        WorldPoint landingTile = projectile.getTargetPoint();
        if (landingTile == null) {
            return;
        }

        int ticksToImpact = Math.max(0,
                Math.round(projectile.getRemainingCycles() / (float) CYCLES_PER_GAME_TICK));
        int expiryTick = client.getTickCount() + ticksToImpact + CRYSTAL_BOMB_BLAST_HOLD_TICKS;
        crystalBombBlasts.merge(landingTile, expiryTick, Math::max);
    }

    @Override
    public void onGameTick(GameTick event) {
        // Drop crystal bomb blasts once their explosion window has passed.
        int currentTick = client.getTickCount();
        crystalBombBlasts.entrySet().removeIf(entry -> currentTick > entry.getValue());
    }

    @Override
    public Actor getBossActor(Client client) {
        NPC fallback = null;
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (isOlmNpc(npc)) {
                String name = npc.getName();
                if (name != null && name.equalsIgnoreCase("Great Olm")) {
                    return npc;
                }
                fallback = npc;
            }
        }
        return fallback;
    }

    @Override
    public void reset() {
        currentStyle = OlmStyle.UNKNOWN;
        crystalBombBlasts.clear();
    }

    public OlmStyle getOlmStyle() {
        return currentStyle;
    }

    // Landing tiles of crystal bombs whose blast is still a threat.
    public Set<WorldPoint> getCrystalBombBlastTiles() {
        return crystalBombBlasts.keySet();
    }
}
