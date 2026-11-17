package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Hält die öffentliche API gegen die Baseline {@code api-baseline.txt} (erzeugt aus dem zuletzt veröffentlichten Jar,
 * gegen das die Plugins gebaut werden). Bricht, wenn
 * <ul>
 *   <li>ein öffentliches Element der Baseline fehlt oder seine Signatur geändert wurde, oder</li>
 *   <li>ein bestehendes Interface eine neue abstrakte Methode bekommt (neue Methoden brauchen einen {@code default}).</li>
 * </ul>
 * Pakete {@code ….internal.…} sind Core-intern und gehören nicht zur API (weder in der Baseline noch geprüft).
 *
 * <p>Internal-marked classes are excluded as well: a top-level class annotated with
 * {@code @org.jetbrains.annotations.ApiStatus.Internal} (Javadoc line "Internal: not part of the public API; may
 * change or disappear in any version.") and its nested types are neither in {@code api-baseline.txt} nor checked,
 * and a dump leaves them out. The baseline file has no comment syntax (every non-blank line is an API element), so
 * this note lives here. Since 0.63.0 this covers the implementation classes of {@code network.core},
 * {@code network.paper} and {@code trace} and the ones listed under "Internal classes" in {@code docs/API.md}.
 * The annotation is not visible to reflection, so it is read from the class file of the top-level class.
 *
 * <p>
 * Neue Baseline aus einem Jar: {@code VYSTORM_API_DUMP_JAR=<jar> VYSTORM_API_DUMP_OUT=<datei> ./gradlew test --tests '*ApiCompatibilityTest*' --no-daemon}.
 */
class ApiCompatibilityTest {
    private static final String ROOT = "Vystorm/vystorm_core/";

    @Test
    void givenBaseline_whenCurrentApiInspected_thenNothingRemovedAndNoNewAbstractInterfaceMethods() throws Exception {
        String dumpJar = System.getenv("VYSTORM_API_DUMP_JAR");
        if (dumpJar != null && !dumpJar.isBlank()) {
            Set<String> dump = fromJar(Path.of(dumpJar));
            Files.write(Path.of(System.getenv("VYSTORM_API_DUMP_OUT")), dump, StandardCharsets.UTF_8);
            return;
        }
        Set<String> baseline = new TreeSet<>();
        try (InputStream in = getClass().getResourceAsStream("/api-baseline.txt")) {
            assertNotNull(in, "api-baseline.txt fehlt");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
                if (!line.isBlank() && !internal(line.substring(0, Math.max(0, line.indexOf(' '))))) baseline.add(line.strip());
        }
        Set<String> current = fromClasses(getClass().getClassLoader(), currentClassNames());

        List<String> missing = baseline.stream().filter(line -> !current.contains(line)
                // abstrakt → default ist kompatibel
                && !(line.contains(" abstract ") && current.contains(line.replace(" abstract ", " default ")))).toList();
        Set<String> baselineInterfaces = baseline.stream().filter(l -> l.contains(" interface-method ")).map(l -> l.substring(0, l.indexOf(' '))).collect(Collectors.toSet());
        List<String> newAbstract = current.stream()
                .filter(l -> l.contains(" interface-method ") && l.contains(" abstract "))
                .filter(l -> baselineInterfaces.contains(l.substring(0, l.indexOf(' '))))
                .filter(l -> !baseline.contains(l)).toList();
        assertTrue(missing.isEmpty(), "Aus der API entfernt oder geändert:\n" + String.join("\n", missing));
        assertTrue(newAbstract.isEmpty(), "Neue abstrakte Methoden in bestehenden Interfaces (default nötig):\n" + String.join("\n", newAbstract));
    }

    private static List<String> currentClassNames() throws IOException {
        Set<String> names = new TreeSet<>();
        // The public native-dialog API now lives in the optional adapter source set, the proxy entry classes
        // (named in bungee.yml and velocity-plugin.json) in the proxy source set; the jar ships all three.
        for (Class<?> anchor : List.of(Vystorm.vystorm_core.CoreApi.class,
                Vystorm.vystorm_core._uiMethods.dialog.DialogInterface.class,
                Vystorm.vystorm_core.network.bungee.BungeeEntry.class)) {
            Path root = Path.of(URI(anchor.getProtectionDomain().getCodeSource().getLocation()));
            try (Stream<Path> files = Files.walk(root.resolve(ROOT))) {
                files.filter(p -> p.toString().endsWith(".class"))
                        .map(p -> root.relativize(p).toString().replace('\\', '.').replace('/', '.').replace(".class", ""))
                        .forEach(names::add);
            }
        }
        return List.copyOf(names);
    }

    private static java.net.URI URI(URL url) { try { return url.toURI(); } catch (Exception e) { throw new IllegalStateException(e); } }

    private static Set<String> fromJar(Path jar) throws Exception {
        List<String> names = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(ROOT) && name.endsWith(".class")) names.add(name.replace('/', '.').replace(".class", ""));
            }
        }
        ClassLoader parent = ApiCompatibilityTest.class.getClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("Vystorm.")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    return loaded;
                }
            }
        }) {
            return fromClasses(loader, names);
        }
    }

    /** Core-interne Pakete ({@code web.internal}, {@code panel.internal}, {@code internal}) sind keine API. */
    static boolean internal(String className) { return className.contains(".internal."); }

    /** True when the top-level class of {@code className} carries {@code @ApiStatus.Internal} (class-file retention). */
    static boolean markedInternal(ClassLoader loader, String className) {
        int nested = className.indexOf('$');
        String outer = nested < 0 ? className : className.substring(0, nested);
        try (InputStream in = loader.getResourceAsStream(outer.replace('.', '/') + ".class")) {
            return in != null && new String(in.readAllBytes(), StandardCharsets.ISO_8859_1)
                    .contains("Lorg/jetbrains/annotations/ApiStatus$Internal;");
        } catch (IOException e) {
            return false;
        }
    }

    static Set<String> fromClasses(ClassLoader loader, List<String> names) {
        Set<String> result = new TreeSet<>();
        for (String name : names) {
            if (internal(name) || markedInternal(loader, name)) continue;
            Class<?> type;
            try { type = Class.forName(name, false, loader); } catch (ClassNotFoundException | LinkageError e) { continue; }
            if (!isVisible(type)) continue;
            String owner = type.getName();
            boolean iface = type.isInterface();
            result.add(owner + " type " + kind(type) + (Modifier.isFinal(type.getModifiers()) ? " final" : ""));
            try {
                for (Method method : type.getDeclaredMethods()) {
                    int m = method.getModifiers();
                    if (method.isSynthetic() || !(Modifier.isPublic(m) || Modifier.isProtected(m))) continue;
                    String flavor = Modifier.isStatic(m) ? "static" : Modifier.isAbstract(m) ? "abstract" : iface ? "default" : "instance";
                    result.add(owner + (iface ? " interface-method " : " method ") + flavor + " " + method.getName()
                            + params(method.getParameterTypes()) + method.getReturnType().getTypeName());
                }
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    int m = constructor.getModifiers();
                    if (constructor.isSynthetic() || !(Modifier.isPublic(m) || Modifier.isProtected(m))) continue;
                    result.add(owner + " constructor " + params(constructor.getParameterTypes()));
                }
                for (Field field : type.getDeclaredFields()) {
                    int m = field.getModifiers();
                    if (field.isSynthetic() || !(Modifier.isPublic(m) || Modifier.isProtected(m))) continue;
                    result.add(owner + " field " + (Modifier.isStatic(m) ? "static " : "") + field.getName() + ":" + field.getType().getTypeName());
                }
            } catch (LinkageError skipped) {
                // Klassen mit fehlenden optionalen Abhängigkeiten (z. B. Treiber) überspringen
            }
        }
        return result;
    }

    private static boolean isVisible(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            int m = c.getModifiers();
            if (c.isAnonymousClass() || c.isLocalClass() || c.isSynthetic()) return false;
            if (!(Modifier.isPublic(m) || (c != type && Modifier.isProtected(m)))) return false;
        }
        return true;
    }

    private static String kind(Class<?> type) {
        if (type.isAnnotation()) return "annotation";
        if (type.isInterface()) return "interface";
        if (type.isEnum()) return "enum";
        if (type.isRecord()) return "record";
        return "class";
    }

    private static String params(Class<?>[] types) {
        return Arrays.stream(types).map(Class::getTypeName).collect(Collectors.joining(",", "(", ")"));
    }
}
