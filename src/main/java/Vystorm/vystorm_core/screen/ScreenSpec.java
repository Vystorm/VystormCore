package Vystorm.vystorm_core.screen;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/**
 * What a screen shows and how it reacts (since 0.57.0). Panel units are blocks at distance 1, so a 0.9 x 0.55 panel
 * fills about the same part of the view in both modes.
 */
public record ScreenSpec(String id, ScreenLayout layout, Mode preferred, Set<ScreenInput.Key> claimedKeys,
                         BiConsumer<ScreenSession, ScreenInput> onInput, Consumer<Player> fallback, Backdrop backdrop) {
    /** AUTO: PINNED when the Core pack is loaded and the player did not choose classic menus, else FLOATING. */
    public enum Mode { AUTO, PINNED, FLOATING }

    /**
     * What fills the view behind a PINNED panel. DIM keeps the world faintly visible (like the vanilla menu background);
     * BLACK, GRAY and WHITE hide it completely, so only the pointer moves while the player looks around; NONE shows the world.
     */
    public enum Backdrop {
        DIM(0x0B0D12, 205), BLACK(0x08090C, 255), GRAY(0x5E636B, 255), WHITE(0xE6E8EB, 255), NONE(0, 0);
        private final int rgb, opacity;
        Backdrop(int rgb, int opacity) { this.rgb = rgb; this.opacity = opacity; }
        public int rgb() { return rgb; }
        public int opacity() { return opacity; }
    }

    public ScreenSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(layout, "layout");
        preferred = preferred == null ? Mode.AUTO : preferred;
        claimedKeys = claimedKeys == null || claimedKeys.isEmpty() ? Set.of() : Set.copyOf(claimedKeys);
        onInput = onInput == null ? (s, i) -> { } : onInput;
        fallback = fallback == null ? p -> { } : fallback;
        backdrop = backdrop == null ? Backdrop.BLACK : backdrop;
    }

    public static Builder builder(String id, ScreenLayout layout) { return new Builder(id, layout); }

    public static final class Builder {
        private final String id;
        private final ScreenLayout layout;
        private Mode mode = Mode.AUTO;
        private Backdrop backdrop = Backdrop.BLACK;
        private final Set<ScreenInput.Key> keys = EnumSet.noneOf(ScreenInput.Key.class);
        private BiConsumer<ScreenSession, ScreenInput> onInput;
        private Consumer<Player> fallback;

        private Builder(String id, ScreenLayout layout) { this.id = id; this.layout = layout; }

        public Builder mode(Mode preferred) { this.mode = preferred; return this; }
        /** Background behind PINNED panels (default BLACK, chosen in the owner test 2026-11-15). */
        public Builder backdrop(Backdrop fill) { this.backdrop = fill; return this; }
        /** Delivers these keys as {@link ScreenInput.KeyInput}; claiming SNEAK means sneaking no longer closes the screen. */
        public Builder claim(ScreenInput.Key... claimed) { keys.addAll(Set.of(claimed)); return this; }
        public Builder onInput(BiConsumer<ScreenSession, ScreenInput> handler) { this.onInput = handler; return this; }
        /** Runs instead of the screen when it cannot open (refused, unsupported). */
        public Builder fallback(Consumer<Player> classic) { this.fallback = classic; return this; }
        public ScreenSpec build() { return new ScreenSpec(id, layout, mode, keys, onInput, fallback, backdrop); }
    }
}
