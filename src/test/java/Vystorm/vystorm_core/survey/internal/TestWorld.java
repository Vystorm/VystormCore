package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.survey.ChunkSurvey;
import Vystorm.vystorm_core.survey.SurveyRule;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A small block world of cell classes for survey tests (chunks never set are solid stone). */
final class TestWorld {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    final int minY;
    final int height;
    private final Map<Long, byte[]> chunks = new HashMap<>();

    TestWorld(int minY, int height) {
        this.minY = minY;
        this.height = height;
    }

    private byte[] chunk(int cx, int cz) { return chunks.computeIfAbsent(RoomJoin.key(cx, cz), k -> new byte[256 * height]); }

    TestWorld set(int x, int y, int z, byte cls) {
        chunk(x >> 4, z >> 4)[((y - minY) * 16 + (z & 15)) * 16 + (x & 15)] = cls;
        return this;
    }

    byte get(int x, int y, int z) {
        byte[] c = chunks.get(RoomJoin.key(x >> 4, z >> 4));
        return c == null ? CellClass.OPAQUE : c[((y - minY) * 16 + (z & 15)) * 16 + (x & 15)];
    }

    /** Fills the box (inclusive corners). */
    TestWorld box(int x0, int y0, int z0, int x1, int y1, int z1, byte cls) {
        for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) set(x, y, z, cls);
        return this;
    }

    /** Air from y up to the top of the world over the chunk range (a flat surface at y - 1). */
    TestWorld sky(int cx0, int cz0, int cx1, int cz1, int y) {
        return box(cx0 * 16, y, cz0 * 16, cx1 * 16 + 15, minY + height - 1, cz1 * 16 + 15, CellClass.AIR);
    }

    ChunkClasses classes(int cx, int cz) { return ChunkClasses.of(minY, height, chunk(cx, cz).clone()); }

    SurveyGrid grid(int cx, int cz, int margin) {
        ChunkClasses[] nine = new ChunkClasses[9];
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) nine[(dz + 1) * 3 + dx + 1] = classes(cx + dx, cz + dz);
        return SurveyGrid.assemble(margin, nine);
    }

    ChunkSurvey survey(int cx, int cz, SurveyRule rule) {
        return SurveyEngine.survey(grid(cx, cz, rule.margin()), rule, ID, cx, cz, 1);
    }
}
