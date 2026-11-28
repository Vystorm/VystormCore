package Vystorm.vystorm_core.actions.internal;

import Vystorm.vystorm_core.actions.ActionLogEntry;
import Vystorm.vystorm_core.actions.ActionLogInterface;
import Vystorm.vystorm_core.actions.ActionPreview;
import Vystorm.vystorm_core.actions.ActionResult;
import Vystorm.vystorm_core.actions.DispatchResult;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** The action runner behind {@code api.admin().actions()}. Server thread unless stated. Core-internal. */
public final class ActionRunner {
    private static final Map<String, ActionLogStore> LOGS = new ConcurrentHashMap<>();

    private ActionRunner() {}

    /**
     * The family's permission rule: a node that is set (granted or negated, also through a wildcard or group) decides,
     * also for an op; a node that is not set at all is allowed for ops only. The console may do everything.
     */
    public static boolean allowed(CommandSender actor, String node) {
        if (actor == null) return false;
        if (!(actor instanceof Player)) return true;
        if (node == null || node.isBlank()) return true;
        return actor.isPermissionSet(node) ? actor.hasPermission(node) : actor.isOp();
    }

    /** The first node of the list the actor lacks, or null. */
    public static String missing(CommandSender actor, List<String> nodes) {
        for (String n : nodes) if (!allowed(actor, n)) return n;
        return null;
    }

    public static ActionResult run(Plugin owner, CommandSender actor, ActionPreview preview, boolean confirmed, Supplier<ActionResult> action) {
        if (owner == null || !owner.isEnabled() || actor == null) return new ActionResult(ActionResult.DISABLED, "", "", "");
        String lacking = missing(actor, preview.permissions());
        if (lacking != null) {
            ActionResult refused = new ActionResult(ActionResult.NO_PERMISSION, lacking, "", "");
            record(owner, actor, preview, refused);
            return refused;
        }
        if (preview.confirm() && !confirmed) return new ActionResult(ActionResult.NEEDS_CONFIRM, "", "", "");
        ActionResult result;
        try {
            result = action.get();
            if (result == null) result = ActionResult.ok("");
        } catch (RuntimeException failure) {
            owner.getLogger().log(Level.WARNING, "Admin action " + preview.id() + " failed", failure);
            result = new ActionResult(ActionResult.ERROR, String.valueOf(failure.getMessage()), "", "");
        }
        record(owner, actor, preview, result);
        if (result.isOk() && !preview.notifyPermission().isEmpty()) staffNote(owner, actor, preview);
        return result;
    }

    static void record(Plugin owner, CommandSender actor, ActionPreview p, ActionResult r) {
        UUID id = actor instanceof Player player ? player.getUniqueId() : null;
        String name = actor instanceof Player player ? player.getName() : "CONSOLE";
        ActionLogEntry e = new ActionLogEntry(Instant.now(), id, name, owner.getName(), p.id(), p.world(), p.target(),
                r.before().isEmpty() ? p.before() : r.before(), r.after().isEmpty() ? p.after() : r.after(),
                p.command() == null ? "" : p.command(), r.code(), r.message());
        store(owner).add(e);
        owner.getLogger().info("[" + name + ": " + ActionLogFormat.escape(p.text().isEmpty() ? p.id() : p.text()) + "] " + r.code());
    }

