package Vystorm.vystorm_core.licensing.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Defines a bounded module directly from an in-memory JAR. Classes and resources in the configured package are
 * module-only; all other classes and resources are parent-only. Closing clears the retained archive bytes and prevents
 * further module class definitions. Classes already defined by this loader cannot be revoked by closing it.
 */
public final class RamModuleClassLoader extends ClassLoader implements AutoCloseable {
    private static final int MAX_JAR_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final String MANIFEST = "META-INF/MANIFEST.MF";
    private static final List<String> PROTECTED_ROOTS = List.of(
            "java", "javax", "jdk", "sun", "org.bukkit", "io.papermc", "Vystorm.vystorm_core");

    private final String packagePrefix;
    private final String packagePath;
    private final Map<String, byte[]> entries;
    private boolean closed;

    public RamModuleClassLoader(byte[] jar, ClassLoader parent, String packagePrefix) throws IOException {
        super(parent);
        this.packagePrefix = validatePackagePrefix(packagePrefix);
        this.packagePath = this.packagePrefix.replace('.', '/');
        if (jar == null) throw new IllegalArgumentException("jar must not be null");
        if (jar.length > MAX_JAR_BYTES) throw new IOException("Module JAR exceeds 4 MiB");
        this.entries = readEntries(jar);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (name.startsWith(packagePrefix)) loaded = findClass(name);
                else {
                    ClassLoader parent = getParent();
                    if (parent != null) loaded = parent.loadClass(name);
                    else loaded = findSystemClass(name);
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    @Override
    protected synchronized Class<?> findClass(String name) throws ClassNotFoundException {
        if (closed) throw new ClassNotFoundException(name + " (module loader is closed)");
        if (!name.startsWith(packagePrefix)) throw new ClassNotFoundException(name);
        String path = name.replace('.', '/') + ".class";
        byte[] bytes = entries.get(path);
        if (bytes == null) throw new ClassNotFoundException(name);
        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    public synchronized InputStream getResourceAsStream(String name) {
        if (isModulePath(name)) {
            byte[] bytes = closed ? null : entries.get(name);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }
        ClassLoader parent = getParent();
        return parent == null ? ClassLoader.getSystemResourceAsStream(name) : parent.getResourceAsStream(name);
    }

    @Override
    public synchronized URL getResource(String name) {
        if (isModulePath(name)) {
            byte[] bytes = closed ? null : entries.get(name);
            return bytes == null ? null : memoryUrl(name, bytes);
        }
        ClassLoader parent = getParent();
        return parent == null ? ClassLoader.getSystemResource(name) : parent.getResource(name);
    }

    @Override
    public synchronized Enumeration<URL> getResources(String name) throws IOException {
        if (isModulePath(name)) {
            URL resource = getResource(name);
            return Collections.enumeration(resource == null ? List.of() : List.of(resource));
        }
        ClassLoader parent = getParent();
        return parent == null ? ClassLoader.getSystemResources(name) : parent.getResources(name);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        entries.values().forEach(bytes -> java.util.Arrays.fill(bytes, (byte) 0));
        entries.clear();
    }

    private boolean isModulePath(String name) {
        if (name == null || name.indexOf('\\') >= 0 || name.startsWith("/")) return false;
        return name.startsWith(packagePath);
    }

    private static URL memoryUrl(String name, byte[] bytes) {
        try {
            return new URL(null, "rammodule:/" + name, new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        @Override public void connect() {}
                        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
                        @Override public int getContentLength() { return bytes.length; }
                    };
                }
            });
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Map<String, byte[]> readEntries(byte[] jar) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        Set<String> names = new HashSet<>();
        int expanded = 0;
        int count = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new IOException("Module JAR has too many entries");
                String name = entry.getName();
                validateEntryName(name, entry.isDirectory());
                String duplicateKey = entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
                if (!names.add(duplicateKey)) throw new IOException("Duplicate JAR entry: " + name);
                if (name.startsWith("META-INF/versions/")) throw new IOException("Multi-release JARs are not allowed");
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.matches(".*\\.so(?:\\.[0-9]+)*$") || lower.endsWith(".dll") || lower.endsWith(".dylib")
                        || lower.endsWith(".jnilib") || lower.endsWith(".a") || lower.endsWith(".o")) {
                    throw new IOException("Native entries are not allowed");
                }
                boolean manifest = name.equals(MANIFEST);
                boolean allowedDirectory = entry.isDirectory() && (name.equals("META-INF/")
                        || packagePath.startsWith(name) || name.startsWith(packagePath));
                if (!manifest && name.startsWith("META-INF/") && !allowedDirectory) {
                    throw new IOException("JAR metadata is not allowed: " + name);
                }
                if (!manifest && !name.startsWith(packagePath) && !allowedDirectory) {
                    throw new IOException("Entry outside module package: " + name);
                }
                if (name.endsWith(".class") && !name.startsWith(packagePath)) {
                    throw new IOException("Class outside module package: " + name);
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                int read;
                while ((read = input.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > MAX_EXPANDED_BYTES) throw new IOException("Expanded module JAR exceeds 16 MiB");
                    output.write(buffer, 0, read);
                }
                if (entry.isDirectory() && output.size() != 0) throw new IOException("JAR directory entry has content: " + name);
                if (!manifest && !entry.isDirectory()) result.put(name, output.toByteArray());
            }
        } catch (IOException | RuntimeException e) {
            result.values().forEach(bytes -> java.util.Arrays.fill(bytes, (byte) 0));
            throw e;
        }
        if (count == 0 && !isCanonicalEmptyZip(jar)) throw new IOException("Input is not a ZIP archive");
        return result;
    }

    private static boolean isCanonicalEmptyZip(byte[] bytes) {
        if (bytes.length != 22 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 5 || bytes[3] != 6) return false;
        for (int i = 4; i < bytes.length; i++) if (bytes[i] != 0) return false;
        return true;
    }

    private void validateEntryName(String name, boolean directory) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0) throw new IOException("Unsafe JAR entry path");
        String path = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (path.isEmpty()) throw new IOException("Unsafe JAR entry path");
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe JAR entry path: " + name);
        }
    }

    private static String validatePackagePrefix(String prefix) {
        if (prefix == null || !prefix.endsWith(".") || prefix.length() < 2) {
            throw new IllegalArgumentException("packagePrefix must be a trailing-dot Java package");
        }
        String packageName = prefix.substring(0, prefix.length() - 1);
        if (!javax.lang.model.SourceVersion.isName(packageName)) throw new IllegalArgumentException("Invalid Java package prefix: " + prefix);
        for (String root : PROTECTED_ROOTS) {
            if (packageName.equals(root) || packageName.startsWith(root + ".") || root.startsWith(packageName + ".")) {
                throw new IllegalArgumentException("Protected Java package prefix: " + prefix);
            }
        }
        return prefix;
    }
}
