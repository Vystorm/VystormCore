package Vystorm.vystorm_core._advancedMethods.placeholderHandler.internal;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderContext;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import Vystorm.vystorm_core._integrations.vault.VaultInterface;
import Vystorm.vystorm_core.party.PartyView;
import Vystorm.vystorm_core.region.Region;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The values Core itself answers, in the namespaces {@code core}, {@code region}, {@code party}, {@code vault} and
 * {@code character}. Everything is asked through the managers of the hub, so a replaced manager answers.
 * <p>
 * The description of a value is the language key {@code placeholders.values.<namespace>.<key>} of Core's bundle;
 * the list in {@code docs/API.md} is generated from these declarations and the English texts
 * ({@code CorePlaceholdersTest}).
 * <p>
 * Async-safe are the values that only read fields of the player or the server (name, position, counts, clock,
 * tick times) and the lock-free region index. Values that ask a provider plugin (party, economy, character level)
 * or Core's player data (language) run on the server thread only.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class CorePlaceholders {
    /** The namespaces Core owns. */
    public static final List<String> NAMESPACES = List.of("core", "region", "party", "vault", "character");
    private static final String TIME = "HH:mm";
    private static final String DATE = "yyyy-MM-dd";
    private static final Map<String, DateTimeFormatter> FORMATS = new ConcurrentHashMap<>();

    private CorePlaceholders() {}

    /**
     * Declares Core's values in {@code registry}.
     *
     * @param hub the hub whose managers answer; asked at every call, so it may be empty while Core starts
     * @param description turns a key such as {@code core.online} into the description text
     */
    public static void register(Plugin owner, PlaceholderInterface registry, Supplier<API> hub, Function<String, String> description) {
        Declarer core = new Declarer(registry.register(owner, "core"), description, null);
        core.add(PlaceholderValue.viewer("player", OfflinePlayer::getName).asyncSafe());
        core.add(PlaceholderValue.player("displayname", player -> PlainTextComponentSerializer.plainText()
                .serialize(hub.get().compat().platform().displayName(player))).asyncSafe());
        core.add(PlaceholderValue.viewer("uuid", OfflinePlayer::getUniqueId).asyncSafe());
        core.add(PlaceholderValue.player("world", player -> player.getWorld().getName()).asyncSafe());
        core.add(PlaceholderValue.player("x", player -> player.getLocation().getBlockX()).asyncSafe());
        core.add(PlaceholderValue.player("y", player -> player.getLocation().getBlockY()).asyncSafe());
        core.add(PlaceholderValue.player("z", player -> player.getLocation().getBlockZ()).asyncSafe());
        core.add(PlaceholderValue.global("online", () -> Bukkit.getOnlinePlayers().size()).asyncSafe());
        core.add(PlaceholderValue.global("max", Bukkit::getMaxPlayers).asyncSafe());
        core.add(PlaceholderValue.of("time", context -> clock(context, TIME)).arguments("[pattern]").asyncSafe());
        core.add(PlaceholderValue.of("date", context -> clock(context, DATE)).arguments("[pattern]").asyncSafe());
        core.add(PlaceholderValue.of("world_time", CorePlaceholders::worldTime).arguments("[world]"));
        core.add(PlaceholderValue.player("language", player -> hub.get().lang().language(player)));
        core.add(PlaceholderValue.global("tps", () -> decimal(Math.min(20.0, hub.get().compat().platform().getTPS()[0]))).asyncSafe());
        core.add(PlaceholderValue.global("mspt", () -> decimal(hub.get().compat().platform().getAverageTickTime())).asyncSafe());
        core.add(PlaceholderValue.player("ping", Player::getPing).asyncSafe());
        core.add(PlaceholderValue.player("level", Player::getLevel).asyncSafe());
        core.add(PlaceholderValue.player("xp", player -> hub.get().player().experience(player)));
        core.add(PlaceholderValue.player("health", player -> (int) Math.ceil(player.getHealth())).asyncSafe());
        core.add(PlaceholderValue.player("food", Player::getFoodLevel).asyncSafe());
        core.add(PlaceholderValue.player("gamemode", player -> player.getGameMode().name().toLowerCase(Locale.ROOT)).asyncSafe());
        core.add(PlaceholderValue.global("version", CorePlaceholders::coreVersion).asyncSafe());
        core.add(PlaceholderValue.global("minecraft", () -> hub.get().compat().platform().getMinecraftVersion()).asyncSafe());

        Declarer region = new Declarer(registry.register(owner, "region"), description,
                () -> Vystorm.vystorm_core.lifecycle.CoreSwitches.on(Vystorm.vystorm_core.lifecycle.CoreSwitches.Switch.REGIONS));
        region.add(PlaceholderValue.player("id", player -> top(hub, player).map(Region::id).orElse("")).asyncSafe());
        region.add(PlaceholderValue.player("kind", player -> top(hub, player).map(Region::kind).orElse("")).asyncSafe());
        region.add(PlaceholderValue.player("key", player -> top(hub, player).map(Region::key).orElse("")).asyncSafe());
        region.add(PlaceholderValue.player("owner", player -> top(hub, player).map(Region::owner).orElse("")).asyncSafe());
        region.add(PlaceholderValue.player("count", player -> hub.get().area().regions().at(player.getLocation()).size()).asyncSafe());
        region.add(PlaceholderValue.of("flag", context -> {
            Player player = context.player();
            String flag = context.rest(0);
            return player == null || flag.isEmpty() ? null : hub.get().area().regions().flag(player.getLocation(), flag, false);
        }).arguments("<flag>").asyncSafe());
        region.add(PlaceholderValue.of("attribute", context -> {
            Player player = context.player();
            String name = context.rest(0);
            return player == null || name.isEmpty() ? null : top(hub, player).flatMap(found -> found.attribute(name)).orElse("");
        }).arguments("<name>").asyncSafe());

        Declarer party = new Declarer(registry.register(owner, "party"), description, null);
        party.add(PlaceholderValue.viewer("name", viewer -> party(hub, viewer).map(PartyView::name).orElse("")));
        party.add(PlaceholderValue.viewer("size", viewer -> party(hub, viewer).map(found -> found.members().size()).orElse(0)));
        party.add(PlaceholderValue.viewer("online", viewer -> party(hub, viewer).map(found -> online(found.members())).orElse(0)));
        party.add(PlaceholderValue.viewer("leader", viewer -> party(hub, viewer).map(found -> name(found.leader())).orElse("")).cacheTicks(200));
        party.add(PlaceholderValue.viewer("is_leader", viewer -> party(hub, viewer).map(found -> viewer.getUniqueId().equals(found.leader())).orElse(false)));
        party.add(PlaceholderValue.relational("same", (one, two) -> hub.get().parties().sameParty(one.getUniqueId(), two.getUniqueId())));

        Declarer vault = new Declarer(registry.register(owner, "vault"), description, null);
        vault.add(PlaceholderValue.viewer("balance", viewer -> {
            VaultInterface economy = hub.get().vault();
            if (!economy.isEconomyAvailable()) return "";
            int digits = economy.fractionalDigits();
            return String.format(Locale.ROOT, "%." + (digits < 0 || digits > 8 ? 2 : digits) + "f", economy.balance(viewer));
        }));
        vault.add(PlaceholderValue.viewer("balance_formatted", viewer -> {
            VaultInterface economy = hub.get().vault();
            return economy.isEconomyAvailable() ? economy.format(economy.balance(viewer)) : "";
        }));
        vault.add(PlaceholderValue.global("economy", () -> {
            VaultInterface economy = hub.get().vault();
            return economy.isEconomyAvailable() ? economy.economyName() : "";
        }));

        Declarer character = new Declarer(registry.register(owner, "character"), description, null);
        character.add(PlaceholderValue.viewer("level", viewer -> {
            var levels = hub.get().player().character();
            return levels.available() ? levels.level(viewer.getUniqueId()) : "";
        }));
        character.add(PlaceholderValue.global("max_level", () -> {
            var levels = hub.get().player().character();
            return levels.available() ? levels.maxLevel() : "";
        }));
    }

    /** {@code on} (may be null): while it says false, every value of the namespace is empty (its feature is off). */
    private record Declarer(PlaceholderNamespace namespace, Function<String, String> description, java.util.function.BooleanSupplier on) {
        void add(PlaceholderValue value) {
            PlaceholderValue declared = value;
            if (on != null) {
                PlaceholderValue original = value;
                declared = PlaceholderValue.of(original.key(), context -> on.getAsBoolean() ? original.resolver().resolve(context) : "")
                        .arguments(original.arguments()).cacheTicks(original.cacheTicks());
                if (original.isAsyncSafe()) declared = declared.asyncSafe();
                if (original.isFormatted()) declared = declared.formatted();
            }
            namespace.add(declared.description(description.apply(namespace.name() + "." + value.key())));
        }
    }

    private static String clock(PlaceholderContext context, String standard) {
        String pattern = context.rest(0);
        try {
            return LocalDateTime.now().format(FORMATS.computeIfAbsent(pattern.isEmpty() ? standard : pattern, DateTimeFormatter::ofPattern));
        } catch (RuntimeException invalidPattern) {
            return null;
        }
    }

    /** The clock of a world as {@code HH:mm}: tick 0 is 06:00, 1000 ticks are an hour. */
    private static String worldTime(PlaceholderContext context) {
        String name = context.rest(0);
        World world;
        if (name.isEmpty()) {
            Player player = context.player();
            if (player == null) return null;
            world = player.getWorld();
        } else {
            world = Bukkit.getWorld(name);
            if (world == null) return null;
        }
        return clockOf(world.getTime());
    }

    static String clockOf(long ticks) {
        long day = Math.floorMod(ticks, 24000L);
        long hours = (day / 1000 + 6) % 24;
        long minutes = day % 1000 * 60 / 1000;
        return (hours < 10 ? "0" : "") + hours + ":" + (minutes < 10 ? "0" : "") + minutes;
    }

    private static String decimal(double value) { return String.format(Locale.ROOT, "%.1f", value); }

    private static String coreVersion() {
        String version = CoreApi.VERSION;
        int cut = version.indexOf('-');
        return cut < 0 ? version : version.substring(0, cut);
    }

    private static Optional<Region> top(Supplier<API> hub, Player player) {
        return hub.get().area().regions().top(player.getLocation());
    }

    private static Optional<PartyView> party(Supplier<API> hub, OfflinePlayer viewer) {
        return hub.get().parties().partyOf(viewer.getUniqueId());
    }

    private static int online(Iterable<UUID> members) {
        int count = 0;
        for (UUID member : members) if (Bukkit.getPlayer(member) != null) count++;
        return count;
    }

    private static String name(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) return online.getName();
        String known = Bukkit.getOfflinePlayer(player).getName();
        return known == null ? "" : known;
    }
}
