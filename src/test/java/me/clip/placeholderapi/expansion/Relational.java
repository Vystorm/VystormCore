package me.clip.placeholderapi.expansion;

import org.bukkit.entity.Player;

/** Hand-written minimal stand-in for PlaceholderAPI's interface for {@code %rel_<identifier>_<params>%}. */
public interface Relational {
    String onPlaceholderRequest(Player one, Player two, String identifier);
}
