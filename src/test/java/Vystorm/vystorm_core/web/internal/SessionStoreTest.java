package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SessionStoreTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final SessionStore store = new SessionStore(now::get, 60_000, 30 * 60_000, 12 * 3_600_000L, 3);
    private final UUID alice = UUID.randomUUID();

    @Test
    void givenIssuedToken_whenConsumedTwice_thenOnlyFirstSucceeds() {
        String token = store.issueLoginToken(alice, "Alice", "skills");
        assertEquals(SessionStore.TOKEN_LENGTH, token.length());
        var first = store.consumeLoginToken(token);
        assertTrue(first.isPresent());
        assertEquals(alice, first.get().player());
        assertEquals("skills", first.get().module());
        assertTrue(store.consumeLoginToken(token).isEmpty(), "single use");
    }

    @Test
    void givenExpiredToken_whenConsumed_thenRejected() {
        String token = store.issueLoginToken(alice, "Alice", null);
        now.addAndGet(60_000);
        assertTrue(store.consumeLoginToken(token).isEmpty());
        assertEquals(0, store.loginTokenCount(), "expired token removed");
    }

    @Test
    void givenMalformedOrForeignTokens_whenConsumed_thenRejectedWithoutException() {
        store.issueLoginToken(alice, "Alice", null);
        assertTrue(store.consumeLoginToken(null).isEmpty());
        assertTrue(store.consumeLoginToken("").isEmpty());
        assertTrue(store.consumeLoginToken("short").isEmpty());
        assertTrue(store.consumeLoginToken("x".repeat(42) + "!").isEmpty());
        assertTrue(store.consumeLoginToken(SessionStore.randomToken()).isEmpty());
    }

    @Test
    void givenManyTokensForOnePlayer_whenIssued_thenOldestAreDropped() {
        String first = store.issueLoginToken(alice, "Alice", null);
        for (int i = 0; i < SessionStore.MAX_LOGIN_TOKENS_PER_PLAYER; i++) { now.incrementAndGet(); store.issueLoginToken(alice, "Alice", null); }
        assertTrue(store.consumeLoginToken(first).isEmpty());
        assertEquals(SessionStore.MAX_LOGIN_TOKENS_PER_PLAYER, store.loginTokenCount());
    }

    @Test
    void givenTokens_whenGenerated_thenUniqueAndUrlSafe() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = SessionStore.randomToken();
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
            assertTrue(seen.add(token));
        }
    }

    @Test
    void givenSession_whenIdleLongerThanTtl_thenExpiredButActivityExtendsIt() {
        var issued = store.createSession(alice, "Alice");
        now.addAndGet(29 * 60_000);
        assertTrue(store.resolve(issued.token()).isPresent(), "activity before idle timeout");
        now.addAndGet(29 * 60_000);
        assertTrue(store.resolve(issued.token()).isPresent(), "sliding: still valid after 58 minutes with activity");
        now.addAndGet(30 * 60_000);
        assertTrue(store.resolve(issued.token()).isEmpty(), "idle timeout");
    }

    @Test
    void givenActiveSession_whenAbsoluteMaximumReached_thenExpiredDespiteActivity() {
        var issued = store.createSession(alice, "Alice");
        for (int i = 0; i < 12 * 3; i++) {
            now.addAndGet(20 * 60_000);
            if (i < 12 * 3 - 1) assertTrue(store.resolve(issued.token()).isPresent(), "step " + i);
        }
        assertTrue(store.resolve(issued.token()).isEmpty());
    }

    @Test
    void givenSessions_whenOverPerPlayerLimit_thenOldestEvicted() {
        var first = store.createSession(alice, "Alice");
        now.incrementAndGet();
        store.createSession(alice, "Alice");
        now.incrementAndGet();
        store.createSession(alice, "Alice");
        now.incrementAndGet();
        var fourth = store.createSession(alice, "Alice");
        assertTrue(store.resolve(first.token()).isEmpty());
        assertTrue(store.resolve(fourth.token()).isPresent());
        assertEquals(3, store.sessionCount());
    }

    @Test
    void givenSession_whenCsrfCompared_thenOnlyExactTokenMatches() {
        var session = store.createSession(alice, "Alice").session();
        assertTrue(SessionStore.csrfMatches(session, session.csrf()));
        assertFalse(SessionStore.csrfMatches(session, null));
        assertFalse(SessionStore.csrfMatches(session, ""));
        assertFalse(SessionStore.csrfMatches(session, SessionStore.randomToken()));
        assertNotEquals(session.id(), session.csrf());
    }

    @Test
    void givenPlayerSessions_whenRevoked_thenAllGoneAndTokensToo() {
        var a = store.createSession(alice, "Alice");
        var b = store.createSession(alice, "Alice");
        UUID bob = UUID.randomUUID();
        var c = store.createSession(bob, "Bob");
        String token = store.issueLoginToken(alice, "Alice", null);
        assertEquals(2, store.revokePlayer(alice));
        assertTrue(store.resolve(a.token()).isEmpty());
        assertTrue(store.resolve(b.token()).isEmpty());
        assertTrue(store.consumeLoginToken(token).isEmpty());
        assertTrue(store.resolve(c.token()).isPresent());
        store.revokeAll();
        assertTrue(store.resolve(c.token()).isEmpty());
    }

    @Test
    void givenClientAndBrowserSessions_whenClientRevoked_thenBrowserSessionStays() {
        String clientToken = store.issueLoginToken(alice, "Alice", "skills", "client");
        var used = store.consumeLoginToken(clientToken).orElseThrow();
        assertEquals("client", used.issuer());
        var client = store.createSession(alice, "Alice", used.issuer());
        var browser = store.createSession(alice, "Alice", "chat");
        var legacy = store.createSession(alice, "Alice");
        String pendingClient = store.issueLoginToken(alice, "Alice", null, "client");
        String pendingChat = store.issueLoginToken(alice, "Alice", null, "chat");
        assertEquals("client", client.session().issuer());
        assertEquals("player", legacy.session().issuer());
        assertEquals(1, store.revokePlayer(alice, "client"::equals));
        assertTrue(store.resolve(client.token()).isEmpty());
        assertTrue(store.resolve(browser.token()).isPresent());
        assertTrue(store.resolve(legacy.token()).isPresent());
        assertTrue(store.consumeLoginToken(pendingClient).isEmpty(), "open client tokens are dropped too");
        assertEquals("chat", store.consumeLoginToken(pendingChat).orElseThrow().issuer());
    }

    @Test
    void givenStoredSession_whenInspected_thenRawTokenIsNotKept() throws Exception {
        var issued = store.createSession(alice, "Alice");
        var field = SessionStore.class.getDeclaredField("sessions");
        field.setAccessible(true);
        var map = (java.util.Map<?, ?>) field.get(store);
        assertFalse(map.containsKey(issued.token()), "keys are hashes");
        assertTrue(map.containsKey(SessionStore.hash(issued.token())));
    }
}
