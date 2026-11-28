package Vystorm.vystorm_core.survey.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core.survey.ChunkSurvey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Time per chunk and stored bytes per chunk. Runs on the nine fixtures of the repository; with
 * {@code -PsurveyBench=<folder>[,<folder>...]} (fixtures of every interior chunk of a region, made by the study's
 * {@code export-fixtures.js <region> <dim> <folder> all}) it measures those as well and checks the study's band for
 * a folder named {@code ow}. The table goes to standard output and {@code build/survey-bench.txt}.
 */
class SurveyBenchmarkTest {
    @BeforeAll
    static void workerThread() { SurveyThreads.install(() -> false); }

    @AfterAll
    static void reset() { SurveyThreads.install(null); }

    record Row(String set, int chunks, double surveyMs, double assembleMs, double encodeMs, int p50, int p95, int max, int p50c, int p95c) {
        Row(String set, int chunks, double surveyMs, double assembleMs, double encodeMs, int p50, int p95, int max) {
            this(set, chunks, surveyMs, assembleMs, encodeMs, p50, p95, max, 0, 0);
        }

        String line() {
            return String.format(Locale.ROOT, "| %s | %d | %.2f | %.2f | %.2f | %d | %d | %d | %d | %d |", set, chunks, surveyMs, assembleMs, encodeMs, p50, p95, max, p50c, p95c);
        }
    }

    /** Measures one fixture at a time (a folder can hold hundreds; each one is about 3 MB in memory). */
    static Row measure(String set, List<Supplier<SurveyFixtures.Fixture>> fixtures, int rounds) {
        int n = fixtures.size();
        long survey = 0, assemble = 0, encode = 0;
        int[] bytes = new int[n], study = new int[n];
        for (int i = 0; i < n; i++) {
            SurveyFixtures.Fixture f = fixtures.get(i).get();
            ChunkClasses[] chunks = f.chunks();
            if (i < 3) // warm-up on the first chunks
                for (int k = 0; k < 20; k++)
                    SurveyCodec.encode(SurveyEngine.survey(SurveyGrid.assemble(f.rule().margin(), chunks), f.rule(),
                            SurveyFixtures.WORLD, f.chunkX(), f.chunkZ(), 1));
            for (int round = 0; round < rounds; round++) {
                long t0 = System.nanoTime();
                SurveyGrid g = SurveyGrid.assemble(f.rule().margin(), chunks);
                long t1 = System.nanoTime();
                ChunkSurvey s = SurveyEngine.survey(g, f.rule(), SurveyFixtures.WORLD, f.chunkX(), f.chunkZ(), 1);
                long t2 = System.nanoTime();
                byte[] b = SurveyCodec.encode(s);
                long t3 = System.nanoTime();
                assemble += t1 - t0;
                survey += t2 - t1;
                encode += t3 - t2;
                bytes[i] = b.length;
                study[i] = SurveyCodec.encode(s, false).length;
                if (round == 0) {
                    // the mask a consumer builds at chunk load: from the stored record, without cells
                    try {
                        long d0 = System.nanoTime();
                        ChunkSurvey stored = SurveyCodec.decode(b, SurveyFixtures.WORLD, f.chunkX(), f.chunkZ());
                        long decode = System.nanoTime() - d0;
                        long m0 = System.nanoTime();
                        MASKS.add(new long[] {stored.mask().footprint(), System.nanoTime() - m0, decode});
                    } catch (SurveyCodec.FormatException e) { throw new AssertionError(e); }
                }
            }
        }
        int[] sorted = bytes.clone();
        Arrays.sort(sorted);
        double per = 1e6 * rounds * n;
        int[] sortedStudy = study.clone();
        Arrays.sort(sortedStudy);
        return new Row(set, n, survey / per, assemble / per, encode / per, quantile(sorted, 0.5), quantile(sorted, 0.95), sorted[n - 1],
                quantile(sortedStudy, 0.5), quantile(sortedStudy, 0.95));
    }

