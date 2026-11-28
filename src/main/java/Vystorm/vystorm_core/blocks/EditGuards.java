package Vystorm.vystorm_core.blocks;

import Vystorm.vystorm_core.blocks.internal.EditGuardRegistry;
import org.bukkit.plugin.Plugin;

/**
 * The guards plugins register for bulk edits, combined with Core's regions ({@code api.area().editGuards()}).
 * {@code api.area().mayEdit(ask)} combines Core's answer and every guard: any {@link EditVerdict.Deny} wins, and
 * {@link EditVerdict.Partial} answers are and-ed. Callers that hold a bypass permission do not ask.
 *
 * @since 0.69.0
 */
public interface EditGuards {
    /**
     * Registers a guard until the returned handle is closed or {@code owner} is disabled. Any thread.
     *
     * @return closing it unregisters the guard
     */
    AutoCloseable register(Plugin owner, EditGuard guard);

    /**
     * Registers a guard that answers for the regions {@code owner} published itself ({@code Region.owner()} is the
     * owner's name), so a plugin can let its own builders, members or bypassing admins edit where its regions set
     * {@code build} to deny for everyone. While it is registered, Core's region answer skips the flag {@code build} of
     * the owner's regions (the flag stays readable through {@code RegionService.flag} for everything else); the
     * regions of other plugins count as before, and the guard's answer is and-ed with them (any deny wins). Where a
     * region of the owner and a region of another plugin overlap, both must allow, where before the region with the
     * higher priority that sets {@code build} decided alone. When the handle is closed or the owner is disabled the
     * owner's flags count again. The guard also gets the asks without an actor (console, machines): it should answer
     * them as its published flag says. Any thread.
     *
     * <p>On a Core without this registration (older than 0.71.0, or a replacement manager) the default registers an
     * ordinary guard, which can only make the answer stricter. Capability {@code edit-ask-own-regions}.
     *
     * @return closing it unregisters the guard and lets the owner's {@code build} flags count again
     * @since 0.71.0
     */
    default AutoCloseable registerOwnRegions(Plugin owner, EditGuard guard) { return register(owner, guard); }

    /** The shared guards of this server. */
    static EditGuards instance() { return EditGuardRegistry.shared(); }

    /** Core's regions and every registered guard asked for one chunk. Server thread. */
    static EditVerdict ask(EditAsk ask) { return EditGuardRegistry.shared().ask(ask); }
}
