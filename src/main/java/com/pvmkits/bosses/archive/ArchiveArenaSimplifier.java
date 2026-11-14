package com.pvmkits.bosses.archive;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.core.FloorRepainter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

import javax.inject.Inject;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * "Simplify Arena" for the Archive Guardian: repaints the whole walled room's
 * floor one flat colour so the fight's tile highlights read against a uniform
 * background.
 *
 * <p>The Doom of Mokhaiotl's version paints a fixed octagon sized for its round
 * room, which only reaches about half of this longer one. Rather than guess
 * this room's dimensions, the room is measured from the scene: the walkable
 * floor is flooded outwards from the Guardian (bounded by a box so an open
 * doorway cannot run it down a corridor), and a margin is added around it so
 * the walls and the inert guardians' plinths are painted too.
 */
@Slf4j
public class ArchiveArenaSimplifier {

    // Same pastel brown as the Mokhaiotl arena, for a cleared colour setting.
    private static final Color DEFAULT_FLOOR_COLOR = new Color(166, 138, 108);

    // How far from the Guardian the floor flood may reach on each axis. The
    // inert guardians sit about 12 tiles east/west and 6 north/south of it, so
    // the room fits well inside; this only stops a leak through an open door.
    static final int ROOM_SEARCH_RADIUS = 30;
    // Tiles painted beyond the walkable floor, covering the wall ring and any
    // blocked scenery inside the room.
    static final int WALL_MARGIN = 2;

    private static final int[][] NEIGHBOURS = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

    private final Client client;
    private final PvmKitsConfig config;
    private final FloorRepainter repainter;

    private boolean failed;
    // The Guardian's centre tile the current paint was measured from, kept so the
    // floor stays painted if the Guardian despawns (after the kill) while you are
    // still in the room.
    private WorldPoint arenaCentre;
    private WorldPoint appliedCentre;

    @Inject
    public ArchiveArenaSimplifier(Client client, PvmKitsConfig config) {
        this.client = client;
        this.config = config;
        this.repainter = new FloorRepainter(client, "Archive");
    }

    private Color floorColor() {
        Color configured = config.archiveArenaColor();
        return configured != null ? configured : DEFAULT_FLOOR_COLOR;
    }

    /** Called every tick: paints the room while you are in it, restores otherwise. */
    public void update() {
        if (failed) {
            return;
        }
        if (!config.simplifyArchiveArena() || !FracturedArchiveHandler.isInGuardianRoom(client)) {
            restore();
            return;
        }
        WorldView wv = client.getTopLevelWorldView();
        Scene scene = wv != null ? wv.getScene() : null;
        if (scene == null) {
            return;
        }
        WorldPoint guardian = guardianCentre(wv);
        if (guardian != null) {
            arenaCentre = guardian;
        }
        if (arenaCentre == null) {
            return;
        }
        Color color = floorColor();
        if (repainter.isAppliedTo(scene, color) && arenaCentre.equals(appliedCentre)) {
            return;
        }
        repainter.restore();
        Set<WorldPoint> room = roomTiles(wv, arenaCentre);
        if (room.isEmpty()) {
            return;
        }
        repainter.apply(scene, tile -> isRoomTile(tile, room), color);
        appliedCentre = arenaCentre;
        log.info("[Archive] Simplify Arena measured the room around {}: {} tiles", arenaCentre, room.size());
    }

    public void shutDown() {
        restore();
    }

    // The scene is being rebuilt: the saved floor belongs to tiles that are gone.
    public void onSceneRebuilding() {
        repainter.discard();
        arenaCentre = null;
        appliedCentre = null;
    }

    public void disableAfterError() {
        repainter.discard();
        failed = true;
    }

    private void restore() {
        repainter.restore();
        arenaCentre = null;
        appliedCentre = null;
    }

    private static boolean isRoomTile(Tile tile, Set<WorldPoint> room) {
        WorldPoint loc = tile.getWorldLocation();
        return loc != null && room.contains(loc);
    }

