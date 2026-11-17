package Vystorm.vystorm_core.party;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Unveränderliche Sicht auf eine Party. {@code settings} sind frei erweiterbare Schlüssel (z. B. {@code xp-share},
 * {@code friendly-fire}, oder Einstellungen anderer Plugins wie {@code bloodmoon.shared-spawns}).
 */
public record PartyView(UUID id, String name, UUID leader, Set<UUID> members, Map<String, String> settings, long createdAt) {
    public PartyView {
        members = Set.copyOf(members);
        settings = Map.copyOf(settings);
    }

    public boolean contains(UUID player) { return members.contains(player); }

    public String setting(String key, String fallback) { return settings.getOrDefault(key, fallback); }

    public boolean flag(String key, boolean fallback) {
        String value = settings.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
