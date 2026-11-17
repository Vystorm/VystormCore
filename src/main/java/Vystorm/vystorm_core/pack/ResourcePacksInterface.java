package Vystorm.vystorm_core.pack;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core.main;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import Vystorm.vystorm_core.pack.ResourcePacks.*;

/**
 * Manager view of {@link ResourcePacks}, reached through {@code api.ressource().packs()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ResourcePacks}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ResourcePacksInterface {
    /** The delegating default. Plugins use {@code api.ressource().packs()}. */
    ResourcePacksInterface DEFAULT = new ResourcePacksInterface() {};

    /** See {@link ResourcePacks#assets}. */
    default AssetRegistry assets() { return ResourcePacks.assets(); }

    /** See {@link ResourcePacks#register}. */
    default void register(Plugin owner, String module, List<String> namespaces, Map<String, byte[]> files, int priority) {
        ResourcePacks.register(owner, module, namespaces, files, priority);
    }

    /** See {@link ResourcePacks#state}. */
    default PackState state(Player player) { return ResourcePacks.state(player); }

    /** See {@link ResourcePacks#hasRequiredAssets}. */
    default boolean hasRequiredAssets(Player player, AssetRequirement requirement) { return ResourcePacks.hasRequiredAssets(player, requirement); }

    /** See {@link ResourcePacks#ensurePack}. */
    default CompletionStage<PackState> ensurePack(Player player) { return ResourcePacks.ensurePack(player); }

    /** See {@link ResourcePacks#hash}. */
    default Optional<String> hash() { return ResourcePacks.hash(); }

    /** See {@link ResourcePacks#conflicts}. */
    default List<String> conflicts() { return ResourcePacks.conflicts(); }

    /** See {@link ResourcePacks#url}. */
    default Optional<String> url() { return ResourcePacks.url(); }

    /** See {@link ResourcePacks#rebuildSoon}. */
    default void rebuildSoon() { ResourcePacks.rebuildSoon(); }
}
