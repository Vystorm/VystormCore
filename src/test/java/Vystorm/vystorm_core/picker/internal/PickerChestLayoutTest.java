package Vystorm.vystorm_core.picker.internal;

import static Vystorm.vystorm_core.picker.internal.PickerModelTest.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core.picker.PickVerdict;
import Vystorm.vystorm_core.picker.PlayerSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PickerChestLayoutTest {
    static final PickerChestLayout.Context CTX = new PickerChestLayout.Context(false, PickerModelTest.NOW, ZoneOffset.UTC, null, null);

    static Map<Integer, PickerChestLayout.Slot> slots(PickerModel m, PickerChestLayout.Context ctx) {
        Map<Integer, PickerChestLayout.Slot> out = new TreeMap<>();
        for (PickerChestLayout.Slot s : PickerChestLayout.layout(m, ctx)) out.put(s.slot(), s);
        return out;
    }

    static Map<Integer, String> icons(Map<Integer, PickerChestLayout.Slot> slots) {
        Map<Integer, String> out = new TreeMap<>();
        slots.forEach((k, v) -> out.put(k, v.icon() + (v.glint() ? "*" : "")));
        return out;
    }

    static PickerModelTest.World many(int n) {
        PickerModelTest.World w = new PickerModelTest.World();
        for (int i = 0; i < n; i++) w.online(100 + i, String.format("P%03d", i), -1);
        return w;
    }

    @Test
    void givenSingleFirstPage_whenLaidOut_thenHeadsBackTabsSearchInfoAndNext() {
        PickerModel m = PickerModelTest.single();
        m.refresh(many(50));
        m.select(PlayerSource.ONLINE);
        Map<Integer, PickerChestLayout.Slot> s = slots(m, CTX);
        for (int i = 0; i < 45; i++) assertEquals(PickerChestLayout.Icon.HEAD, s.get(i).icon(), "slot " + i);
        assertEquals("P000", s.get(0).row().candidate().name());
        Map<Integer, String> bottom = icons(s);
        bottom.keySet().removeIf(k -> k < 45);
        assertEquals(Map.of(45, "BARRIER", 46, "CLOCK*", 49, "NAME_TAG", 50, "PAPER", 53, "ARROW"), bottom,
                "no Nearby/Recent items (empty), no Done (single), no Previous on page 1");
        assertEquals(PickerChestLayout.Action.NEXT, s.get(53).action());
        assertEquals("pickers.row.click-pick", s.get(0).lore().getLast().key());
    }

    @Test
    void givenMiddleAndLastPage_whenLaidOut_thenArrowsFollow() {
        PickerModel m = PickerModelTest.single();
        m.refresh(many(100));
        m.select(PlayerSource.ONLINE);
        m.page(1, PickerModel.CHEST_PAGE);
        Map<Integer, PickerChestLayout.Slot> mid = slots(m, CTX);
        assertTrue(mid.containsKey(52) && mid.containsKey(53));
        assertEquals("P045", mid.get(0).row().candidate().name());
        m.page(2, PickerModel.CHEST_PAGE);
        Map<Integer, PickerChestLayout.Slot> last = slots(m, CTX);
        assertTrue(last.containsKey(52));
        assertFalse(last.containsKey(53));
        assertEquals(10, last.values().stream().filter(x -> x.icon() == PickerChestLayout.Icon.HEAD).count());
        assertFalse(last.containsKey(10), "slots after the last head stay empty");
    }

    @Test
    void givenMultipleWithChosenAndDenied_whenLaidOut_thenGlintDoneAndReason() {
        PickerModel m = PickerModelTest.model(true, 3, Set.of(), c -> true, c -> c.name().equals("Cleo") ? PickVerdict.deny("busy") : PickVerdict.ok());
        m.refresh(PickerModelTest.world());
        m.select(PlayerSource.ONLINE);
        m.toggle(id(2));
        Map<Integer, PickerChestLayout.Slot> s = slots(m, CTX);
        // a ticked head looked exactly like an unticked one (heads draw no glint): a ticked row is a lime block,
        // its name starts with a green check and "chosen" is the first lore line
        assertEquals(PickerChestLayout.Icon.CHOSEN, s.get(0).icon(), "Alex ticked");
        assertEquals(org.bukkit.Material.LIME_CONCRETE, PickerChest.material(s.get(0).icon()));
        assertEquals("pickers.row.name-chosen", s.get(0).name().key());
        assertEquals(List.of("pickers.row.chosen", "pickers.row.online-near", "pickers.row.click-untick"), s.get(0).lore().stream().map(PickerText.Line::key).toList());
        assertEquals(PickerChestLayout.Icon.HEAD, s.get(1).icon(), "bob is not ticked: still a head");
        assertEquals("pickers.row.name", s.get(1).name().key());
        assertFalse(s.get(1).glint());
        PickerChestLayout.Slot cleo = s.get(2);
        assertEquals("pickers.row.denied", cleo.lore().getLast().key());
        assertEquals("busy", cleo.lore().getLast().args()[1]);
        assertEquals(PickerChestLayout.Icon.LIME_DYE, s.get(51).icon());
        assertEquals("pickers.chest.done", s.get(51).name().key(), "short enough for a tile of a mirrored screen");
        assertEquals(List.of("pickers.chosen-count", "pickers.rows", "pickers.chest.page"), s.get(50).lore().stream().map(PickerText.Line::key).toList());
        assertTrue(s.containsKey(47) && s.containsKey(48), "Nearby and Recent have rows");
    }

    @Test
    void givenSearchTab_whenEmptyShortOrFound_thenInfoSaysWhy() {
        PickerModel m = PickerModelTest.single();
        PickerModelTest.World w = PickerModelTest.world();
        m.refresh(w);
        m.select(PlayerSource.SEARCH);
        Map<Integer, PickerChestLayout.Slot> empty = slots(m, CTX);
        assertTrue(empty.get(49).glint());
        assertEquals("pickers.chest.search-none", empty.get(49).lore().getFirst().key());
        assertTrue(empty.get(50).lore().stream().anyMatch(l -> "pickers.search.type".equals(l.key())));
        m.query("al");
        m.refresh(w);
        assertTrue(slots(m, CTX).get(50).lore().stream().anyMatch(l -> "pickers.search.short".equals(l.key())));
        m.query("zzz");
        m.refresh(w);
        assertTrue(slots(m, CTX).get(50).lore().stream().anyMatch(l -> "pickers.empty.search".equals(l.key())));
        m.query("ale");
        m.refresh(w);
        Map<Integer, PickerChestLayout.Slot> found = slots(m, CTX);
        assertEquals("pickers.chest.search-current", found.get(49).lore().getFirst().key());
        assertEquals(3, found.values().stream().filter(x -> x.icon() == PickerChestLayout.Icon.HEAD).count());
    }

    @Test
    void givenErrorAndConfirmLabel_whenLaidOut_thenShown() {
        PickerModel m = PickerModelTest.model(true, 2, Set.of(), c -> true, c -> PickVerdict.ok());
        m.refresh(PickerModelTest.world());
        Map<Integer, PickerChestLayout.Slot> s = slots(m, new PickerChestLayout.Context(false, PickerModelTest.NOW, ZoneOffset.UTC, "Area is full", "Add"));
        assertEquals("Add", s.get(51).name().raw());
        assertEquals("pickers.error", s.get(50).lore().getLast().key());
    }

    @Test
    void givenOneSource_whenLaidOut_thenNoTabItems() {
        PickerModel m = new PickerModel(PickerModelTest.VIEWER, List.of(PlayerSource.RECENT), false, 1, false, 16, Set.of(), c -> true, c -> PickVerdict.ok(), PickerModel.Limits.DEFAULTS);
        m.refresh(PickerModelTest.world());
        Map<Integer, PickerChestLayout.Slot> s = slots(m, CTX);
        for (int slot = 46; slot <= 49; slot++) assertFalse(s.containsKey(slot), "slot " + slot);
        assertEquals(3, s.values().stream().filter(x -> x.icon() == PickerChestLayout.Icon.HEAD).count());
    }

    /** Every key the chest and the panel use exists, in all seven language files with the same keys. */
    @Test
    void givenAllStates_whenKeysCollected_thenEveryLanguageHasThem() throws Exception {
        Set<String> used = new TreeSet<>();
        PickerModel multi = PickerModelTest.model(true, 3, Set.of(), c -> true, c -> c.name().equals("Cleo") ? PickVerdict.deny("x") : PickVerdict.ok());
        multi.refresh(PickerModelTest.world());
        multi.toggle(id(2));
        for (PlayerSource tab : PlayerSource.values()) {
            multi.select(tab);
            PickerChestLayout.layout(multi, new PickerChestLayout.Context(true, PickerModelTest.NOW, ZoneOffset.UTC, "e", null)).forEach(sl -> {
                if (sl.name().key() != null) used.add(sl.name().key());
                sl.lore().forEach(l -> { if (l.key() != null) used.add(l.key()); });
            });
        }
        for (PickerText.Seen seen : PickerText.Seen.values()) used.add("pickers.row.seen-" + seen.name().toLowerCase(java.util.Locale.ROOT));
        for (PlayerSource s : PlayerSource.values()) {
            used.add(PickerText.tabKey(s));
            used.add("pickers.empty." + s.name().toLowerCase(java.util.Locale.ROOT));
        }
        used.addAll(List.of("pickers.title", "pickers.closed", "pickers.switched-off", "pickers.gone", "pickers.full", "pickers.denied-chosen",
                "pickers.timed-out", "pickers.open-again", "pickers.open-again-hover", "pickers.store-off", "pickers.search.label",
                "pickers.search.placeholder", "pickers.search.title", "pickers.search.question", "pickers.row.click-tick",
                "pickers.row.click-pick", "pickers.demo.title", "pickers.demo.subtitle", "pickers.demo.not-yourself", "pickers.demo.picked",
                "pickers.demo.usage", "pickers.demo.dump-head", "known-players.admin.usage", "settings.privacy.findable-offline",
                "switches.pickers", "switch-names.pickers", "switches.known-players", "switch-names.known-players"));
        Map<String, Object> en = load("en");
        Set<String> missing = used.stream().filter(k -> !en.containsKey(k)).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of(), missing, "keys missing in en.yml");
        Set<String> ours = en.keySet().stream().filter(k -> k.startsWith("pickers.") || k.startsWith("known-players.") || k.startsWith("settings.privacy."))
                .collect(Collectors.toCollection(TreeSet::new));
        for (String code : new String[] {"de", "fr", "it", "pt_br", "ru", "zh_cn"}) {
            Map<String, Object> other = load(code);
            Set<String> lacking = ours.stream().filter(k -> !other.containsKey(k)).collect(Collectors.toCollection(TreeSet::new));
            assertEquals(Set.of(), lacking, code + ".yml");
            for (String k : List.of("switches.pickers", "switch-names.pickers", "switches.known-players", "switch-names.known-players"))
                assertTrue(other.containsKey(k), code + " " + k);
        }
    }

    /** Defect 5: labels of tiles in mirrored chest screens were cut ("Done (1 chose…", "Use the block…"): 14 characters fit. */
    @Test
    void givenTileLabels_whenShortenedForAMirroredScreen_thenTheyFitInEveryLanguage() throws Exception {
        for (String code : new String[] {"en", "de", "fr", "it", "pt_br", "ru", "zh_cn"}) {
            Map<String, Object> lang = load(code);
            for (String key : List.of("pickers.world.options.stand-position", "pickers.world.options.stand-block", "pickers.world.options.type",
                    "pickers.world.options.resume", "pickers.world.options.cancel", "pickers.chest.done", "pickers.back", "pickers.tab.search",
                    "pickers.world.options.redo-first", "pickers.world.area.confirm.use", "pickers.world.area.confirm.redo-first",
                    "pickers.world.area.confirm.redo-second", "pickers.world.area.confirm.height", "pickers.world.area.confirm.height-off",
                    "pickers.world.area.confirm.cancel", "pickers.world.area.confirm.blocks-label")) {
                String text = String.valueOf(lang.get(key)).replaceAll("<[^>]+>", "").replace("{count}", "10").replace("{blocks}", "10,752");   // 5 digits; larger counts are cut with an ellipsis on the tile, the lore and boss bar keep them
                assertTrue(text.codePointCount(0, text.length()) <= Vystorm.vystorm_core.screen.internal.InventoryScreenLayout.NAME_CHARS,
                        code + " " + key + " = " + text);
            }
        }
    }

    static Map<String, Object> load(String code) throws Exception {
        try (InputStream in = PickerChestLayoutTest.class.getClassLoader().getResourceAsStream("lang/" + code + ".yml")) {
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Object> out = new TreeMap<>();
            for (String k : yaml.getKeys(true)) if (!yaml.isConfigurationSection(k)) out.put(k, yaml.get(k));
            return out;
        }
    }
}
