package Vystorm.vystorm_core.panel.internal;

import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.panel.FallbackContext;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.web.WebOpenResult;
import Vystorm.vystorm_core.web.WebServices;
import java.util.logging.Level;
import org.bukkit.entity.Player;

/**
 * {@code Panels.open} (Spec §7): Recht → natives Panel → eigenes {@code fallback} des Plugins → [Paper-Dialog] →
 * Web-Link ({@code WebServices.openOrLink}) → Hinweis. Server-Thread. Core-intern.
 */
public final class PanelOpener {
    private PanelOpener() {}

    /** Core config {@code screens.panels} (default true on the screens branch; the owner decides before release). */
    static boolean screensForPanels() {
        try { return Vystorm.vystorm_core.main.getPlugin().getConfig().getBoolean("screens.panels", true); }
        catch (RuntimeException | LinkageError noCore) { return false; }
    }

    /** The values and status line for the next session of {@code panelId} opened for {@code player} (one reopen). */
    static final class Seed {
        final java.util.UUID player;
        final String panelId;
        final java.util.Map<String, Object> values;
        final java.util.List<String> status;
        /** 0.71.0: the pick's result for a {@link FallbackContext} (null = none). */
        final Vystorm.vystorm_core.panel.Result result;
        /** 0.71.0: false for {@link Call#reopen()} (same session, no return). */
        final boolean returning;
        boolean statusShown;

        Seed(java.util.UUID player, String panelId, java.util.Map<String, Object> values, java.util.List<String> status) {
            this(player, panelId, values, status, null, true);
        }

        Seed(java.util.UUID player, String panelId, java.util.Map<String, Object> values, java.util.List<String> status,
             Vystorm.vystorm_core.panel.Result result, boolean returning) {
            this.player = player;
            this.panelId = panelId;
            this.values = values;
            this.status = status;
            this.result = result;
            this.returning = returning;
        }
    }

    /**
     * 0.71.0: the {@link FallbackContext} of one fallback call. Its session is the seed's map itself (not a copy), so
     * what the chest writes reaches {@link #reopen()} and {@code PickReturn.panel(ctx)}. Core keeps no reference to
     * it after the call: it lives as long as the plugin's chest holds it. Server thread. Core-internal.
     */
    static final class Call implements FallbackContext {
        final Player player;
        final PanelRegistry.Registered reg;   // null: a spec that is not registered (fresh context from the getter)
        final String arg;
        final java.util.Map<String, Object> values;
        final Vystorm.vystorm_core.panel.Result result;
        final boolean returning;
        final MapSession session;
        /** Set only while the fallback call runs: {@link #resultShown()} counts then (the chat lines go right after it). */
        boolean resultShown;
        boolean callRunning;

        Call(Player player, PanelRegistry.Registered reg, String arg, java.util.Map<String, Object> values, Vystorm.vystorm_core.panel.Result result, boolean returning) {
            this.player = player;
            this.reg = reg;
            this.arg = arg == null ? "" : arg;
            this.values = values;
            this.result = result;
            this.returning = returning;
            this.session = new MapSession(values);
        }

        @Override public Player player() { return player; }
        @Override public String arg() { return arg; }
        @Override public PanelSession session() { return session; }
        @Override public java.util.Optional<Vystorm.vystorm_core.panel.Result> result() { return java.util.Optional.ofNullable(result); }
        @Override public boolean returning() { return returning; }
        @Override public void resultShown() { if (callRunning) resultShown = true; }

        @Override public PanelOpenResult reopen() {
            if (reg == null || player == null || !player.isOnline() || running(player, reg.id())) return PanelOpenResult.NONE;
            // the same registration only: after a disable and re-enable the old values belong to the old plugin instance
            PanelRegistry.Registered now = PanelRegistry.find(reg.id()).orElse(null);
            if (now == null || now.owner() != reg.owner() || !now.owner().isEnabled()) return PanelOpenResult.NONE;
            Seed seed = new Seed(player.getUniqueId(), now.id(), values, java.util.List.of(), null, false);
            return withSeed(seed, () -> open(player, now, arg));
        }
    }

    /** A session store over a map; id 0 (no client-mod session). */
    static final class MapSession implements PanelSession {
        final java.util.Map<String, Object> map;

        MapSession(java.util.Map<String, Object> map) { this.map = map; }

        @Override public int id() { return 0; }
        @SuppressWarnings("unchecked") @Override public <T> T get(String key) { return (T) map.get(key); }
        @Override public void put(String key, Object value) { map.put(key, value); }
        @Override public void remove(String key) { map.remove(key); }
    }

    /** {@code PanelSpec.fallback()} for a context fallback: arg empty, fresh session, no result. Core-internal. */
    public static FallbackContext freshContext(Player player, Vystorm.vystorm_core.panel.PanelSpec spec) {
        return new Call(player, PanelRegistry.findBySpec(spec).orElse(null), "", new java.util.LinkedHashMap<>(), null, false);
    }

