package me.clip.placeholderapi.expansion;

import me.clip.placeholderapi.PlaceholderHook;
import me.clip.placeholderapi.StubExpansions;

/**
 * Hand-written minimal stand-in for PlaceholderAPI's expansion base class (documented signatures only, see
 * {@link PlaceholderHook}). {@code register} and {@code unregister} keep the expansion in {@link StubExpansions}.
 */
public abstract class PlaceholderExpansion extends PlaceholderHook {
    public abstract String getIdentifier();

    public abstract String getAuthor();

    public abstract String getVersion();

    public String getName() { return getIdentifier(); }

    public boolean persist() { return false; }

    public boolean canRegister() { return true; }

    public final boolean isRegistered() { return StubExpansions.registered(this); }

    public boolean register() { return canRegister() && StubExpansions.register(this); }

    public final boolean unregister() { return StubExpansions.unregister(this); }
}
