package Vystorm.vystorm_core.news.internal;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.news.News;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The stored news posts ({@code plugins/vystorm_core/news.yml}): ids count up and are never reused, so "read up to id N"
 * per player is enough to know what is new. Posts older than {@code max-age-days} are hidden and dropped on the next
 * write; only the newest {@code max-posts} are kept. Thread-safe. Not public API.
 */
public final class NewsBoard {
    public static final int MAX_TITLE = 64;
    public static final int MAX_TEXT = 1000;
    static final long DAY = 24L * 60 * 60 * 1000;

    private final Path file;
    private final List<News.Post> posts = new ArrayList<>();
    private int nextId = 1;
    private Path broken;

    public NewsBoard(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file.toFile()); }
        catch (IOException | org.bukkit.configuration.InvalidConfigurationException failure) {
            // Never start empty and overwrite the posts with the next one: keep the file aside and stop saving.
            broken = AtomicFiles.moveAside(file);
            java.util.logging.Logger.getLogger("Vystorm_Core").severe("[News] news.yml could not be read (" + failure.getMessage() + "). It was kept as "
                    + broken.getFileName() + " and news are not saved until the next restart. Fix that file, rename it back to news.yml and restart.");
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("posts");
        if (section != null) for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;
            try {
                int id = Integer.parseInt(key.trim());
                String title = clean(s.getString("title", ""), MAX_TITLE, false);
                if (id <= 0 || title.isEmpty()) continue;
                posts.add(new News.Post(id, s.getLong("time"), s.getString("author", "?"), title, clean(s.getString("text", ""), MAX_TEXT, true)));
            } catch (NumberFormatException broken) {
                DebugMessages.mid("[News] Skipped post with invalid id " + key);
            }
        }
        posts.sort(Comparator.comparingInt(News.Post::id));
        nextId = Math.max(yaml.getInt("next-id", 1), posts.isEmpty() ? 1 : posts.getLast().id() + 1);
    }

    /**
     * Adds a post. Title and text are trimmed to plain text (control characters and § colour codes removed, line breaks
     * kept in the text, at most {@link #MAX_TITLE}/{@link #MAX_TEXT} characters).
     *
     * @return the new post
     * @throws IllegalArgumentException if the title is empty after cleaning
     */
    public synchronized News.Post post(String author, String title, String text, long now, int maxPosts, int maxAgeDays) {
        String cleanTitle = clean(title, MAX_TITLE, false);
        if (cleanTitle.isEmpty()) throw new IllegalArgumentException("news title is empty");
        String cleanAuthor = clean(author, 32, false);
        News.Post post = new News.Post(nextId++, now, cleanAuthor.isEmpty() ? "?" : cleanAuthor, cleanTitle, clean(text, MAX_TEXT, true));
        posts.add(post);
        prune(now, maxPosts, maxAgeDays);
        save();
        return post;
    }

    /** Corrects a post without changing its author, age, order or read state. */
    public synchronized Optional<News.Post> edit(int id, String title, String text) {
        String cleanTitle = clean(title, MAX_TITLE, false);
        if (cleanTitle.isEmpty()) throw new IllegalArgumentException("news title is empty");
        for (int i = 0; i < posts.size(); i++) {
            News.Post old = posts.get(i);
            if (old.id() != id) continue;
            News.Post updated = new News.Post(id, old.time(), old.author(), cleanTitle, clean(text, MAX_TEXT, true));
            posts.set(i, updated);
            save();
            return Optional.of(updated);
        }
        return Optional.empty();
    }

    /** Removes a post; false if there is none with that id. */
    public synchronized boolean remove(int id) {
        boolean removed = posts.removeIf(p -> p.id() == id);
        if (removed) save();
        return removed;
    }

    public synchronized Optional<News.Post> get(int id) { return posts.stream().filter(p -> p.id() == id).findFirst(); }

    /** Posts that are not older than {@code maxAgeDays} (0 = no limit), newest first. */
    public synchronized List<News.Post> visible(long now, int maxAgeDays) {
        List<News.Post> out = new ArrayList<>();
        for (int i = posts.size() - 1; i >= 0; i--) if (fresh(posts.get(i), now, maxAgeDays)) out.add(posts.get(i));
        return out;
    }

    /** Visible posts with an id above {@code lastRead}, newest first. */
    public List<News.Post> unread(int lastRead, long now, int maxAgeDays) {
        return visible(now, maxAgeDays).stream().filter(p -> p.id() > lastRead).toList();
    }

    /** The highest id ever given out (0 = none yet); a player who read up to this id has nothing new. */
    public synchronized int latestId() { return nextId - 1; }

    private static boolean fresh(News.Post post, long now, int maxAgeDays) {
        return maxAgeDays <= 0 || now - post.time() <= maxAgeDays * DAY;
    }

    private void prune(long now, int maxPosts, int maxAgeDays) {
        posts.removeIf(p -> !fresh(p, now, maxAgeDays));
        int keep = Math.max(1, maxPosts);
        while (posts.size() > keep) posts.removeFirst();
    }

    /** Where the unreadable news.yml was moved to, or null when it loaded fine; while set nothing is saved. */
    Path broken() { return broken; }

    private void save() {
        if (file == null || broken != null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Server news (/vmenu news). Written by Vystorm Core; edit in game or with /vadmin news."));
        yaml.set("next-id", nextId);
        for (News.Post p : posts) {
            String key = "posts." + p.id();
            yaml.set(key + ".time", p.time());
            yaml.set(key + ".author", p.author());
            yaml.set(key + ".title", p.title());
            yaml.set(key + ".text", p.text());
        }
        try {
            Files.createDirectories(file.getParent());
            AtomicFiles.write(file, yaml);
        } catch (IOException | RuntimeException failure) {
            DebugMessages.high("[News] news.yml not saved: " + failure);
        }
    }

    /**
     * Plain text: no control characters (line breaks only if {@code multiline}, at most one empty line in a row), no §
     * colour codes, trimmed and cut to {@code max} characters.
     */
    static String clean(String raw, int max, boolean multiline) {
        if (raw == null) return "";
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').replace('§', '&');
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') out.append(multiline ? '\n' : ' ');
            else if (c == '\t') out.append(' ');
            else if (!Character.isISOControl(c)) out.append(c);
        }
        String cleaned = out.toString().replaceAll("\n{3,}", "\n\n").replaceAll("[ ]+\n", "\n").strip();
        if (!multiline) cleaned = cleaned.replaceAll("\\s{2,}", " ");
        if (cleaned.length() > max) cleaned = cleaned.substring(0, max).strip();
        return cleaned;
    }
}
