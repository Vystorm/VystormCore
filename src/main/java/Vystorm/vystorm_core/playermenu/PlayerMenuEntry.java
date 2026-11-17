package Vystorm.vystorm_core.playermenu;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * One button in the player menu ({@link PlayerMenu}): what a viewer can do with the player they clicked. Built once in
 * {@code onEnable}, evaluated per viewer and target every time the menu is shown, so icon and visibility may show live
 * data ("already in your party", the target's level, ...). All functions run on the server thread; an exception only
 * hides this entry and is logged.
 *
 * <pre>{@code
 * PlayerMenuEntry.builder("party-invite")
 *         .priority(100)
 *         .icon((viewer, target) -> lang.name(new ItemStack(Material.CAKE), viewer, "menu.invite", "player", target.getName()))
 *         .visibleWhen((viewer, target) -> parties.canInvite(viewer, target))
 *         .onClick((viewer, target, click) -> { parties.invite(viewer, target); return PlayerMenu.Result.CLOSE; })
 *         .build();
 * }</pre>
 *
 * @since 0.43.0
 */
public final class PlayerMenuEntry {
    private final String id;
    private final int priority;
    private final BiFunction<Player, Player, ItemStack> icon;
    private final BiPredicate<Player, Player> visible;
    private final PlayerMenu.ClickHandler click;

    private PlayerMenuEntry(Builder builder) {
        this.id = builder.id;
        this.priority = builder.priority;
        this.icon = Objects.requireNonNull(builder.icon, "icon");
        this.visible = builder.visible;
        this.click = Objects.requireNonNull(builder.click, "onClick");
    }

    /** @param id unique within your plugin, {@code [a-z0-9_.-]}, 1..64 characters, e.g. {@code party-invite} */
    public static Builder builder(String id) { return new Builder(id); }

    public String id() { return id; }
    /** Sort order in the menu: lower values come first; equal values keep registration order. */
    public int priority() { return priority; }
    /** {@code (viewer, target) -> ItemStack}; {@code null} or air hides the entry for this pair. */
    public BiFunction<Player, Player, ItemStack> icon() { return icon; }
    /** {@code (viewer, target) -> boolean}; only visible entries are shown and can be clicked. */
    public BiPredicate<Player, Player> visible() { return visible; }
    public PlayerMenu.ClickHandler click() { return click; }

    @Override public String toString() { return "PlayerMenuEntry[" + id + ", priority " + priority + "]"; }

    public static final class Builder {
        private final String id;
        private int priority;
        private BiFunction<Player, Player, ItemStack> icon;
        private BiPredicate<Player, Player> visible = (viewer, target) -> true;
        private PlayerMenu.ClickHandler click;

        private Builder(String id) {
            if (id == null || !id.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("invalid player menu entry id: " + id);
            this.id = id;
        }

        /** Lower values come first (default 0). Suggested: 0-99 social (party, friends), 100-199 groups (city, guild), 200+ info (stats). */
        public Builder priority(int priority) { this.priority = priority; return this; }

        /** Required. Called each time the menu is drawn; return a fresh stack (Core does not keep it). */
        public Builder icon(BiFunction<Player, Player, ItemStack> icon) { this.icon = icon; return this; }

        /** Optional (default: always visible). Checked when the menu is drawn and again right before a click runs. */
        public Builder visibleWhen(BiPredicate<Player, Player> visible) {
            this.visible = Objects.requireNonNull(visible, "visible");
            return this;
        }

        /** Required. Runs one tick after the click, so it may open another inventory or dialog. */
        public Builder onClick(PlayerMenu.ClickHandler click) { this.click = click; return this; }

        public PlayerMenuEntry build() { return new PlayerMenuEntry(this); }
    }
}
