package Vystorm.vystorm_core.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._uiMethods.admin.AdminAction;
import Vystorm.vystorm_core._uiMethods.admin.AdminModule;
import Vystorm.vystorm_core._uiMethods.admin.AdminRegistry;
import Vystorm.vystorm_core._uiMethods.admin.CoreAdminDashboard;
import Vystorm.vystorm_core.lang.Lang;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** /vadmin modules of plugins whose command is a Core tree (registered through CoreCommands, not in plugin.yml). */
class AdminDashboardCommandsTest {
    private final Map<String, Command> known = new HashMap<>();
    private final List<List<String>> ran = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Registry.INSTANCE.reset();
        CommandMap map = mock(CommandMap.class);
        when(map.getKnownCommands()).thenReturn(known);
        when(map.getCommand(anyString())).thenAnswer(i -> known.get((String) i.getArgument(0)));
        when(map.register(anyString(), any(Command.class))).thenAnswer(i -> known.putIfAbsent(((Command) i.getArgument(1)).getName(), i.getArgument(1)) == null);
        Registry.INSTANCE.commandMap = () -> map;
        Registry.INSTANCE.serverLanguage = () -> "en";
        Registry.INSTANCE.serverLoadedForTests(true);
        Settings.set(Settings.DEFAULTS);
    }

    @AfterEach
    void tearDown() {
        Registry.INSTANCE.reset();
        Settings.set(Settings.DEFAULTS);
    }

    private JavaPlugin owner(String name) {
        JavaPlugin owner = mock(JavaPlugin.class);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn(name);
        when(owner.getCommand(anyString())).thenReturn(null);
        return owner;
    }

    private void register(JavaPlugin owner) {
        Lang lang = Lang.create(owner.getName(), Map.of("en", "commands: {}\n"));
        CoreCommands.register(owner, lang, CommandSpec.command("owned").aliases("own")
                .sub("go", s -> s.arg(Arg.greedy("rest").optional())
                        .executes(ctx -> ran.add(List.of(ctx.label(), "go", String.valueOf(ctx.<String>get("rest"))))))
                .build());
    }

    private static void invoke(CoreAdminDashboard dashboard, Player p, AdminModule m, AdminAction a, String raw) throws Exception {
        Method run = CoreAdminDashboard.class.getDeclaredMethod("execute", Player.class, AdminModule.class, AdminAction.class, String.class);
        run.setAccessible(true);
        run.invoke(dashboard, p, m, a, raw);
    }

    @Test
    void givenModuleCommandIsACoreTree_whenActionRuns_thenTheTreeRunsItAsThePlayer() throws Exception {
        JavaPlugin core = mock(JavaPlugin.class);
        when(core.isEnabled()).thenReturn(true);
        JavaPlugin owner = owner("Owner");
        register(owner);
        Player p = mock(Player.class);
        when(p.isOnline()).thenReturn(true);
        when(p.hasPermission(anyString())).thenReturn(true);
        AdminRegistry modules = new AdminRegistry();
        AdminModule module = new AdminModule("owned", "Owned", "Owner", "owned", "module.admin", "", List.of());
        modules.register(owner, module);
        AdminAction action = new AdminAction("go", "Go", "action.admin", "go", "", "", false);

        invoke(new CoreAdminDashboard(core, modules), p, module, action, "value");
        assertEquals(List.of(List.of("owned", "go", "value")), ran);

        AdminModule byAlias = new AdminModule("own", "Own", "Owner", "own", "module.admin", "", List.of());
        modules.register(owner, byAlias);
        invoke(new CoreAdminDashboard(core, modules), p, byAlias, action, "x");
        assertEquals(List.of("own", "go", "x"), ran.get(1));
    }

    @Test
    void givenTreeOfAnotherPlugin_whenActionRuns_thenRefusedLikeAForeignPluginCommand() throws Exception {
        JavaPlugin core = mock(JavaPlugin.class);
        when(core.isEnabled()).thenReturn(true);
        register(owner("Other"));
        JavaPlugin owner = owner("Owner");
        Player p = mock(Player.class);
        when(p.isOnline()).thenReturn(true);
        when(p.hasPermission(anyString())).thenReturn(true);
        AdminRegistry modules = new AdminRegistry();
        AdminModule module = new AdminModule("owned", "Owned", "Owner", "owned", "module.admin", "", List.of());
        modules.register(owner, module);
        invoke(new CoreAdminDashboard(core, modules), p, module, new AdminAction("go", "Go", "action.admin", "go", "", "", false), "value");
        assertEquals(List.of(), ran);
    }
}
