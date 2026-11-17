package Vystorm.vystorm_core.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The proxy side must load without Bukkit/Paper or the rest of Core on the classpath. */
class NetworkArchitectureTest {

    private static final List<String> FORBIDDEN = List.of("org/bukkit/", "io/papermc/paper/", "net/kyori/");
    private static final String CORE_PREFIX = "Vystorm/vystorm_core/";
    private static final String NETWORK_PREFIX = "Vystorm/vystorm_core/network/";
    private static final String PAPER_ADAPTER_PREFIX = "Vystorm/vystorm_core/network/paper/";

    @Test
    void proxySideClassesDoNotReferenceBukkit() throws IOException, URISyntaxException {
        // build/classes/java/test -> build/classes/java/{main,proxy}
        Path classes = Path.of(NetworkArchitectureTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        List<String> violations = new ArrayList<>();
        int scannedProxyClasses = 0;
        for (String sub : List.of("core", "velocity", "bungee")) {
            Path root = classes.resolve(sub.equals("core") ? "main" : "proxy");
            Path dir = root.resolve("Vystorm/vystorm_core/network").resolve(sub);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                    if (!sub.equals("core")) scannedProxyClasses++;
                    String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    for (String bad : FORBIDDEN) {
                        // Velocity ships Adventure itself; BungeeCord and the neutral core do not.
                        if (bad.equals("net/kyori/") && sub.equals("velocity")) continue;
                        if (text.contains(bad)) violations.add(root.relativize(file) + " -> " + bad);
                    }
                    int at = 0;
                    while ((at = text.indexOf(CORE_PREFIX, at)) >= 0) {
                        boolean allowed = text.startsWith(NETWORK_PREFIX, at) && !text.startsWith(PAPER_ADAPTER_PREFIX, at);
                        if (!allowed) violations.add(root.relativize(file) + " -> " + text.substring(at, Math.min(text.length(), at + 60)).split("[;\\x00-\\x1f]")[0]);
                        at += CORE_PREFIX.length();
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Proxy-side network classes reference forbidden types:\n" + String.join("\n", violations));
        assertTrue(scannedProxyClasses > 0, "Velocity/BungeeCord adapter classes were not found under build/classes/java/proxy");
    }
}
