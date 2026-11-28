package Vystorm.vystorm_core.packet;

import java.util.List;

/**
 * Client block counters ({@code api.packages().clientBlocks().stats()}, also {@code /vadmin packets}). Sets, viewers and
 * cells are the state now; the other numbers count since Core started.
 *
 * @param sets          open sets
 * @param viewers       players shown at least one set
 * @param cells         cells of all open sets (a shared set counts once)
 * @param cellsSent     cells sent (client blocks and restores)
 * @param batches       batches sent, one per chunk section and tick (one packet on Paper and Purpur)
 * @param singleBatches batches of one cell (sent as a single block change)
 * @param restores      cells set back to the next lower set's or the real block
 * @param repairs       cells sent again after a real block change, a chunk resend or a click
 * @param blocked       times a solid client block was held back inside a viewer's bounding box
 * @param refused       opens or changes refused at a limit
 * @param owners        per plugin, by name
 * @since 0.72.0
 */
public record ClientBlocksStats(int sets, int viewers, int cells, long cellsSent, long batches, long singleBatches,
                                long restores, long repairs, long blocked, long refused, List<Owner> owners) {
    public ClientBlocksStats {
        owners = List.copyOf(owners);
    }

    /**
     * @param sets          open sets of the plugin
     * @param cells         their cells
     * @param viewers       players shown at least one of them
     * @param collisionSets sets that declared {@code collision(true)}
     */
    public record Owner(String plugin, int sets, int cells, int viewers, int collisionSets) {}

    /** No sets, nothing sent. */
    public static ClientBlocksStats empty() { return new ClientBlocksStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of()); }
}
