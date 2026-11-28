package Vystorm.vystorm_core.blocks;

/**
 * A plugin's own answer to bulk edits (claims, plots, arenas), registered with
 * {@code api.area().editGuards().register(plugin, guard)}. Called on the server thread for every chunk an edit
 * touches: it must be fast and must not do I/O. A guard that throws counts as {@link EditVerdict.Deny}.
 *
 * @since 0.69.0
 */
@FunctionalInterface
public interface EditGuard {
    EditVerdict ask(EditAsk ask);
}
