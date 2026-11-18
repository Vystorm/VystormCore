package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import java.util.List;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * What a {@link PlaceholderResolver} is asked: for whom, which key, with which arguments.
 * <p>
 * {@code %vystorm_boards_votes_event_pvp%} and {@code {boards:votes:event:pvp}} both arrive as namespace
 * {@code boards}, key {@code votes}, arguments {@code [event, pvp]}. In the percent form the underscore separates,
 * so an argument that contains underscores arrives in pieces: {@link #rest(int)} puts them together again as they
 * were written.
 *
 * @since 0.65.0
 */
public final class PlaceholderContext {
    private final OfflinePlayer viewer;
    private final Player other;
    private final String namespace;
    private final String key;
    private final List<String> arguments;
    private final String raw;
    private final String separator;
    private final boolean serverThread;

    PlaceholderContext(OfflinePlayer viewer, Player other, String namespace, String key, List<String> arguments,
                       String raw, String separator, boolean serverThread) {
        this.viewer = viewer;
        this.other = other;
        this.namespace = namespace;
        this.key = key;
        this.arguments = arguments;
        this.raw = raw;
        this.separator = separator;
        this.serverThread = serverThread;
    }

    /** The player the text is shown to; {@code null} for the console or a text without a viewer. */
    public OfflinePlayer viewer() { return viewer; }

    /** The viewer if online, else {@code null}. */
    public Player player() {
        if (viewer instanceof Player online) return online;
        return viewer == null ? null : viewer.getPlayer();
    }

    /** The viewer's id, or {@code null} without a viewer. */
    public UUID viewerId() { return viewer == null ? null : viewer.getUniqueId(); }

    /** The second player of a relational placeholder ("how does the viewer see this player"), else {@code null}. */
    public Player other() { return other; }

    /** Whether a second player was given. */
    public boolean relational() { return other != null; }

    /** The namespace, lower case. */
    public String namespace() { return namespace; }

    /** The key, lower case. For a namespace-wide resolver: the first part after the namespace. */
    public String key() { return key; }

    /** The arguments after the key, as written (case kept); never null. */
    public List<String> arguments() { return arguments; }

    /** The argument at {@code index}, or {@code null} if there are fewer. */
    public String argument(int index) { return index >= 0 && index < arguments.size() ? arguments.get(index) : null; }

    /** The argument at {@code index}, or {@code fallback} if it is missing or empty. */
    public String argument(int index, String fallback) {
        String value = argument(index);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** The argument at {@code index} as a whole number, or {@code fallback} if it is missing or not a number. */
    public int intArgument(int index, int fallback) {
        String value = argument(index);
        if (value == null) return fallback;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException notANumber) { return fallback; }
    }

    /** The argument at {@code index} as a number, or {@code fallback} if it is missing or not a finite number. */
    public double numberArgument(int index, double fallback) {
        String value = argument(index);
        if (value == null) return fallback;
        try {
            double number = Double.parseDouble(value.trim());
            return Double.isFinite(number) ? number : fallback;
        } catch (NumberFormatException notANumber) { return fallback; }
    }

    /**
     * The arguments from {@code index} on as one text, joined the way they were written ({@code _} in the percent
     * form, {@code :} in the brace form): a world name such as {@code world_nether} or a time pattern such as
     * {@code HH:mm}. Empty if there are fewer arguments.
     */
    public String rest(int index) {
        if (index <= 0) return String.join(separator, arguments);
        return index >= arguments.size() ? "" : String.join(separator, arguments.subList(index, arguments.size()));
    }

    /** Everything after the namespace as it was written, for a resolver that splits it itself. */
    public String raw() { return raw; }

    /** Whether this call runs on the server thread. Values that are not async-safe are only called there. */
    public boolean onServerThread() { return serverThread; }

    @Override public String toString() { return namespace + ":" + key + arguments; }
}
