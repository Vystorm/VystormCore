package Vystorm.vystorm_core.knownplayers.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the server's own {@code usercache.json} (vanilla: {@code [{"name","uuid","expiresOn"}]}, written by the server
 * for every player that joined, with an expiry one month after the last use). Used once, when Core starts without a
 * {@code known-players.dat}: it gives the recent players without touching player data files and without any network
 * lookup. Last seen is the expiry minus 30 days (never in the future); entries with a bad UUID or name are skipped; for a
 * UUID listed twice the newest entry wins. Pure. Core-internal.
 */
public final class UserCacheSeed {
    static final long MONTH_MS = 30L * 24 * 60 * 60 * 1000;
    private static final DateTimeFormatter EXPIRES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    /** Result: entries and the number of skipped ones. */
    public record Seed(List<KnownPlayerStore.Entry> entries, int skipped) {}

    private UserCacheSeed() {}

    public static Seed parse(String json, long now) {
        List<KnownPlayerStore.Entry> none = new ArrayList<>();
        if (json == null || json.isBlank()) return new Seed(none, 0);
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException broken) {
            return new Seed(none, 1);
        }
        if (!root.isJsonArray()) return new Seed(none, 1);
        JsonArray array = root.getAsJsonArray();
        Map<UUID, KnownPlayerStore.Entry> byId = new LinkedHashMap<>();
        int skipped = 0;
        for (JsonElement element : array) {
            KnownPlayerStore.Entry e = entry(element, now);
            if (e == null) {
                skipped++;
                continue;
            }
            KnownPlayerStore.Entry old = byId.get(e.id());
            if (old == null || e.lastSeen() > old.lastSeen()) {
                if (old != null) skipped++;
                byId.put(e.id(), e);
            } else {
                skipped++;
            }
        }
        return new Seed(new ArrayList<>(byId.values()), skipped);
    }

    private static KnownPlayerStore.Entry entry(JsonElement element, long now) {
        if (!element.isJsonObject()) return null;
        JsonObject o = element.getAsJsonObject();
        try {
            UUID id = UUID.fromString(o.get("uuid").getAsString());
            String name = KnownPlayerCodec.cleanName(o.get("name").getAsString());
            if (name.isEmpty()) return null;
            long seen = now;
            if (o.has("expiresOn")) {
                try {
                    seen = Math.min(now, ZonedDateTime.parse(o.get("expiresOn").getAsString(), EXPIRES).toInstant().toEpochMilli() - MONTH_MS);
                } catch (DateTimeParseException | UnsupportedOperationException odd) {
                    seen = now - MONTH_MS;
                }
            }
            seen = Math.max(0, seen);
            return new KnownPlayerStore.Entry(id, name, seen, seen, null, false);
        } catch (RuntimeException bad) {   // missing field, not a string, bad UUID
            return null;
        }
    }
}
