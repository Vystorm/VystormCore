package Vystorm.vystorm_core.compat.gamerule;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The catalog against the rule lists the vanilla 1.20.6 and 26.2 server jars report (data generator, command tree of
 * {@code /gamerule}: name, value type, min and max), in {@code src/test/resources/gamerules}.
 */
class GameRuleCatalogTest {
    record Vanilla(String name, GameRuleKind kind, Integer min, Integer max) {}

    static Map<String, Vanilla> read(String file) throws IOException {
        Map<String, Vanilla> out = new TreeMap<>();
        try (InputStream in = GameRuleCatalogTest.class.getResourceAsStream("/gamerules/" + file)) {
            assertNotNull(in, file);
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                out.put(f[0], new Vanilla(f[0], GameRuleKind.valueOf(f[1]), f[2].isEmpty() ? null : Integer.valueOf(f[2]),
                        f.length < 4 || f[3].isEmpty() ? null : Integer.valueOf(f[3])));
            }
        }
        return out;
    }

    @Test
    void givenServerLists_whenCounted_then51And59Rules() throws IOException {
        assertEquals(51, read("vanilla-1.20.6.tsv").size());
        assertEquals(59, read("vanilla-26.2.tsv").size());
        assertEquals(60, GameRuleCatalog.all().size());
    }

    @Test
    void given26Rules_whenLookedUp_thenSameIdKindAndRange() throws IOException {
        for (Vanilla v : read("vanilla-26.2.tsv").values()) {
            GameRuleCatalog.Entry e = GameRuleCatalog.byId(v.name()).orElseThrow(() -> new AssertionError("missing " + v.name()));
            assertTrue(e.modern(), v.name());
            assertEquals(v.kind(), e.kind(), v.name());
            if (v.kind() == GameRuleKind.INTEGER) {
                assertEquals(v.min() == null ? 0 : v.min(), e.min(), v.name() + " min");
                assertEquals(v.max() == null ? Integer.MAX_VALUE : v.max(), e.max(), v.name() + " max");
            }
        }
        List<String> notOn26 = GameRuleCatalog.all().stream().filter(e -> !e.modern()).map(GameRuleCatalog.Entry::id).toList();
        assertEquals(List.of("spawn_chunk_radius"), notOn26);
    }

    @Test
    void given1206Rules_whenResolved_thenEveryOldNameMapsWithItsKind() throws IOException {
        Map<String, Vanilla> old = read("vanilla-1.20.6.tsv");
        for (Vanilla v : old.values()) {
            GameRuleCatalog.Entry e = GameRuleCatalog.resolve(v.name()).orElseThrow(() -> new AssertionError("missing " + v.name()));
            assertEquals(v.name(), e.legacyName());
            assertEquals(v.kind(), e.legacyKind(), v.name());
        }
        assertEquals(32, GameRuleCatalog.byId("spawn_chunk_radius").orElseThrow().max());
        Set<String> legacyNames = GameRuleCatalog.all().stream().map(GameRuleCatalog.Entry::legacyName).filter(n -> n != null).collect(Collectors.toSet());
        assertEquals(old.keySet(), legacyNames, "every 1.20.6 rule and nothing else");
    }

    @Test
    void givenBothVersions_whenCompared_then22Renamed2FlippedAndFireBecameNumber() throws IOException {
        Map<String, Vanilla> modern = read("vanilla-26.2.tsv");
        List<String> renamed = new ArrayList<>(), flipped = new ArrayList<>(), kindChanged = new ArrayList<>(), added = new ArrayList<>();
        for (GameRuleCatalog.Entry e : GameRuleCatalog.all()) {
            if (!e.modern()) continue;
            if (e.legacyName() == null) { added.add(e.id()); continue; }
            if (e.kind() != e.legacyKind()) kindChanged.add(e.legacyName() + "->" + e.id());
            else if (e.renamed()) renamed.add(e.legacyName());
            if (e.invertedLegacy()) flipped.add(e.legacyName() + "->" + e.id());
        }
        assertEquals(22, renamed.size(), renamed.toString());
        assertEquals(List.of("disableElytraMovementCheck->elytra_movement_check", "disableRaids->raids"), flipped);
        assertEquals(List.of("doFireTick->fire_spread_radius_around_player"), kindChanged);
        assertEquals(9, added.size(), added.toString());
        for (String a : added) assertTrue(modern.containsKey(a));
    }

    @Test
    void givenIds_whenResolved_thenAnySpellingFindsTheStableId() {
        assertEquals("spawn_phantoms", GameRuleCatalog.stableId("doInsomnia"));
        assertEquals("spawn_phantoms", GameRuleCatalog.stableId("minecraft:spawn_phantoms"));
        assertEquals("spawn_phantoms", GameRuleCatalog.stableId("DOINSOMNIA"));
        assertEquals("some_future_rule", GameRuleCatalog.stableId("someFutureRule"));
        assertEquals("do_daylight_cycle", GameRuleCatalog.snake("doDaylightCycle"));
    }
}