    /** {@code PanelSpec.fallback()} of a context fallback: runs it with a fresh context under the same guard as Core's own call. */
    public static void runFresh(Player player, Vystorm.vystorm_core.panel.PanelSpec spec, java.util.function.BiConsumer<Player, FallbackContext> fallback) {
        Call call = (Call) freshContext(player, spec);
        call(player, call.reg == null ? null : call.reg.id(), call, fallback);
    }

    /** Server thread only: "player|panel id" of the fallback calls running now (reopen from inside one returns NONE). */
    private static final java.util.Set<String> runningCalls = new java.util.HashSet<>();

    static boolean running(Player player, String panelId) { return runningCalls.contains(player.getUniqueId() + "|" + panelId); }

    /** Runs a context fallback: guard on, no pending seed visible to nested opens (they are fresh opens). True = returned normally. */
    private static boolean call(Player player, String panelId, Call call, java.util.function.BiConsumer<Player, FallbackContext> fallback) {
        String key = player == null || panelId == null ? null : player.getUniqueId() + "|" + panelId;
        boolean added = key != null && runningCalls.add(key);
        Seed before = pending;
        pending = null;
        call.callRunning = true;
        try {
            fallback.accept(player, call);
            return true;
        } finally {
            call.callRunning = false;
            pending = before;
            if (added) runningCalls.remove(key);
        }
    }

    /**
     * 0.71.0 ({@code PickReturn.panel(FallbackContext)}): the panel, argument and the session map itself of a context
     * Core handed to a fallback; empty for a context from elsewhere (a test double) or of an unregistered spec.
     */
    public static java.util.Optional<PanelResume> resumeOf(FallbackContext ctx) {
        if (!(ctx instanceof Call call) || call.reg == null) return java.util.Optional.empty();
        return java.util.Optional.of(new PanelResume(call.reg, call.arg, call.values));
    }

    /** 0.71.0: told when the screen of a panel closed, with the reason (Core's player picker tells Back from damage). */
    @FunctionalInterface
    public interface ClosedListener { void closed(Player player, String panelId, String arg, Vystorm.vystorm_core.screen.ScreenInput.Reason reason); }

    /** Core-internal; one listener (the picker runtime), null = none. */
    public static volatile ClosedListener closedListener;

    /**
     * 0.71.1: what a list row of a panel draws as its icon on a Vystorm Screen instead of the row's item; null keeps
     * the item. Core's player picker gives its rows the player's skin ({@code ScreenHeads.Head}) this way, since a
     * panel's item icon carries only the item id.
     */
    @FunctionalInterface
    public interface ListIcons { Object icon(Player viewer, String panelId, String entryKey); }

    /** Core-internal; one provider (the picker runtime), null = none. Server thread. */
    public static volatile ListIcons listIcons;

    /** Server thread only: per player, the input whose typing box opens after the current screen action (one use). */
    private static final java.util.Map<java.util.UUID, String> typeNext = new java.util.HashMap<>();

    /**
     * 0.71.0: called from a panel handler on a Vystorm Screen: after the action the redrawn screen opens the typing box
     * of the input {@code elementId} at once (Core's player picker: a click on the Search tab is one prompt). Ignored
     * on other routes and when the redrawn panel has no such input. Server thread.
     */
    public static void typeNext(Player player, String elementId) {
        if (player == null || elementId == null) return;
        typeNext.put(player.getUniqueId(), elementId);
    }

    /** The pending request of {@link #typeNext}, taken (null when none). */
    public static String takeTypeNext(Player player) { return player == null ? null : typeNext.remove(player.getUniqueId()); }

    /** Server thread only: set for the duration of one {@link #reopen}. */
    private static Seed pending;

    /**
     * 0.71.0 ({@code PickReturn}): opens {@code resume}'s panel again by the normal route with the carried session
     * values in the new session and {@code status} as its status line (screen and dialog); a route without a status
     * line (client mod, the plugin's own chest fallback, web) gets the lines in chat. Server thread.
     */
    public static PanelOpenResult reopen(Player player, PanelResume resume, java.util.List<String> status) {
        return reopen(player, resume, status, null);
    }

    /**
     * 0.71.0: as {@link #reopen(Player, PanelResume, java.util.List)} with the handler's result for a plugin's
     * context fallback ({@link FallbackContext#result()}); its status lines go to chat unless the fallback calls
     * {@link FallbackContext#resultShown()}.
     */
    public static PanelOpenResult reopen(Player player, PanelResume resume, Vystorm.vystorm_core.panel.Result result) {
        return reopen(player, resume, status(player, result), result);
    }

