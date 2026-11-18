package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * All command trees registered through Core, their Bukkit command-map entries and the localized root aliases.
 * Registration and alias changes run on the server thread.
 *
 * <p><b>Global labels.</b> Labels are server-wide, so localized root aliases are applied only once the server has
 * finished starting (first tick): by then every plugin's own commands exist, and Core never takes a label another
 * command already has (state {@link CommandRegistration.AliasStatus#COLLISION}, logged once).
 */
final class Registry {
    static final Registry INSTANCE = new Registry();

    private final List<CommandRegistration> all = new CopyOnWriteArrayList<>();
    /** Command-map entries Core added per registration (root for the Bukkit mount, localized aliases). */
    private final Map<CommandRegistration, Map<String, TreeCommand>> entries = new ConcurrentHashMap<>();
    private volatile boolean serverLoaded;
    Supplier<CommandMap> commandMap = Vystorm.vystorm_core.compat.PlatformCompat::getCommandMap;
    Supplier<Lang> core = () -> Localization.get().core();
    /** Language of {@link AliasPolicy#DEFAULT}. */
    Supplier<String> serverLanguage = Registry::configuredServerLanguage;
    /** The players who get the changed command list. */
    Supplier<java.util.Collection<? extends Player>> online = Bukkit::getOnlinePlayers;
    private int batchDepth;
    private boolean syncPending;

    List<CommandRegistration> all() { return List.copyOf(all); }

    boolean contains(CommandRegistration registration) { return all.contains(registration); }

    boolean serverLoaded() { return serverLoaded; }

    // ------------------------------------------------------------------ register

    synchronized CommandRegistration register(Plugin plugin, Lang lang, CommandSpec spec) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(spec, "spec");
        Lang bundle = lang != null ? lang : Localization.get().bundle(plugin).orElseGet(() -> Localization.get().register(plugin));
        CommandRegistration mounted = paperMounted(plugin, spec.name());
        if (mounted != null) {
            // 0.65.0: after startup Paper keeps its node; a new root would only reach /<plugin>:<label>. Swap the tree.
            mounted.replace(bundle, spec);
            DebugMessages.low("[Commands] /" + spec.name() + " of " + plugin.getName() + ": new tree inside the Paper registration");
            aliasesChanged(mounted);
            return mounted;
        }
        replaceExisting(plugin, spec.name());
        CommandRegistration registration = new CommandRegistration(plugin, bundle, spec, null, core.get());
        all.add(registration);
        boolean paper = false;
        if (Settings.current().paper() && !serverLoaded) {
            try { paper = PaperMount.available() && PaperMount.mount(registration, englishDescription(registration)); }
            catch (LinkageError | RuntimeException unavailable) { paper = false; }
        }
        if (!paper) mountBukkit(registration);
        aliasesChanged(registration);
        return registration;
    }

    synchronized CommandRegistration adopt(JavaPlugin plugin, Lang lang, CommandSpec declaration) {
        Objects.requireNonNull(plugin, "plugin");
        PluginCommand command = plugin.getCommand(declaration.name());
        if (command == null) throw new IllegalArgumentException(plugin.getName() + " has no plugin.yml command " + declaration.name());
        Lang bundle = lang != null ? lang : Localization.get().bundle(plugin).orElseGet(() -> Localization.get().register(plugin));
        replaceExisting(plugin, declaration.name());
        CommandRegistration.Legacy legacy = new CommandRegistration.Legacy(command, command.getExecutor(), command.getTabCompleter());
        CommandRegistration registration = new CommandRegistration(plugin, bundle, declaration, legacy, core.get());
        TabExecutor wrapper = new AdapterExecutor(registration);
        command.setExecutor(wrapper);
        command.setTabCompleter(wrapper);
        List<String> labels = new ArrayList<>();
        labels.add(command.getName());
        labels.addAll(command.getAliases());
        registration.registeredLabels = List.copyOf(labels);
        registration.mount = CommandRegistration.Mount.ADAPTER;
        all.add(registration);
        aliasesChanged(registration);
        return registration;
    }

    /** The plugin's own Paper-mounted registration of {@code label} once the server has loaded, else {@code null}. */
    private CommandRegistration paperMounted(Plugin plugin, String label) {
        if (!serverLoaded) return null;
        for (CommandRegistration r : all)
            if (r.plugin() == plugin && r.label().equals(label) && r.legacy() == null && r.mount == CommandRegistration.Mount.PAPER) return r;
        return null;
    }

    private void replaceExisting(Plugin plugin, String label) {
        for (CommandRegistration r : all) if (r.plugin() == plugin && r.label().equals(label)) unregister(r);
    }

    /** The wrapped executor of an adapter: Core's tree first, the old executor for whatever it does not handle. */
    record AdapterExecutor(CommandRegistration registration) implements TabExecutor {
        @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            registration.execute(sender, label, args);
            return true;
        }

        @Override public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
            return registration.suggest(sender, label, args);
        }
    }

    private void mountBukkit(CommandRegistration registration) {
        CommandMap map = map();
        if (map == null) return;
        TreeCommand root = new TreeCommand(registration.label(), registration.spec().aliases(), registration, englishDescription(registration));
        String prefix = registration.plugin().getName().toLowerCase(Locale.ROOT);
        Command before = map.getCommand(registration.label());
        boolean free = map.register(prefix, root);
        List<String> labels = new ArrayList<>();
        labels.add(prefix + ":" + registration.label());
        if (free) labels.add(registration.label());
        for (String alias : registration.spec().aliases()) if (map.getCommand(alias) == root) labels.add(alias);
        registration.registeredLabels = List.copyOf(labels);
        registration.labelConflict = free ? null : owner(before);
        if (!free) DebugMessages.high("[Commands] /" + registration.label() + " of " + registration.plugin().getName()
                + " is taken by " + owner(before) + "; use /" + prefix + ":" + registration.label());
        entries.computeIfAbsent(registration, r -> new ConcurrentHashMap<>()).put("\u0000root", root);
        registration.mount = CommandRegistration.Mount.BUKKIT;
        sync();
    }

    // ------------------------------------------------------------------ unregister

    synchronized void unregister(Plugin plugin) {
        for (CommandRegistration r : all) if (r.plugin() == plugin) unregister(r);
    }

    synchronized void unregister(CommandRegistration registration) {
        if (!all.remove(registration)) return;
        Map<String, TreeCommand> own = entries.remove(registration);
        if (own != null) for (TreeCommand c : own.values()) remove(c);
        if (registration.legacy() != null && registration.legacy().command() instanceof PluginCommand pc) {
            if (pc.getExecutor() instanceof AdapterExecutor) pc.setExecutor(registration.legacy().executor());
            if (pc.getTabCompleter() instanceof AdapterExecutor) pc.setTabCompleter(registration.legacy().completer());
        }
        registration.mount = CommandRegistration.Mount.NONE;
        registration.aliases(Map.of());
        sync();
    }

    private void remove(TreeCommand command) {
        CommandMap map = map();
        if (map == null) return;
        try {
            command.unregister(map);
            Map<String, Command> known = Vystorm.vystorm_core.compat.PlatformCompat.getKnownCommands(map);
            // Paper's map is a view on the Brigadier tree: its entry set cannot remove, remove(key) can.
            List<String> labels = new ArrayList<>();
            for (Map.Entry<String, Command> e : known.entrySet()) if (e.getValue() == command) labels.add(e.getKey());
            for (String label : labels) known.remove(label);
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.high("[Commands] Removing /" + command.getName() + " failed: " + failure);
        }
    }

    // ------------------------------------------------------------------ startup and settings

    /** First server tick: Paper registrations that never fired move to the command map, then aliases apply. */
    synchronized void markServerLoaded() {
        serverLoaded = true;
        for (CommandRegistration r : all)
            if (r.mount == CommandRegistration.Mount.NONE && r.legacy() == null) {
                DebugMessages.mid("[Commands] /" + r.label() + ": Paper's command event did not run, using the Bukkit command map");
                mountBukkit(r);
            }
        applyAll();
    }

    /** Re-applies the localized aliases of every registration (settings or language files changed). */
    synchronized void applyAll() {
        for (CommandRegistration r : all) apply(r);
        sync();
    }

    private void aliasesChanged(CommandRegistration registration) {
        if (serverLoaded) { apply(registration); sync(); }
        else {
            Map<String, CommandRegistration.Alias> pending = new LinkedHashMap<>();
            wantedAliases(registration).forEach((alias, language) ->
                    pending.put(alias, new CommandRegistration.Alias(alias, language, CommandRegistration.AliasStatus.PENDING, null)));
            registration.aliases(pending);
        }
    }

    /** Localized aliases from the language files minus the fixed ones (an adopted command's plugin.yml aliases). */
    private static Map<String, String> wantedAliases(CommandRegistration registration) {
        Map<String, String> wanted = new LinkedHashMap<>(registration.names().rootAliases(registration.spec()));
        for (String fixed : registration.fixedAliases()) wanted.remove(fixed.toLowerCase(Locale.ROOT));
        return wanted;
    }

    /** Desired localized aliases → command-map entries, never taking a label someone else has. */
    private void apply(CommandRegistration registration) {
        CommandMap map = map();
        Settings settings = Settings.current();
        String server = Localization.languagePart(serverLanguage.get());
        Map<String, TreeCommand> own = entries.computeIfAbsent(registration, r -> new ConcurrentHashMap<>());
        Map<String, CommandRegistration.Alias> states = new LinkedHashMap<>();
        Map<String, String> wanted = wantedAliases(registration);
        fixedConflicts(map, registration);
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            String alias = e.getKey(), language = e.getValue();
            CommandRegistration.AliasStatus status;
            String detail = null;
            if (settings.disabled(registration.plugin().getName(), alias)) status = CommandRegistration.AliasStatus.DISABLED;
            else if (!enabledBy(settings.policy(), language, server)) status = CommandRegistration.AliasStatus.OFF_BY_POLICY;
            else {
                TreeCommand mine = own.get(alias);
                String taken = mine != null ? null : takenBy(map, registration, alias);
                if (taken != null) { status = CommandRegistration.AliasStatus.COLLISION; detail = taken; }
                else status = CommandRegistration.AliasStatus.ACTIVE;
            }
            if (status == CommandRegistration.AliasStatus.ACTIVE && !own.containsKey(alias) && map != null) {
                TreeCommand command = new TreeCommand(alias, List.of(), registration, englishDescription(registration));
                if (map.register(registration.plugin().getName().toLowerCase(Locale.ROOT), command) && map.getCommand(alias) == command) own.put(alias, command);
                else { remove(command); status = CommandRegistration.AliasStatus.COLLISION; detail = owner(map.getCommand(alias)); }
            }
            if (status == CommandRegistration.AliasStatus.COLLISION)
                DebugMessages.mid("[Commands] Localized alias /" + alias + " (" + language + ") of /" + registration.label() + " skipped: taken by " + detail);
            states.put(alias, new CommandRegistration.Alias(alias, language, status, detail));
        }
        for (var it = own.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().equals("\u0000root")) continue;
            var state = states.get(e.getKey());
            if (state == null || state.status() != CommandRegistration.AliasStatus.ACTIVE) { remove(e.getValue()); it.remove(); }
        }
        registration.aliases(states);
    }

    /**
     * Fixed aliases whose label another plugin's command already had (the server keeps the first, e.g. Core's
     * plugin.yml alias {@code /m} of {@code /menu} over Chat's {@code /msg} alias {@code /m}). Shown in
     * {@code /vadmin commands}; logged once when it changes.
     */
    private void fixedConflicts(CommandMap map, CommandRegistration registration) {
        Map<String, String> out = new LinkedHashMap<>();
        if (map != null) for (String fixed : registration.fixedAliases()) {
            String alias = fixed.toLowerCase(Locale.ROOT);
            Command existing;
            try { existing = map.getCommand(alias); } catch (RuntimeException e) { existing = null; }
            if (existing == null || existing instanceof TreeCommand tree && tree.registration() == registration) continue;
            if (existing instanceof PluginIdentifiableCommand pic && pic.getPlugin() != null && pic.getPlugin() != registration.plugin())
                out.put(alias, owner(existing));
            else if (registration.mount == CommandRegistration.Mount.PAPER && !registration.registeredLabels.isEmpty()
                    && !registration.registeredLabels.contains(alias) && !(existing instanceof PluginIdentifiableCommand))
                out.put(alias, owner(existing));
        }
        Map<String, String> result = Map.copyOf(out);
        if (!result.equals(registration.fixedConflicts) && !result.isEmpty())
            DebugMessages.mid("[Commands] Fixed alias(es) of /" + registration.label() + " (" + registration.plugin().getName()
                    + ") taken by other commands: " + out);
        registration.fixedConflicts = result;
    }

    static boolean enabledBy(AliasPolicy policy, String language, String serverLanguage) {
        return switch (policy) {
            case ALL -> true;
            case NONE -> false;
            case DEFAULT -> Localization.languagePart(language).equals(Localization.languagePart(serverLanguage));
        };
    }

    /** Owner of a label that is not ours, or {@code null} if free. */
    private String takenBy(CommandMap map, CommandRegistration registration, String alias) {
        for (CommandRegistration other : all) {
            if (other == registration) continue;
            if (other.label().equals(alias) || other.fixedAliases().contains(alias)) return other.plugin().getName() + " /" + other.label();
            Map<String, TreeCommand> theirs = entries.get(other);
            if (theirs != null && theirs.containsKey(alias)) return other.plugin().getName() + " /" + other.label();
        }
        if (registration.label().equals(alias) || registration.fixedAliases().contains(alias)) return null;
        if (map == null) return null;
        Command existing;
        try { existing = map.getCommand(alias); } catch (RuntimeException e) { existing = null; }
        if (existing == null) return null;
        if (existing instanceof TreeCommand tree && tree.registration() == registration) return null;
        return owner(existing);
    }

    static String owner(Command command) {
        if (command == null) return "?";
        if (command instanceof PluginIdentifiableCommand pic && pic.getPlugin() != null) return pic.getPlugin().getName() + " /" + command.getName();
        return "server /" + command.getName();
    }

    private CommandMap map() {
        try { return commandMap.get(); } catch (RuntimeException | LinkageError e) { return null; }
    }

    /**
     * Runs several registrations and removals and sends the players their command list once at the end (0.65.0).
     * The server builds that list on another thread; a second change of the command map while it is being built makes
     * the build fail with a ConcurrentModificationException in the console, so changes that belong together are
     * sent together.
     */
    synchronized void batch(Runnable changes) {
        batchDepth++;
        try {
            changes.run();
        } finally {
            if (--batchDepth == 0 && syncPending) {
                syncPending = false;
                sync();
            }
        }
    }

    /** Whether the caller is on the server thread (true without a server, e.g. in tests). */
    java.util.function.BooleanSupplier primaryThread = () -> {
        try { return Bukkit.getServer() == null || Bukkit.isPrimaryThread(); }
        catch (RuntimeException | LinkageError noServer) { return true; }
    };

    /**
     * Sends the online players their command list again (0.65.0, {@code api.command().refresh()}), e.g. after a
     * reload that changed a feature switch. Inside {@link #batch} it is sent once at the end; off the server thread
     * it moves to the server thread.
     */
    void refresh() {
        if (!primaryThread.getAsBoolean()) {
            try {
                Plugin core = Bukkit.getPluginManager().getPlugin("vystorm_core");
                if (core != null && core.isEnabled()) { Bukkit.getScheduler().runTask(core, this::refresh); return; }
            } catch (RuntimeException | LinkageError ignored) { }
            return;
        }
        synchronized (this) { sync(); }
    }

    private void sync() {
        if (!serverLoaded) return;
        if (batchDepth > 0) { syncPending = true; return; }
        try { for (Player p : online.get()) p.updateCommands(); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    private static String englishDescription(CommandRegistration registration) {
        String key = registration.spec().langPath() + ".description";
        if (registration.lang().has(key)) return registration.lang().plain(Localization.FALLBACK, key);
        return registration.spec().description() == null ? "" : registration.spec().description();
    }

    /** {@code language.default} when fixed; with {@code auto} the server/console language (else English). */
    static String configuredServerLanguage() {
        Localization service = Localization.get();
        String configured = service.defaultLanguage();
        if (configured != null && !configured.isBlank() && !Localization.AUTO.equals(configured)) return configured;
        String server = service.serverLanguage();
        return server == null || server.isBlank() ? Localization.FALLBACK : server;
    }

    /** Tests only. */
    synchronized void reset() {
        for (CommandRegistration r : List.copyOf(all)) unregister(r);
        all.clear();
        entries.clear();
        serverLoaded = false;
        commandMap = Vystorm.vystorm_core.compat.PlatformCompat::getCommandMap;
        core = () -> Localization.get().core();
        serverLanguage = Registry::configuredServerLanguage;
        online = Bukkit::getOnlinePlayers;
        primaryThread = () -> {
            try { return Bukkit.getServer() == null || Bukkit.isPrimaryThread(); }
            catch (RuntimeException | LinkageError noServer) { return true; }
        };
        batchDepth = 0;
        syncPending = false;
    }

    void serverLoadedForTests(boolean loaded) { serverLoaded = loaded; }
}
