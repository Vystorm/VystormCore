package Vystorm.vystorm_core.survey.internal;

import static Vystorm.vystorm_core.survey.internal.CellClass.AIR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core.survey.CellKind;
import Vystorm.vystorm_core.survey.ChunkSurvey;
import Vystorm.vystorm_core.survey.RoomComponent;
import Vystorm.vystorm_core.survey.RoomId;
import Vystorm.vystorm_core.survey.SkyContact;
import Vystorm.vystorm_core.survey.SurveyFace;
import Vystorm.vystorm_core.survey.SurveyRule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoomJoinTest {
    private static final SurveyRule RULE = SurveyRule.OVERWORLD;

    @BeforeEach
    void workerThread() { SurveyThreads.install(() -> false); }

    @AfterEach
    void reset() { SurveyThreads.install(null); }

    /** Random caves under a flat surface over chunks -2..2. */
    private static TestWorld caves(long seed) {
        TestWorld w = new TestWorld(0, 48).sky(-2, -2, 2, 2, 40);
        Random r = new Random(seed);
        for (int i = 0; i < 160; i++) {
            int x = r.nextInt(80) - 40, y = 2 + r.nextInt(30), z = r.nextInt(80) - 40;
            int len = 3 + r.nextInt(14);
            boolean alongX = r.nextBoolean();
            w.box(x, y, z, alongX ? x + len : x, y + r.nextInt(2), alongX ? z : z + len, AIR);
        }
        return w;
    }

    private static Map<Long, ChunkSurvey> surveys(TestWorld w, int radius) {
        Map<Long, ChunkSurvey> out = new HashMap<>();
        for (int cz = -radius; cz <= radius; cz++)
            for (int cx = -radius; cx <= radius; cx++) out.put(RoomJoin.key(cx, cz), w.survey(cx, cz, RULE));
        return out;
    }

    @Test
    void givenNeighbourChunks_whenJoinedFromEitherSide_thenTheLinksAreTheSame() {
        for (long seed = 1; seed <= 4; seed++) {
            Map<Long, ChunkSurvey> all = surveys(caves(seed), 1);
            for (ChunkSurvey a : all.values())
                for (SurveyFace face : SurveyFace.values()) {
                    ChunkSurvey b = all.get(RoomJoin.key(a.chunkX() + face.dx(), a.chunkZ() + face.dz()));
                    if (b == null) continue;
                    Set<String> ab = new HashSet<>(), ba = new HashSet<>();
                    for (int[] l : RoomJoin.links(a, face, b)) ab.add(l[0] + ">" + l[1]);
                    for (int[] l : RoomJoin.links(b, face.opposite(), a)) ba.add(l[1] + ">" + l[0]);
                    assertEquals(ab, ba, "seed " + seed + " " + a + " " + face);
                }
        }
    }

    @Test
    void givenChunkRooms_whenJoinedByUnionFind_thenTheGroupsAreTheConnectedEnclosedCellsOfTheWholeArea() {
        for (long seed = 1; seed <= 4; seed++) {
            TestWorld w = caves(seed);
            Map<Long, ChunkSurvey> all = surveys(w, 1);
            Map<RoomId, RoomId> roots = RoomJoin.union(all.values());
            // reference: flood fill of enclosed cells over the 48 x 48 area, ignoring chunk borders
            int minX = -16, size = 48, h = 48;
            int[] comp = new int[size * size * h];
            int next = 0;
            for (int i = 0; i < comp.length; i++) {
                if (comp[i] != 0 || !enclosed(all, i, size, minX)) continue;
                next++;
                ArrayDeque<Integer> q = new ArrayDeque<>();
                q.add(i);
                comp[i] = next;
                while (!q.isEmpty()) {
                    int c = q.poll(), x = c % size, z = (c / size) % size, y = c / (size * size);
                    int[][] nb = {{x - 1, y, z}, {x + 1, y, z}, {x, y, z - 1}, {x, y, z + 1}, {x, y - 1, z}, {x, y + 1, z}};
                    for (int[] n : nb) {
                        if (n[0] < 0 || n[0] >= size || n[2] < 0 || n[2] >= size || n[1] < 0 || n[1] >= h) continue;
                        int j = (n[1] * size + n[2]) * size + n[0];
                        if (comp[j] == 0 && enclosed(all, j, size, minX)) { comp[j] = next; q.add(j); }
                    }
                }
            }
            // same partition: two cells share a reference group exactly when their rooms share a root
            Map<Integer, RoomId> rootOfGroup = new HashMap<>();
            Map<RoomId, Integer> groupOfRoot = new HashMap<>();
            for (int i = 0; i < comp.length; i++) {
                if (comp[i] == 0) continue;
                int x = i % size + minX, z = (i / size) % size + minX, y = i / (size * size);
                ChunkSurvey s = all.get(RoomJoin.key(x >> 4, z >> 4));
                RoomId root = roots.get(s.id(s.roomAt(x, y, z)));
                RoomId known = rootOfGroup.putIfAbsent(comp[i], root);
                assertEquals(known == null ? root : known, root, "one reference group, one root");
                Integer group = groupOfRoot.putIfAbsent(root, comp[i]);
                assertEquals(group == null ? comp[i] : group, comp[i], "one root, one reference group");
            }
            assertTrue(next > 3, "the test world has caves");
        }
    }

    private static boolean enclosed(Map<Long, ChunkSurvey> all, int i, int size, int minX) {
        int x = i % size + minX, z = (i / size) % size + minX, y = i / (size * size);
        return all.get(RoomJoin.key(x >> 4, z >> 4)).kindAt(x, y, z) == CellKind.ENCLOSED;
    }

    @Test
    void givenALongSealedTunnel_whenTheComponentIsSearched_thenTheChunkLimitDecidesBetweenSealedAndUnknown() {
        // a tunnel at y 10 from x 2 to x 93 (six chunks), far below the surface
        TestWorld w = new TestWorld(0, 48).sky(-1, -1, 7, 1, 40).box(2, 10, 8, 93, 10, 8, AIR);
        Map<Long, ChunkSurvey> all = new HashMap<>();
        for (int cx = 0; cx <= 5; cx++) all.put(RoomJoin.key(cx, 0), w.survey(cx, 0, RULE));
        RoomId start = all.get(RoomJoin.key(0, 0)).id(1);
        RoomComponent small = RoomJoin.component(start, 3, all::get);
        assertFalse(small.complete());
        assertEquals(SkyContact.UNKNOWN, small.skyContact());
        assertEquals(3, small.chunks());
        RoomComponent whole = RoomJoin.component(start, 10, all::get);
        assertTrue(whole.complete(), whole.toString());
        assertEquals(SkyContact.SEALED, whole.skyContact());
        assertEquals(6, whole.rooms().size());
        assertEquals(92, whole.volume());
        // the same tunnel with a shaft to the sky in the last chunk
        w.box(90, 11, 8, 90, 39, 8, AIR);
        all.put(RoomJoin.key(5, 0), w.survey(5, 0, RULE));
        all.put(RoomJoin.key(4, 0), w.survey(4, 0, RULE));
        RoomComponent open = RoomJoin.component(start, 10, all::get);
        assertEquals(SkyContact.MOUTH, open.skyContact());
        // a missing neighbour survey makes the result unknown
        Map<Long, ChunkSurvey> gap = new HashMap<>(all);
        gap.remove(RoomJoin.key(2, 0));
        assertEquals(SkyContact.UNKNOWN, RoomJoin.component(start, 10, gap::get).skyContact());
        List<RoomId> adjacent = new ArrayList<>(RoomJoin.adjacent(all.get(RoomJoin.key(1, 0)), 1, all::get));
        assertEquals(2, adjacent.size(), "the tunnel room of chunk 1 touches chunks 0 and 2: " + adjacent);
    }
}
