package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.survey.ChunkSurvey;
import Vystorm.vystorm_core.survey.OpenChunk;
import java.util.Optional;
import java.util.UUID;

/**
 * What Core does with a stored record when its chunk loads, decided on the server thread inside the chunk load event
 * without a snapshot and without reading a file:
 *
 * <ul>
 *   <li>{@link Outcome#OFFER}: the record's stamp equals the stamp in the chunk's persistent data (saved together with
 *   the blocks), and format and rule hash match: the record is offered at once; the open cells are checked later by
 *   the low-priority audit.</li>
 *   <li>{@link Outcome#STALE}: there is a record, but stamp, rule or format differ.</li>
 *   <li>{@link Outcome#NONE}: the region file is in memory and has no record for the chunk.</li>
 *   <li>{@link Outcome#NOT_YET}: the region file is not in memory yet; it is read on the file thread and the chunk is
 *   decided again then.</li>
 * </ul>
 *
 * What the stamp cannot see: blocks changed without the chunk's persistent data changing, by a tool that writes
 * region files directly or a plugin that changes blocks without events (WorldEdit's fast mode). Only the audit finds
 * those.
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class LoadGate {
    public enum Outcome { OFFER, STALE, NONE, NOT_YET }

    /** The decision and, for {@link Outcome#OFFER}, the decoded record (without cells). */
    public record Result(Outcome outcome, ChunkSurvey record, byte[] bytes) {
        public Result(Outcome outcome, ChunkSurvey record) { this(outcome, record, null); }
    }

    private LoadGate() {}

    /**
     * @param region    the region's records if the file is in memory, else null
     * @param pdcStamp  the stamp in the chunk's persistent data (0 = none)
     * @param ruleHash  the world's current rule hash
     */
    public static Result decide(RegionRecords region, int chunkX, int chunkZ, long pdcStamp, int ruleHash, UUID worldId) {
        return decide(region, chunkX, chunkZ, pdcStamp, ruleHash, worldId, true);
    }

    /**
     * {@code decode = false}: an offer carries the record's bytes only (the header was checked); the record is decoded
     * when somebody asks for it, so a load costs a map lookup and a header check.
     */
    public static Result decide(RegionRecords region, int chunkX, int chunkZ, long pdcStamp, int ruleHash, UUID worldId, boolean decode) {
        if (region == null) return new Result(Outcome.NOT_YET, null);
        byte[] bytes = region.get(RegionRecords.index(chunkX, chunkZ));
        if (bytes == null) return new Result(Outcome.NONE, null);
        if (pdcStamp == 0 || SurveyCodec.formatOf(bytes) != ChunkSurvey.FORMAT || SurveyCodec.ruleHashOf(bytes) != ruleHash
                || SurveyCodec.stampOf(bytes) != pdcStamp)
            return new Result(Outcome.STALE, null);
        if (!decode) return new Result(Outcome.OFFER, null, bytes);
        try {
            return new Result(Outcome.OFFER, SurveyCodec.decode(bytes, worldId, chunkX, chunkZ));
        } catch (SurveyCodec.FormatException damaged) {
            return new Result(Outcome.STALE, null);
        }
    }

    /** The audit of an offered record: the record with the chunk's cells, or empty if the blocks changed. */
    public static Optional<ChunkSurvey> audit(ChunkSurvey record, OpenChunk cells) {
        Optional<ChunkSurvey> bound = record.withCells(cells);
        return bound.isPresent() && bound.get().consistent() ? bound : Optional.empty();
    }

    /** Which offered records the audit checks: none, all, or a fixed share chosen by chunk position. */
    public record AuditPolicy(Mode mode, double share) {
        public enum Mode { OFF, SAMPLE, ALL }

        public static AuditPolicy parse(String mode, double share) {
            Mode m = switch (mode == null ? "sample" : mode.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "off", "false", "none" -> Mode.OFF;
                case "all", "true" -> Mode.ALL;
                default -> Mode.SAMPLE;
            };
            return new AuditPolicy(m, Math.max(0, Math.min(1, share)));
        }

        /** Same answer for the same chunk every time (no randomness in tests or between restarts). */
        public boolean audits(int chunkX, int chunkZ) {
            return switch (mode) {
                case OFF -> false;
                case ALL -> true;
                case SAMPLE -> {
                    long h = (chunkX * 0x9E3779B97F4A7C15L) ^ (chunkZ * 0xC2B2AE3D27D4EB4FL);
                    h ^= h >>> 29;
                    h *= 0xBF58476D1CE4E5B9L;
                    h ^= h >>> 32;
                    yield (h >>> 11) * 0x1.0p-53 < share;
                }
            };
        }
    }
}
