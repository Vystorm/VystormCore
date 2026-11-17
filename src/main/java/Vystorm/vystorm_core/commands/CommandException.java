package Vystorm.vystorm_core.commands;

/**
 * Thrown by a {@link CommandHandler}, an {@link Arg.Parser} or a check to stop the command with a localized message.
 * The key is looked up in the command's plugin bundle first, then in Core's bundle (so Core's {@code command.error.*}
 * texts can be reused); placeholders are name/value pairs as in {@code Lang.component}.
 *
 * <pre>{@code
 * if (cart == null) throw new CommandException("no-cart");
 * }</pre>
 *
 * @since 0.45.0
 */
public class CommandException extends RuntimeException {
    private final String key;
    private final transient Object[] placeholders;
    private final boolean showUsage;

    public CommandException(String key, Object... placeholders) { this(key, false, placeholders); }

    private CommandException(String key, boolean showUsage, Object... placeholders) {
        super(key, null, false, false);
        this.key = key == null ? "" : key;
        this.placeholders = placeholders == null ? new Object[0] : placeholders.clone();
        this.showUsage = showUsage;
    }

    /** Like the constructor, and the usage line of the command is shown below the message. */
    public static CommandException withUsage(String key, Object... placeholders) { return new CommandException(key, true, placeholders); }

    public String key() { return key; }

    public Object[] placeholders() { return placeholders.clone(); }

    public boolean showUsage() { return showUsage; }
}
