package Vystorm.vystorm_core.packet.internal.wire;

/**
 * Facts about the server's global block state ids that the section format needs: which ids are air (they do not count
 * as blocks), which hold a fluid, and how many bits a direct (paletteless) container uses. Immutable; any thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version (moved from VystormAntiXray's
 * {@code wire.BlockTables} without its terrain part, which stays in that plugin).
 */
public final class WireTables {
    final boolean[] air, fluid;
    final int directBits;

    public WireTables(boolean[] air, boolean[] fluid) {
        if (air.length != fluid.length || air.length < 2) throw new IllegalArgumentException("tables of different size");
        this.air = air.clone();
        this.fluid = fluid.clone();
        this.directBits = 32 - Integer.numberOfLeadingZeros(air.length - 1);
    }

    public int size() { return air.length; }

    public boolean air(int id) { return air[id]; }

    public boolean fluid(int id) { return fluid[id]; }

    public int directBits() { return directBits; }
}
