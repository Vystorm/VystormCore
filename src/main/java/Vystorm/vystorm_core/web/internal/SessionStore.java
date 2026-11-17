package Vystorm.vystorm_core.web.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Einmal-Login-Tokens und Browser-Sitzungen, nur im Speicher. Gespeichert werden SHA-256-Hashes der Tokens, nie die
 * Tokens selbst; ein gestohlener Heap-Dump oder Log enthält also keine verwendbaren Anmeldedaten. Tokens sind 256 Bit
 * aus {@link SecureRandom}, base64url ohne Padding (43 Zeichen). Threadsicher. Core-intern.
 *
 * @since 0.17.0
 */
public final class SessionStore {
    /** Länge eines Tokens in Zeichen (32 Bytes base64url). */
    public static final int TOKEN_LENGTH = 43;
    static final int MAX_LOGIN_TOKENS = 10_000;
    static final int MAX_LOGIN_TOKENS_PER_PLAYER = 3;
    static final int MAX_SESSIONS = 20_000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    /** Herkunft von Tokens/Sitzungen ohne Angabe. */
    public static final String ISSUER_PLAYER = "player";

    /**
     * Ein ausgestelltes, noch nicht eingelöstes Login-Token. {@code issuer} nennt den Kanal (z. B. {@code chat},
     * {@code client}) und geht auf die Sitzung über, damit z. B. Client-Mod-Sitzungen beim Quit enden können.
     */
    public record LoginToken(UUID player, String name, String module, String issuer, long created, long expiresAt) {
        public LoginToken(UUID player, String name, String module, long created, long expiresAt) {
            this(player, name, module, ISSUER_PLAYER, created, expiresAt);
        }
    }

    /** Eine Browser-Sitzung. {@code id} ist nicht geheim (Logs, Ratenlimits), {@code csrf} schon. */
    public static final class Session {
        private final String id;
        private final String tokenHash;
        private final UUID player;
        private final String name;
        private final String csrf;
        private final String issuer;
        private final long created;
        private final long expiresAt;
        private volatile long lastSeen;

        Session(String id, String tokenHash, UUID player, String name, String csrf, String issuer, long created, long expiresAt) {
            this.id = id; this.tokenHash = tokenHash; this.player = player; this.name = name; this.csrf = csrf; this.issuer = issuer;
            this.created = created; this.expiresAt = expiresAt; this.lastSeen = created;
        }

        public String id() { return id; }
        public UUID player() { return player; }
        public String name() { return name; }
        public String csrf() { return csrf; }
        /** Kanal des Login-Tokens, aus dem die Sitzung entstand ({@code chat}, {@code client}, …). @since 0.20.0 */
        public String issuer() { return issuer; }
        public long created() { return created; }
        public long lastSeen() { return lastSeen; }
        /** Absolutes Ende der Sitzung (unabhängig von Aktivität). */
        public long expiresAt() { return expiresAt; }
    }

    /** Ergebnis von {@link #createSession}: das rohe Cookie-Token (nur einmal sichtbar) und die Sitzung. */
    public record Issued(String token, Session session) {}

    private final Map<String, LoginToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final List<java.util.function.Consumer<Session>> revocationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile long loginTtlMs;
    private volatile long idleMs;
    private volatile long maxMs;
    private volatile int perPlayer;

    public SessionStore(LongSupplier clockMillis, long loginTtlMs, long idleMs, long maxMs, int perPlayer) {
        this.clock = clockMillis;
        configure(loginTtlMs, idleMs, maxMs, perPlayer);
    }

    public void configure(long loginTtlMs, long idleMs, long maxMs, int perPlayer) {
        this.loginTtlMs = loginTtlMs;
        this.idleMs = idleMs;
        this.maxMs = maxMs;
        this.perPlayer = perPlayer;
    }

    /** Neues Einmal-Token für {@code player}; ältere offene Tokens desselben Spielers über dem Limit verfallen. */
    public String issueLoginToken(UUID player, String name, String module) { return issueLoginToken(player, name, module, ISSUER_PLAYER); }