    private static PanelOpenResult reopen(Player player, PanelResume resume, java.util.List<String> status, Vystorm.vystorm_core.panel.Result result) {
        Seed seed = new Seed(player.getUniqueId(), resume.reg().id(), resume.values(), status == null ? java.util.List.of() : status, result, true);
        PanelOpenResult result2 = withSeed(seed, () -> open(player, resume.reg(), resume.arg()));
        if (!seed.statusShown) for (String line : seed.status) chat(player, line);
        return result2;
    }

    static PanelOpenResult withSeed(Seed seed, java.util.function.Supplier<PanelOpenResult> open) {
        Seed before = pending;
        pending = seed;
        try {
            return open.get();
        } finally {
            pending = before;
        }
    }

    /** A new session of the reopened panel takes the carried values; returns the seed (null when none applies). */
    static Seed seed(PanelHub.Session s) {
        Seed seed = pendingFor(s.player, s.reg);
        if (seed == null) return null;
        s.state.putAll(seed.values);
        return seed;
    }

    /** The pending seed when it belongs to this player and panel, else null. */
    static Seed pendingFor(Player player, PanelRegistry.Registered reg) {
        Seed seed = pending;
        if (seed == null || player == null || reg == null || !seed.player.equals(player.getUniqueId()) || !seed.panelId.equals(reg.id())) return null;
        return seed;
    }

    /**
     * The plugin's own fallback: a context fallback gets arg, the carried session (the seed's map itself, so the chest
     * writes into the values a later return carries) and the result; a one-argument fallback only the player.
     */
    static void runFallback(Player player, PanelRegistry.Registered reg, String arg) {
        Vystorm.vystorm_core.panel.PanelSpec spec = reg.spec();
        java.util.function.BiConsumer<Player, FallbackContext> withContext = spec.contextFallback();
        if (withContext == null) {
            spec.fallback().accept(player);
            return;
        }
        Seed seed = pendingFor(player, reg);
        Call call = new Call(player, reg, arg, seed == null ? new java.util.LinkedHashMap<>() : seed.values,
                seed == null ? null : seed.result, seed != null && seed.returning);
        // only a fallback that returned normally may take the chat line away (a throwing one leaves it in chat)
        if (call(player, reg.id(), call, withContext) && seed != null && call.resultShown) seed.statusShown = true;
    }

    /** Status lines of a handler result as screens and dialogs show them ({@code ✔ }/{@code ✖ } in front). */
    public static java.util.List<String> status(Player player, Vystorm.vystorm_core.panel.Result result) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (result == null) return out;
        if (!result.message().isEmpty()) out.add((result.success() ? "✔ " : "✖ ") + PanelHub.plainText(player, result.message()));
        result.fields().values().forEach(v -> out.add("✖ " + PanelHub.plainText(player, v)));
        return out;
    }

    /** One status line ({@code "✔ text"} / {@code "✖ text"}) in chat, green or red. */
    static void chat(Player player, String line) {
        if (line == null || line.isEmpty()) return;
        String color = line.startsWith("✖") ? "§c" : "§a";
        try { Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(player, color + line); }
        catch (RuntimeException | LinkageError noChat) { }
    }

    public static PanelOpenResult open(Player player, PanelRegistry.Registered reg, String arg) {
        if (player == null || reg == null || !reg.owner().isEnabled()) return PanelOpenResult.NONE;
        boolean allowed;
        try { allowed = reg.spec().access().test(player); } catch (RuntimeException failure) { allowed = false; }
        if (!allowed) {
            Localization.get().core().send(player, "panel.no-permission");
            return PanelOpenResult.NONE;
        }
        PanelHub hub = PanelPlatform.hub().orElse(null);
        if (hub != null && hub.available(player)) return hub.openNative(player, reg, arg, 0);
        // Vystorm Screens (0.57.0): before the plugin's own fallback, so classic chest menus give way to the screen.
        if (screensForPanels() && hub != null && hub.openScreen(player, reg, arg)) return PanelOpenResult.DIALOG;
        if (reg.spec().fallback() != null || reg.spec().contextFallback() != null) {
            try {
                runFallback(player, reg, arg);
            } catch (RuntimeException failure) {
                reg.owner().getLogger().log(Level.WARNING, "Panel fallback of " + reg.id() + " failed", failure);
            }
            return PanelOpenResult.FALLBACK;
        }
        if (reg.spec().dialogFallback()) {
            PanelHub dialogHub = PanelPlatform.hub().orElse(null);
            if (dialogHub != null && dialogHub.openDialog(player, reg, arg)) return PanelOpenResult.DIALOG;
        }
        if (reg.spec().webModule() != null) {
            try {
                WebOpenResult r = WebServices.openOrLink(player, reg.spec().webModule(), reg.spec().webSubPath(), null);
                if (r == WebOpenResult.LINK || r == WebOpenResult.OVERLAY) return PanelOpenResult.WEB;
            } catch (RuntimeException | LinkageError webOff) {
                // weiter zum Hinweis
            }
        }
        Localization.get().core().send(player, "panel.unavailable");
        return PanelOpenResult.NONE;
    }
}
