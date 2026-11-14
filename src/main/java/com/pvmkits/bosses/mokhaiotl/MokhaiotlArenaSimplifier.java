package com.pvmkits.bosses.mokhaiotl;

import com.pvmkits.PvmKitsConfig;
import com.pvmkits.bosses.archive.FracturedArchiveHandler;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.hooks.DrawCallbacks;

import javax.inject.Inject;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Simplify Arena" for the Doom of Mokhaiotl: repaints the delve arena's floor
 * a single flat configurable colour so the fight's tile highlights (dash path,
 * slam area, boulder shatter tiles) read against a uniform background instead of
 * the arena's own patterned floor.
 *
 * <p>The client draws a tile's ground in one of two ways and both are handled:
 * <ul>
 * <li>a flat tile carries a {@link SceneTilePaint} with one colour per corner,
 * which the renderer interpolates across the square;</li>
 * <li>a sloped or diagonally-split tile carries a {@link SceneTileModel} with a
 * colour per triangle vertex instead.</li>
 * </ul>
 *
 * <p><b>Why these are mutated in place rather than replaced.</b> The obvious
 * approach - wrapping the existing paint in an implementation that returns the
 * new colour - does not work. {@code Tile.setSceneTilePaint} is declared
 * against the API interface, but the client's implementation casts the argument
 * straight to its own concrete class, so handing it a third-party
 * implementation throws {@link ClassCastException}. Nothing in RuneLite ever
 * constructs one of these either. The only workable route is to write through
 * the setters the client's own object already exposes, which is what this does.
 *
 * <p>Because the objects themselves are mutated, the original colours are saved
 * as <i>values</i> and written back on restore. Tile models are additionally
 * deduplicated by identity: the client shares one colour array between every
 * tile that happens to have identical shading, so the same array can be reached
 * through many tiles and must only be recoloured (and saved) once.
 */
@Slf4j
public class MokhaiotlArenaSimplifier {

    // Fallback for a cleared or unset colour setting: the pastel brown this
    // used to be hard-coded to. A desaturated mid brown - dark enough that the
    // pastel overlay fills (greens, reds, oranges) all stay legible on top of
    // it, light enough that the arena does not read as a pit.
    private static final Color DEFAULT_FLOOR_COLOR = new Color(166, 138, 108);

    // Sentinel for "no texture". The client treats -1 as untextured and falls
    // back to the vertex colours, which is exactly the flat look wanted here.
    private static final int NO_TEXTURE = -1;

    // The GPU renderers bucket the scene into 8x8-tile zones and cache each
    // one's uploaded geometry separately, so invalidation is per zone. The grid
    // covers the *extended* scene (184 tiles, 23 zones), not the standard 104.
    // Invalidating only the inner 13 leaves the outer zones drawing their
    // cached original floor, which is what made the recolour cover roughly half
    // the arena.
    private static final int ZONE_SIZE = 8;
    private static final int ZONE_GRID = Constants.EXTENDED_SCENE_SIZE / ZONE_SIZE;

    // The arena is a walled, roughly circular room centred on the arena-centre
    // object, bounded here as an octagon: within ARENA_RADIUS of the centre on
    // each axis, with the corners cut by a diagonal limit on |dx|+|dy|.
    //
    // The room is wider than the 25x25 the fight's other maths uses. That 25x25
    // is the *standable floor*; the visible room also takes in its corners, the
    // wall ring and the entrance alcove, all of which need painting or the
    // recolour reads as a circle sitting inside a larger room. Measured against
    // the real arena by walking the edges.
    private static final int ARENA_RADIUS = 14;
    // The diagonal cut, left at 24 while the radius came down from 16 to 14.
    // Shrinking it to match would pull the corners back in, and the corners are
    // the part that had to be covered - so this now only trims the four
    // cardinal edges, which is where the overhang was.
    private static final int ARENA_CHAMFER = 24;

