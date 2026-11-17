package Vystorm.vystorm_core.network.paper;

import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CoreCommands;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.network.NetworkServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /vnet status|servers|broadcast|pair accept|unpair}. Texts under {@code network.*} in Core's bundle.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class NetworkCommands {
    public static final String PERM_STATUS = "vystorm_core.network.status";
    public static final String PERM_BROADCAST = "vystorm_core.network.broadcast";
    public static final String PERM_ADMIN = "vystorm_core.network.admin";

    private NetworkCommands() {}

    public static void register(Plugin plugin) {
        Lang lang = Localization.get().core();
        CoreCommands.register(plugin, lang, CommandSpec.command("vnet")
                .permission(PERM_STATUS).executes(c -> status(lang, c.sender()))
                .sub("status", s -> s.permission(PERM_STATUS).executes(c -> status(lang, c.sender())))
                .sub("servers", s -> s.permission(PERM_STATUS).executes(c -> servers(lang, c.sender())))
                .sub("broadcast", s -> s.permission(PERM_BROADCAST).arg(Arg.greedy("message"))
                        .executes(c -> broadcast(lang, c.sender(), c.get("message"))))
                .sub("pair", s -> s.permission(PERM_ADMIN)
                        .sub("accept", a -> a.permission(PERM_ADMIN).arg(Arg.word("code"))
                                .executes(c -> accept(lang, c.sender(), c.get("code")))))
                .sub("unpair", s -> s.permission(PERM_ADMIN).executes(c -> unpair(lang, c.sender())))
                .build());
        PaperNetwork.running().ifPresent(network -> network.onBroadcast(text -> show(plugin, lang, text)));
    }

    static void status(Lang lang, CommandSender sender) {
        var running = PaperNetwork.running();
        if (running.isEmpty()) { lang.send(sender, "network.off"); return; }
        PaperNetwork n = running.get();
        String hub = n.hubAddress().map(a -> a.getHostString() + ":" + a.getPort()).orElse("-");
        lang.send(sender, n.connected() ? "network.status-connected" : "network.status-disconnected",
                "server", n.serverName().isEmpty() ? "-" : n.serverName(), "hub", hub,
                "key", n.keySource() + n.keyFingerprint().map(f -> " " + f).orElse(""),
                "latency", n.latencyMillis() < 0 ? "-" : n.latencyMillis() + " ms");
        n.pendingPairingCode().ifPresent(code -> lang.send(sender, "network.pair-pending", "code", code));
    }

    static void servers(Lang lang, CommandSender sender) {
        var running = PaperNetwork.running();
        if (running.isEmpty() || !running.get().connected()) { lang.send(sender, "network.not-connected"); return; }
        for (NetworkServer server : running.get().servers()) {
            lang.send(sender, server.connected() ? "network.server-line-online" : "network.server-line-offline",
                    "server", server.name(), "players", server.players());
        }
    }

    static void broadcast(Lang lang, CommandSender sender, String message) {
        var running = PaperNetwork.running();
        if (running.isEmpty()) { lang.send(sender, "network.off"); return; }
        running.get().broadcastText(message);
        if (!running.get().connected()) lang.send(sender, "network.broadcast-local-only");
    }

    static void accept(Lang lang, CommandSender sender, String code) {
        var running = PaperNetwork.running();
        if (running.isEmpty()) { lang.send(sender, "network.off"); return; }
        lang.send(sender, running.get().confirmPairing(code) ? "network.paired" : "network.pair-wrong-code");
    }

    static void unpair(Lang lang, CommandSender sender) {
        var running = PaperNetwork.running();
        if (running.isEmpty()) { lang.send(sender, "network.off"); return; }
        running.get().unpair();
        lang.send(sender, "network.unpaired");
    }

    private static void show(Plugin plugin, Lang lang, String miniMessage) {
        Component message;
        try {
            message = MiniMessage.miniMessage().deserialize(miniMessage);
        } catch (RuntimeException invalid) {
            message = Component.text(miniMessage);
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(player, lang.component(player, "network.broadcast", "message", message));
        }
        CommandSender console = plugin.getServer().getConsoleSender();
        Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(console, lang.component(console, "network.broadcast", "message", message));
    }
}
