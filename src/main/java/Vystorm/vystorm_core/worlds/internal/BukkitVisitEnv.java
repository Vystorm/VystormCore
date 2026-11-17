package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.item.ItemRestrictions;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.party.PartyServices;
import Vystorm.vystorm_core.region.Region;
import Vystorm.vystorm_core.region.RegionService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Server-Umsetzung von {@link VisitEnv}. */
final class BukkitVisitEnv implements VisitEnv {
    static final NamespacedKey MARKER = new NamespacedKey("vystorm_core", "visit");
    static final NamespacedKey TOOL = new NamespacedKey("vystorm_core", "visit_tool");
    static final NamespacedKey LEGACY_TOOL = new NamespacedKey("vystormskills", "tw_item");
    private final Plugin core;

    BukkitVisitEnv(Plugin core) { this.core = core; }

    @Override public boolean primaryThread() { return Bukkit.isPrimaryThread(); }
    @Override public long now() { return System.currentTimeMillis(); }

    @Override public boolean pendingMigration(UUID player) {
        Plugin skills = Bukkit.getPluginManager().getPlugin("VystormSkills");
        java.nio.file.Path data = skills == null ? core.getDataFolder().toPath().getParent().resolve("VystormSkills")
                : skills.getDataFolder().toPath();
        return java.nio.file.Files.exists(data.resolve("talentwelt/sessions").resolve(player + ".yml"));
    }

    @Override public String legacy(UUID player, String action) {
        Plugin skills = Bukkit.getPluginManager().getPlugin("VystormSkills");
        java.nio.file.Path data = skills == null ? core.getDataFolder().toPath().getParent().resolve("VystormSkills")
                : skills.getDataFolder().toPath();
        String worldName = skills == null ? "vystorm_talentwelt" : skills.getConfig().getString("talent-world.world-name", "vystorm_talentwelt");
        return new LegacyVisitFiles(data.resolve("talentwelt"),
                new VisitSnapshotStore(core.getDataFolder().toPath().resolve("visits")), worldName, this::log).operate(player, action);
    }

    @Override public void nextTick(Runnable task) {
        if (core.isEnabled()) Bukkit.getScheduler().runTask(core, task);
    }

    @Override public void later(long ticks, Runnable task) {
        if (core.isEnabled()) Bukkit.getScheduler().runTaskLater(core, task, ticks);
    }

    @Override public void warn(String message) { core.getLogger().warning("[ManagedWorlds] " + message); }

    @Override public CompletableFuture<Boolean> teleportAsync(Player player, Location to) {
        return Vystorm.vystorm_core.compat.PlatformCompat.teleportAsync(player, to, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @Override public boolean teleport(Player player, Location to) { return player.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN); }
    @Override public Location mainSpawn() { return Bukkit.getWorlds().getFirst().getSpawnLocation(); }
    @Override public World world(String name) { return name == null ? null : Bukkit.getWorld(name); }
    @Override public Player player(UUID id) { return Bukkit.getPlayer(id); }
    @Override public List<Region> regionsAt(Location location) { return RegionService.get().at(location); }

    @Override public boolean sameParty(UUID a, UUID b) { return PartyServices.sameParty(a, b); }

    @Override public String commandName(String label) {
        return primaryName(label, Vystorm.vystorm_core.compat.PlatformCompat.getCommandMap().getCommand(label), Vystorm.vystorm_core.commands.CoreCommands::find);
    }

    /**
     * Main name of a command-map entry. A localized root alias of a Core command tree ({@code /aufwachen} for
     * {@code /wake}) resolves to the tree's English label, so allow-lists that name the English command accept it too.
     */
    static String primaryName(String label, Command command,
                              java.util.function.Function<String, java.util.Optional<Vystorm.vystorm_core.commands.CommandRegistration>> find) {
        if (command == null) return null;
        String plain = label.toLowerCase(Locale.ROOT);
        if (!plain.contains(":") && command instanceof org.bukkit.command.PluginIdentifiableCommand owned) {
            var tree = find.apply(plain);
            if (tree.isPresent() && tree.get().activeAliases().contains(plain) && owned.getPlugin() == tree.get().plugin())
                return tree.get().label();
        }
        return command.getName().toLowerCase(Locale.ROOT);
    }

    @Override public boolean sameCommand(String label, String primaryLabel) {
        Command command = Vystorm.vystorm_core.compat.PlatformCompat.getCommandMap().getCommand(label);
        return command != null && command == Vystorm.vystorm_core.compat.PlatformCompat.getCommandMap().getCommand(primaryLabel);
    }

    @Override public void hide(Player viewer, Player other) { viewer.hidePlayer(core, other); }
    @Override public void show(Player viewer, Player other) { viewer.showPlayer(core, other); }

    @Override public boolean hasMarker(Player player) { return player.getPersistentDataContainer().has(MARKER, PersistentDataType.STRING); }
    @Override public void setMarker(Player player, String worldId) { player.getPersistentDataContainer().set(MARKER, PersistentDataType.STRING, worldId); }
    @Override public void clearMarker(Player player) { player.getPersistentDataContainer().remove(MARKER); }

    @Override public boolean isTool(ItemStack stack, String legacyKey) {
        if (stack == null || Vystorm.vystorm_core.compat.PlatformCompat.isEmpty(stack)) return false;
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        if (pdc.has(TOOL, PersistentDataType.STRING) || pdc.has(LEGACY_TOOL)) return true;
        NamespacedKey legacy = legacyKey == null ? null : NamespacedKey.fromString(legacyKey);
        return legacy != null && pdc.has(legacy);
    }

    @Override public ItemStack markTool(ItemStack stack, String worldId) {
        ItemStack copy = stack.clone();
        Vystorm.vystorm_core.compat.PlatformCompat.editMeta(copy, meta -> meta.getPersistentDataContainer().set(TOOL, PersistentDataType.STRING, worldId));
        return ItemRestrictions.mark(copy, ItemRestrictions.VISIT_TOOL);
    }

    @Override public ItemCodec codec() { return ItemCodec.BUKKIT; }

    @Override public void message(Player player, String key, Object... placeholders) {
        try { Localization.get().core().send(player, key, placeholders); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.mid("[ManagedWorlds] message " + key + " failed: " + failure); }
    }

    @Override public void log(String message) { DebugMessages.high("[ManagedWorlds] " + message); }

    @Override public void drop(Location at, ItemStack stack) {
        if (at != null && at.getWorld() != null && stack != null && !Vystorm.vystorm_core.compat.PlatformCompat.isEmpty(stack)) at.getWorld().dropItemNaturally(at, stack);
    }
}
