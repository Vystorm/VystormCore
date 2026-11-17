package Vystorm.vystorm_core.compat;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Shared Paper, Spigot and CraftBukkit adapters. World/entity operations require the server thread.
 * Native Paper operations are retained; Bukkit fallbacks do not install replacement server API classes.
 * <p>Preferred access: {@code api.compat().platform()}.
 *
 * @since 0.51.0
 */
public final class PlatformCompat {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('§')
            .hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static volatile Plugin owner;
    private static volatile boolean stopping;
    private static volatile int ticks;
    private static volatile double measuredTps = 20;
    private static long lastSample;
    private static BukkitTask clock;
    private static final Map<BossBar, BarBridge> BARS = new IdentityHashMap<>();
    private static final Map<UUID, Integer> FREEZE = new HashMap<>();
    private PlatformCompat() {}

    /** Core lifecycle only. Starts one shared clock and fallback freeze maintenance task. */
    public static void initialize(Plugin plugin) {
        owner = Objects.requireNonNull(plugin);
        stopping = false;
        lastSample = System.nanoTime();
        if (clock != null) clock.cancel();
        clock = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ticks++;
            if (ticks % 20 == 0) {
                long now = System.nanoTime();
                measuredTps = Math.min(20, 20_000_000_000d / Math.max(1, now - lastSample));
                lastSample = now;
            }
            for (var it = FREEZE.entrySet().iterator(); it.hasNext();) {
                var entry = it.next();
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player == null || !player.isOnline()) {
                    it.remove();
                    continue;
                }
                player.setFreezeTicks(entry.getValue());
            }
        }, 1L, 1L);
    }

    /** Core lifecycle only. */
    public static void shutdown() {
        stopping = true;
        if (clock != null) {
            clock.cancel();
            clock = null;
        }
        FREEZE.clear();
        for (BarBridge bridge : List.copyOf(BARS.values())) bridge.close();
        BARS.clear();
        owner = null;
    }

    public static String legacy(Component value) {
        return value == null ? "" : LEGACY.serialize(value);
    }

    public static void sendMessage(CommandSender recipient, Component message) {
        Objects.requireNonNull(recipient);
        Objects.requireNonNull(message);
        if (((Object) recipient) instanceof Audience audience) {
            audience.sendMessage(message);
            return;
        }
        if (recipient instanceof Player player) NativeChat.send(player, message, false);
        else recipient.sendMessage(legacy(message));
    }

    /** String overload permits mechanically migrated common call sites without losing legacy formatting. */
    public static void sendMessage(CommandSender recipient, String message) {
        recipient.sendMessage(message);
    }

    public static void sendMessage(CommandSender recipient, String... messages) {
        recipient.sendMessage(messages);
    }

    public static void sendActionBar(Player player, String message) {
        sendActionBar(player, LEGACY.deserialize(message));
    }

    public static void sendActionBar(Player player, Component message) {
        if (((Object) player) instanceof Audience audience) audience.sendActionBar(message);
        else NativeChat.send(player, message, true);
    }

    public static void playSound(Player player, net.kyori.adventure.sound.Sound sound) {
        if (((Object) player) instanceof Audience audience) audience.playSound(sound);
        else
            player.playSound(
                    player.getLocation(), sound.name().asString(), SoundCategory.valueOf(sound.source().name()),
                    sound.volume(), sound.pitch());
    }

    public static void text(TextDisplay display, Component text) {
        try {
            display.text(text);
        } catch (NoSuchMethodError missing) {
            display.setText(legacy(text));
        }
    }

    public static void sendMultiBlockChange(Player player, Map<Location, org.bukkit.block.data.BlockData> blocks) {
        try {
            player.sendMultiBlockChange(blocks);
        } catch (NoSuchMethodError missing) {
            blocks.forEach(player::sendBlockChange);
        }
    }

    public static void showTitle(Player player, Title title) {
        if (((Object) player) instanceof Audience audience) {
            audience.showTitle(title);
            return;
        }
        Title.Times times = title.times();
        player.sendTitle(
                legacy(title.title()), legacy(title.subtitle()), times == null ? 10 : durationTicks(times.fadeIn()),
                times == null ? 70 : durationTicks(times.stay()), times == null ? 20 : durationTicks(times.fadeOut()));
    }

    private static int durationTicks(java.time.Duration value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value.toMillis() / 50));
    }

    public static void showBossBar(Player player, BossBar bar) {
        if (((Object) player) instanceof Audience audience) {
            audience.showBossBar(bar);
            return;
        }
        BARS.computeIfAbsent(bar, BarBridge::new).bar.addPlayer(player);
    }

    public static void hideBossBar(Player player, BossBar bar) {
        if (((Object) player) instanceof Audience audience) {
            audience.hideBossBar(bar);
            return;
        }
        BarBridge bridge = BARS.get(bar);
        if (bridge != null) {
            bridge.bar.removePlayer(player);
            if (bridge.bar.getPlayers().isEmpty()) {
                bridge.close();
                BARS.remove(bar);
            }
        }
    }

    private static final class BarBridge implements BossBar.Listener {
        final BossBar source;
        final org.bukkit.boss.BossBar bar;

        BarBridge(BossBar source) {
            this.source = source;
            bar = Bukkit.createBossBar(legacy(source.name()), color(source.color()), style(source.overlay()));
            update();
            source.addListener(this);
        }

        static org.bukkit.boss.BarColor color(BossBar.Color color) {
            return org.bukkit.boss.BarColor.valueOf(color.name());
        }

        static org.bukkit.boss.BarStyle style(BossBar.Overlay style) {
            return org.bukkit.boss.BarStyle.valueOf(
                    style == BossBar.Overlay.PROGRESS ? "SOLID" : style.name().replace("NOTCHED", "SEGMENTED"));
        }

        void update() {
            bar.setTitle(legacy(source.name()));
            bar.setProgress(Math.max(0, Math.min(1, source.progress())));
            bar.setColor(color(source.color()));
            bar.setStyle(style(source.overlay()));
            for (org.bukkit.boss.BarFlag flag : org.bukkit.boss.BarFlag.values()) bar.removeFlag(flag);
            for (BossBar.Flag flag : source.flags()) {
                String name = flag == BossBar.Flag.PLAY_BOSS_MUSIC
                        ? "PLAY_BOSS_MUSIC"
                        : flag == BossBar.Flag.CREATE_WORLD_FOG ? "CREATE_FOG" : "DARKEN_SKY";
                bar.addFlag(org.bukkit.boss.BarFlag.valueOf(name));
            }
        }

        void close() {
            source.removeListener(this);
            bar.removeAll();
        }

        public void bossBarNameChanged(BossBar bar, Component oldName, Component newName) {
            update();
        }

        public void bossBarProgressChanged(BossBar bar, float oldProgress, float newProgress) {
            update();
        }

        public void bossBarColorChanged(BossBar bar, BossBar.Color oldColor, BossBar.Color newColor) {
            update();
        }

        public void bossBarOverlayChanged(BossBar bar, BossBar.Overlay oldOverlay, BossBar.Overlay newOverlay) {
            update();
        }

        public void bossBarFlagsChanged(BossBar bar, Set<BossBar.Flag> added, Set<BossBar.Flag> removed) {
            update();
        }
    }

    public static void displayName(ItemMeta meta, Component name) {
        try {
            meta.displayName(name);
        } catch (NoSuchMethodError missing) {
            meta.setDisplayName(name == null ? null : legacy(name));
        }
    }

    public static Component displayName(ItemMeta meta) {
        try {
            return meta.displayName();
        } catch (NoSuchMethodError missing) {
            return meta.hasDisplayName() ? LEGACY.deserialize(meta.getDisplayName()) : null;
        }
    }

    public static Component displayName(ItemStack item) {
        try {
            return item.displayName();
        } catch (NoSuchMethodError missing) {
            try {
                String craft = Bukkit.getServer().getClass().getPackageName();
                Object nativeItem = Class.forName(craft + ".inventory.CraftItemStack")
                        .getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
                Object name = nativeItem.getClass().getMethod("getDisplayName").invoke(nativeItem);
                String json = (String) Class.forName(craft + ".util.CraftChatMessage")
                        .getMethod("toJSON", Class.forName("net.minecraft.network.chat.Component")).invoke(null, name);
                return GsonComponentSerializer.gson().deserialize(json);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native item display/hover unavailable", failure);
            }
        }
    }

    public static void lore(ItemMeta meta, List<? extends Component> lines) {
        try {
            meta.lore(lines);
        } catch (NoSuchMethodError missing) {
            meta.setLore(lines == null ? null : lines.stream().map(PlatformCompat::legacy).toList());
        }
    }

    public static List<Component> lore(ItemMeta meta) {
        try {
            return meta.lore();
        } catch (NoSuchMethodError missing) {
            return meta.hasLore() ? meta.getLore().stream().map(x -> (Component) LEGACY.deserialize(x)).toList() : null;
        }
    }

    public static void lore(ItemStack item, List<? extends Component> lines) {
        editMeta(item, meta -> lore(meta, lines));
    }

    public static List<Component> lore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : lore(meta);
    }

    public static void displayName(Player player, Component name) {
        try {
            player.displayName(name);
        } catch (NoSuchMethodError missing) {
            player.setDisplayName(legacy(name));
        }
    }

    public static Component displayName(Player player) {
        try {
            return player.displayName();
        } catch (NoSuchMethodError missing) {
            return LEGACY.deserialize(player.getDisplayName());
        }
    }

    public static boolean editMeta(ItemStack item, Consumer<ItemMeta> edit) {
        try {
            return item.editMeta(edit);
        } catch (NoSuchMethodError missing) {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return false;
            edit.accept(meta);
            return item.setItemMeta(meta);
        }
    }

    public static <M extends ItemMeta> boolean editMeta(ItemStack item, Class<M> type, Consumer<? super M> edit) {
        try {
            return item.editMeta(type, edit);
        } catch (NoSuchMethodError missing) {
            ItemMeta meta = item.getItemMeta();
            if (!type.isInstance(meta)) return false;
            edit.accept(type.cast(meta));
            return item.setItemMeta(meta);
        }
    }

    public static Inventory createInventory(InventoryHolder holder, int size, Component title) {
        try {
            return Bukkit.createInventory(holder, size, title);
        } catch (NoSuchMethodError missing) {
            return Bukkit.createInventory(holder, size, legacy(title));
        }
    }

    public static Inventory createInventory(InventoryHolder holder, int size, String title) {
        return Bukkit.createInventory(holder, size, title);
    }

    public static Inventory createInventory(
            InventoryHolder holder, org.bukkit.event.inventory.InventoryType type, Component title) {
        try {
            return Bukkit.createInventory(holder, type, title);
        } catch (NoSuchMethodError missing) {
            return Bukkit.createInventory(holder, type, legacy(title));
        }
    }

    public static Inventory createInventory(
            InventoryHolder holder, org.bukkit.event.inventory.InventoryType type, String title) {
        return Bukkit.createInventory(holder, type, title);
    }

    public static void customName(Nameable target, Component name) {
        try {
            target.customName(name);
        } catch (NoSuchMethodError missing) {
            target.setCustomName(name == null ? null : legacy(name));
        }
    }

    public static org.bukkit.scoreboard.Objective registerNewObjective(
            org.bukkit.scoreboard.Scoreboard board, String name, org.bukkit.scoreboard.Criteria criteria,
            Component title) {
        try {
            return board.registerNewObjective(name, criteria, title);
        } catch (NoSuchMethodError missing) {
            return board.registerNewObjective(name, criteria, legacy(title));
        }
    }

    public static void customName(org.bukkit.scoreboard.Score score, Component name) {
        try {
            score.customName(name);
        } catch (NoSuchMethodError missing) {
            try {
                Object objective = NativeScore.objective(score.getObjective());
                Object board = score.getScoreboard().getClass().getMethod("getHandle").invoke(score.getScoreboard());
                Field entry = score.getClass().getDeclaredField("entry");
                entry.setAccessible(true);
                Object access = board.getClass().getMethod(
                        "getOrCreatePlayerScore", Class.forName("net.minecraft.world.scores.ScoreHolder"),
                        objective.getClass()).invoke(board, entry.get(score), objective);
                Class<?> component = Class.forName("net.minecraft.network.chat.Component");
                String craft = Bukkit.getServer().getClass().getPackageName();
                Object nativeName = name == null
                        ? null
                        : Class.forName(craft + ".util.CraftChatMessage").getMethod("fromJSON", String.class)
                                .invoke(null, GsonComponentSerializer.gson().serialize(name));
                Class.forName("net.minecraft.world.scores.ScoreAccess").getMethod("display", component)
                        .invoke(access, nativeName);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native score display unavailable", failure);
            }
        }
    }

    public static void blankNumberFormat(org.bukkit.scoreboard.Objective objective) {
        try {
            objective.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
        } catch (NoClassDefFoundError | NoSuchMethodError missing) {
            try {
                Object handle = NativeScore.objective(objective);
                Object blank =
                        Class.forName("net.minecraft.network.chat.numbers.BlankFormat").getField("INSTANCE").get(null);
                handle.getClass()
                        .getMethod("setNumberFormat", Class.forName("net.minecraft.network.chat.numbers.NumberFormat"))
                        .invoke(handle, blank);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native score number format unavailable", failure);
            }
        }
    }

    private static final class NativeScore {
        static Object objective(org.bukkit.scoreboard.Objective objective) throws ReflectiveOperationException {
            Method getter = objective.getClass().getDeclaredMethod("getHandle");
            getter.setAccessible(true);
            return getter.invoke(objective);
        }
    }

    public static Component customName(Nameable target) {
        try {
            return target.customName();
        } catch (NoSuchMethodError missing) {
            String text = target.getCustomName();
            return text == null ? null : LEGACY.deserialize(text);
        }
    }

    public static Locale locale(Player player) {
        try {
            return player.locale();
        } catch (NoSuchMethodError missing) {
            return Locale.forLanguageTag(player.getLocale().replace('_', '-'));
        }
    }

    public static String translationKey(Material type) {
        return type.getTranslationKey();
    }

    public static String translationKey(EntityType type) {
        return type.getTranslationKey();
    }

    public static boolean isEmpty(ItemStack item) {
        if (item == null) return true;
        try {
            return item.isEmpty();
        } catch (NoSuchMethodError missing) {
            return item.getAmount() <= 0 || item.getType().isAir();
        }
    }

    public static ItemStack emptyItem() {
        try {
            return ItemStack.empty();
        } catch (NoSuchMethodError missing) {
            return new ItemStack(Material.AIR);
        }
    }

    public static void setResourcePack(
            Player player, UUID id, String url, byte[] hash, Component prompt, boolean required) {
        try {
            player.setResourcePack(id, url, hash, prompt, required);
        } catch (NoSuchMethodError missing) {
            player.setResourcePack(id, url, hash, legacy(prompt), required);
        }
    }

    public static BlockState getState(Block block, boolean snapshot) {
        try {
            return block.getState(snapshot);
        } catch (NoSuchMethodError missing) {
            return block.getState();
        }
    }

    public static long getChunkKey(Chunk chunk) {
        return getChunkKey(chunk.getX(), chunk.getZ());
    }

    public static long getChunkKey(int x, int z) {
        return (x & 0xffffffffL) | ((long) z << 32);
    }

    public static long getBlockKey(Block block) {
        try {
            return block.getBlockKey();
        } catch (NoSuchMethodError missing) {
            return (block.getX() & 0x7ffffffL) | ((block.getZ() & 0x7ffffffL) << 27) | ((long) block.getY() << 54);
        }
    }

    public static Entity getEntity(World world, UUID id) {
        try {
            return world.getEntity(id);
        } catch (NoSuchMethodError missing) {
            Entity entity = Bukkit.getEntity(id);
            return entity != null && entity.getWorld().equals(world) ? entity : null;
        }
    }

    public static UUID getOwnerUniqueId(Tameable entity) {
        try {
            return entity.getOwnerUniqueId();
        } catch (NoSuchMethodError missing) {
            AnimalTamer owner = entity.getOwner();
            return owner == null ? null : owner.getUniqueId();
        }
    }

    public static org.bukkit.block.BlockFace getTargetBlockFace(Player player, int distance) {
        try {
            return player.getTargetBlockFace(distance);
        } catch (NoSuchMethodError missing) {
            org.bukkit.util.RayTraceResult hit = player.rayTraceBlocks(distance);
            return hit == null ? null : hit.getHitBlockFace();
        }
    }

    public static boolean isChunkLoaded(Location location) {
        return location.getWorld() != null
                && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public static int getCurrentTick() {
        try {
            return Bukkit.getCurrentTick();
        } catch (NoSuchMethodError missing) {
            return ticks;
        }
    }

    public static int getSendViewDistance(Player player) {
        try {
            return player.getSendViewDistance();
        } catch (NoSuchMethodError missing) {
            return Math.max(2, Math.min(Bukkit.getViewDistance(), player.getClientViewDistance()));
        }
    }

    public static String getMinecraftVersion() {
        try {
            return Bukkit.getMinecraftVersion();
        } catch (NoSuchMethodError missing) {
            return Bukkit.getBukkitVersion().split("-", 2)[0];
        }
    }

    /** Returns the online profile, including its existing skin properties, across both API return types. */
    public static org.bukkit.profile.PlayerProfile getPlayerProfile(Player player) {
        try {
            if (((Object) player) instanceof Audience)
                return (org.bukkit.profile.PlayerProfile) player.getClass().getMethod("getPlayerProfile")
                        .invoke(player);
            // CraftBukkit's public getter reconstructs only NameAndId on 26.x, losing the online textures.
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object profile = handle.getClass().getMethod("getGameProfile").invoke(handle);
            String craft = player.getClass().getPackageName().replace(".entity", "");
            return (org.bukkit.profile.PlayerProfile) Class.forName(craft + ".profile.CraftPlayerProfile")
                    .getConstructor(Class.forName("com.mojang.authlib.GameProfile")).newInstance(profile);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Online player profile unavailable", failure);
        }
    }

    public static boolean isStopping() {
        try {
            return Bukkit.isStopping();
        } catch (NoSuchMethodError missing) {
            return stopping;
        }
    }

    public static double[] getTPS() {
        try {
            return Bukkit.getTPS();
        } catch (NoSuchMethodError missing) {
            return new double[] {measuredTps, measuredTps, measuredTps};
        }
    }

    public static double getAverageTickTime() {
        try {
            return Bukkit.getAverageTickTime();
        } catch (NoSuchMethodError missing) {
            try {
                Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
                return (
                        (Number) server.getClass().getMethod("getAverageTickTimeNanos")
                                .invoke(server)).doubleValue() / 1_000_000d;
            } catch (ReflectiveOperationException unavailable) {
                return 1000d / Math.max(1, measuredTps);
            }
        }
    }

    /** Cache-only lookup: never calls name-based Bukkit.getOfflinePlayer (which may make a network request). */
    public static OfflinePlayer getOfflinePlayerIfCached(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        try {
            return Bukkit.getOfflinePlayerIfCached(name);
        } catch (NoSuchMethodError missing) {
            for (OfflinePlayer p : Bukkit.getOfflinePlayers()) if (name.equalsIgnoreCase(p.getName())) return p;
            return null;
        }
    }

    public static Collection<Player> getNearbyPlayers(Location location, double radius) {
        return getNearbyEntitiesByType(location.getWorld(), Player.class, location, radius);
    }

    public static <T extends Entity> Collection<T> getNearbyEntitiesByType(
            World world, Class<T> type, Location location, double radius) {
        return getNearbyEntitiesByType(world, type, location, radius, radius, radius);
    }

    public static <T extends Entity> Collection<T> getNearbyEntitiesByType(
            World world, Class<T> type, Location location, double x, double y, double z) {
        if (world == null || x < 0 || y < 0 || z < 0 || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z))
            return List.of();
        List<T> out = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(location, x, y, z))
            if (type.isInstance(entity)) out.add(type.cast(entity));
        return out;
    }

    public static CommandMap getCommandMap() {
        try {
            return Bukkit.getCommandMap();
        } catch (NoSuchMethodError missing) {
            try {
                return (CommandMap) Bukkit.getServer().getClass().getMethod("getCommandMap").invoke(Bukkit.getServer());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Server command map unavailable", failure);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Command> getKnownCommands(CommandMap map) {
        try {
            return map.getKnownCommands();
        } catch (NoSuchMethodError missing) {
            try {
                Field f = SimpleCommandMap.class.getDeclaredField("knownCommands");
                f.setAccessible(true);
                return (Map<String, Command>) f.get(map);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Server command registry unavailable", failure);
            }
        }
    }

    public static CompletableFuture<Chunk> getChunkAtAsync(World world, int x, int z) {
        return getChunkAtAsync(world, x, z, true);
    }

    public static CompletableFuture<Chunk> getChunkAtAsync(World world, Location location) {
        return getChunkAtAsync(world, location.getBlockX() >> 4, location.getBlockZ() >> 4, true);
    }

    public static CompletableFuture<Chunk> getChunkAtAsync(World world, int x, int z, boolean generate) {
        try {
            return world.getChunkAtAsync(x, z, generate);
        } catch (NoSuchMethodError missing) {
            return onMain(
                    () -> world.isChunkLoaded(x, z) || generate || world.isChunkGenerated(x, z)
                            ? world.getChunkAt(x, z, generate)
                            : null);
        }
    }

    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location target) {
        return teleportAsync(entity, target, TeleportCause.PLUGIN);
    }

    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location target, TeleportCause cause) {
        try {
            return entity.teleportAsync(target, cause);
        } catch (NoSuchMethodError missing) {
            Location copy = target.clone();
            return onMain(() -> entity.teleport(copy, cause));
        }
    }

    private static <T> CompletableFuture<T> onMain(Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable apply = () -> {
            try {
                future.complete(work.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        };
        if (Bukkit.isPrimaryThread()) apply.run();
        else {
            Plugin plugin = owner;
            if (plugin == null || !plugin.isEnabled())
                future.completeExceptionally(new IllegalStateException("Core is not enabled"));
            else Bukkit.getScheduler().runTask(plugin, apply);
        }
        return future;
    }

    public static void lockFreezeTicks(Player player, boolean locked) {
        try {
            player.lockFreezeTicks(locked);
        } catch (NoSuchMethodError missing) {
            if (locked) FREEZE.put(player.getUniqueId(), player.getFreezeTicks());
            else FREEZE.remove(player.getUniqueId());
        }
    }

    /** Set plugin-controlled freeze state; required while the Bukkit freeze lock is active. */
    public static void setFreezeTicks(Player player, int ticks) {
        player.setFreezeTicks(ticks);
        if (FREEZE.containsKey(player.getUniqueId())) FREEZE.put(player.getUniqueId(), ticks);
    }

    public static ItemStack enchantWithLevels(ItemStack item, int level, boolean treasure, Random random) {
        try {
            return Bukkit.getItemFactory().enchantWithLevels(item, level, treasure, random);
        } catch (NoSuchMethodError missing) {
            try {
                Class<?> source = Class.forName("net.minecraft.util.RandomSource");
                Object seeded = source.getMethod("create", long.class).invoke(null, random.nextLong());
                Method enchant = Bukkit.getItemFactory().getClass()
                        .getDeclaredMethod("enchantItem", source, ItemStack.class, int.class, boolean.class);
                enchant.setAccessible(true);
                return (ItemStack) enchant.invoke(null, seeded, item, level, treasure);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Seeded vanilla enchanting unavailable", failure);
            }
        }
    }

    public static int calculateTotalExperiencePoints(Player player) {
        try {
            return player.calculateTotalExperiencePoints();
        } catch (NoSuchMethodError missing) {
            return bukkitExperience(player);
        }
    }

    static int bukkitExperience(Player player) {
        int level = player.getLevel();
        long base = level <= 16
                ? (long) level * level + 6L * level
                : level <= 31
                        ? (5L * level * level - 81L * level + 720) / 2
                        : (9L * level * level - 325L * level + 4440) / 2;
        return (int) Math.min(Integer.MAX_VALUE, base + Math.round(player.getExp() * xpToNext(level)));
    }

    public static void setExperienceLevelAndProgress(Player player, int total) {
        try {
            player.setExperienceLevelAndProgress(total);
        } catch (NoSuchMethodError missing) {
            bukkitExperience(player, total);
        }
    }

    static void bukkitExperience(Player player, int total) {
        int xp = Math.max(0, total), level = 0;
        while (level < 21863 && xp >= xpToNext(level)) {
            xp -= xpToNext(level);
            level++;
        }
        player.setLevel(level);
        player.setExp((float) xp / xpToNext(level));
        player.setTotalExperience(Math.max(0, total));
    }

    private static int xpToNext(int level) {
        return level >= 30 ? 9 * level - 158 : level >= 15 ? 5 * level - 38 : 2 * level + 7;
    }

    /** Native JSON preserves click/hover/translation components on Bukkit without a Paper Audience. */
    private static final class NativeChat {
        static void send(Player player, Component message, boolean actionBar) {
            try {
                Class<?> craft = player.getClass();
                String pkg = craft.getPackageName().replace(".entity", "");
                Class<?> chat = Class.forName(pkg + ".util.CraftChatMessage");
                Object component = chat.getMethod("fromJSON", String.class)
                        .invoke(null, GsonComponentSerializer.gson().serialize(message));
                Class<?> nativeComponent = Class.forName("net.minecraft.network.chat.Component");
                Object packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSystemChatPacket")
                        .getConstructor(nativeComponent, boolean.class).newInstance(component, actionBar);
                Object handle = craft.getMethod("getHandle").invoke(player);
                Object connection = handle.getClass().getField("connection").get(handle);
                connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"))
                        .invoke(connection, packet);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native Bukkit component delivery unavailable", failure);
            }
        }
    }
}