    // ---- Boss-independent room search ----
    // How far from the player the floor flood is allowed to reach. Comfortably
    // more than the room, so the whole floor is found from anywhere inside it,
    // without crawling the rest of the instance.
    private static final int ROOM_SEARCH_RADIUS = 40;
    // Hard cap on the flood, so an unexpectedly open area cannot turn into a
    // long walk over the scene.
    private static final int MAX_ROOM_TILES = 4000;
    // How far the winning tile must be from the nearest wall for the flood to
    // count as the arena. The room is ~33 across, so its middle sits well over
    // 10 tiles from any wall, while the entrance corridor never does - this is
    // what stops a centre being taken while the player is still outside.
    private static final int MIN_ROOM_CLEARANCE = 10;
    // Ticks between room searches while no centre is known. The search only
    // runs when there is no object and no boss to key off, and it stops for
    // good as soon as it succeeds.
    private static final int ROOM_SEARCH_INTERVAL_TICKS = 5;

    private static final int[][] NEIGHBOURS = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

    // Ground object that sits on the exact arena centre every delve, underneath
    // where the boss spawns. This is the anchor the repaint is centred on, and
    // it is in the scene from the moment the arena loads, so the floor can be
    // flattened before the boss has appeared.
    private static final int ARENA_CENTRE_OBJECT_ID = 57285;

    private final Client client;
    private final PvmKitsConfig config;

    // The client's packed-HSL form of the colour the current repaint was done
    // in, so a change to the setting is noticed and the floor repainted rather
    // than left in the old colour until the next descent.
    private int appliedHsl;

    // Set while the floor is currently overwritten, so the work is done once on
    // entering the arena rather than every frame, and so a restore is only
    // attempted when there is something to restore.
    private boolean applied;

    // Latched once the effect has thrown, so a fault that repeats every tick
    // does not spam the log or keep half-painting the scene.
    private boolean failed;

    // The scene the paint was applied to. The client hands out a fresh Scene on
    // a rebuild, and a stale one must never be written back to.
    private Scene appliedScene;

    // The centre the current repaint is bounded around, so a delve descent -
    // which drops the player into a new room, often at different coordinates -
    // is noticed and repainted rather than left with the previous room's patch.
    private WorldPoint appliedCentre;

    // The arena centre, once known. Set from the centre object's spawn event
    // and cleared when it despawns or the scene is rebuilt.
    private WorldPoint arenaCentre;

    // The scene the full centre scan has already been run against. Scanning the
    // extended scene is ~135k tiles, so it runs at most once per scene (as the
    // fallback for enabling the plugin mid-delve, when the spawn event is long
    // past) rather than every tick the setting is on but we are not in a delve.
    private Scene scannedScene;

    // Throttle for the floor-walking room search.
    private int ticksSinceRoomSearch;

    // Everything that was overwritten, in the order it was touched, so it can
    // all be put back exactly as it was.
    private final List<SavedPaint> savedPaints = new ArrayList<>();
    private final List<SavedModel> savedModels = new ArrayList<>();

    @Inject
    public MokhaiotlArenaSimplifier(Client client, PvmKitsConfig config) {
        this.client = client;
        this.config = config;
    }

    // The configured floor colour, falling back to the original pastel brown if
    // the setting has been cleared.
    private Color floorColor() {
        Color configured = config.mokhaiotlArenaColor();
        return configured != null ? configured : DEFAULT_FLOOR_COLOR;
    }

    /**
     * Drives the effect. Called every tick; applies the flat floor as soon as
     * the arena room is in the scene and restores it once it is not, or as soon
     * as the setting is switched off.
     *
     * <p>Deliberately not gated on the boss being present. The fight's other
     * overlays key off the boss NPC, but the floor is part of the room rather
     * than the fight, so waiting for the spawn would leave it switching colour
     * underfoot part-way through the delve. {@link #apply()} finds the arena by
     * its centre object and does nothing while that is absent, so this stays
     * off everywhere outside the delve without needing a boss check.
     */
    public void update() {
        if (failed) {
            return;
        }
        // The Archive Guardian's room is a walled instance too, so the floor
        // search below would take it for this arena and paint half of it. It
        // has its own simplifier (ArchiveArenaSimplifier) sized for that room.
        if (!config.simplifyMokhaiotlArena() || FracturedArchiveHandler.isInGuardianRoom(client)) {
            restore();
            return;
        }
        Scene scene = currentScene();
        if (scene == null) {
            return;
        }
        WorldPoint centre = resolveArenaCentre(scene);
        if (centre == null) {
            // Not in the arena - either never was, or we have just left it.
            restore();
            return;
        }
        if (applied && scene == appliedScene && centre.equals(appliedCentre)
                && packHsl(floorColor()) == appliedHsl) {
            return;
        }
        if (applied) {
            // The scene was rebuilt, the room moved under us (a delve descent)
            // or the colour setting changed. Put the old floor back before
            // painting the new one, so nothing is left half-painted.
            restore();
        }
        apply(scene, centre);
    }

