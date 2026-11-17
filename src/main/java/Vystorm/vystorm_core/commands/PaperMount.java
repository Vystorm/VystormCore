package Vystorm.vystorm_core.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Registers a root through Paper's Brigadier lifecycle API. Only touched when the classes exist, so servers without
 * it fall back to the Bukkit command map ({@link LinkageError} is caught by the caller). The handler must be added while
 * the owning plugin is enabling; Paper then calls it once at startup (after all plugins are enabled) and again on
 * {@code /minecraft:reload}.
 */
final class PaperMount {
    private PaperMount() {}

    static boolean available() {
        try {
            Class.forName("io.papermc.paper.command.brigadier.BasicCommand");
            Class.forName("io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents");
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    /** Adds the lifecycle handler; {@code false} if Paper refuses (plugin not enabling) – then use Bukkit. */
    static boolean mount(CommandRegistration registration, String description) {
        BasicCommand command = new BasicCommand() {
            @Override public void execute(CommandSourceStack source, String[] args) {
                registration.execute(source.getSender(), registration.label(), args);
            }

            @Override public Collection<String> suggest(CommandSourceStack source, String[] args) {
                return registration.suggest(source.getSender(), registration.label(), args.length == 0 ? new String[]{""} : args);
            }

            public String permission() { return registration.spec().permission(); }
        };
        try {
            registration.plugin().getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                if (!Registry.INSTANCE.contains(registration)) return;
                Set<String> labels = event.registrar().register(registration.label(), description, registration.spec().aliases(), command);
                List<String> got = new ArrayList<>(labels == null ? Set.of() : labels);
                registration.registeredLabels = List.copyOf(got);
                registration.labelConflict = got.contains(registration.label()) ? null : "?";
                registration.mount = CommandRegistration.Mount.PAPER;
            });
            return true;
        } catch (IllegalStateException | UnsupportedOperationException refused) {
            return false;
        }
    }
}
