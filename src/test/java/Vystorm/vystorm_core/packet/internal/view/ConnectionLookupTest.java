package Vystorm.vystorm_core.packet.internal.view;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The Spigot way to a player's connection: the entry of the server's list whose listener is the player's own. */
class ConnectionLookupTest {
    /** Stands in for net.minecraft.network.Connection: only its listener matters. */
    record Conn(Object listener) {}

    /** A listener that equals every other one: the lookup must compare identities. */
    static final class SameListener {
        @Override public boolean equals(Object o) { return o instanceof SameListener; }
        @Override public int hashCode() { return 1; }
    }

    @Test void findsTheConnectionOfExactlyThisListener() {
        Object a = new SameListener(), b = new SameListener(), c = new SameListener();
        List<Conn> list = Collections.synchronizedList(new ArrayList<>(List.of(new Conn(a), new Conn(b), new Conn(c))));
        assertSame(list.get(1), ConnectionLookup.find(list, x -> ((Conn) x).listener(), b));
        assertSame(list.get(2), ConnectionLookup.find(list, x -> ((Conn) x).listener(), c));
    }

    @Test void nothingFoundIsNull() {
        List<Conn> list = Collections.synchronizedList(new ArrayList<>(List.of(new Conn(new Object()), new Conn(null))));
        assertNull(ConnectionLookup.find(list, x -> ((Conn) x).listener(), new Object()));
        assertNull(ConnectionLookup.find(list, x -> ((Conn) x).listener(), null));
        assertNull(ConnectionLookup.find(null, x -> ((Conn) x).listener(), new Object()));
        List<Conn> withNull = new ArrayList<>();
        withNull.add(null);
        assertNull(ConnectionLookup.find(withNull, x -> ((Conn) x).listener(), new Object()));
    }

    @Test void readsUnderTheListLockWhileTheNetworkChangesIt() throws Exception {
        List<Conn> list = Collections.synchronizedList(new ArrayList<>());
        Object wanted = new Object();
        for (int i = 0; i < 2000; i++) list.add(new Conn(new Object()));
        list.add(new Conn(wanted));
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            started.countDown();
            while (!stop.get()) { list.add(0, new Conn(new Object())); list.remove(0); }
        });
        writer.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        try {
            for (int i = 0; i < 300; i++) assertNotNull(ConnectionLookup.find(list, x -> ((Conn) x).listener(), wanted));
        } finally { stop.set(true); writer.join(5000); }
    }
}
