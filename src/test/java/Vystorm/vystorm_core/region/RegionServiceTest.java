package Vystorm.vystorm_core.region;

import java.util.*;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegionServiceTest {
    final UUID world = UUID.randomUUID(), other = UUID.randomUUID();
    final RegionService service = RegionService.get();
    static Plugin plugin(String name) { Plugin p = mock(Plugin.class); when(p.getName()).thenReturn(name); return p; }
    @AfterEach void reset() { service.clear(); }

    @Test void priorityOrderAndFlags() {
        var city = new Region("city", "c1", "", RegionPriority.CITY, new RegionShape.Chunks(world, Set.of(RegionShape.Chunks.pack(0, 0), RegionShape.Chunks.pack(1, 0)), -64, 320),
                Set.of(), Map.of("owner", "u1"), Map.of("pvp", false));
        var arena = new Region("arena", "a1", "", RegionPriority.ARENA, new RegionShape.Box(world, 10, 60, 2, 20, 80, 12), Set.of(), Map.of(), Map.of("pvp", true));
        service.register(plugin("City"), city);
        service.register(plugin("Arena"), arena);
        assertEquals(List.of("arena:a1", "city:c1"), service.at(world, 15, 70, 5).stream().map(Region::key).toList());
        assertEquals(Optional.of(true), service.at(world, 15, 70, 5).getFirst().flag("pvp"), "arena beats the city pvp flag");
        assertEquals(List.of("city:c1"), service.at(world, 20, 70, 14).stream().map(Region::key).toList(), "outside the arena box");
        assertTrue(service.at(other, 15, 70, 5).isEmpty(), "other world");
        assertTrue(service.at(world, 40, 70, 5).isEmpty(), "chunk 2 not claimed");
        assertEquals("City", service.get("city", "c1").orElseThrow().owner());
    }

    @Test void syncAndOwnerCleanup() {
        var owner = plugin("Factions");
        var a = Region.of("faction", "orcs", new RegionShape.Cylinder(world, 0, 0, 3000, -64, 320));
        var b = Region.of("faction", "elves", new RegionShape.Cylinder(world, 8000, 0, 3000, -64, 320));
        service.sync(owner, "faction", List.of(a, b));
        assertEquals(2, service.all("faction").size());
        long revision = service.revision();
        service.sync(owner, "faction", List.of(a.withOwner("Factions"), b.withOwner("Factions")));
        assertEquals(revision, service.revision(), "unchanged regions are not rewritten");
        service.sync(owner, "faction", List.of(a));
        assertEquals(List.of("faction:orcs"), service.all("faction").stream().map(Region::key).toList());
        assertEquals(1, service.at(world, 1500, 64, 1500).size(), "large regions are found without index");
        assertEquals(1, service.unregisterOwner(owner));
        assertTrue(service.all().isEmpty());
    }

    @Test void polygonAndIntersection() {
        var triangle = Region.of("faction", "t", new RegionShape.Polygon(world, List.of(new double[]{0, 0}, new double[]{100, 0}, new double[]{0, 100}), -64, 320));
        service.register(null, triangle);
        assertEquals(1, service.at(world, 10, 64, 10).size());
        assertTrue(service.at(world, 90, 64, 90).isEmpty());
        assertEquals(1, service.intersecting(world, 40, 40, 60, 60).size());
        assertTrue(service.intersecting(world, 80, 80, 99, 99).isEmpty());
    }

    @Test void legacyAreasAppearAsRegions() {
        var areas = new Vystorm.vystorm_core._advancedMethods.areaHandler.AreaHandler();
        var shop1 = new Vystorm.vystorm_core._advancedMethods.areaHandler.Area();
        shop1.setAreaType(Vystorm.vystorm_core._advancedMethods.areaHandler.AreaType.Cylinder);
        shop1.setWorld(world);shop1.setCenterX(0);shop1.setCenterZ(0);shop1.setRadius(8);shop1.setY1(-64);shop1.setY2(320);shop1.setNBT("market:shop");
        var shop2 = new Vystorm.vystorm_core._advancedMethods.areaHandler.Area();
        shop2.setAreaType(Vystorm.vystorm_core._advancedMethods.areaHandler.AreaType.Cylinder);
        shop2.setWorld(world);shop2.setCenterX(40);shop2.setCenterZ(0);shop2.setRadius(8);shop2.setY1(-64);shop2.setY2(320);shop2.setNBT("market:shop");
        areas.register(shop1);areas.register(shop2);
        assertEquals(List.of("market:shop"), service.at(world, 0, 64, 0).stream().map(Region::key).toList());
        assertEquals(List.of("market:shop#2"), service.at(world, 40, 64, 0).stream().map(Region::key).toList(), "same tag, unique region");
        assertEquals(RegionPriority.MARKET, service.at(world, 0, 64, 0).getFirst().priority());
        areas.unregister(shop1);
        assertTrue(service.at(world, 0, 64, 0).isEmpty());
        areas.clear();
        assertTrue(service.all().isEmpty());
    }

    @Test void replacingKeepsIndexConsistent() {
        service.register(null, Region.of("market", "m", new RegionShape.Cylinder(world, 0, 0, 8, -64, 320)));
        service.register(null, Region.of("market", "m", new RegionShape.Cylinder(world, 500, 500, 8, -64, 320)));
        assertTrue(service.at(world, 0, 64, 0).isEmpty());
        assertEquals(1, service.at(world, 500, 64, 500).size());
        assertTrue(service.unregister("market", "m"));
        assertTrue(service.at(world, 500, 64, 500).isEmpty());
    }
}