    /**
     * Restores the floor unconditionally, for plugin shutdown, so the arena is
     * never left recoloured behind us.
     */
    public void shutDown() {
        restore();
    }

    /**
     * Drops the saved floor without writing it back, for when the client is
     * rebuilding the scene (a delve descent, a hop, a relog). The tiles those
     * originals came from are being thrown away, and the rebuilt scene brings
     * its own untouched floor, so restoring into it would paint the wrong
     * shading onto whatever now occupies those coordinates. The next tick
     * inside the arena re-applies the flat floor to the new scene.
     */
    public void onSceneRebuilding() {
        discardSavedFloor();
        arenaCentre = null;
        scannedScene = null;
        // Search on the very first tick in the new room rather than waiting out
        // the throttle, so the floor is already flat as it comes into view.
        ticksSinceRoomSearch = ROOM_SEARCH_INTERVAL_TICKS;
    }

    /**
     * Watches for the arena-centre object so the room can be recognised the
     * moment it loads, without scanning the scene every tick.
     */
    public void onGameObjectSpawned(GameObjectSpawned event) {
        GameObject object = event.getGameObject();
        if (object != null && object.getId() == ARENA_CENTRE_OBJECT_ID) {
            WorldPoint centre = footprintCentre(object);
            if (centre != null) {
                arenaCentre = centre;
                log.info("[Mokhaiotl] Simplify Arena saw the arena centre object spawn, "
                        + "footprint centre {}", arenaCentre);
            }
        }
    }

    /**
     * Deliberately does not forget the arena when the centre object despawns.
     * The object comes and goes during a delve, and dropping the centre with it
     * would restore the floor for a tick and then repaint it - a visible flash
     * mid-fight. Leaving the room rebuilds the scene, and
     * {@link #onSceneRebuilding()} is what ends the effect.
     */
    public void onGameObjectDespawned(GameObjectDespawned event) {
        // Intentionally empty; kept so the plugin's forwarding reads the same
        // as every other handler's and the reasoning above is not lost.
    }

    /**
     * Gives up on the effect for the rest of the session after it threw. The
     * floor is left as-is rather than restored: whatever went wrong happened
     * part-way through, so the saved originals may be incomplete, and walking
     * them back could make a half-painted arena worse rather than better.
     */
    public void disableAfterError() {
        discardSavedFloor();
        failed = true;
    }

    // ------------------------------------------------------------------
    // Apply / restore
    // ------------------------------------------------------------------

    private void apply(Scene scene, WorldPoint centre) {
        Tile[][][] tiles = extendedTiles(scene);
        if (tiles == null) {
            return;
        }
        Color color = floorColor();
        int hsl = packHsl(color);
        // Colour arrays are shared between identically shaded tiles, so the
        // same array is reached over and over. Recolour each one once.
        Map<int[], Boolean> seenColorArrays = new IdentityHashMap<>();
        int paintCount = 0;
        int modelCount = 0;
        for (Tile[][] plane : tiles) {
            if (plane == null) {
                continue;
            }
            for (Tile[] column : plane) {
                if (column == null) {
                    continue;
                }
                for (Tile tile : column) {
                    if (tile == null || !isArenaTile(tile, centre)) {
                        continue;
                    }
                    if (flattenPaint(tile, hsl)) {
                        paintCount++;
                    }
                    if (flattenModel(tile, seenColorArrays, hsl)) {
                        modelCount++;
                    }
                }
            }
        }
        applied = true;
        appliedScene = scene;
        appliedCentre = centre;
        appliedHsl = hsl;
        invalidateZones(scene);
        // Logged unconditionally: this is the one line that says whether the
        // repaint actually reached any tiles, and a count of zero is the whole
        // diagnosis when the floor comes out unchanged.
        log.info("[Mokhaiotl] Simplify Arena applied around {}: {} tile paints and {} tile model "
                + "arrays repainted to rgb({},{},{}) (packed hsl {})",
                centre, paintCount, modelCount, color.getRed(), color.getGreen(),
                color.getBlue(), hsl);
    }