    // The Guardian's centre tile in any of its forms (dormant, flying, downed).
    // Its reported location is the south-west tile of its 5x5 footprint.
    private WorldPoint guardianCentre(WorldView wv) {
        for (NPC npc : wv.npcs()) {
            if (npc == null || !FracturedArchiveHandler.ALL_GUARDIAN_FORM_IDS.contains(npc.getId())) {
                continue;
            }
            WorldPoint sw = npc.getWorldLocation();
            if (sw == null) {
                return null;
            }
            int half = (npc.getComposition() != null ? npc.getComposition().getSize() : 5) / 2;
            return new WorldPoint(sw.getX() + half, sw.getY() + half, sw.getPlane());
        }
        return null;
    }

    /**
     * The room's walkable floor, flooded out from the Guardian (or from you, if
     * its tile is blocked), plus {@link #WALL_MARGIN} tiles around it.
     */
    private Set<WorldPoint> roomTiles(WorldView wv, WorldPoint centre) {
        int[][] flags = collisionFlags(wv);
        LocalPoint centreLocal = LocalPoint.fromWorld(wv, centre);
        if (flags == null || centreLocal == null) {
            return new HashSet<>();
        }
        int startX = centreLocal.getSceneX();
        int startY = centreLocal.getSceneY();
        if (!isWalkable(flags, startX, startY)) {
            Player player = client.getLocalPlayer();
            LocalPoint lp = player != null ? player.getLocalLocation() : null;
            if (lp == null || !isWalkable(flags, lp.getSceneX(), lp.getSceneY())) {
                return new HashSet<>();
            }
            startX = lp.getSceneX();
            startY = lp.getSceneY();
        }

        boolean[][] floor = floodFloor(flags, startX, startY,
                centreLocal.getSceneX(), centreLocal.getSceneY(), ROOM_SEARCH_RADIUS);
        Set<WorldPoint> room = new HashSet<>();
        int plane = wv.getPlane();
        for (int x = 0; x < floor.length; x++) {
            for (int y = 0; y < floor[x].length; y++) {
                if (!floor[x][y]) {
                    continue;
                }
                for (int dx = -WALL_MARGIN; dx <= WALL_MARGIN; dx++) {
                    for (int dy = -WALL_MARGIN; dy <= WALL_MARGIN; dy++) {
                        room.add(WorldPoint.fromScene(wv, x + dx, y + dy, plane));
                    }
                }
            }
        }
        return room;
    }

    /**
     * Floods the walkable floor from a start tile, never further than
     * {@code radius} tiles from the given box centre on either axis.
     */
    static boolean[][] floodFloor(int[][] flags, int startX, int startY,
            int boxX, int boxY, int radius) {
        boolean[][] flooded = new boolean[flags.length][];
        for (int x = 0; x < flags.length; x++) {
            flooded[x] = new boolean[flags[x] == null ? 0 : flags[x].length];
        }
        if (!isWalkable(flags, startX, startY)) {
            return flooded;
        }
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] { startX, startY });
        flooded[startX][startY] = true;
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            for (int[] step : NEIGHBOURS) {
                int nx = at[0] + step[0];
                int ny = at[1] + step[1];
                if (Math.abs(nx - boxX) > radius || Math.abs(ny - boxY) > radius) {
                    continue;
                }
                if (!isWalkable(flags, nx, ny) || flooded[nx][ny]) {
                    continue;
                }
                flooded[nx][ny] = true;
                queue.add(new int[] { nx, ny });
            }
        }
        return flooded;
    }

    static int[][] collisionFlags(WorldView wv) {
        CollisionData[] maps = wv.getCollisionMaps();
        int plane = wv.getPlane();
        if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null) {
            return null;
        }
        return maps[plane].getFlags();
    }

    static boolean isWalkable(int[][] flags, int x, int y) {
        if (x < 0 || x >= flags.length || flags[x] == null || y < 0 || y >= flags[x].length) {
            return false;
        }
        int blocked = CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
                | CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
                | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION;
        return (flags[x][y] & blocked) == 0;
    }
}
