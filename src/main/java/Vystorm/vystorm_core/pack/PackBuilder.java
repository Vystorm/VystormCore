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
    static String mcmeta(int packFormat, String description) { return mcmeta(packFormat, description, java.util.Set.of()); }

    /**
     * An overlay folder in the pack: its files replace the base files on clients with resource format
     * {@code minFormat} or newer (up to the pack's highest format). Module files under {@code <directory>/assets/...}
     * belong to it.
     */
    record Overlay(String directory, int minFormat) {}

    /** 26.3 (format 97.1) compiles core shaders differently: shaders for it live in this overlay (since 0.65.1). */
    static final List<Overlay> OVERLAYS = List.of(new Overlay("vystorm_26_3", 97));

    /** The overlay a pack path belongs to, or null for a base file. */
    static Overlay overlayOf(String path) {
        for (Overlay overlay : OVERLAYS) if (path.startsWith(overlay.directory() + "/")) return overlay;
        return null;
    }

    /** pack.mcmeta with an {@code overlays} entry for every used overlay that the pack's format range reaches. */
    static String mcmeta(int packFormat, String description, java.util.Set<String> usedOverlays) {
        String escaped = description.replace("\\", "\\\\").replace("\"", "\\\"");
        int max = Math.max(MAX_FORMAT, packFormat);
        String formats = packFormat > 64
                ? "\"min_format\":" + packFormat + ",\"max_format\":" + max
                : "\"pack_format\":" + packFormat;
        StringBuilder entries = new StringBuilder();
        if (packFormat > 64) for (Overlay overlay : OVERLAYS) {
            if (!usedOverlays.contains(overlay.directory()) || overlay.minFormat() > max) continue;
            if (!entries.isEmpty()) entries.append(',');
            entries.append("{\"min_format\":").append(Math.max(overlay.minFormat(), packFormat)).append(",\"max_format\":").append(max)
                    .append(",\"directory\":\"").append(overlay.directory()).append("\"}");
        }
        String overlays = entries.isEmpty() ? "" : ",\"overlays\":{\"entries\":[" + entries + "]}";
        return "{\"pack\":{" + formats + ",\"description\":\"" + escaped + "\"}" + overlays + "}";
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
        java.util.Set<String> used = new java.util.TreeSet<>();
        for (String path : merged.keySet()) { Overlay overlay = overlayOf(path); if (overlay != null) used.add(overlay.directory()); }
        merged.put("pack.mcmeta", mcmeta(packFormat, description, used).getBytes(StandardCharsets.UTF_8));
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