    /** Vanilla's grey "[Admin: ...]" note to staff with the preview's notify node, when the world logs admin commands. */
    static void staffNote(Plugin owner, CommandSender actor, ActionPreview p) {
        try {
            World world = p.world() == null ? null : Bukkit.getWorld(p.world());
            if (world != null) {
                Object log = Vystorm.vystorm_core.compat.gamerule.internal.GameRuleService.access().rule(world, "log_admin_commands")
                        .map(Vystorm.vystorm_core.compat.gamerule.GameRuleInfo::value).orElse(Boolean.TRUE);
                if (Boolean.FALSE.equals(log)) return;
            }
            var lang = Vystorm.vystorm_core.lang.Localization.get().core();
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.equals(actor) || !allowed(staff, p.notifyPermission())) continue;
                lang.send(staff, "actions.staff-note", "actor", actor.getName(), "text", p.text().isEmpty() ? p.id() : p.text());
            }
        } catch (RuntimeException | LinkageError failure) {
            owner.getLogger().fine("Staff note not sent: " + failure);
        }
    }

    public static ActionLogInterface log(Plugin owner) { return store(owner); }

    static ActionLogStore store(Plugin owner) {
        return LOGS.computeIfAbsent(owner.getName(), name -> new ActionLogStore(owner.getDataFolder().toPath().resolve("log"),
                Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles::append, ActionRunner::onMainThread, owner.getLogger(), true));
    }

    /** The audit log of a plugin by name, if it has one in this server run. */
    public static ActionLogInterface existing(String plugin) {
        for (Map.Entry<String, ActionLogStore> e : LOGS.entrySet()) if (e.getKey().equalsIgnoreCase(plugin)) return e.getValue();
        return null;
    }

    public static List<String> plugins() { return new ArrayList<>(LOGS.keySet()); }

    private static void onMainThread(Runnable task) {
        Plugin core = Vystorm.vystorm_core.CoreApi.plugin();
        if (core != null && core.isEnabled() && !Bukkit.isPrimaryThread()) Bukkit.getScheduler().runTask(core, task);
        else task.run();
    }

    /** Lifecycle: writes and closes the plugin's audit log. */
    public static void release(Plugin owner) {
        ActionLogStore s = owner == null ? null : LOGS.remove(owner.getName());
        if (s != null) s.close();
    }

    /** Chat line with the command that copies on click; the open screen stays open. */
    public static void copyAsCommand(Player viewer, ActionPreview preview) {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        if (preview.command() == null || preview.command().isBlank()) {
            lang.send(viewer, "actions.copy-none", "note", preview.commandNote().isEmpty() ? "-" : preview.commandNote());
            return;
        }
        var line = lang.component(viewer, "actions.copy-line")
                .append(net.kyori.adventure.text.Component.space())
                .append(Vystorm.vystorm_core._uiMethods.chat.ChatActions.copy(preview.command(), preview.command()));
        Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, line);
        if (!preview.commandNote().isEmpty()) lang.send(viewer, "actions.copy-note", "note", preview.commandNote());
    }

    /**
     * Paper/Purpur 26.x run a command of a non-player sender a tick later, so the answer is collected until it has not
     * grown for one tick (at most 20 ticks), then handed over on the server thread.
     */
    private static void deliver(Plugin owner, List<String> got, Consumer<DispatchResult> done, int ticks, int lastSize) {
        int size = got.size();
        boolean settled = size > 0 && size == lastSize;
        if (settled || ticks >= 20 || owner == null || !owner.isEnabled()) {
            done.accept(new DispatchResult(true, true, List.copyOf(got), null));
            return;
        }
        Bukkit.getScheduler().runTaskLater(owner, () -> deliver(owner, got, done, ticks + 1, size), 1L);
    }

    /** Whether this server captures a dispatched command's answer ({@code Server#createCommandSender}, Paper/Purpur). */
    public static boolean captures() { return feedbackFactory() != null; }

    private static Method feedbackFactory() {
        try { return org.bukkit.Server.class.getMethod("createCommandSender", Consumer.class); }
        catch (NoSuchMethodException | LinkageError spigot) { return null; }
    }

    public static void dispatchVanilla(Plugin owner, CommandSender actor, String commandLine, Consumer<DispatchResult> done) {
        String line = VanillaDispatch.normalize(commandLine);
        if (line.isEmpty()) { done.accept(new DispatchResult(false, false, List.of(), "empty")); return; }
        if (VanillaDispatch.blocked(line) != null) { done.accept(new DispatchResult(false, false, List.of(), "blocked")); return; }
        if (missing(actor, VanillaDispatch.permissions(line)) != null) { done.accept(new DispatchResult(false, false, List.of(), "no-permission")); return; }
        // The captured sender is console-like: for a player it is used only for lines without selectors and relative
        // coordinates, so nothing in the line resolves against another source or skips minecraft.command.selector.
        Method factory = actor instanceof Player && VanillaDispatch.contextual(line) ? null : feedbackFactory();
        if (factory != null) {
            List<String> lines = new ArrayList<>();
            try {
                Consumer<Object> sink = component -> lines.add(component instanceof net.kyori.adventure.text.Component c
                        ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c) : String.valueOf(component));
                CommandSender feedback = (CommandSender) factory.invoke(Bukkit.getServer(), sink);
                Bukkit.dispatchCommand(feedback, "minecraft:" + line);
                deliver(owner, lines, done, 0, -1);
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                owner.getLogger().fine("Captured dispatch unavailable, running as the sender: " + failure);
            }
        }
        if (actor instanceof Player player) player.performCommand("minecraft:" + line);
        else Bukkit.dispatchCommand(actor, "minecraft:" + line);
        done.accept(new DispatchResult(true, false, List.of(), null));
    }
}
