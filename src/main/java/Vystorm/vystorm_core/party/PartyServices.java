package Vystorm.vystorm_core.party;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.main;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Zentraler Party-Zugang für alle Vystorm-Plugins, ohne Abhängigkeit auf das Party-Plugin. Genau eine Quelle
 * (VystormParties) registriert sich; ohne Quelle ist jeder Spieler allein. Änderungen kommen als
 * {@link PartyChangeEvent}.
 * <p>Preferred access: {@code api.parties()}.
 */
public final class PartyServices {
    private record Registration(Plugin owner, PartySource source) {}
    private static final AtomicReference<Registration> SOURCE = new AtomicReference<>();
    private static final AtomicBoolean LIFECYCLE = new AtomicBoolean();

    private PartyServices() {}

    public static void register(Plugin owner, PartySource source) {
        if (owner == null || source == null) throw new IllegalArgumentException("owner/source missing");
        Registration previous = SOURCE.getAndSet(new Registration(owner, source));
        if (previous != null) ProviderFailures.replaced("PartyServices", "Party-Quelle", previous.owner(), owner);
        ensureLifecycle();
    }

    public static void unregister(Plugin owner) {
        SOURCE.updateAndGet(current -> current != null && current.owner().equals(owner) ? null : current);
    }

    public static boolean available() { return SOURCE.get() != null; }

    public static Optional<PartyView> partyOf(UUID player) {
        var registration = SOURCE.get();
        if (registration == null || player == null) return Optional.empty();
        try { return registration.source().partyOf(player); } catch (RuntimeException failure) { failed(registration, "partyOf", failure); return Optional.empty(); }
    }

    public static Optional<PartyView> party(UUID partyId) {
        var registration = SOURCE.get();
        if (registration == null || partyId == null) return Optional.empty();
        try { return registration.source().party(partyId); } catch (RuntimeException failure) { failed(registration, "party", failure); return Optional.empty(); }
    }

    public static Collection<PartyView> all() {
        var registration = SOURCE.get();
        if (registration == null) return List.of();
        try { return List.copyOf(registration.source().all()); } catch (RuntimeException failure) { failed(registration, "all", failure); return List.of(); }
    }

    /** Ob beide Spieler (verschieden) in derselben Party sind. */
    public static boolean sameParty(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return false;
        return partyOf(a).map(party -> party.contains(b)).orElse(false);
    }

    /** Online-Mitglieder der Party des Spielers (ohne ihn selbst). */
    public static List<Player> onlineMembers(Player player) {
        List<Player> result = new ArrayList<>();
        partyOf(player.getUniqueId()).ifPresent(party -> {
            for (UUID id : party.members()) {
                if (id.equals(player.getUniqueId())) continue;
                Player member = Bukkit.getPlayer(id);
                if (member != null && member.isOnline()) result.add(member);
            }
        });
        return result;
    }

    /** Online-Mitglieder in derselben Welt innerhalb von {@code radius} Blöcken (ohne den Spieler). */
    public static List<Player> nearbyMembers(Player player, double radius) {
        List<Player> result = new ArrayList<>();
        double squared = radius * radius;
        for (Player member : onlineMembers(player))
            if (member.getWorld().equals(player.getWorld()) && member.getLocation().distanceSquared(player.getLocation()) <= squared) result.add(member);
        return result;
    }

    /** Party-Einstellung lesen ({@code fallback} ohne Party/Quelle). */
    public static boolean flag(UUID player, String key, boolean fallback) {
        return partyOf(player).map(party -> party.flag(key, fallback)).orElse(fallback);
    }

    /** Party-Einstellung setzen (für Erweiterungen anderer Plugins). */
    public static boolean setting(UUID partyId, String key, String value) {
        var registration = SOURCE.get();
        if (registration == null || partyId == null || key == null) return false;
        try { return registration.source().setting(partyId, key, value); } catch (RuntimeException failure) { failed(registration, "setting", failure); return false; }
    }

    /** Server-thread advisory signal; consumers must recheck membership, expiry and their own entry rules. */
    public static Optional<PartyReadyCheck> readyCheck(UUID partyId) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Party readiness requires the server thread");
        var registration = SOURCE.get();
        if (registration == null || !registration.owner().isEnabled() || partyId == null) return Optional.empty();
        try { return registration.source().readyCheck(partyId); }
        catch (RuntimeException failure) { failed(registration, "readyCheck", failure); return Optional.empty(); }
    }

    /** Records observed progress in an opted-in session; no XP or other rewards are granted here. Server thread only. */
    public static boolean recordProgress(Plugin provider, UUID partyId, UUID eventId, String goal, double sharedXp) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Party progress requires the server thread");
        var registration = SOURCE.get();
        if (registration == null || !registration.owner().isEnabled() || provider == null || !provider.isEnabled()
                || partyId == null || eventId == null || goal == null || goal.length() > 120 || !Double.isFinite(sharedXp) || sharedXp < 0) return false;
        try { return registration.source().recordProgress(partyId, eventId, provider.getName(), goal, sharedXp); }
        catch (RuntimeException failure) { failed(registration, "recordProgress", failure); return false; }
    }

    private static void failed(Registration registration, String operation, RuntimeException failure) {
        ProviderFailures.report("PartyServices", registration.owner().getName(), operation, failure);
    }

    /** Von der Quelle aufzurufen (Server-Thread). */
    public static void fire(PartyChangeEvent event) {
        if (Bukkit.isPrimaryThread()) { Bukkit.getPluginManager().callEvent(event);return; }
        Plugin core = main.getPlugin();
        if (core != null && core.isEnabled()) Bukkit.getScheduler().runTask(core, () -> Bukkit.getPluginManager().callEvent(event));
    }

    private static void ensureLifecycle() {
        if (LIFECYCLE.get() || Bukkit.getServer() == null || !Bukkit.isPrimaryThread()) return;
        Plugin core = main.getPlugin();
        if (core == null || !core.isEnabled() || !LIFECYCLE.compareAndSet(false, true)) return;
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void onDisable(PluginDisableEvent event) { unregister(event.getPlugin()); }
        }, core);
    }
}