    // True if the tile is inside the walled arena room: the 25x25 box around
    // the centre with its corners chamfered off, which is the octagon the game
    // draws as a round room. Tiles on other planes are never part of it.
    private boolean isArenaTile(Tile tile, WorldPoint centre) {
        WorldPoint loc = tile.getWorldLocation();
        if (loc == null || loc.getPlane() != centre.getPlane()) {
            return false;
        }
        return isInArena(loc.getX() - centre.getX(), loc.getY() - centre.getY());
    }

    // The arena's octagon, as offsets from its centre tile.
    static boolean isInArena(int dx, int dy) {
        int ax = Math.abs(dx);
        int ay = Math.abs(dy);
        return ax <= ARENA_RADIUS && ay <= ARENA_RADIUS && ax + ay <= ARENA_CHAMFER;
    }

    /**
     * Locates the centre of the arena room, which is what the repaint is
     * bounded around.
     *
     * <p>The anchor is the arena-centre ground object, which is in the scene
     * from the moment the room loads - so the floor can be flattened on the way
     * in rather than only once the boss has spawned. The boss itself is used as
     * a fallback, since it spawns on that same centre tile; that covers the
     * case where the plugin is switched on mid-fight and the object's spawn
     * event has long since passed.
     */
    private WorldPoint resolveArenaCentre(Scene scene) {
        if (arenaCentre != null) {
            return arenaCentre;
        }
        // The fallback scan, at most once per scene, for when the plugin is
        // switched on mid-delve and the spawn event is long past.
        if (scannedScene != scene) {
            scannedScene = scene;
            Tile[][][] tiles = extendedTiles(scene);
            if (tiles != null) {
                arenaCentre = scanForCentreObject(tiles);
            }
            if (arenaCentre != null) {
                log.info("[Mokhaiotl] Simplify Arena found the arena centre object at {}",
                        arenaCentre);
                return arenaCentre;
            }
        }
        // The boss spawns on the centre tile, so it is an exact anchor - but it
        // only exists once the fight starts, which is why it is not the first
        // choice any more.
        WorldPoint boss = bossCentre();
        if (boss != null) {
            arenaCentre = boss;
            log.info("[Mokhaiotl] Simplify Arena took the centre from the boss at {}", boss);
            return arenaCentre;
        }
        // Nothing to key off but the room itself. Throttled, because it walks
        // the floor rather than reading a single object.
        if (ticksSinceRoomSearch++ < ROOM_SEARCH_INTERVAL_TICKS) {
            return null;
        }
        ticksSinceRoomSearch = 0;
        WorldPoint room = roomCentreFromPlayer();
        if (room != null) {
            arenaCentre = room;
            log.info("[Mokhaiotl] Simplify Arena derived the room centre from the floor at {}",
                    room);
        }
        return arenaCentre;
    }

