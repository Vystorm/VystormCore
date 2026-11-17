package Vystorm.vystorm_core.licensing;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.licensing.internal.RamModuleClassLoader;
import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Optional Core API for paid-plugin modules, not for protecting Core itself.
 * Requires a compatible lease/download backend; the existing license/verify endpoint is insufficient.
 * All lifecycle methods and notifications run on the server thread; network/verification/unpacking do not.
 * @since 0.28.0 */
public final class LicensedModules {
    private static final Map<Plugin, Session> SESSIONS = new IdentityHashMap<>();
    private LicensedModules() {}

    public enum State { LOADING, ACTIVE, DENIED, UNAVAILABLE, EXPIRED, FAILED, CLOSED }

    /** Starts one remote module for this owner. Must be called on the server thread after the owner is enabled.
     * The callback receives changes (not initial LOADING); it must not start the module itself.
     * No protected classes are persisted by Core. Requests use normal verified HTTPS, without redirects.
     * Backend must atomically limit leases per customer/product. No offline/restart cache is provided. */
    public static Session start(Plugin owner, ModuleRequest request, Consumer<State> status) {
        serverThread();
        Objects.requireNonNull(owner);
        Objects.requireNonNull(request);
        Objects.requireNonNull(status);
        Plugin core = CoreApi.plugin();
        if (!owner.isEnabled() || core == null || !core.isEnabled()) throw new IllegalStateException("Plugins must be enabled");
        if (SESSIONS.containsKey(owner)) throw new IllegalStateException("Owner already has a module session");
        Session session = new Session(owner, core, request, status);
        SESSIONS.put(owner, session);
        try {
            session.watchdog = owner.getServer().getScheduler().runTaskTimer(core, session::tick, 20L, 20L);
            session.exchange("acquire");
        } catch (RuntimeException failure) {
            session.finish(State.FAILED);
        }
        return session;
    }

    /** Server thread. Also invoked automatically by CoreLifecycle when owner is disabled. */
    public static void release(Plugin owner) {
        Session session = SESSIONS.get(owner);
        if (session != null) session.close();
    }

    /** Server thread. Called before Core's other services shut down. */
    public static void shutdown() {
        serverThread();
        for (Session session : new ArrayList<>(SESSIONS.values())) session.close();
        ModuleTransport.shutdown();
    }