    /** Wie {@link #issueLoginToken(UUID, String, String)} mit Kanal ({@code chat}, {@code client}, …). */
    public String issueLoginToken(UUID player, String name, String module, String issuer) {
        long now = clock.getAsLong();
        purgeExpiredTokens(now);
        List<Map.Entry<String, LoginToken>> own = new ArrayList<>(tokens.entrySet().stream().filter(e -> e.getValue().player().equals(player)).toList());
        own.sort(Comparator.comparingLong(e -> e.getValue().created()));
        while (own.size() >= MAX_LOGIN_TOKENS_PER_PLAYER) tokens.remove(own.removeFirst().getKey());
        if (tokens.size() >= MAX_LOGIN_TOKENS) throw new IllegalStateException("too many open login tokens");
        String raw = randomToken();
        tokens.put(hash(raw), new LoginToken(player, name, module, issuer(issuer), now, now + loginTtlMs));
        return raw;
    }

    /** Löst ein Login-Token genau einmal ein; abgelaufene, unbekannte oder fehlerhafte Tokens liefern leer. */
    public Optional<LoginToken> consumeLoginToken(String raw) {
        if (!wellFormed(raw)) return Optional.empty();
        LoginToken token = tokens.remove(hash(raw));
        if (token == null || clock.getAsLong() >= token.expiresAt()) return Optional.empty();
        return Optional.of(token);
    }

    /** Neue Sitzung; über {@code max-per-player} wird die älteste Sitzung des Spielers beendet. */
    public Issued createSession(UUID player, String name) { return createSession(player, name, ISSUER_PLAYER); }

    /** Wie {@link #createSession(UUID, String)}; {@code issuer} = Kanal des eingelösten Tokens. */
    public Issued createSession(UUID player, String name, String issuer) {
        long now = clock.getAsLong();
        List<Session> own = new ArrayList<>(sessions.values().stream().filter(s -> s.player().equals(player)).toList());
        own.sort(Comparator.comparingLong(Session::created));
        while (own.size() >= perPlayer) { Session old = own.removeFirst(); if (sessions.remove(old.tokenHash, old)) ended(old); }
        if (sessions.size() >= MAX_SESSIONS) purgeExpired();
        if (sessions.size() >= MAX_SESSIONS) throw new IllegalStateException("too many web sessions");
        String raw = randomToken();
        String tokenHash = hash(raw);
        Session session = new Session(randomToken().substring(0, 16), tokenHash, player, name, randomToken(), issuer(issuer), now, now + maxMs);
        sessions.put(tokenHash, session);
        return new Issued(raw, session);
    }

    /** Sitzung zum Cookie-Token; verlängert die Leerlaufzeit (gleitend) bis zum absoluten Ende. */
    public Optional<Session> resolve(String raw) {
        if (!wellFormed(raw)) return Optional.empty();
        String key = hash(raw);
        Session session = sessions.get(key);
        if (session == null) return Optional.empty();
        long now = clock.getAsLong();
        if (expired(session, now)) { if (sessions.remove(key, session)) ended(session); return Optional.empty(); }
        session.lastSeen = now;
        return Optional.of(session);
    }

    /** Sitzung zum Cookie-Token ohne Verlängerung (z. B. nur um die Sprache zu bestimmen). */
    public Optional<Session> peek(String raw) {
        if (!wellFormed(raw)) return Optional.empty();
        Session session = sessions.get(hash(raw));
        if (session == null || expired(session, clock.getAsLong())) return Optional.empty();
        return Optional.of(session);
    }

    /** Ob die Sitzung noch besteht und nicht abgelaufen ist; mit {@code touch} zählt der Aufruf als Aktivität. */
    public boolean alive(Session session, boolean touch) {
        if (session == null || sessions.get(session.tokenHash) != session) return false;
        long now = clock.getAsLong();
        if (expired(session, now)) { if (sessions.remove(session.tokenHash, session)) ended(session); return false; }
        if (touch) session.lastSeen = now;
        return true;
    }

    /** Wird für jede beendete Sitzung aufgerufen (Logout, Entzug, Ablauf, Verdrängung). */
    public void onRevoke(java.util.function.Consumer<Session> listener) { revocationListeners.add(listener); }

