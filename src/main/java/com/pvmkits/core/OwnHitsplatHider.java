package com.pvmkits.core;

import com.pvmkits.PvmKitsConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.events.HitsplatApplied;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.List;

/**
 * Hides every hitsplat that lands on the local player (damage, 0s/blocks, poison,
 * venom, acid, heals - any type), leaving NPC and other players' hitsplats, the
 * player's prayer icon, overhead text and health bar untouched.
 *
 * The API has no way to remove a hitsplat, and the render callback for an actor's
 * 2D elements is all-or-nothing (it would also hide the prayer icon and overhead
 * text). But the client keeps each actor's visible hitsplats in a plain List and
 * draws whatever is in it, and the Hitsplat in HitsplatApplied is the very object
 * it just put there. So the list is found once by identity - whichever List field
 * on the actor holds that object - which doesn't depend on the obfuscated field
 * name and so survives game updates. Each of the player's new hitsplats is then
 * taken straight back out, so it is never drawn. The draw loop places hitsplats by
 * list index and adding just appends while the list is short, so a missing entry
 * only means one fewer splat.
 *
 * This uses reflection on the client, which suits a sideloaded plugin but would
 * not be accepted on the Plugin Hub. If the list can't be found it gives up
 * quietly, and hitsplats show as normal.
 */
@Slf4j
@Singleton
public class OwnHitsplatHider {

    private final Client client;
    private final PvmKitsConfig config;

    // The actor field that holds the visible hitsplats, once found.
    private Field hitsplatListField;
    // Set if reflection itself fails, so we stop trying (and warn only once).
    private boolean unavailable;

    @Inject
    public OwnHitsplatHider(Client client, PvmKitsConfig config) {
        this.client = client;
        this.config = config;
    }

    public void onHitsplatApplied(HitsplatApplied event) {
        if (unavailable || !config.hideOwnHitsplats()) {
            return;
        }
        Actor actor = event.getActor();
        Hitsplat hitsplat = event.getHitsplat();
        if (actor == null || hitsplat == null || actor != client.getLocalPlayer()) {
            return;
        }

        try {
            remove(actor, hitsplat);
        } catch (ReflectiveOperationException | RuntimeException e) {
            unavailable = true;
            log.warn("Can't hide your hitsplats on this client version, so they will show as normal", e);
        }
    }

    /**
     * Takes the hitsplat out of the actor's visible hitsplat list. Returns false if
     * no list on the actor holds it (the client skipped it, e.g. an unknown type).
     * Takes plain Objects so it can be tested without a real client.
     */
    boolean remove(Object actor, Object hitsplat) throws IllegalAccessException {
        List<?> hitsplats = hitsplatList(actor, hitsplat);
        return hitsplats != null && removeByIdentity(hitsplats, hitsplat);
    }

    // The actor's visible hitsplat list, or null when the hitsplat isn't in any list.
    private List<?> hitsplatList(Object actor, Object hitsplat) throws IllegalAccessException {
        if (hitsplatListField != null && hitsplatListField.getDeclaringClass().isInstance(actor)) {
            Object value = hitsplatListField.get(actor);
            return value instanceof List ? (List<?>) value : null;
        }

        for (Class<?> type = actor.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(actor);
                if (value instanceof List && containsByIdentity((List<?>) value, hitsplat)) {
                    hitsplatListField = field;
                    log.debug("Found the hitsplat list: {}.{}", type.getName(), field.getName());
                    return (List<?>) value;
                }
            }
        }
        return null;
    }

    private static boolean containsByIdentity(List<?> list, Object target) {
        for (Object element : list) {
            if (element == target) {
                return true;
            }
        }
        return false;
    }

    private static boolean removeByIdentity(List<?> list, Object target) {
        for (Iterator<?> it = list.iterator(); it.hasNext();) {
            if (it.next() == target) {
                it.remove();
                return true;
            }
        }
        return false;
    }
}