    private static void serverThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Module lifecycle requires the server thread");
    }

    /** Per-process session: identity and lease credentials exist only in RAM. No reusable server-id file.
     * active() and state() are thread-safe; close() must run on the server thread. */
    public static final class Session implements AutoCloseable {
        private final Plugin owner, core;
        private final ModuleRequest request;
        private final Consumer<State> listener;
        private final String instance = UUID.randomUUID().toString();
        private volatile State state = State.LOADING;
        private volatile long deadline;
        private volatile boolean terminal;
        private String lease = "";
        private long nextRenew;
        private ModuleTransport.Call pending;
        private BukkitTask watchdog;
        private RamModuleClassLoader loader;
        private LicensedModule module;

        private Session(Plugin owner, Plugin core, ModuleRequest request, Consumer<State> listener) {
            this.owner = owner;
            this.core = core;
            this.request = request;
            this.listener = listener;
        }

        public State state() { return state; }

        /** Check before EVERY protected operation. Uses monotonic time even when the server tick is delayed.
         * A loaded Java class cannot be forcibly unloaded/revoked; module cooperation is required. */
        public boolean active() { return !terminal && state == State.ACTIVE && deadline - System.nanoTime() > 0; }

        @Override public void close() { serverThread(); finish(State.CLOSED); }

        private void tick() {
            if (terminal) return;
            if (!owner.isEnabled() || !core.isEnabled()) { finish(State.CLOSED); return; }
            if (state != State.ACTIVE) return;
            if (!active()) { finish(State.EXPIRED); return; }
            if (pending == null && System.nanoTime() - nextRenew >= 0) exchange("renew");
        }

        private void exchange(String action) {
            ModuleTransport.Call call = ModuleTransport.request(request, instance, action, lease);
            pending = call;
            call.result().whenCompleteAsync((reply, failure) -> {
                RamModuleClassLoader prepared = null;
                boolean failed = failure != null;
                if (!failed && reply.granted() && action.equals("acquire")) {
                    try { prepared = new RamModuleClassLoader(reply.jar(), owner.getClass().getClassLoader(), request.packagePrefix); }
                    catch (Exception | LinkageError invalid) { failed = true; }
                    finally { Arrays.fill(reply.jar(), (byte) 0); }
                }
                RamModuleClassLoader ready = prepared;
                boolean unavailable = failed;
                try {
                    owner.getServer().getScheduler().runTask(core, () -> accept(call, action, reply, ready, unavailable));
                } catch (RuntimeException stopped) {
                    if (ready != null) ready.close();
                    // A remote acquire/renew may already have succeeded. Its bounded lease expires server-side.
                }
            });
        }

        private void accept(ModuleTransport.Call call, String action, ModuleTransport.Reply reply,
                            RamModuleClassLoader prepared, boolean failed) {
            if (terminal || pending != call || !owner.isEnabled() || !core.isEnabled()) {
                if (prepared != null) prepared.close();
                if (!terminal) finish(State.CLOSED);
                return;
            }
            pending = null;
            if (failed) {
                if (prepared != null) prepared.close();
                if (state == State.LOADING) finish(State.UNAVAILABLE);
                else nextRenew = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                return; // Original deadline is never extended by transport/verification failures.
            }
            if (!reply.granted()) { if (prepared != null) prepared.close(); finish(State.DENIED); return; }
            // Never resurrect a lease after its previous deadline, even if a late renew was accepted remotely.
            if ((state == State.ACTIVE && !active()) || reply.deadline() - System.nanoTime() <= 0) {
                if (prepared != null) prepared.close();
                finish(State.EXPIRED);
                return;
            }
            lease = reply.lease();
            deadline = reply.deadline();
            nextRenew = System.nanoTime() + (deadline - System.nanoTime()) / 3;
            if (!action.equals("acquire")) return;
            loader = prepared;
            try {
                Class<? extends LicensedModule> entry = loader.loadClass(request.entryPoint).asSubclass(LicensedModule.class);
                module = entry.getConstructor().newInstance();
                state = State.ACTIVE;
                module.start(owner, this::active);
                // Module start may have disabled its owner or explicitly closed this session.
                if (!terminal && owner.isEnabled() && active()) notifyState();
                else if (!terminal) finish(State.EXPIRED);
            } catch (Exception | LinkageError failure) {
                report("start");
                finish(State.FAILED);
            }
        }

        private void finish(State end) {
            if (terminal) return;
            terminal = true; // Authorization closes BEFORE module cleanup or lease release.
            state = end;
            if (SESSIONS.get(owner) == this) SESSIONS.remove(owner);
            if (watchdog != null) watchdog.cancel();
            if (pending != null) { pending.cancel().run(); pending = null; }
            boolean stopped = true;
            try { if (module != null) module.stop(); }
            catch (Exception | LinkageError failure) { stopped = false; report("stop"); }
            finally {
                module = null;
                if (loader != null) loader.close();
                loader = null;
            }
            // Best effort only. If a crash, lost response, or failed stop prevents release, the backend waits for TTL.
            if (stopped && !lease.isEmpty()) {
                try { ModuleTransport.request(request, instance, "release", lease); }
                catch (RuntimeException ignored) { }
            }
            lease = "";
            notifyState();
        }

        private void notifyState() {
            try { listener.accept(state); }
            catch (RuntimeException | LinkageError failure) { report("status callback"); }
        }

        private void report(String operation) {
            // A module/observer exception may include credentials; never log its message or cause.
            ProviderFailures.report("LicensedModules", owner.getName(), operation,
                    new IllegalStateException("Module lifecycle operation failed"));
        }

        @Override public String toString() { return "ModuleSession[state=" + state + "]"; }
    }
}