    private void ended(Session session) {
        for (var listener : revocationListeners) {
            try { listener.accept(session); } catch (RuntimeException ignored) { /* Core-intern, fehlertolerant */ }
        }
    }

    /** Vergleicht das CSRF-Token in konstanter Zeit. */
    public static boolean csrfMatches(Session session, String presented) {
        if (session == null || presented == null || presented.length() != TOKEN_LENGTH) return false;
        return MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.US_ASCII), presented.getBytes(StandardCharsets.US_ASCII));
    }

    public boolean revoke(Session session) {
        boolean removed = session != null && sessions.remove(session.tokenHash, session);
        if (removed) ended(session);
        return removed;
    }

    /** Beendet alle Sitzungen und offenen Tokens eines Spielers; liefert die Zahl beendeter Sitzungen. */
    public int revokePlayer(UUID player) {
        tokens.values().removeIf(t -> t.player().equals(player));
        List<Session> removed = new ArrayList<>();
        sessions.values().removeIf(s -> { boolean hit = s.player().equals(player); if (hit) removed.add(s); return hit; });
        removed.forEach(this::ended);
        return removed.size();
    }

    /**
     * Beendet die Sitzungen und offenen Tokens eines Spielers, deren Kanal {@code issuer} erfüllt (z. B. nur
     * {@code client}); liefert die Zahl beendeter Sitzungen.
     *
     * @since 0.20.0
     */
    public int revokePlayer(UUID player, java.util.function.Predicate<String> issuer) {
        tokens.values().removeIf(t -> t.player().equals(player) && issuer.test(t.issuer()));
        List<Session> removed = new ArrayList<>();
        sessions.values().removeIf(s -> { boolean hit = s.player().equals(player) && issuer.test(s.issuer()); if (hit) removed.add(s); return hit; });
        removed.forEach(this::ended);
        return removed.size();
    }

    private static String issuer(String issuer) { return issuer == null || issuer.isBlank() ? ISSUER_PLAYER : issuer; }

    public void revokeAll() {
        tokens.clear();
        List<Session> removed = new ArrayList<>(sessions.values());
        sessions.clear();
        removed.forEach(this::ended);
    }

    public void purgeExpired() {
        long now = clock.getAsLong();
        purgeExpiredTokens(now);
        List<Session> removed = new ArrayList<>();
        sessions.values().removeIf(s -> { boolean hit = expired(s, now); if (hit) removed.add(s); return hit; });
        removed.forEach(this::ended);
    }

    /** Spieler mit mindestens einer gültigen Sitzung. */
    public Set<UUID> activePlayers() {
        long now = clock.getAsLong();
        Set<UUID> out = new HashSet<>();
        for (Session s : sessions.values()) if (!expired(s, now)) out.add(s.player());
        return out;
    }

    /**
     * Spieler mit gültiger Sitzung oder offenem Login-Link. Für diese hält {@link BukkitPlayerAccess} Schnappschüsse, damit
     * ein Link, den ein Spieler online holt und erst nach dem Ausloggen einlöst, die zuletzt online gemessenen Rechte nutzt
     * und nicht die (großzügigeren) Bukkit-Standardwerte.
     *
     * @since 0.18.1
     */
    public Set<UUID> playersWithAccess() {
        long now = clock.getAsLong();
        Set<UUID> out = activePlayers();
        for (LoginToken t : tokens.values()) if (now < t.expiresAt()) out.add(t.player());
        return out;
    }

    public int sessionCount() { return sessions.size(); }
    public int loginTokenCount() { return tokens.size(); }

    private boolean expired(Session s, long now) { return now >= s.expiresAt() || now - s.lastSeen >= idleMs; }

    private void purgeExpiredTokens(long now) { tokens.values().removeIf(t -> now >= t.expiresAt()); }

    static boolean wellFormed(String raw) {
        if (raw == null || raw.length() != TOKEN_LENGTH) return false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
        }
        return true;
    }

    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    static String hash(String raw) {
        try {
            return B64.encodeToString(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
