package Vystorm.vystorm_core.network.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * One network message.
 *
 * @param type    frame type
 * @param channel plugin channel ({@code "<plugin>:<name>"}), empty for protocol frames
 * @param from    sending server name, empty if not applicable
 * @param to      target: a server name, {@code "*"} for every other server, {@code "@<uuid>"} for a player's server,
 *                empty for the hub itself
 * @param payload opaque bytes, never null
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public record Frame(FrameType type, String channel, String from, String to, byte[] payload) {
    public static final String ALL = "*";

    public Frame {
        Objects.requireNonNull(type, "type");
        channel = channel == null ? "" : channel;
        from = from == null ? "" : from;
        to = to == null ? "" : to;
        payload = payload == null ? new byte[0] : payload;
    }

    public static Frame of(FrameType type, byte[] payload) { return new Frame(type, "", "", "", payload); }

    public static String playerTarget(java.util.UUID player) { return "@" + player; }

    public Frame withFrom(String sender) { return new Frame(type, channel, sender, to, payload); }

    @Override public boolean equals(Object other) {
        return other instanceof Frame f && type == f.type && channel.equals(f.channel) && from.equals(f.from)
                && to.equals(f.to) && Arrays.equals(payload, f.payload);
    }

    @Override public int hashCode() { return Objects.hash(type, channel, from, to, Arrays.hashCode(payload)); }

    @Override public String toString() {
        return "Frame[" + type + " " + channel + " " + from + "->" + to + " " + payload.length + "B]";
    }
}