    /** The same surveys on several threads at once (as Core's worker pool runs them): time per survey per thread. */
    static Row parallel(List<Supplier<SurveyFixtures.Fixture>> fixtures, int threads) {
        List<SurveyFixtures.Fixture> loaded = new ArrayList<>();
        List<SurveyGrid> grids = new ArrayList<>();
        for (Supplier<SurveyFixtures.Fixture> s : fixtures) {
            SurveyFixtures.Fixture f = s.get();
            loaded.add(f);
            grids.add(f.fullGrid().crop(f.rule().margin()));
        }
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong count = new java.util.concurrent.atomic.AtomicLong();
        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int offset = t;
            pool[t] = new Thread(() -> {
                for (int round = 0; round < 3; round++)
                    for (int i = 0; i < loaded.size(); i++) {
                        SurveyFixtures.Fixture f = loaded.get((i + offset * 17) % loaded.size());
                        long t0 = System.nanoTime();
                        SurveyEngine.survey(grids.get((i + offset * 17) % loaded.size()), f.rule(), SurveyFixtures.WORLD, f.chunkX(), f.chunkZ(), 1);
                        if (round > 0) { nanos.addAndGet(System.nanoTime() - t0); count.incrementAndGet(); }
                    }
            });
            pool[t].start();
        }
        for (Thread t : pool) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return new Row("ow, " + threads + " threads at once", loaded.size(), nanos.get() / 1e6 / Math.max(1, count.get()), 0, 0, 0, 0, 0);
    }

    static final List<long[]> MASKS = new ArrayList<>();

    static String maskLine(String set) {
        if (MASKS.isEmpty()) return "";
        long[] bytes = MASKS.stream().mapToLong(m -> m[0]).sorted().toArray();
        double ms = MASKS.stream().mapToLong(m -> m[1]).average().orElse(0) / 1e6;
        double dec = MASKS.stream().skip(Math.min(20, MASKS.size() - 1)).mapToLong(m -> m[2]).average().orElse(0) / 1e6;
        String line = String.format(Locale.ROOT, "record decode %.3f ms; mask at load (%s): %d bytes median, %d p95, built in %.3f ms on average", dec,
                set, bytes[bytes.length / 2], bytes[(int) Math.min(bytes.length - 1, Math.floor(bytes.length * 0.95))], ms);
        MASKS.clear();
        return line;
    }

    static int quantile(int[] sorted, double q) { return sorted[Math.min(sorted.length - 1, (int) Math.floor(q * sorted.length))]; }

    @Test
    void givenRealChunks_whenSurveyed_thenTimePerChunkAndBytesPerChunkAreReported() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("| set | chunks | survey ms | assemble ms | encode ms | bytes p50 | bytes p95 | bytes max | without sky columns p50 | p95 |");
        lines.add("|---|---|---|---|---|---|---|---|---|---|");
        List<Supplier<SurveyFixtures.Fixture>> inRepo = new ArrayList<>();
        for (String name : SurveyFixtures.NAMES) inRepo.add(() -> SurveyFixtures.load(name));
        Row repo = measure("repository fixtures", inRepo, 5);
        lines.add(repo.line());
        MASKS.clear();
        assertTrue(repo.max() < 1500, "a record of the repository fixtures stays small: " + repo.max());
        String bench = System.getProperty("vystorm.surveyBench", "");
        for (String dir : bench.split(",")) {
            if (dir.isBlank()) continue;
            Path p = Path.of(dir.trim());
            List<Supplier<SurveyFixtures.Fixture>> fixtures = SurveyFixtures.folder(p);
            if (fixtures.isEmpty()) continue;
            Row row = measure(p.getFileName().toString(), fixtures, 2);
            lines.add(row.line());
            String masks = maskLine(p.getFileName().toString());
            if (!masks.isEmpty()) lines.add(masks);
            if (p.getFileName().toString().equals("ow")) {
                for (int threads : new int[] {1, 4}) lines.add(parallel(fixtures.subList(0, Math.min(40, fixtures.size())), threads).line());
                // the study's band for format C (seeds + reach) in the Overworld: median 257 to 339 bytes, p95 449 to 687
                assertTrue(row.p50c() <= 400, "median bytes per chunk, study part " + row.p50c());
                assertTrue(row.p95c() < 750, "p95 bytes per chunk, study part " + row.p95c());
                // format 2 adds the sky column per column (masking at chunk load without cells): about 80 bytes
                assertTrue(row.p50() <= 450, "median bytes per chunk " + row.p50());
                assertTrue(row.p95() < 800, "p95 bytes per chunk " + row.p95());
            }
        }
        String table = String.join(System.lineSeparator(), lines);
        System.out.println(table);
        Path out = Path.of("build", "survey-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table + System.lineSeparator());
    }
}
