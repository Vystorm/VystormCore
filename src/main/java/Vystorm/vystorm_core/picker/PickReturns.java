package Vystorm.vystorm_core.picker;

import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.internal.PanelHub;
import Vystorm.vystorm_core.panel.internal.PanelOpener;
import Vystorm.vystorm_core.panel.internal.PanelRegistry;
import Vystorm.vystorm_core.panel.internal.PanelResume;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** The four kinds of {@link PickReturn}. Package-private: plugins use the factories on {@link PickReturn}. */
final class PickReturns {
    private PickReturns() {}

    /** A session store over a map (the carried values). */
    static final class Values implements PanelSession {
        final Map<String, Object> map;

        Values(Map<String, Object> map) { this.map = map; }

        @Override public int id() { return 0; }
        @SuppressWarnings("unchecked") @Override public <T> T get(String key) { return (T) map.get(key); }
        @Override public void put(String key, Object value) { map.put(key, value); }
        @Override public void remove(String key) { map.remove(key); }
    }

    static PickReturn same(PanelAction from) {
        Objects.requireNonNull(from, "from");
        return PanelHub.resumeOf(from).<PickReturn>map(Same::new).orElseGet(Stay::new);
    }

    /** 0.71.0: from a plugin's chest fallback; the carried values are the context's session map itself. */
    static PickReturn same(Vystorm.vystorm_core.panel.FallbackContext from) {
        Objects.requireNonNull(from, "from");
        java.util.UUID owner = from.player() == null ? null : from.player().getUniqueId();
        return PanelOpener.resumeOf(from).<PickReturn>map(r -> new Same(r, owner)).orElseGet(Stay::new);
    }

    static PickReturn named(Plugin owner, String panelName, String arg) {
        Objects.requireNonNull(owner, "owner");
        String id = Panels.of(owner).id(panelName);   // checks the name now
        return new Named(owner, id, arg == null ? "" : arg);
    }

    static PickReturn run(Consumer<Player> next) { return new Run(Objects.requireNonNull(next, "next")); }

    static PickReturn stay() { return new Stay(); }

    static void chat(Player viewer, Result result) {
        for (String line : PanelOpener.status(viewer, result)) {
            try { Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, (line.startsWith("✖") ? "§c" : "§a") + line); }
            catch (RuntimeException | LinkageError noChat) { }
        }
    }

    /** Opens the registered panel again (owner still enabled), else only the chat line. */
    static void reopen(Player viewer, PanelResume resume, Result result) {
        PanelRegistry.Registered now = PanelRegistry.find(resume.reg().id()).orElse(null);
        if (now == null || !now.owner().isEnabled() || now.owner() != resume.reg().owner()) {   // disabled since, or re-enabled: values of the old instance
            chat(viewer, result);
            return;
        }
        // 0.71.0: the result itself too, so a plugin's context fallback can show it (FallbackContext.result())
        PanelOpener.reopen(viewer, new PanelResume(now, resume.arg(), resume.values()), result);
    }

    static final class Same implements PickReturn {
        final PanelResume resume;
        final Values session;
        /** From a chest context: the live map belongs to this player only (null = any, a copy from an action). */
        final java.util.UUID onlyFor;

        Same(PanelResume resume) { this(resume, null); }

        Same(PanelResume resume, java.util.UUID onlyFor) {
            this.resume = resume;
            this.session = new Values(resume.values());
            this.onlyFor = onlyFor;
        }

        @Override public PanelSession session() { return session; }
        @Override public Optional<Plugin> owner() { return Optional.of(resume.reg().owner()); }
        @Override public void go(Player viewer, Result result) {
            if (viewer == null || !viewer.isOnline()) return;
            if (onlyFor != null && !onlyFor.equals(viewer.getUniqueId())) { chat(viewer, result); return; }   // another player's chest values
            reopen(viewer, resume, result);
        }
    }

    static final class Named implements PickReturn {
        final Plugin plugin;
        final String panelId;
        final String arg;
        final Values session = new Values(new LinkedHashMap<>());

        Named(Plugin plugin, String panelId, String arg) {
            this.plugin = plugin;
            this.panelId = panelId;
            this.arg = arg;
        }

        @Override public PanelSession session() { return session; }
        @Override public Optional<Plugin> owner() { return Optional.of(plugin); }
        @Override public void go(Player viewer, Result result) {
            if (viewer == null || !viewer.isOnline()) return;
            PanelRegistry.Registered reg = PanelRegistry.find(panelId).orElse(null);
            if (reg == null) chat(viewer, result);
            else reopen(viewer, new PanelResume(reg, arg, session.map), result);
        }
    }

    static final class Run implements PickReturn {
        final Consumer<Player> next;
        final Values session = new Values(new LinkedHashMap<>());

        Run(Consumer<Player> next) { this.next = next; }

        @Override public PanelSession session() { return session; }
        @Override public Optional<Plugin> owner() { return Optional.empty(); }
        @Override public void go(Player viewer, Result result) {
            if (viewer == null || !viewer.isOnline()) return;
            try {
                next.accept(viewer);
            } catch (RuntimeException failure) {
                Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages.high("[Pickers] Return code failed", failure);
            }
            chat(viewer, result);
        }
    }

    static final class Stay implements PickReturn {
        final Values session = new Values(new LinkedHashMap<>());

        @Override public PanelSession session() { return session; }
        @Override public Optional<Plugin> owner() { return Optional.empty(); }
        @Override public void go(Player viewer, Result result) {
            if (viewer == null || !viewer.isOnline()) return;
            chat(viewer, result);
        }
    }

}
