package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * Unit tests of placeholders without a server: a registry of its own that is not connected to Bukkit, to Core's
 * registry or to another test. Values are registered exactly as in a plugin and resolved by Core's real parser, so
 * syntax, arguments, the thread rule and the handling of failures are those of the server.
 *
 * <pre>{@code
 * PlaceholderTesting placeholders = PlaceholderTesting.detached();
 * myPlugin.registerPlaceholders(placeholders.placeholders());      // the method that declares the plugin's values
 *
 * OfflinePlayer alex = PlaceholderTesting.viewer("Alex");
 * assertEquals("Balance: 120", placeholders.resolve(alex, "Balance: %vystorm_xpvault_balance%"));
 * assertEquals("Balance: 120", placeholders.resolve(alex, "Balance: {xpvault:balance}"));
 * }</pre>
 *
 * A plugin that asks the hub itself ({@code core.placeholders()}) gets the registry through a stubbed hub:
 *
 * <pre>{@code
 * API core = mock(API.class);
 * when(core.placeholders()).thenReturn(placeholders.placeholders());
 * }</pre>
 *
 * The thread that calls {@link #detached()} is the "server thread" of this registry; {@link #resolveOffThread}
 * resolves from another one, where only async-safe values answer. Not meant for production code.
 *
 * @since 0.65.0
 */
public final class PlaceholderTesting {
    private final Thread server = Thread.currentThread();
    private final AtomicLong tick = new AtomicLong();
    private final PlaceholderHandler handler = new PlaceholderHandler(() -> Thread.currentThread() == server, tick::get);

    private PlaceholderTesting() {}

    /** A new, empty registry without a server, without Core's own values and without bridges. */
    public static PlaceholderTesting detached() { return new PlaceholderTesting(); }

    /** An enabled plugin with this name to own a namespace (no mocking library needed). */
    public static Plugin plugin(String name) { return EventStageTesting.plugin(name); }

    /**
     * An offline player with this name and a UUID derived from it, as the viewer of a text. Every other method of it
     * returns null, false or 0, so values that need the player online stay unresolved.
     */
    public static OfflinePlayer viewer(String name) {
        Objects.requireNonNull(name, "name");
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return (OfflinePlayer) Proxy.newProxyInstance(PlaceholderTesting.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getName": return name;
                case "getUniqueId": return id;
                case "equals": return proxy == args[0];
                case "hashCode": return id.hashCode();
                case "toString": return "TestViewer[" + name + "]";
                default:
            }
            Class<?> type = method.getReturnType();
            if (!type.isPrimitive() || type == void.class) return null;
            if (type == boolean.class) return false;
            if (type == char.class) return '\0';
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            return 0;
        });
    }

    /** The registry itself, to hand to the code under test wherever it expects {@code api.placeholders()}. */
    public PlaceholderInterface placeholders() { return handler; }

    /** Same as {@link PlaceholderInterface#register(Plugin, String)} on {@link #placeholders()}. */
    public PlaceholderNamespace register(Plugin owner, String namespace) { return handler.register(owner, namespace); }

    /** Same as {@link PlaceholderInterface#resolve(OfflinePlayer, String)}; {@code viewer} may be null (console). */
    public String resolve(OfflinePlayer viewer, String text) { return handler.resolve(viewer, null, text); }

    /** Same as {@link PlaceholderInterface#component(OfflinePlayer, String)}. */
    public Component component(OfflinePlayer viewer, String miniMessage) { return handler.component(viewer, null, miniMessage); }

    /**
     * Resolves the text on another thread and waits for it: what a plugin sees that resolves from an asynchronous
     * task. Values that are not async-safe stay in the text as written.
     */
    public String resolveOffThread(OfflinePlayer viewer, String text) {
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { result.set(handler.resolve(viewer, null, text)); }
            catch (Throwable thrown) { failure.set(thrown); }
        }, "placeholder-test-async");
        worker.start();
        try { worker.join(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while resolving off the server thread", interrupted);
        }
        if (failure.get() != null) throw new IllegalStateException("resolving off the server thread failed", failure.get());
        return result.get();
    }

    /** What Core does when a plugin is disabled: removes its namespaces and bridges. Returns how many. */
    public int disable(Plugin owner) { return handler.unregisterAll(owner); }

    /** Adds a bridge owned by a test plugin; see {@link PlaceholderInterface#bridge}. Returns this. */
    public PlaceholderTesting bridge(PlaceholderBridge bridge) {
        handler.bridge(plugin("BridgeOwner"), bridge);
        return this;
    }

    /** Moves the server tick forward, for values with {@link PlaceholderValue#cacheTicks(int)}. Returns this. */
    public PlaceholderTesting advanceTicks(long ticks) {
        tick.addAndGet(ticks);
        return this;
    }
}
