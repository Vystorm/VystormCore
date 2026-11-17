package Vystorm.vystorm_core._uiMethods.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns flat config paths into folders for the admin menu; a section with one value stays in its parent. @since 0.54.0 */
final class ConfigTree {
    record Folder(String path, List<Folder> folders, List<String> options) {
        String name() { return path.substring(path.lastIndexOf('.') + 1); }
        int size() { return options.size() + folders.stream().mapToInt(Folder::size).sum(); }
        List<String> all() {
            List<String> out = new ArrayList<>(options);
            for (Folder f : folders) out.addAll(f.all());
            return out;
        }
    }
    private ConfigTree() {}

    /** {@code root} is the common prefix of all paths without a trailing dot, or empty for top-level values. */
    static Folder build(String root, List<String> paths) {
        String prefix = root.isEmpty() ? "" : root + ".";
        LinkedHashMap<String, List<String>> groups = new LinkedHashMap<>();
        for (String path : paths) {
            String rest = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
            int dot = rest.indexOf('.');
            groups.computeIfAbsent(dot < 0 ? "\0" + path : prefix + rest.substring(0, dot), k -> new ArrayList<>()).add(path);
        }
        List<Folder> folders = new ArrayList<>();
        List<String> options = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            if (group.getKey().startsWith("\0") || group.getValue().size() == 1) options.addAll(group.getValue());
            else folders.add(build(group.getKey(), group.getValue()));
        }
        return new Folder(root, List.copyOf(folders), List.copyOf(options));
    }

    /** Lines of at most {@code width} characters, broken at spaces, at most {@code lines} lines. */
    static List<String> wrap(String text, int width, int lines) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
                if (out.size() == lines) { out.set(lines - 1, out.get(lines - 1) + "…"); return out; }
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }
}
