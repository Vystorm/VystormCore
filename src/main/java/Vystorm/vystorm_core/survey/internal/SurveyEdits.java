package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.blocks.EditedChunk;
import Vystorm.vystorm_core.survey.OpenChunk;

/**
 * The survey's pass over the cells of one {@code BlocksEditedEvent}: the same rule as for single block changes,
 * applied to all written cells of the chunk at once. A cell counts when it turns open or opaque; the chunk and the
 * neighbours within the rule's margin of such a cell are marked for a new survey, each once per event. The open
 * cells and the cached classes are copied once per touched section, not once per cell, so an edit of a whole chunk
 * costs one array lookup and a few bit operations per cell.
 *
 * <p>Plain Java, no Bukkit calls. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class SurveyEdits {
    /** Cell class of a block state id of the shared state table. */
    @FunctionalInterface
    public interface IdClasses {
        byte of(int stateId);
    }

    @FunctionalInterface
    public interface ChunkVisitor {
        void chunk(int chunkX, int chunkZ);
    }

    /**
     * What the edit changed: new open cells and classes (the same instances when nothing changed, null when there
     * were none), the number of cells that turned open or opaque, and the 3 x 3 chunks to mark (bit
     * {@code (dz + 1) * 3 + dx + 1}).
     */
    public record Result(OpenChunk cells, ChunkClasses classes, int changedCells, int neighbours) {
        /** Calls the visitor once for every chunk to mark around chunk {@code cx, cz}. */
        public void forEachChunk(int cx, int cz, ChunkVisitor visitor) {
            for (int bit = 0; bit < 9; bit++)
                if ((neighbours & (1 << bit)) != 0) visitor.chunk(cx + bit % 3 - 1, cz + bit / 3 - 1);
        }
    }

    private SurveyEdits() {}

    /**
     * One pass over the written cells.
     *
     * @param cells   the chunk's open cells the survey keeps, or null
     * @param cached  the chunk's cached classes, or null
     * @param margin  the rule's margin ({@code SurveyRule.margin()})
     */
    public static Result apply(EditedChunk edit, IdClasses ids, OpenChunk cells, ChunkClasses cached, int minY, int height, int margin) {
        int n = edit.size();
        long[][] words = null;
        byte[][] bytes = null;
        int changed = 0, mask = 0;
        int maxY = minY + height;
        for (int i = 0; i < n; i++) {
            int y = edit.y(i);
            if (y < minY || y >= maxY) continue;
            int lx = edit.x(i) & 15, lz = edit.z(i) & 15, yi = y - minY;
            byte now = ids.of(edit.afterId(i));
            boolean open = CellClass.open(now);
            // the class the survey knew, from its own copies when one exists, else the state before the write
            boolean before;
            int k = (y & 15) << 8 | lz << 4 | lx;
            if (cells != null) {
                int s = (y >> 4) - cells.minSection();
                if (s < 0 || s >= cells.sectionCount()) before = true;
                else if (words != null && words[s] != null) before = (words[s][k >>> 6] & (1L << (k & 63))) != 0;
                else before = cells.isOpen(lx, y, lz);
            } else if (cached != null) {
                int s = yi >> 4;
                before = CellClass.open(bytes != null && bytes[s] != null ? bytes[s][k] : cached.at(lx, yi, lz));
            } else {
                before = CellClass.open(ids.of(edit.beforeId(i)));
            }
            if (cached != null && yi < cached.height()) {
                int s = yi >> 4;
                byte known = bytes != null && bytes[s] != null ? bytes[s][k] : cached.at(lx, yi, lz);
                if (known != now) {
                    if (bytes == null) bytes = new byte[cached.sectionCount()][];
                    if (bytes[s] == null) bytes[s] = cached.copySection(s);
                    bytes[s][k] = now;
                }
            }
            if (before == open) continue;
            changed++;
            if (cells != null) {
                int s = (y >> 4) - cells.minSection();
                if (s >= 0 && s < cells.sectionCount()) {
                    if (words == null) words = new long[cells.sectionCount()][];
                    if (words[s] == null) words[s] = cells.copySection(y >> 4);
                    if (open) words[s][k >>> 6] |= 1L << (k & 63);
                    else words[s][k >>> 6] &= ~(1L << (k & 63));
                }
            }
            int dx0 = lx < margin ? 0 : 1, dx1 = lx > 15 - margin ? 2 : 1;
            int dz0 = lz < margin ? 0 : 1, dz1 = lz > 15 - margin ? 2 : 1;
            for (int dz = dz0; dz <= dz1; dz++) for (int dx = dx0; dx <= dx1; dx++) mask |= 1 << (dz * 3 + dx);
        }
        OpenChunk nextCells = cells;
        if (words != null) {
            for (int s = 0; s < words.length; s++) if (words[s] == null) words[s] = cells.copySection(cells.minSection() + s);
            nextCells = OpenChunk.adopt(cells.minSection(), words);
        }
        ChunkClasses nextClasses = bytes == null ? cached : cached.withSections(bytes);
        return new Result(nextCells, nextClasses, changed, mask);
    }
}
