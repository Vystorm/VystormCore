package Vystorm.vystorm_core.blocks.internal;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.blocks.CellTest;
import Vystorm.vystorm_core.blocks.EditAsk;
import Vystorm.vystorm_core.blocks.EditGuard;
import Vystorm.vystorm_core.blocks.EditGuards;
import Vystorm.vystorm_core.blocks.EditVerdict;
import Vystorm.vystorm_core.region.RegionService;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.bukkit.plugin.Plugin;

/**
 * Core's {@link EditGuards}: Core's region answer first, then every registered guard. Registration is lock-free
 * (copy on write); a guard that throws is a deny and is logged once per registration. Guards registered with
 * {@link #registerOwnRegions} make Core's region answer skip the {@code build} flag of their owner's regions while at
 * least one such guard of the owner is registered; every ask takes one snapshot of the guards and derives the skipped
 * owners from it, so a flag is never skipped in a pass that does not also ask the guard answering for it.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
public final class EditGuardRegistry implements EditGuards {
    private static volatile EditGuardRegistry shared;

    private record Entry(Plugin owner, EditGuard guard, AtomicBoolean logged, boolean ownRegions) {}

    private final RegionEditGuard core;
    private final BiConsumer<String, Throwable> log;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    /** Names of the plugins with an own-regions guard; replaced as a whole, never changed in place. */
    private volatile Set<String> ownRegionOwners = Set.of();

    /** @param log receives the one message per failing guard ({@code DebugMessages.high} on a server) */
    public EditGuardRegistry(RegionService regions, BiConsumer<String, Throwable> log) {
        this.core = new RegionEditGuard(regions);
        this.log = Objects.requireNonNull(log, "log");
    }

    /** The registry behind {@code api.area().editGuards()}. */
    public static EditGuardRegistry shared() {
        EditGuardRegistry current = shared;
        if (current != null) return current;
        synchronized (EditGuardRegistry.class) {
            if (shared == null) shared = new EditGuardRegistry(RegionService.get(), DebugMessages::high);
            return shared;
        }
    }

    @Override
    public AutoCloseable register(Plugin owner, EditGuard guard) { return add(owner, guard, false); }

    @Override
    public AutoCloseable registerOwnRegions(Plugin owner, EditGuard guard) { return add(owner, guard, true); }

    private AutoCloseable add(Plugin owner, EditGuard guard, boolean ownRegions) {
        Entry entry = new Entry(Objects.requireNonNull(owner, "owner"), Objects.requireNonNull(guard, "guard"), new AtomicBoolean(), ownRegions);
        entries.add(entry);
        if (ownRegions) refreshOwners();
        return () -> { if (entries.remove(entry) && ownRegions) refreshOwners(); };
    }

    /** Removes every guard of the plugin (plugin disabled); its regions' {@code build} flags count again. */
    public void release(Plugin owner) {
        if (entries.removeIf(entry -> entry.owner() == owner)) refreshOwners();
    }

    /** The owners with an own-regions guard now (for status output; {@link #ask} derives its own set per ask). */
    public Set<String> ownRegionOwners() { return ownRegionOwners; }

    private synchronized void refreshOwners() {
        Set<String> names = new HashSet<>();
        for (Entry entry : entries) if (entry.ownRegions()) names.add(entry.owner().getName());
        ownRegionOwners = Set.copyOf(names);
    }

    /** Core's regions and every registered guard asked for one chunk. Server thread. */
    public EditVerdict ask(EditAsk ask) {
        Objects.requireNonNull(ask, "ask");
        // one snapshot for both: a flag is only skipped when the guard that answers for it is asked in this same pass
        Object[] snapshot = entries.toArray();
        Set<String> skipped = Set.of();
        for (Object o : snapshot) {
            Entry entry = (Entry) o;
            if (!entry.ownRegions()) continue;
            if (skipped.isEmpty()) skipped = Set.of(entry.owner().getName());
            else if (!skipped.contains(entry.owner().getName())) { Set<String> more = new HashSet<>(skipped); more.add(entry.owner().getName()); skipped = Set.copyOf(more); }
        }
        EditVerdict combined = core.ask(ask, skipped);
        if (combined instanceof EditVerdict.Deny) return combined;
        for (Object o : snapshot) {
            Entry entry = (Entry) o;
            EditVerdict verdict;
            try {
                verdict = entry.guard().ask(ask);
                if (verdict == null) verdict = new EditVerdict.Allow();
            } catch (RuntimeException | LinkageError failure) {
                if (entry.logged().compareAndSet(false, true))
                    log.accept("[EditGuards] The edit guard of " + entry.owner().getName() + " failed; its chunks count as protected", failure);
                verdict = new EditVerdict.Deny("guard-failed");
            }
            if (verdict instanceof EditVerdict.Deny) return verdict;
            combined = and(combined, verdict);
        }
        return combined;
    }

    private static EditVerdict and(EditVerdict a, EditVerdict b) {
        if (a instanceof EditVerdict.Allow) return b;
        if (b instanceof EditVerdict.Allow) return a;
        CellTest first = ((EditVerdict.Partial) a).allowed(), second = ((EditVerdict.Partial) b).allowed();
        return new EditVerdict.Partial((x, y, z) -> first.allowed(x, y, z) && second.allowed(x, y, z));
    }
}
