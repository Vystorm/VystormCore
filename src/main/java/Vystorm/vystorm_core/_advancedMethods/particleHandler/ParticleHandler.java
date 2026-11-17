package Vystorm.vystorm_core._advancedMethods.particleHandler;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.IntFunction;

public class ParticleHandler implements ParticleInterface {
    public static final int DEFAULT_PARTICLES_PER_TICK = 4096;
    private final int particlesPerTick;
    private int budgetTick;
    private int used;
    private boolean reportedLimit;
    private final LinkedHashMap<Integer, double[]> lines = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<Integer, double[]> circles = new LinkedHashMap<>(16, 0.75f, true);

    public ParticleHandler() { this(DEFAULT_PARTICLES_PER_TICK); }

    /** Shared budget for all emissions through this instance; use the API's singleton handler. */
    public ParticleHandler(int particlesPerTick) {
        if (particlesPerTick < 1 || particlesPerTick > 65536) {
            throw new IllegalArgumentException("Particle budget must be between 1 and 65536 per tick");
        }
        this.particlesPerTick = particlesPerTick;
    }

    private int reserve(int requested, int minimum) {
        int tick = Vystorm.vystorm_core.compat.PlatformCompat.getCurrentTick();
        if (tick != budgetTick) { budgetTick = tick; used = 0; }
        int allowed = Math.min(requested, particlesPerTick - used);
        if (allowed < minimum) allowed = 0;
        used += allowed;
        if (allowed < requested && !reportedLimit) {
            reportedLimit = true;
            DebugMessages.low("[ParticleHandler] Particle tick budget reached; effects are reduced or skipped; limit=" + particlesPerTick);
        }
        return allowed;
    }

    private double[] geometry(LinkedHashMap<Integer, double[]> cache, int points, IntFunction<double[]> create) {
        double[] result = cache.get(points);
        if (result != null) return result;
        result = create.apply(points);
        // Bound memory even when callers continually change resolution or use a larger tick budget.
        if (points <= 4096) {
            if (cache.size() == 16) cache.pollFirstEntry();
            cache.put(points, result);
        }
        return result;
    }

    private void validate(Particle particle, Location location, Object data) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Particle effects require the server thread");
        Objects.requireNonNull(particle, "particle");
        Objects.requireNonNull(location, "location").checkFinite();
        Objects.requireNonNull(location.getWorld(), "world");
        Class<?> type = particle.getDataType();
        if (type == Void.class ? data != null : !type.isInstance(data)) {
            throw new IllegalArgumentException("Particle " + particle + " requires " + type.getSimpleName());
        }
    }
    private void count(int count) {
        if (count < 0) throw new IllegalArgumentException("Particle count cannot be negative");
    }
    @Override public <T> void spawn(Particle particle, Location location, int count, T data) {
        spawn(particle, location, count, 0, 0, 0, 0, data);
    }
    @Override public <T> void spawn(Particle particle, Location location, int count,
                                   double offsetX, double offsetY, double offsetZ, double extra, T data) {
        validate(particle, location, data); count(count);
        if (!Double.isFinite(offsetX) || !Double.isFinite(offsetY) || !Double.isFinite(offsetZ)
                || !Double.isFinite(extra)) throw new IllegalArgumentException("Particle offsets and speed must be finite");
        int allowed = reserve(Math.max(1, count), 1);
        if (allowed == 0) return;
        location.getWorld().spawnParticle(particle, location, count == 0 ? 0 : allowed, offsetX, offsetY, offsetZ, extra, data);
        DebugMessages.low("[ParticleHandler] World particles emitted; type=" + particle + "; count=" + (count == 0 ? 0 : allowed));
    }
    @Override public <T> void spawn(Player viewer, Particle particle, Location location, int count, T data) {
        validate(particle, location, data); count(count);
        Objects.requireNonNull(viewer, "viewer");
        if (!viewer.getWorld().equals(location.getWorld())) return;
        int allowed = reserve(Math.max(1, count), 1);
        if (allowed == 0) return;
        viewer.spawnParticle(particle, location, count == 0 ? 0 : allowed, 0, 0, 0, 0, data);
        DebugMessages.low("[ParticleHandler] Player particles emitted; type=" + particle + "; count=" + (count == 0 ? 0 : allowed));
    }
    @Override public <T> void line(Particle particle, Location from, Location to, int points, T data) {
        validate(particle, from, data); validate(particle, to, data);
        if (points < 2 || !from.getWorld().equals(to.getWorld())) throw new IllegalArgumentException("Line requires at least two points in one world");
        int allowed = reserve(points, 2);
        if (allowed == 0) return;
        double[] fractions = geometry(lines, allowed, size -> {
            double[] values = new double[size];
            for (int i = 0; i < size; i++) values[i] = (double) i / (size - 1);
            return values;
        });
        for (double t : fractions) {
            from.getWorld().spawnParticle(particle, from.getX() * (1-t) + to.getX() * t,
                    from.getY() * (1-t) + to.getY() * t, from.getZ() * (1-t) + to.getZ() * t,
                    1, 0, 0, 0, 0, data);
        }
        DebugMessages.low("[ParticleHandler] Particle line emitted; points=" + allowed);
    }
    @Override public <T> void circle(Particle particle, Location center, double radius, int points, T data) {
        validate(particle, center, data);
        if (!Double.isFinite(radius) || radius < 0 || points < 3) throw new IllegalArgumentException("Circle requires a finite non-negative radius and at least three points");
        if (!Double.isFinite(Math.abs(center.getX()) + radius) || !Double.isFinite(Math.abs(center.getZ()) + radius)) {
            throw new IllegalArgumentException("Circle coordinates must remain finite");
        }
        int allowed = reserve(points, 3);
        if (allowed == 0) return;
        double[] offsets = geometry(circles, allowed, size -> {
            double[] values = new double[size * 2];
            for (int i = 0; i < size; i++) {
                double angle = 2 * Math.PI * i / size;
                values[i * 2] = Math.cos(angle); values[i * 2 + 1] = Math.sin(angle);
            }
            return values;
        });
        for (int i = 0; i < allowed; i++) {
            center.getWorld().spawnParticle(particle, center.getX() + radius * offsets[i * 2], center.getY(),
                    center.getZ() + radius * offsets[i * 2 + 1], 1, 0, 0, 0, 0, data);
        }
        DebugMessages.low("[ParticleHandler] Particle circle emitted; points=" + allowed);
    }
    @Override public BukkitTask repeat(Plugin owner, Runnable effect, long periodTicks, int iterations) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(effect, "effect");
        if (periodTicks < 1 || iterations < 1) throw new IllegalArgumentException("Period and iterations must be positive");
        DebugMessages.mid("[ParticleHandler] Particle animation scheduled; iterations=" + iterations);
        return new BukkitRunnable() {
            private int remaining = iterations;
            @Override public void run() {
                try { effect.run(); }
                catch (RuntimeException failure) {
                    cancel();
                    DebugMessages.high("[ParticleHandler] Particle animation stopped after callback failure");
                    throw failure;
                }
                if (--remaining == 0) cancel();
            }
        }.runTaskTimer(owner, 0, periodTicks);
    }
}
