package Vystorm.vystorm_core.commands;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Shared fixtures: a rails-like tree with en/de/zh_cn names, mocked players with a locale and captured messages. */
final class CommandTestKit {
    static final String EN = """
            commands:
              rails:
                description: "Minecart destinations and the network map"
                sub:
                  dest:
                    description: "Set or show your destination"
                    args:
                      name: NAME
                  map:
                    description: "Open the network map"
                  speed:
                    description: "Set the cart speed"
                  power:
                    description: "Switch boost on or off"
                  reload:
                    description: "Reload the config"
            no-cart: "No cart here."
            """;
    static final String DE = """
            commands:
              rails:
                description: "Ziele und Streckenkarte"
                aliases: [schienen, bahn]
                sub:
                  dest:
                    name: ziel
                    aliases: [reiseziel]
                    description: "Ziel setzen oder anzeigen"
                    args:
                      name: NAME
                  map:
                    name: karte
                    description: "Streckenkarte öffnen"
                  speed:
                    name: tempo
                    description: "Tempo setzen"
                  power:
                    name: schub
                    description: "Schub an- oder ausschalten"
                  reload:
                    name: neuladen
                    description: "Konfiguration neu laden"
            no-cart: "Hier ist kein Wagen."
            """;
    static final String ZH = """
            commands:
              rails:
                description: "矿车目的地和线路图"
                aliases: [铁轨]
                sub:
                  dest:
                    name: 目的地
                    description: "设置或显示目的地"
                  map:
                    name: 地图
                    description: "打开线路图"
                  speed:
                    name: 速度
                  power:
                    name: 加速
                  reload:
                    name: 重载
            """;

    record Ran(String node, Map<String, Object> values, String label) {}

    final List<Ran> ran = new ArrayList<>();
    final Plugin plugin;
    final Lang lang;
    final CommandSpec spec;
    final CommandRegistration registration;

    CommandTestKit() {
        plugin = mock(Plugin.class);
        lenient().when(plugin.isEnabled()).thenReturn(true);
        lenient().when(plugin.getName()).thenReturn("TestRails");
        lang = Lang.create("TestRails", Map.of("en", EN, "de", DE, "zh_cn", ZH));
        spec = CommandSpec.command("rails")
                .aliases("vrails", "cart")
                .sub("dest", s -> s.aliases("destination").playerOnly()
                        .arg(Arg.greedy("name").optional().suggests((c, p) -> List.of("clear", "Central", "Harbour")))
                        .executes(record("dest")))
                .sub("map", s -> s.playerOnly().executes(record("map")))
                .sub("speed", s -> s.arg(Arg.integer("kmh", 1, 200)).executes(record("speed")))
                .sub("power", s -> s.arg(Arg.bool("state")).executes(record("power")))
                .sub("reload", s -> s.permission("rails.admin").executes(record("reload")))
                .build();
        registration = new CommandRegistration(plugin, lang, spec, null, Localization.get().core());
    }

    CommandHandler record(String node) {
        return ctx -> ran.add(new Ran(node, ctx.values(), ctx.label()));
    }

    /** A player with a client locale, permissions and captured chat lines. */
    static Player player(Locale locale, Set<String> permissions, List<String> chat) {
        Player p = mock(Player.class);
        lenient().when(p.locale()).thenReturn(locale);
        lenient().when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(p.getName()).thenReturn("Bot");
        lenient().when(p.hasPermission(anyString())).thenAnswer(i -> permissions.contains((String) i.getArgument(0)));
        doAnswer(i -> { chat.add(plain(i.getArgument(0))); return null; }).when(p).sendMessage(any(Component.class));
        return p;
    }

    static ConsoleCommandSender console(List<String> chat) {
        ConsoleCommandSender c = mock(ConsoleCommandSender.class);
        when(c.hasPermission(anyString())).thenReturn(true);
        doAnswer(i -> { chat.add(plain(i.getArgument(0))); return null; }).when(c).sendMessage(any(Component.class));
        return c;
    }

    static String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }

    static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;
}
