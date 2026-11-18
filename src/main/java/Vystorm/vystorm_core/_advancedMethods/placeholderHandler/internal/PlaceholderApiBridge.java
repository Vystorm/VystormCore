package Vystorm.vystorm_core._advancedMethods.placeholderHandler.internal;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderBridge;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Core's bridge to PlaceholderAPI, without a dependency on it.
 * <ul>
 *   <li><b>provide:</b> registers one expansion with the identifier {@code vystorm}
 *       ({@link PlaceholderApiExpansion}, loaded by name once the plugin is enabled), so every Vystorm value works in
 *       every plugin that uses PlaceholderAPI.</li>
 *   <li><b>consume:</b> calls {@code PlaceholderAPI.setPlaceholders(OfflinePlayer, String)} (and
 *       {@code setRelationalPlaceholders} for {@code %rel_...%} with two players) through method handles, for one
 *       placeholder at a time.</li>
 * </ul>
 * {@link #provide} is called again whenever a plugin is enabled or disabled, so PlaceholderAPI may be enabled after
 * Core, disabled, or replaced by a new instance; an expansion that PlaceholderAPI dropped is registered again.
 * Nothing is written to PlaceholderAPI's folder.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class PlaceholderApiBridge implements PlaceholderBridge {
    public static final String PLUGIN = "PlaceholderAPI";
    private static final String EXPANSION = "Vystorm.vystorm_core._advancedMethods.placeholderHandler.internal.PlaceholderApiExpansion";
    private static final String ENTRY = "me.clip.placeholderapi.PlaceholderAPI";

    private record Handles(MethodHandle single, MethodHandle relational) {}

    private final Supplier<Plugin> plugin;
    private final String author;
    private final String version;
    private final BooleanSupplier offer;
    private final BooleanSupplier ask;
    private final BooleanSupplier askAsync;
    private final Object lock = new Object();
    private Plugin attached;
    private Object expansion;
    private Source bound;
    private volatile Handles handles;
    private volatile boolean providing;

    /**
     * @param plugin finds the PlaceholderAPI plugin, or null if it is not installed
     * @param author author shown by {@code /papi info vystorm}
     * @param version version shown there
     * @param offer whether Core's values are offered to PlaceholderAPI
     * @param ask whether PlaceholderAPI is asked for foreign placeholders
     * @param askAsync whether it may also be asked off the server thread
     */
    public PlaceholderApiBridge(Supplier<Plugin> plugin, String author, String version,
                                BooleanSupplier offer, BooleanSupplier ask, BooleanSupplier askAsync) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.author = author == null ? "Vystorm" : author;
        this.version = version == null ? "0" : version;
        this.offer = Objects.requireNonNull(offer, "offer");
        this.ask = Objects.requireNonNull(ask, "ask");
        this.askAsync = Objects.requireNonNull(askAsync, "askAsync");
    }

    @Override public String name() { return PLUGIN; }

    @Override
    public boolean available() {
        Plugin other = plugin.get();
        return other != null && other.isEnabled();
    }

    @Override public boolean providing() { return providing; }

    @Override public boolean asyncSafe() { return askAsync.getAsBoolean(); }

    @Override
    public boolean provide(Source source) {
        Objects.requireNonNull(source, "source");
        Plugin other = plugin.get();
        synchronized (lock) {
            if (other == null || !other.isEnabled()) {
                forget();
                return false;
            }
            if (attached != other) {
                // a new instance of the plugin: the old one took our expansion with it
                forget();
                attached = other;
            }
            handles = ask.getAsBoolean() ? (handles != null ? handles : lookup(other)) : null;
            if (!offer.getAsBoolean()) {
                unregister();
                return false;
            }
            if (expansion != null && bound == source && call(expansion, "isRegistered")) return providing = true;
            unregister();
            try {
                Object created = Class.forName(EXPANSION, true, PlaceholderApiBridge.class.getClassLoader())
                        .getConstructor(String.class, String.class, String.class, Source.class)
                        .newInstance(PlaceholderInterface.IDENTIFIER, author, version, source);
                if (!call(created, "register")) {
                    DebugMessages.high("[Placeholders] PlaceholderAPI did not accept the expansion '" + PlaceholderInterface.IDENTIFIER
                            + "' (another expansion with this identifier?); %vystorm_...% is not available in other plugins");
                    return providing = false;
                }
                expansion = created;
                bound = source;
                DebugMessages.mid("[Placeholders] PlaceholderAPI connected: expansion '" + PlaceholderInterface.IDENTIFIER + "' registered");
                return providing = true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                DebugMessages.high("[Placeholders] PlaceholderAPI expansion not registered; this version of PlaceholderAPI is not supported", failure);
                return providing = false;
            }
        }
    }

    @Override
    public void withdraw() {
        synchronized (lock) {
            // a PlaceholderAPI that is already disabled has dropped the expansion itself
            if (attached != null && attached.isEnabled()) unregister();
            forget();
        }
    }

    @Override
    public String consume(OfflinePlayer viewer, Player other, String placeholder) {
        Handles current = handles;
        if (current == null || placeholder == null) return null;
        try {
            if (other != null && current.relational() != null && viewer instanceof Player one
                    && placeholder.regionMatches(true, 0, "%rel_", 0, 5))
                return (String) current.relational().invoke(one, other, placeholder);
            return (String) current.single().invoke(viewer, placeholder);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("PlaceholderAPI failed for " + placeholder, failure);
        }
    }

    private static Handles lookup(Plugin other) {
        try {
            Class<?> entry;
            try { entry = Class.forName(ENTRY, true, other.getClass().getClassLoader()); }
            catch (ClassNotFoundException notInItsLoader) { entry = Class.forName(ENTRY, true, PlaceholderApiBridge.class.getClassLoader()); }
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle single = lookup.findStatic(entry, "setPlaceholders", MethodType.methodType(String.class, OfflinePlayer.class, String.class));
            MethodHandle relational;
            try { relational = lookup.findStatic(entry, "setRelationalPlaceholders", MethodType.methodType(String.class, Player.class, Player.class, String.class)); }
            catch (ReflectiveOperationException absent) { relational = null; }
            return new Handles(single, relational);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            DebugMessages.high("[Placeholders] PlaceholderAPI.setPlaceholders not found; placeholders of other plugins stay as written", failure);
            return null;
        }
    }

    /** Takes the expansion out of PlaceholderAPI if it is in there. Caller holds the lock. */
    private void unregister() {
        providing = false;
        Object current = expansion;
        expansion = null;
        bound = null;
        if (current == null) return;
        try {
            if (call(current, "isRegistered")) call(current, "unregister");
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.mid("[Placeholders] PlaceholderAPI expansion not unregistered: " + failure);
        }
    }

    /** Drops everything that belongs to a PlaceholderAPI instance that is gone. Caller holds the lock. */
    private void forget() {
        providing = false;
        expansion = null;
        bound = null;
        handles = null;
        attached = null;
    }

    private static boolean call(Object target, String method) {
        try { return Boolean.TRUE.equals(target.getClass().getMethod(method).invoke(target)); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("PlaceholderAPI expansion." + method + "() failed", failure); }
    }
}
