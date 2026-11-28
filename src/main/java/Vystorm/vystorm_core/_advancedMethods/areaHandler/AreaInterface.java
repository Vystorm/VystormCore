package Vystorm.vystorm_core._advancedMethods.areaHandler;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;

public interface AreaInterface {
    /** @deprecated Use {@code api.area().regions()}. */
    @Deprecated(since = "0.64.0")
    Area getArea();
    /** @deprecated Use {@code api.area().regions().register(owner, region)}. */
    @Deprecated(since = "0.64.0")
    void register(Area area);
    /** @deprecated Use {@code api.area().regions().register(owner, region)}. */
    @Deprecated(since = "0.64.0")
    boolean update(Area area);
    /** @deprecated Use {@code api.area().regions().unregister(kind, id)}. */
    @Deprecated(since = "0.64.0")
    boolean unregister(Area area);
    /** @deprecated Use {@code api.area().regions().at(location)}. */
    @Deprecated(since = "0.64.0")
    List<Area> find(Location location);
    /** @deprecated Use {@code api.area().regions().unregisterOwner(owner)}. */
    @Deprecated(since = "0.64.0")
    void clear();
    /** Legacy grouped result. New callers should use find(player.getLocation()). */
    @Deprecated
    ArrayList<Area>[] compare(Player player);

    /** The region service: shapes, priorities, enter and leave events ({@code region.RegionService.get()}). @since 0.64.0 */
    default Vystorm.vystorm_core.region.RegionService regions() { return Vystorm.vystorm_core.region.RegionService.get(); }

    /** Fixed spawn points per region ({@code spawn.SpawnPointService.get()}). @since 0.64.0 */
    default Vystorm.vystorm_core.spawn.SpawnPointService spawnPoints() { return Vystorm.vystorm_core.spawn.SpawnPointService.get(); }

    /**
     * May a plugin change cells of one chunk without per-block events (bulk edits)? Combines Core's regions (flag
     * {@code build}) with every guard plugins registered through {@link #editGuards()}: any deny wins, partial answers
     * are and-ed. Server thread. Capability {@code edit-ask}. @since 0.69.0
     */
    default Vystorm.vystorm_core.blocks.EditVerdict mayEdit(Vystorm.vystorm_core.blocks.EditAsk ask) {
        return Vystorm.vystorm_core.blocks.EditGuards.ask(ask);
    }

    /** The guards plugins register for bulk edits (claims, plots, arenas). Capability {@code edit-ask}. @since 0.69.0 */
    default Vystorm.vystorm_core.blocks.EditGuards editGuards() { return Vystorm.vystorm_core.blocks.EditGuards.instance(); }
}
