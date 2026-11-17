package Vystorm.vystorm_core.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Baut aus den Modulen ein deterministisches Pack-ZIP (sortierte Einträge, feste Zeitstempel) samt SHA-1.
 * Reine Byte-Arbeit ohne Bukkit-Zugriff, daher off-thread zulässig.
 */
public final class PackBuilder {
    public record Built(byte[] zip, String sha1, int files, List<String> conflicts) {}

    private PackBuilder() {}

    /** Höchstes Ressourcenformat, das der Pack als unterstützt meldet (26.2 = 88; Luft für Patch-Versionen). */
    static final int MAX_FORMAT = 99;

    /**
     * pack.mcmeta: ab Format 65 verlangt Minecraft {@code min_format}/{@code max_format} (sonst verwirft der Client die
     * Metadaten und der Pack lädt nicht richtig); ältere Formate behalten {@code pack_format}.
     */
    static String mcmeta(int packFormat, String description) {
        String escaped = description.replace("\\", "\\\\").replace("\"", "\\\"");
        String formats = packFormat > 64
                ? "\"min_format\":" + packFormat + ",\"max_format\":" + Math.max(MAX_FORMAT, packFormat)
                : "\"pack_format\":" + packFormat;
        return "{\"pack\":{" + formats + ",\"description\":\"" + escaped + "\"}}";
    }

    public static Built build(List<AssetRegistry.Module> modules, int packFormat, String description) throws IOException {
        Map<String, byte[]> merged = new TreeMap<>();
        Map<String, String> source = new TreeMap<>();
        List<String> conflicts = new java.util.ArrayList<>();
        List<AssetRegistry.Module> ordered = modules.stream().sorted(Comparator.comparingInt(AssetRegistry.Module::priority).thenComparing(AssetRegistry.Module::id)).toList();
        for (AssetRegistry.Module module : ordered) {
            for (Map.Entry<String, byte[]> file : module.files().entrySet()) {
                String previous = source.put(file.getKey(), module.id());
                if (previous != null) conflicts.add(file.getKey() + ": " + previous + " → " + module.id());
                merged.put(file.getKey(), file.getValue());
            }
        }
        merged.put("pack.mcmeta", mcmeta(packFormat, description).getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : merged.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(315532800000L);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        byte[] zip = bytes.toByteArray();
        return new Built(zip, sha1(zip), merged.size(), List.copyOf(conflicts));
    }

    public static String sha1(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
