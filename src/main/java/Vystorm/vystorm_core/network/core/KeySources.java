package Vystorm.vystorm_core.network.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Where a network key can come from, in spec order: manual Base64, Velocity modern-forwarding secret, pairing.
 * Readers never throw: a missing, unreadable or empty source is simply {@link Optional#empty()}.
 * Only minimal line parsing is used so the proxy side needs no YAML/TOML library.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class KeySources {
    private KeySources() {}

    public static NetworkKey fromVelocitySecret(String secret) {
        return NetworkKey.derive(secret.trim().getBytes(StandardCharsets.UTF_8));
    }

    public static Optional<NetworkKey> fromBase64(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            byte[] raw = Base64.getDecoder().decode(value.trim());
            return raw.length == NetworkKey.LENGTH ? Optional.of(NetworkKey.of(raw)) : Optional.empty();
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
    }

    /**
     * Reads {@code proxies.velocity.secret} from Paper's {@code config/paper-global.yml}, but only when
     * {@code proxies.velocity.enabled} is true: a leftover secret behind BungeeCord must not block pairing.
     */
    public static Optional<String> readPaperVelocitySecret(Path paperGlobalYml) {
        List<String> lines = readLines(paperGlobalYml);
        int proxiesIndent = -1;
        int velocityIndent = -1;
        boolean enabled = false;
        String secret = null;
        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.isBlank()) continue;
            int indent = indentOf(line);
            String trimmed = line.trim();
            if (velocityIndent >= 0 && indent <= velocityIndent) velocityIndent = -1;
            if (proxiesIndent >= 0 && indent <= proxiesIndent && !trimmed.startsWith("proxies:")) proxiesIndent = -1;
            if (trimmed.equals("proxies:")) { proxiesIndent = indent; continue; }
            if (proxiesIndent >= 0 && trimmed.equals("velocity:")) { velocityIndent = indent; continue; }
            if (velocityIndent >= 0 && indent > velocityIndent && trimmed.startsWith("secret:")) {
                secret = unquote(trimmed.substring("secret:".length()));
            }
            if (velocityIndent >= 0 && indent > velocityIndent && trimmed.startsWith("enabled:")) {
                enabled = unquote(trimmed.substring("enabled:".length())).equalsIgnoreCase("true");
            }
        }
        return enabled ? nonBlank(secret) : Optional.empty();
    }

    /**
     * Reads the forwarding secret the Velocity proxy uses: {@code forwarding-secret-file} from {@code velocity.toml}
     * (default {@code forwarding.secret}), resolved against the proxy directory.
     */
    public static Optional<String> readVelocityForwardingSecret(Path velocityToml, Path proxyDir) {
        String fileName = "forwarding.secret";
        for (String raw : readLines(velocityToml)) {
            String line = stripComment(raw).trim();
            if (line.startsWith("forwarding-secret-file")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String value = unquote(line.substring(eq + 1));
                    if (!value.isBlank()) fileName = value;
                }
            }
        }
        Path secretFile = proxyDir.resolve(fileName).normalize();
        try {
            if (!Files.isRegularFile(secretFile)) return Optional.empty();
            return nonBlank(Files.readString(secretFile, StandardCharsets.UTF_8).trim());
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** True if {@code velocity.toml} sets {@code player-info-forwarding-mode = "modern"} (only then the secret is shared). */
    public static boolean velocityModernForwarding(Path velocityToml) {
        for (String raw : readLines(velocityToml)) {
            String line = stripComment(raw).trim();
            if (line.startsWith("player-info-forwarding-mode")) {
                int eq = line.indexOf('=');
                return eq > 0 && unquote(line.substring(eq + 1)).equalsIgnoreCase("modern");
            }
        }
        return false;
    }

    private static List<String> readLines(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) return List.of();
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    private static String stripComment(String line) {
        boolean single = false, dbl = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (c == '#' && !single && !dbl) return line.substring(0, i);
        }
        return line;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    private static String unquote(String value) {
        String v = value.trim();
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1);
        }
        return v.trim();
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