    /**
     * Finds the centre of the room the player is standing in, without needing
     * the boss or any object to key off.
     *
     * <p>Floods the walkable floor outwards from the player, then picks the
     * tile furthest from any wall. That "deepest" tile is the middle of the
     * widest part of the floor, which is the arena itself: the entrance
     * corridor is narrow, so its tiles are always close to a wall and can never
     * win, and it does not matter where in the room the player happens to be
     * standing. The clearance floor rejects the case where the player is still
     * out in the corridor and the room has not been reached yet, so a partial
     * flood is never mistaken for the arena.
     */
    private WorldPoint roomCentreFromPlayer() {
        WorldView wv = client.getTopLevelWorldView();
        Player player = client.getLocalPlayer();
        if (wv == null || player == null) {
            return null;
        }
        LocalPoint lp = player.getLocalLocation();
        if (lp == null) {
            return null;
        }
        int[][] flags = collisionFlags(wv);
        if (flags == null) {
            return null;
        }
        int startX = lp.getSceneX();
        int startY = lp.getSceneY();
        if (!isWalkable(flags, startX, startY)) {
            return null;
        }

        // Flood the floor, recording how far each tile is from the start so the
        // search stays local to this room rather than crawling the instance.
        boolean[][] flooded = new boolean[flags.length][];
        for (int x = 0; x < flags.length; x++) {
            flooded[x] = new boolean[flags[x] == null ? 0 : flags[x].length];
        }
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] { startX, startY });
        flooded[startX][startY] = true;
        List<int[]> floor = new ArrayList<>();
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            floor.add(at);
            if (floor.size() > MAX_ROOM_TILES) {
                return null;
            }
            for (int[] step : NEIGHBOURS) {
                int nx = at[0] + step[0];
                int ny = at[1] + step[1];
                if (Math.abs(nx - startX) > ROOM_SEARCH_RADIUS
                        || Math.abs(ny - startY) > ROOM_SEARCH_RADIUS) {
                    continue;
                }
                if (!isWalkable(flags, nx, ny) || flooded[nx][ny]) {
                    continue;
                }
                flooded[nx][ny] = true;
                queue.add(new int[] { nx, ny });
            }
        }

        int[] deepest = deepestTile(flooded, floor);
        if (deepest == null || deepest[2] < MIN_ROOM_CLEARANCE) {
            return null;
        }
        return WorldPoint.fromScene(wv, deepest[0], deepest[1], wv.getPlane());
    }

    /**
     * The flooded tile furthest from any wall, as {@code {x, y, clearance}}, or
     * null if there is none.
     *
     * <p>A distance transform: every tile touching a wall starts at 1 and the
     * count grows inwards, so the winner is the middle of the widest part of
     * the floor. This is what makes the room centre independent of where the
     * player is standing, and what keeps a narrow entrance corridor from ever
     * winning however long it is.
     */
    static int[] deepestTile(boolean[][] flooded, List<int[]> floor) {
        int[][] clearance = new int[flooded.length][];
        for (int x = 0; x < flooded.length; x++) {
            clearance[x] = new int[flooded[x].length];
        }
        Deque<int[]> ring = new ArrayDeque<>();
        for (int[] tile : floor) {
            for (int[] step : NEIGHBOURS) {
                if (!isFlooded(flooded, tile[0] + step[0], tile[1] + step[1])) {
                    clearance[tile[0]][tile[1]] = 1;
                    ring.add(tile);
                    break;
                }
            }
        }
        int[] deepest = null;
        int best = 0;
        while (!ring.isEmpty()) {
            int[] at = ring.poll();
            int here = clearance[at[0]][at[1]];
            if (here > best) {
                best = here;
                deepest = new int[] { at[0], at[1], here };
            }
            for (int[] step : NEIGHBOURS) {
                int nx = at[0] + step[0];
                int ny = at[1] + step[1];
                if (!isFlooded(flooded, nx, ny) || clearance[nx][ny] != 0) {
                    continue;
                }
                clearance[nx][ny] = here + 1;
                ring.add(new int[] { nx, ny });
            }
        }
        return deepest;
    }

    private static boolean isFlooded(boolean[][] flooded, int x, int y) {
        return x >= 0 && x < flooded.length && y >= 0 && y < flooded[x].length && flooded[x][y];
    }

    private int[][] collisionFlags(WorldView wv) {
        CollisionData[] maps = wv.getCollisionMaps();
        int plane = wv.getPlane();
        if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null) {
            return null;
        }
        return maps[plane].getFlags();
    }

    // Somewhere the player could stand: rules out the void outside the room and
    // the walls and scenery standing on a tile, which is what bounds the flood.
    private boolean isWalkable(int[][] flags, int x, int y) {
        if (x < 0 || x >= flags.length || flags[x] == null || y < 0 || y >= flags[x].length) {
            return false;
        }
        int blocked = CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
                | CollisionDataFlag.BLOCK_MOVEMENT_OBJECT
                | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION;
        return (flags[x][y] & blocked) == 0;
    }

    // The extended tiles (184), not the standard 104, so an arena sitting near
    // the edge of the loaded scene is still covered end to end.
    private Tile[][][] extendedTiles(Scene scene) {
        Tile[][][] tiles = scene.getExtendedTiles();
        return tiles != null ? tiles : scene.getTiles();
    }

    private WorldPoint scanForCentreObject(Tile[][][] tiles) {
        for (Tile[][] plane : tiles) {
            if (plane == null) {
                continue;
            }
            for (Tile[] column : plane) {
                if (column == null) {
                    continue;
                }
                for (Tile tile : column) {
                    if (tile == null) {
                        continue;
                    }
                    GameObject[] objects = tile.getGameObjects();
                    if (objects == null) {
                        continue;
                    }
                    for (GameObject object : objects) {
                        if (object != null && object.getId() == ARENA_CENTRE_OBJECT_ID) {
                            return footprintCentre(object);
                        }
                    }
                }
            }
        }
        return bossCentre();
    }

    /**
     * The middle tile of a multi-tile object's footprint.
     *
     * <p>Worth spelling out, because getting this wrong is what left the north
     * edge of the room unpainted: the arena-centre object is 5x5, and both the
     * tile it is found on during a scan and its own reported location are the
     * <i>south-west</i> corner of that footprint, not its middle. Taking the
     * corner put the octagon 2 tiles south-west of where it belonged, so it
     * overshot into the south wall and fell 2 tiles short along the north.
     * The scene min/max are used rather than a hardcoded offset so the centre
     * stays right whatever size the object turns out to be.
     */
    private WorldPoint footprintCentre(GameObject object) {
        WorldView wv = client.getTopLevelWorldView();
        Point min = object.getSceneMinLocation();
        Point max = object.getSceneMaxLocation();
        if (wv == null || min == null || max == null) {
            return object.getWorldLocation();
        }
        return WorldPoint.fromScene(wv,
                midpoint(min.getX(), max.getX()),
                midpoint(min.getY(), max.getY()),
                object.getPlane());
    }

    // Middle of an inclusive tile span, e.g. 0..4 (a 5-wide footprint) -> 2.
    static int midpoint(int min, int max) {
        return (min + max) / 2;
    }

    // Offset from a creature's south-west tile to its middle one: 2 for a 5x5,
    // 0 for a 1x1. Even sizes have no exact middle and round down, which is the
    // same convention the rest of the fight's tile maths uses.
    static int centreOffsetForSize(int size) {
        return size / 2;
    }

    // The boss's centre tile, for when the arena-centre object cannot be found.
    // The boss is 5x5 and spawns on the arena centre, but its reported location
    // is the south-west corner of that footprint, so it needs the same shift.
    private WorldPoint bossCentre() {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) {
            return null;
        }
        for (NPC npc : (Iterable<? extends NPC>) wv.npcs()) {
            if (npc != null && MokhaiotlHandler.BOSS_IDS.contains(npc.getId())) {
                WorldPoint sw = npc.getWorldLocation();
                if (sw == null) {
                    return null;
                }
                int half = centreOffsetForSize(
                        npc.getComposition() != null ? npc.getComposition().getSize() : 1);
                return new WorldPoint(sw.getX() + half, sw.getY() + half, sw.getPlane());
            }
        }
        return null;
    }

    // Recolours a flat tile's four corners, saving what was there first.
    private boolean flattenPaint(Tile tile, int hsl) {
        SceneTilePaint paint = tile.getSceneTilePaint();
        if (paint == null) {
            return false;
        }
        savedPaints.add(new SavedPaint(paint));
        paint.setSwColor(hsl);
        paint.setSeColor(hsl);
        paint.setNwColor(hsl);
        paint.setNeColor(hsl);
        // A surviving texture id would keep painting the original floor over
        // the flat colour, so it has to go too.
        paint.setTexture(NO_TEXTURE);
        return true;
    }

    // Recolours a sloped / split tile's triangles. The colour and texture
    // arrays are handed out by reference, so these are written through
    // directly - and deduplicated, since the client shares them between tiles.
    private boolean flattenModel(Tile tile, Map<int[], Boolean> seen, int hsl) {
        SceneTileModel model = tile.getSceneTileModel();
        if (model == null) {
            return false;
        }
        boolean touched = false;
        touched |= fillColors(model.getTriangleColorA(), seen, hsl);
        touched |= fillColors(model.getTriangleColorB(), seen, hsl);
        touched |= fillColors(model.getTriangleColorC(), seen, hsl);
        touched |= clearTextures(model.getTriangleTextureId(), seen);
        return touched;
    }

    private boolean fillColors(int[] colors, Map<int[], Boolean> seen, int hsl) {
        if (colors == null || seen.put(colors, Boolean.TRUE) != null) {
            return false;
        }
        savedModels.add(new SavedModel(colors));
        java.util.Arrays.fill(colors, hsl);
        return true;
    }

    private boolean clearTextures(int[] textures, Map<int[], Boolean> seen) {
        if (textures == null || seen.put(textures, Boolean.TRUE) != null) {
            return false;
        }
        savedModels.add(new SavedModel(textures));
        java.util.Arrays.fill(textures, NO_TEXTURE);
        return true;
    }

    private void restore() {
        if (!applied) {
            return;
        }
        // The saved objects are the client's own, reached by reference, so they
        // can be written back without walking the scene again. Only do so while
        // the scene they came from is still the live one.
        Scene scene = currentScene();
        if (scene == appliedScene) {
            for (SavedPaint saved : savedPaints) {
                saved.restore();
            }
            for (SavedModel saved : savedModels) {
                saved.restore();
            }
            // The GPU renderers cache the old colours just as readily as the
            // new ones, so putting the values back is only half the job.
            invalidateZones(scene);
        }
        int paints = savedPaints.size();
        int models = savedModels.size();
        discardSavedFloor();
        log.info("[Mokhaiotl] Simplify Arena restored {} tile paints and {} tile model arrays",
                paints, models);
    }

    private void discardSavedFloor() {
        applied = false;
        appliedScene = null;
        appliedCentre = null;
        savedPaints.clear();
        savedModels.clear();
    }

    private Scene currentScene() {
        WorldView wv = client.getTopLevelWorldView();
        return wv == null ? null : wv.getScene();
    }

    /**
     * Tells the active renderer that every zone's geometry has changed.
     *
     * <p>Without this the recolour is invisible under the GPU plugin and 117 HD.
     * Those renderers upload the scene's colours into GPU buffers once, when
     * the scene loads, and then draw from those buffers - they never re-read
     * {@link SceneTilePaint} per frame, so writing new colours into the scene
     * changes nothing on screen until the affected zones are re-uploaded.
     * {@code invalidateZone} is how a renderer is told to do that; the stock
     * software renderer reads the scene directly and does not need it, and its
     * no-op default implementation makes this call harmless there.
     */
    private void invalidateZones(Scene scene) {
        DrawCallbacks callbacks = client.getDrawCallbacks();
        if (callbacks == null) {
            log.info("[Mokhaiotl] Simplify Arena: no draw callbacks, assuming software renderer");
            return;
        }
        log.info("[Mokhaiotl] Simplify Arena: invalidating {}x{} zones on {}",
                ZONE_GRID, ZONE_GRID, callbacks.getClass().getName());
        for (int zoneX = 0; zoneX < ZONE_GRID; zoneX++) {
            for (int zoneY = 0; zoneY < ZONE_GRID; zoneY++) {
                callbacks.invalidateZone(scene, zoneX, zoneY);
            }
        }
    }

    // ------------------------------------------------------------------
    // Packed-HSL conversion
    // ------------------------------------------------------------------

    /**
     * Converts an RGB colour to the client's packed HSL short: 6 bits of hue,
     * 3 of saturation and 7 of luminance, laid out as {@code hhhhhhssslllllll}.
     * Every tile colour in the scene is stored in this format, so a plain RGB
     * value cannot be written into one directly.
     */
    static int packHsl(Color color) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        int h = clamp(Math.round(hsb[0] * 63f), 0, 63);
        int s = clamp(Math.round(hsb[1] * 7f), 0, 7);
        int l = clamp(Math.round(hsb[2] * 127f), 0, 127);
        return (h << 10) | (s << 7) | l;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Saved originals
    // ------------------------------------------------------------------

    // One flat tile's corner colours and texture, kept by value so they can be
    // written back into the client's own paint object.
    private static final class SavedPaint {
        private final SceneTilePaint paint;
        private final int sw;
        private final int se;
        private final int nw;
        private final int ne;
        private final int texture;

        SavedPaint(SceneTilePaint paint) {
            this.paint = paint;
            this.sw = paint.getSwColor();
            this.se = paint.getSeColor();
            this.nw = paint.getNwColor();
            this.ne = paint.getNeColor();
            this.texture = paint.getTexture();
        }

        void restore() {
            paint.setSwColor(sw);
            paint.setSeColor(se);
            paint.setNwColor(nw);
            paint.setNeColor(ne);
            paint.setTexture(texture);
        }
    }

    // A copy of one of a tile model's int arrays, alongside the array itself,
    // so the contents can be put back into the very array the client renders.
    private static final class SavedModel {
        private final int[] array;
        private final int[] original;

        SavedModel(int[] array) {
            this.array = array;
            this.original = array.clone();
        }

        void restore() {
            System.arraycopy(original, 0, array, 0, original.length);
        }
    }
}
