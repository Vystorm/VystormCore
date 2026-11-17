package test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a classpath system property set by build.gradle. Values starting with {@code @} name a file that holds the
 * classpath, because Windows caps command lines at 32k characters and Core's test classpath is longer than that.
 */
public final class TestClasspath {
    private TestClasspath() {}

    public static String property(String name) {
        String value = System.getProperty(name);
        if (value == null) throw new IllegalStateException("System property " + name + " is not set (run tests through Gradle)");
        if (!value.startsWith("@")) return value;
        try {
            return Files.readString(Path.of(value.substring(1)), StandardCharsets.UTF_8).trim();
        } catch (IOException failure) {
            throw new UncheckedIOException("Classpath file for " + name + " not readable", failure);
        }
    }
}
