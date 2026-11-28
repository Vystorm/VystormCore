package Vystorm.vystorm_core._advancedMethods.packageHandler;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Named custom payload channels using Bukkit plugin messaging. Payloads are untrusted client input. */
public interface PackageInterface {
    /** A null listener registers an outgoing-only channel. Existing external channel registrations are preserved. */
    void register(Plugin owner, String channel, PluginMessageListener listener);
    void send(Plugin owner, Player player, String channel, byte[] payload);
    boolean unregister(Plugin owner, String channel);
    void removeAll(Plugin owner);
    void clear();

    /** Client-only biomes ({@code packet.ClientBiomes}). @since 0.64.0 */
    default Vystorm.vystorm_core.packet.ClientBiomesInterface clientBiomes() { return Vystorm.vystorm_core.packet.ClientBiomesInterface.DEFAULT; }

    /** Client-only chunks ({@code packet.ClientChunks}). @since 0.64.0 */
    default Vystorm.vystorm_core.packet.ClientChunksInterface clientChunks() { return Vystorm.vystorm_core.packet.ClientChunksInterface.DEFAULT; }

    /** Client-only blocks: per-player block sets with priorities ({@code packet.ClientBlocks}). @since 0.72.0 */
    default Vystorm.vystorm_core.packet.ClientBlocksInterface clientBlocks() { return Vystorm.vystorm_core.packet.ClientBlocksInterface.DEFAULT; }

    /** The packet view: shared chunk rewriting with layers in each player's connection ({@code packet.PacketView}). @since 0.73.0 */
    default Vystorm.vystorm_core.packet.PacketViewInterface packetView() { return Vystorm.vystorm_core.packet.PacketViewInterface.DEFAULT; }

    /** Client-only map walls ({@code display.ClientDisplays}). @since 0.64.0 */
    default Vystorm.vystorm_core.display.ClientDisplaysInterface clientDisplays() { return Vystorm.vystorm_core.display.ClientDisplaysInterface.DEFAULT; }
}
