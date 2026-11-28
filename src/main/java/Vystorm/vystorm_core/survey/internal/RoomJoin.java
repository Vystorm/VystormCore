package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.survey.BorderRun;
import Vystorm.vystorm_core.survey.ChunkSurvey;
import Vystorm.vystorm_core.survey.RoomComponent;
import Vystorm.vystorm_core.survey.RoomId;
import Vystorm.vystorm_core.survey.RoomInfo;
import Vystorm.vystorm_core.survey.SurveyFace;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;

/**
 * Joins rooms of neighbouring chunks through overlapping border runs (union-find), lazily: only the chunks a
 * question needs are looked at.
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class RoomJoin {
    private RoomJoin() {}

    public static long key(int chunkX, int chunkZ) { return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32); }

    public static int keyX(long key) { return (int) key; }

    public static int keyZ(long key) { return (int) (key >>> 32); }

    /**
     * Pairs of local rooms {@code {roomOfA, roomOfB}} that touch through {@code face} of {@code a} ({@code b} is the
     * neighbour behind that face). Both runs lists are in position order, so one merge pass finds every overlap.
     */
    public static List<int[]> links(ChunkSurvey a, SurveyFace face, ChunkSurvey b) {
        List<BorderRun> ra = a.borders(face), rb = b.borders(face.opposite());
        List<int[]> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < ra.size() && j < rb.size()) {
            BorderRun x = ra.get(i), y = rb.get(j);
            if (x.overlaps(y)) out.add(new int[] {x.room(), y.room()});
            if (x.end() <= y.end()) i++;
            else j++;
        }
        return out;
    }

    /** Rooms of the neighbours that touch one room; neighbours the lookup does not know are skipped. */
    public static Set<RoomId> adjacent(ChunkSurvey survey, int local, LongFunction<ChunkSurvey> lookup) {
        Set<RoomId> out = new LinkedHashSet<>();
        for (SurveyFace face : SurveyFace.values()) {
            ChunkSurvey n = lookup.apply(key(survey.chunkX() + face.dx(), survey.chunkZ() + face.dz()));
            if (n == null) continue;
            for (int[] link : links(survey, face, n)) if (link[0] == local) out.add(n.id(link[1]));
        }
        return out;
    }

    /** Union-find over all rooms of the given surveys (rooms of neighbours that are both present are joined). */
    public static Map<RoomId, RoomId> union(Collection<ChunkSurvey> surveys) {
        Map<Long, ChunkSurvey> byKey = new HashMap<>();
        for (ChunkSurvey s : surveys) byKey.put(key(s.chunkX(), s.chunkZ()), s);
        Map<RoomId, RoomId> parent = new HashMap<>();
        for (ChunkSurvey s : surveys) for (RoomInfo r : s.rooms()) parent.put(r.id(), r.id());
        for (ChunkSurvey s : surveys)
            for (SurveyFace face : new SurveyFace[] {SurveyFace.EAST, SurveyFace.SOUTH}) {
                ChunkSurvey n = byKey.get(key(s.chunkX() + face.dx(), s.chunkZ() + face.dz()));
                if (n == null) continue;
                for (int[] link : links(s, face, n)) unite(parent, s.id(link[0]), n.id(link[1]));
            }
        Map<RoomId, RoomId> roots = new HashMap<>();
        for (RoomId id : parent.keySet()) roots.put(id, find(parent, id));
        return roots;
    }

    private static RoomId find(Map<RoomId, RoomId> parent, RoomId a) {
        RoomId root = a;
        while (!parent.get(root).equals(root)) root = parent.get(root);
        while (!a.equals(root)) {
            RoomId next = parent.get(a);
            parent.put(a, root);
            a = next;
        }
        return root;
    }

    private static void unite(Map<RoomId, RoomId> parent, RoomId a, RoomId b) {
        RoomId ra = find(parent, a), rb = find(parent, b);
        if (ra.equals(rb)) return;
        // the smaller id becomes the root, so the result does not depend on the order
        if (compare(ra, rb) < 0) parent.put(rb, ra);
        else parent.put(ra, rb);
    }

    private static int compare(RoomId a, RoomId b) {
        if (a.chunkX() != b.chunkX()) return Integer.compare(a.chunkX(), b.chunkX());
        if (a.chunkZ() != b.chunkZ()) return Integer.compare(a.chunkZ(), b.chunkZ());
        return Integer.compare(a.local(), b.local());
    }

    /** Synchronous component search; {@code lookup} returns null for chunks without a survey. */
    public static RoomComponent component(RoomId start, int maxChunks, LongFunction<ChunkSurvey> lookup) {
        ComponentSearch search = new ComponentSearch(start, maxChunks);
        while (!search.done()) {
            for (long key : search.needed()) search.feed(key, lookup.apply(key));
            search.run();
        }
        return search.result();
    }

    /**
     * A component search that can wait for chunks: {@link #run()} goes as far as the known chunks allow,
     * {@link #needed()} names the chunks it waits for, {@link #feed} hands them in (null = not available).
     */
    public static final class ComponentSearch {
        private final RoomId start;
        private final int maxChunks;
        private final Map<Long, ChunkSurvey> chunks = new HashMap<>();
        private final Set<Long> missing = new HashSet<>();
        private final Set<RoomId> members = new LinkedHashSet<>();
        private final ArrayDeque<RoomId> queue = new ArrayDeque<>();
        private final List<RoomId> waiting = new ArrayList<>();
        private final Set<Long> needed = new LinkedHashSet<>();
        private long volume;
        private boolean mouth;
        private boolean incomplete;
        private boolean started;

        public ComponentSearch(RoomId start, int maxChunks) {
            this.start = start;
            this.maxChunks = Math.max(1, maxChunks);
            needed.add(start.chunkKey());
        }

        public Set<Long> needed() { return Set.copyOf(needed); }

        public void feed(long key, ChunkSurvey survey) {
            needed.remove(key);
            if (survey == null) missing.add(key);
            else chunks.put(key, survey);
        }

        public boolean done() { return started && needed.isEmpty() && queue.isEmpty() && waiting.isEmpty(); }

        public void run() {
            if (!started) {
                if (!needed.isEmpty()) return;
                started = true;
                ChunkSurvey home = chunks.get(start.chunkKey());
                if (home == null || start.local() > home.rooms().size()) {
                    incomplete = true;
                    return;
                }
                members.add(start);
                queue.add(start);
            }
            if (!needed.isEmpty()) return;
            queue.addAll(waiting);
            waiting.clear();
            while (!queue.isEmpty()) {
                RoomId room = queue.poll();
                ChunkSurvey s = chunks.get(room.chunkKey());
                RoomInfo info = s.room(room.local());
                if (counted.add(room)) {
                    volume += info.volume();
                    mouth |= info.mouth();
                }
                boolean wait = false;
                for (SurveyFace face : SurveyFace.values()) {
                    boolean has = false;
                    for (BorderRun r : s.borders(face)) if (r.room() == room.local()) { has = true; break; }
                    if (!has) continue;
                    long nk = key(s.chunkX() + face.dx(), s.chunkZ() + face.dz());
                    ChunkSurvey n = chunks.get(nk);
                    if (n == null) {
                        if (missing.contains(nk)) { incomplete = true; continue; }
                        if (chunks.size() + needed.size() >= maxChunks && !needed.contains(nk)) { incomplete = true; continue; }
                        needed.add(nk);
                        wait = true;
                        continue;
                    }
                    for (int[] link : links(s, face, n)) {
                        if (link[0] != room.local()) continue;
                        RoomId other = n.id(link[1]);
                        if (members.add(other)) queue.add(other);
                    }
                }
                if (wait) waiting.add(room);
            }
        }

        private final Set<RoomId> counted = new HashSet<>();

        public RoomComponent result() {
            return new RoomComponent(members, volume, mouth, !incomplete, chunks.size());
        }
    }
}
