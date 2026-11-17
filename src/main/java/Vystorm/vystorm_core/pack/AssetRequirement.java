package Vystorm.vystorm_core.pack;

import java.util.Set;

/** Welche Pack-Module ein Feature voraussetzt (z. B. {@code vystorm-items} für die Schmiede-GUI). */
public record AssetRequirement(Set<String> modules) {
    public AssetRequirement {
        modules = modules == null ? Set.of() : Set.copyOf(modules);
    }

    public static AssetRequirement of(String... modules) { return new AssetRequirement(Set.of(modules)); }
}
