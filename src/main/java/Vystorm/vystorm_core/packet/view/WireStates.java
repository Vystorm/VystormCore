package Vystorm.vystorm_core.packet.view;

/**
 * The server's network block state ids (the ids chunk data uses, see {@code packetView().wireId(BlockData)}): how many
 * there are and which are air or hold a fluid. Immutable; any thread.
 *
 * @since 0.73.0
 */
public final class WireStates {
    private final boolean[] air, fluid;

    /** Core builds this from the server's registry; tests may build their own. */
    public WireStates(boolean[] air, boolean[] fluid) {
        if (air.length != fluid.length || air.length < 2) throw new IllegalArgumentException("tables of different size");
        this.air = air.clone();
        this.fluid = fluid.clone();
    }

    public int size() { return air.length; }

    public boolean air(int wireId) { return air[wireId]; }

    public boolean fluid(int wireId) { return fluid[wireId]; }
}
