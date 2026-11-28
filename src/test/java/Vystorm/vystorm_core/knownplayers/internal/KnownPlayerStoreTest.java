package Vystorm.vystorm_core.knownplayers.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.knownplayers.KnownPlayer;
import Vystorm.vystorm_core.knownplayers.KnownPlayers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnownPlayerStoreTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ALEXA = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @AfterEach
    void reset() { KnownPlayersRuntime.TESTING = null; }

    private static KnownPlayerStore three() {
        KnownPlayerStore s = new KnownPlayerStore();
        s.joined(ALEX, "Alex", 1_000, "https://textures.minecraft.net/texture/abc");
        s.joined(ALEXA, "alexa", 2_000, null);
        s.joined(BOB, "Bob", 3_000, null);
        return s;
    }

    @Test
    void givenJoins_whenSearchedByPrefix_thenCaseInsensitiveInNameOrder() {
        KnownPlayerStore s = three();
        assertEquals(List.of("Alex", "alexa"), s.search("ALE", 10, false).stream().map(KnownPlayerStore.Entry::name).toList());
        assertEquals(List.of("Alex"), s.search("al", 1, false).stream().map(KnownPlayerStore.Entry::name).toList());
        assertTrue(s.search("", 10, false).isEmpty());
        assertTrue(s.search("z", 10, false).isEmpty());
    }

    @Test
    void givenNameChange_whenSearched_thenOnlyTheNewNameIsFound() {
        KnownPlayerStore s = three();
        s.joined(ALEX, "Zed", 4_000, null);
        assertTrue(s.byName("Alex").isEmpty());
        assertEquals(ALEX, s.byName("zed").orElseThrow().id());
        assertEquals(1_000, s.get(ALEX).orElseThrow().firstSeen());
        assertEquals("https://textures.minecraft.net/texture/abc", s.get(ALEX).orElseThrow().skin(), "skin kept when the join has none");
        assertEquals(List.of("alexa"), s.search("ale", 10, false).stream().map(KnownPlayerStore.Entry::name).toList());
    }

    @Test
    void givenQuitsAndJoins_whenRecentAsked_thenNewestFirstAndCutOff() {
        KnownPlayerStore s = three();
        s.quit(ALEX, 5_000);
        assertEquals(List.of(ALEX, BOB, ALEXA), s.recent(0, 10, false).stream().map(KnownPlayerStore.Entry::id).toList());
        assertEquals(List.of(ALEX, BOB), s.recent(2_500, 10, false).stream().map(KnownPlayerStore.Entry::id).toList());
        assertEquals(List.of(ALEX), s.recent(0, 1, false).stream().map(KnownPlayerStore.Entry::id).toList());
    }

    @Test
    void givenHiddenPlayer_whenSearchedOrRecent_thenLeftOutButExactNameStillWorks() {
        KnownPlayerStore s = three();
        s.setHidden(ALEXA, true);
        assertEquals(List.of("Alex"), s.search("ale", 10, false).stream().map(KnownPlayerStore.Entry::name).toList());
        assertEquals(2, s.search("ale", 10, true).size());
        assertFalse(s.recent(0, 10, false).stream().anyMatch(e -> e.id().equals(ALEXA)));
        assertEquals(ALEXA, s.byName("ALEXA").orElseThrow().id());
        s.joined(ALEXA, "alexa", 9_000, null);
        assertTrue(s.get(ALEXA).orElseThrow().hidden(), "a join keeps the choice");
    }

    @Test
    void givenOldEntries_whenRetained_thenOnlyOlderOnesDropped() {
        KnownPlayerStore s = three();
        assertEquals(2, s.retain(2_500));
        assertEquals(List.of(BOB), s.all().stream().map(KnownPlayerStore.Entry::id).toList());
        assertTrue(s.search("ale", 10, true).isEmpty());
    }

    @Test
    void givenForget_whenAskedAgain_thenGoneAndDirty() {
        KnownPlayerStore s = three();
        s.takeDirty();
        assertTrue(s.forget(BOB));
        assertFalse(s.forget(BOB));
        assertTrue(s.takeDirty());
        assertFalse(s.takeDirty(), "dirty only once");
        assertTrue(s.byName("bob").isEmpty());
        assertEquals(2, s.size());
    }

    @Test
    void givenLoadedEntryAndEarlierJoin_whenMerged_thenTimesJoinedAndNewestNameKept() {
        KnownPlayerStore s = new KnownPlayerStore();
        s.joined(ALEX, "AlexNew", 10_000, null);
        s.merge(new KnownPlayerStore.Entry(ALEX, "AlexOld", 500, 900, "https://x/skin", true));
        KnownPlayerStore.Entry e = s.get(ALEX).orElseThrow();
        assertEquals("AlexNew", e.name());
        assertEquals(500, e.firstSeen());
        assertEquals(10_000, e.lastSeen());
        assertEquals("https://x/skin", e.skin());
        assertTrue(e.hidden());
    }

    @Test
    void givenStore_whenEncodedAndDecoded_thenSameEntries() {
        KnownPlayerStore s = three();
        s.setHidden(BOB, true);
        String text = KnownPlayerCodec.encode(s.all());
        assertTrue(text.startsWith(KnownPlayerCodec.HEADER + "\n"));
        KnownPlayerCodec.Decoded d = KnownPlayerCodec.decode(text);
        assertEquals(0, d.damaged());
        assertEquals(s.all(), d.entries());
    }

    @Test
    void givenDamagedLines_whenDecoded_thenSkippedAndCounted() {
        String text = KnownPlayerCodec.HEADER + "\r\n"
                + ALEX + "\tAlex\t1\t2\t-\t\r\n"
                + "not-a-uuid\tX\t1\t2\t-\t\n"
                + BOB + "\tBob\tx\t2\t-\t\n"
                + BOB + "\t\t1\t2\t-\t\n"
                + "\n"
                + ALEXA + "\talexa\t1\t2\th\thttps://textures.minecraft.net/texture/z\n";
        KnownPlayerCodec.Decoded d = KnownPlayerCodec.decode(text);
        assertEquals(3, d.damaged());
        assertEquals(List.of(ALEX, ALEXA), d.entries().stream().map(KnownPlayerStore.Entry::id).toList());
        assertTrue(d.entries().get(1).hidden());
        assertEquals("https://textures.minecraft.net/texture/z", d.entries().get(1).skin());
    }

    @Test
    void givenNamesAndSkinsWithSeparators_whenCleaned_thenSafeForTheFile() {
        assertEquals("AlexB", KnownPlayerCodec.cleanName(" Al\tex\nB "));
        assertEquals(32, KnownPlayerCodec.cleanName("x".repeat(40)).length());
        assertEquals(null, KnownPlayerCodec.cleanSkin("javascript:alert(1)"));
        assertEquals(null, KnownPlayerCodec.cleanSkin("https://a b"));
        assertEquals("https://t/x", KnownPlayerCodec.cleanSkin(" https://t/x "));
    }

    @Test
    void givenUserCacheWithOddEntries_whenSeeded_thenGoodOnesKept() {
        long now = 1_800_000_000_000L;
        String json = """
                [{"name":"Alex","uuid":"00000000-0000-0000-0000-00000000000a","expiresOn":"2027-01-14 10:00:00 +0100"},
                 {"name":"Alex","uuid":"00000000-0000-0000-0000-00000000000a","expiresOn":"2026-01-01 10:00:00 +0100"},
                 {"name":"Broken","uuid":"nope","expiresOn":"2026-01-01 10:00:00 +0100"},
                 {"name":"","uuid":"00000000-0000-0000-0000-00000000000d"},
                 {"name":"Bob","uuid":"00000000-0000-0000-0000-00000000000c","expiresOn":"odd date"},
                 42]
                """;
        UserCacheSeed.Seed seed = UserCacheSeed.parse(json, now);
        assertEquals(List.of(ALEX, BOB), seed.entries().stream().map(KnownPlayerStore.Entry::id).toList());
        assertEquals(4, seed.skipped());
        long expires = java.time.ZonedDateTime.parse("2027-01-14T10:00:00+01:00").toInstant().toEpochMilli();
        assertEquals(Math.min(now, expires - UserCacheSeed.MONTH_MS), seed.entries().get(0).lastSeen());
        assertEquals(now - UserCacheSeed.MONTH_MS, seed.entries().get(1).lastSeen(), "odd date: a month ago");
        assertTrue(UserCacheSeed.parse("{not json", now).entries().isEmpty());
        assertTrue(UserCacheSeed.parse("{}", now).entries().isEmpty());
    }

    @Test
    void givenNoStoreFile_whenRead_thenSeededFromUserCacheOtherwiseFromTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(KnownPlayersRuntime.FILE);
        Path cache = dir.resolve("usercache.json");
        Files.writeString(cache, "[{\"name\":\"Bob\",\"uuid\":\"" + BOB + "\",\"expiresOn\":\"2026-12-20 10:00:00 +0000\"}]", StandardCharsets.UTF_8);
        KnownPlayersRuntime.Loaded first = KnownPlayersRuntime.read(file, cache, System.currentTimeMillis());
        assertTrue(first.seeded());
        assertEquals(List.of(BOB), first.entries().stream().map(KnownPlayerStore.Entry::id).toList());
        Files.writeString(file, KnownPlayerCodec.encode(three().all()), StandardCharsets.UTF_8);
        KnownPlayersRuntime.Loaded second = KnownPlayersRuntime.read(file, cache, System.currentTimeMillis());
        assertFalse(second.seeded(), "the cache is read only once, when there is no file");
        assertEquals(3, second.entries().size());
        KnownPlayersRuntime.Loaded none = KnownPlayersRuntime.read(dir.resolve("other.dat"), dir.resolve("missing.json"), 0);
        assertTrue(none.entries().isEmpty());
    }

    @Test
    void givenForeignOrUnreadableFile_whenRead_thenRefusedSoNothingWritesOverIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(KnownPlayersRuntime.FILE);
        Path cache = dir.resolve("usercache.json");
        Files.writeString(file, "something else\n", StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> KnownPlayersRuntime.read(file, cache, 0));
        Files.writeString(file, KnownPlayerCodec.HEADER + "\nbroken\talso broken\n", StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> KnownPlayersRuntime.read(file, cache, 0));
        Files.writeString(file, KnownPlayerCodec.HEADER + "\n", StandardCharsets.UTF_8);
        assertTrue(KnownPlayersRuntime.read(file, cache, 0).entries().isEmpty(), "an empty store is fine");
    }

    @Test
    void givenTestStore_whenPublicCallsUsed_thenPrivacyAndLimitsApply() {
        KnownPlayerStore s = three();
        long now = System.currentTimeMillis();
        s.quit(BOB, now);
        s.setHidden(ALEXA, true);
        KnownPlayersRuntime.TESTING = s;
        assertTrue(KnownPlayers.enabled());
        assertEquals(List.of("Alex"), KnownPlayers.search("ale", 999).stream().map(KnownPlayer::name).toList());
        assertEquals(List.of(BOB), KnownPlayers.recent(Duration.ofMinutes(5), 10).stream().map(KnownPlayer::id).toList());
        assertFalse(KnownPlayers.findable(ALEXA));
        assertTrue(KnownPlayers.findable(UUID.randomUUID()), "unknown players count as findable");
        KnownPlayers.setFindable(ALEXA, true);
        assertTrue(KnownPlayers.findable(ALEXA));
        assertEquals(Optional.of("https://textures.minecraft.net/texture/abc"), KnownPlayers.get(ALEX).orElseThrow().skinUrl());
        assertTrue(KnownPlayers.forget(ALEX));
        assertEquals(2, KnownPlayers.size());
        KnownPlayersRuntime.TESTING = null;
        assertFalse(KnownPlayers.enabled());
        assertTrue(KnownPlayers.search("bob", 5).isEmpty());
    }

    @Test
    void givenCapability_whenAsked_thenKnownSince071() {
        assertEquals(Optional.of("0.71.0"), CoreApi.since(CoreApi.Capability.KNOWN_PLAYERS));
        assertEquals("known-players", CoreApi.Capability.KNOWN_PLAYERS);
    }
}
