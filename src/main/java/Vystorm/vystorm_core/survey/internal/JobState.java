package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.survey.JobOptions;
import Vystorm.vystorm_core.survey.SurveyJob;
import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * The progress file of a pre-compute job ({@code job.properties}): area, options, state and the index up to which
 * every chunk of the {@link RingPlan} is finished. Chunks after that index that were finished out of order are
 * surveyed again after a restart (their records are found up to date, which is cheap).
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class JobState {
    public static final String FILE = "job.properties";
    static final int FORMAT = 1;

    public final UUID worldId;
    public final int centerX;
    public final int centerZ;
    public final int radius;
    public final JobOptions options;
    public volatile SurveyJob.State state;
    /** Every index below this one is finished. */
    public volatile long next;
    public volatile long skipped;
    /** Milliseconds the job has run over all sessions. */
    public volatile long millis;

    public JobState(UUID worldId, int centerX, int centerZ, int radius, JobOptions options, SurveyJob.State state,
                    long next, long skipped, long millis) {
        this.worldId = worldId;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.options = options;
        this.state = state;
        this.next = next;
        this.skipped = skipped;
        this.millis = millis;
    }

    public RingPlan plan() { return RingPlan.ofBlocks(centerX, centerZ, radius); }

    public String toText() {
        return "# Vystorm Core world survey job (written by Core; delete the file to forget the job)\n"
                + "format=" + FORMAT + "\n"
                + "world=" + worldId + "\n"
                + "center-x=" + centerX + "\n"
                + "center-z=" + centerZ + "\n"
                + "radius=" + radius + "\n"
                + "state=" + state.name().toLowerCase(Locale.ROOT) + "\n"
                + "next=" + next + "\n"
                + "skipped=" + skipped + "\n"
                + "millis=" + millis + "\n"
                + "generate=" + options.generate() + "\n"
                + "workers=" + options.workers() + "\n"
                + "snapshots-per-tick=" + options.snapshotsPerTick() + "\n"
                + "millis-per-tick=" + options.millisPerTick() + "\n"
                + "tps-floor=" + options.tpsFloor() + "\n"
                + "yield-to-players=" + options.yieldToPlayers() + "\n";
    }

    /** Reads a progress file; throws IllegalArgumentException for a damaged one. */
    public static JobState parse(String text) {
        Properties p = new Properties();
        try { p.load(new StringReader(text)); }
        catch (IOException impossible) { throw new IllegalArgumentException(impossible); }
        if (!String.valueOf(FORMAT).equals(p.getProperty("format"))) throw new IllegalArgumentException("format " + p.getProperty("format"));
        try {
            JobOptions o = new JobOptions(bool(p, "generate"), Integer.parseInt(req(p, "workers")),
                    Integer.parseInt(req(p, "snapshots-per-tick")), Double.parseDouble(req(p, "millis-per-tick")),
                    Double.parseDouble(req(p, "tps-floor")), bool(p, "yield-to-players"));
            JobState s = new JobState(UUID.fromString(req(p, "world")), Integer.parseInt(req(p, "center-x")),
                    Integer.parseInt(req(p, "center-z")), Integer.parseInt(req(p, "radius")), o,
                    SurveyJob.State.valueOf(req(p, "state").toUpperCase(Locale.ROOT)), Long.parseLong(req(p, "next")),
                    Long.parseLong(req(p, "skipped")), Long.parseLong(req(p, "millis")));
            if (s.next < 0 || s.radius < 0 || s.skipped < 0) throw new IllegalArgumentException("negative value");
            s.plan();
            return s;
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("number: " + bad.getMessage());
        }
    }

    private static String req(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null) throw new IllegalArgumentException("missing " + key);
        return v.trim();
    }

    private static boolean bool(Properties p, String key) {
        String v = req(p, key);
        if (!v.equals("true") && !v.equals("false")) throw new IllegalArgumentException(key + "=" + v);
        return Boolean.parseBoolean(v);
    }
}
