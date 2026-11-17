package Vystorm.vystorm_core.worlds.internal;

import java.util.Collection;
import org.bukkit.World;

/** Registrierte Plugin-Welten (threadsicher). */
public interface WorldLookup {
    ManagedWorldImpl byId(String id);

    /** {@code null}, wenn die Welt keine Plugin-Welt ist. */
    ManagedWorldImpl byWorld(World world);

    Collection<ManagedWorldImpl> all();
}
