package Vystorm.vystorm_core.commands;

/**
 * What a command or subcommand does. Runs on the server thread unless the node is {@link CommandSpec.Builder#async()
 * async}. Throw a {@link CommandException} for a localized error message.
 *
 * @since 0.45.0
 */
@FunctionalInterface
public interface CommandHandler {
    void execute(CommandContext context);
}
