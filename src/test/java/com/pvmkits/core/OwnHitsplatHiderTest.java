package com.pvmkits.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class OwnHitsplatHiderTest {

    // Stand-ins for the client's obfuscated actor classes: the hitsplat list sits
    // on the base class, next to another list (the client's is a movement path).
    static class FakeActor {
        final List<Object> path = new ArrayList<>();
        final List<Object> splats = new ArrayList<>();
    }

    static class FakePlayer extends FakeActor {
        final List<Object> other = new ArrayList<>();
    }

    // Equal to every other Splat, so only identity can tell them apart.
    static class Splat {
        @Override
        public boolean equals(Object o) {
            return o instanceof Splat;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    @Test
    public void removesExactlyTheAppliedHitsplatFromTheListThatHoldsIt() throws Exception {
        FakePlayer player = new FakePlayer();
        Splat older = new Splat();
        Splat applied = new Splat();
        player.path.add(new Splat());
        player.splats.addAll(Arrays.asList(older, applied));

        assertTrue(new OwnHitsplatHider(null, null).remove(player, applied));

        assertEquals(1, player.splats.size());
        assertSame(older, player.splats.get(0));
        assertEquals(1, player.path.size());
    }

    @Test
    public void reusesTheFoundListForLaterHitsplats() throws Exception {
        OwnHitsplatHider hider = new OwnHitsplatHider(null, null);
        FakePlayer player = new FakePlayer();
        Splat first = new Splat();
        Splat second = new Splat();
        player.splats.add(first);
        assertTrue(hider.remove(player, first));

        player.splats.add(second);
        assertTrue(hider.remove(player, second));
        assertTrue(player.splats.isEmpty());
    }

    @Test
    public void leavesEverythingAloneWhenNoListHoldsTheHitsplat() throws Exception {
        FakePlayer player = new FakePlayer();
        player.splats.add(new Splat());

        assertFalse(new OwnHitsplatHider(null, null).remove(player, new Splat()));
        assertEquals(1, player.splats.size());
    }
}
