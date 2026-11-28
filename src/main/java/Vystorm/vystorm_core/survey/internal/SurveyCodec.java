package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.survey.BorderRun;
import Vystorm.vystorm_core.survey.ChunkSurvey;
import Vystorm.vystorm_core.survey.RoomId;
import Vystorm.vystorm_core.survey.RoomInfo;
import Vystorm.vystorm_core.survey.SurveyFace;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The stored record of one chunk, "format C" of the study:
 *
 * <pre>
 * u8  format            u8 flags (1 = void below)
 * i32 rule hash         i64 stamp             i64 open-cell hash
 * deflated body (raw deflate):
 *   zigzag minY, height
 *   rooms: count, per room volume + flags (1 mouth, 2 sealed, 4 fluid)
 *   per section: count, per entry room + seed cell (u16)
 *   4 faces (west, east, north, south): count, per run gap from the previous end + length + room
 *   reach runs: count, per run gap from the previous end + length
 * i32 CRC32 of everything before
 * </pre>
 *
 * All counts and numbers in the body are unsigned varints. The world and chunk coordinates are not stored: the
 * region file says them.
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class SurveyCodec {
    static final int HEADER = 22;

    private SurveyCodec() {}

    /** Thrown for a record that cannot be read (damaged, other format). */
    public static final class FormatException extends Exception {
        public FormatException(String message) { super(message); }
    }

    public static byte[] encode(ChunkSurvey s) { return encode(s, true); }

    /** {@code sky = false} leaves the sky columns out (not readable back; only to measure the study's part). */
    static byte[] encode(ChunkSurvey s, boolean sky) {
        Bytes body = new Bytes(256);
        body.varint(zigzag(s.minY()));
        body.varint(s.height());
        body.varint(s.rooms().size());
        for (RoomInfo r : s.rooms()) {
            body.varint(r.volume());
            body.write((r.mouth() ? 1 : 0) | (r.sealed() ? 2 : 0) | (r.fluid() ? 4 : 0));
        }
        int minSection = s.minY() >> 4;
        for (int sec = 0; sec < s.height() >> 4; sec++) {
            int[] rooms = s.roomsInSection(minSection + sec);
            body.varint(rooms.length);
            for (int room : rooms) {
                int seed = s.seed(room, minSection + sec);
                body.varint(room);
                body.write(seed >>> 8);
                body.write(seed & 255);
            }
        }
        for (SurveyFace face : SurveyFace.values()) {
            List<BorderRun> runs = s.borders(face);
            body.varint(runs.size());
            int end = 0;
            for (BorderRun r : runs) {
                body.varint(r.start() - end);
                body.varint(r.length());
                body.varint(r.room());
                end = r.end();
            }
        }
        int[] reach = s.reachRuns();
        body.varint(reach.length / 2);
        int end = 0;
        for (int i = 0; i < reach.length; i += 2) {
            body.varint(reach[i] - end);
            body.varint(reach[i + 1]);
            end = reach[i] + reach[i + 1];
        }
        // sky columns (format 2): per column the first y index of the top sky column, then (void below) the height of
        // the sky column from the bottom; each as the zigzag difference to a prediction from the west, north and
        // north-west columns (terrain is smooth, so most differences are 0 and deflate to almost nothing)
        int[] top = new int[256];
        for (int col = 0; col < 256; col++) top[col] = s.skyTop(col & 15, col >>> 4) - s.minY();
        if (sky) writeSky(body, top);
        if (sky && s.voidBelow()) {
            int[] bottom = new int[256];
            for (int col = 0; col < 256; col++) bottom[col] = s.skyBottom(col & 15, col >>> 4);
            writeSky(body, bottom);
        }
        byte[] packed = deflate(body.array(), body.size());
        ByteBuffer out = ByteBuffer.allocate(HEADER + packed.length + 4);
        out.put((byte) s.format()).put((byte) (s.voidBelow() ? 1 : 0)).putInt(s.ruleHash()).putLong(s.stamp()).putLong(s.openHash());
        out.put(packed);
        CRC32 crc = new CRC32();
        crc.update(out.array(), 0, HEADER + packed.length);
        out.putInt((int) crc.getValue());
        return out.array();
    }

    public static ChunkSurvey decode(byte[] data, UUID worldId, int chunkX, int chunkZ) throws FormatException {
        if (data == null || data.length < HEADER + 4) throw new FormatException("record too short");
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length - 4);
        if ((int) crc.getValue() != ByteBuffer.wrap(data, data.length - 4, 4).getInt()) throw new FormatException("CRC mismatch");
        ByteBuffer head = ByteBuffer.wrap(data);
        int format = head.get() & 0xFF;
        if (format != ChunkSurvey.FORMAT) throw new FormatException("format " + format);
        boolean voidBelow = (head.get() & 1) != 0;
        int ruleHash = head.getInt();
        long stamp = head.getLong();
        long openHash = head.getLong();
        Reader in = new Reader(inflate(data, HEADER, data.length - 4 - HEADER));
        try {
            int minY = unzigzag(in.varint());
            int height = in.varint();
            if (height <= 0 || height > 4096 || (height & 15) != 0 || (minY & 15) != 0) throw new FormatException("height " + height);
            int count = in.varint();
            if (count > 65535) throw new FormatException("rooms " + count);
            List<RoomInfo> rooms = new ArrayList<>(count);
            for (int r = 1; r <= count; r++) {
                int volume = in.varint();
                int flags = in.u8();
                rooms.add(new RoomInfo(new RoomId(worldId, chunkX, chunkZ, r), volume, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0));
            }
            int[][] seeds = new int[height >> 4][];
            for (int sec = 0; sec < seeds.length; sec++) {
                int n = in.varint();
                if (n > count) throw new FormatException("seeds " + n);
                int[] list = new int[2 * n];
                for (int i = 0; i < n; i++) {
                    list[2 * i] = room(in.varint(), count);
                    int seed = in.u8() << 8 | in.u8();
                    if (seed > 4095) throw new FormatException("seed " + seed);
                    list[2 * i + 1] = seed;
                }
                seeds[sec] = list;
            }
            BorderRun[][] borders = new BorderRun[4][];
            for (int f = 0; f < 4; f++) {
                int n = in.varint();
                if (n > height * 16) throw new FormatException("runs " + n);
                BorderRun[] runs = new BorderRun[n];
                int end = 0;
                for (int i = 0; i < n; i++) {
                    int start = end + in.varint();
                    int length = in.varint();
                    runs[i] = new BorderRun(start, length, room(in.varint(), count));
                    end = runs[i].end();
                    if (end > height * 16) throw new FormatException("run beyond the face");
                }
                borders[f] = runs;
            }
            int n = in.varint();
            if (n > height * 128) throw new FormatException("reach runs " + n);
            int[] reach = new int[2 * n];
            int end = 0;
            for (int i = 0; i < n; i++) {
                reach[2 * i] = end + in.varint();
                reach[2 * i + 1] = in.varint();
                end = reach[2 * i] + reach[2 * i + 1];
                if (end > height * 256) throw new FormatException("reach run beyond the chunk");
            }
            short[] top = sky(in, height), bottom = voidBelow ? sky(in, height) : null;
            if (in.remaining() != 0) throw new FormatException("trailing bytes");
            return ChunkSurvey.of(worldId, chunkX, chunkZ, minY, height, format, ruleHash, stamp, openHash, voidBelow,
                    rooms, seeds, borders, reach, top, bottom, null);
        } catch (IllegalArgumentException | IndexOutOfBoundsException broken) {
            throw new FormatException("damaged record: " + broken.getMessage());
        }
    }

    /** Prediction of a column from its west (a), north (b) and north-west (c) neighbours (LOCO-I median). */
    static int predict(int[] v, int col) {
        int x = col & 15, z = col >>> 4;
        if (x == 0 && z == 0) return 0;
        if (z == 0) return v[col - 1];
        if (x == 0) return v[col - 16];
        int a = v[col - 1], b = v[col - 16], c = v[col - 17];
        if (c >= Math.max(a, b)) return Math.min(a, b);
        if (c <= Math.min(a, b)) return Math.max(a, b);
        return a + b - c;
    }

    private static void writeSky(Bytes body, int[] v) {
        for (int col = 0; col < 256; col++) body.varint(zigzag(v[col] - predict(v, col)));
    }

    private static short[] sky(Reader in, int height) throws FormatException {
        int[] v = new int[256];
        short[] out = new short[256];
        for (int col = 0; col < 256; col++) {
            v[col] = predict(v, col) + unzigzag(in.varint());
            if (v[col] < 0 || v[col] > height) throw new FormatException("sky column " + v[col]);
            out[col] = (short) v[col];
        }
        return out;
    }

    /** Format of a record without decoding it. */
    public static int formatOf(byte[] data) { return data == null || data.length < HEADER + 4 ? -1 : data[0] & 0xFF; }

    /** Stamp of a record without decoding the body (the stale check); 0 for an unreadable record. */
    public static long stampOf(byte[] data) {
        return data == null || data.length < HEADER + 4 ? 0 : ByteBuffer.wrap(data, 6, 8).getLong();
    }

    /** Rule hash of a record without decoding the body. */
    public static int ruleHashOf(byte[] data) {
        return data == null || data.length < HEADER + 4 ? 0 : ByteBuffer.wrap(data, 2, 4).getInt();
    }

    private static int room(int value, int count) throws FormatException {
        if (value < 1 || value > count) throw new FormatException("room " + value);
        return value;
    }

    static int zigzag(int v) { return (v << 1) ^ (v >> 31); }

    static int unzigzag(int v) { return (v >>> 1) ^ -(v & 1); }

    /** One deflater, inflater and buffer per thread: a record is decoded inside the chunk load event. */
    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_COMPRESSION, true));
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(() -> new Inflater(true));
    private static final ThreadLocal<byte[][]> BUFFERS = ThreadLocal.withInitial(() -> new byte[][] {new byte[8192], new byte[8192]});

    private static byte[] deflate(byte[] data, int length) {
        Deflater d = DEFLATER.get();
        d.reset();
        d.setInput(data, 0, length);
        d.finish();
        byte[][] bufs = BUFFERS.get();
        byte[] out = bufs[0];
        int n = 0;
        while (!d.finished()) {
            if (n == out.length) out = bufs[0] = Arrays.copyOf(out, out.length * 2);
            n += d.deflate(out, n, out.length - n);
        }
        return Arrays.copyOf(out, n);
    }

    private static byte[] inflate(byte[] data, int offset, int length) throws FormatException {
        Inflater inf = INFLATER.get();
        inf.reset();
        inf.setInput(data, offset, length);
        byte[][] bufs = BUFFERS.get();
        byte[] out = bufs[1];
        int n = 0;
        try {
            while (!inf.finished()) {
                if (n == out.length) {
                    if (out.length >= 4 << 20) throw new FormatException("body too large");
                    out = bufs[1] = Arrays.copyOf(out, out.length * 2);
                }
                int got = inf.inflate(out, n, out.length - n);
                if (got == 0 && !inf.finished() && (inf.needsInput() || inf.needsDictionary())) throw new FormatException("truncated body");
                n += got;
            }
            if (inf.getRemaining() != 0) throw new FormatException("bytes after the body");
            return Arrays.copyOf(out, n);
        } catch (DataFormatException broken) {
            throw new FormatException("body: " + broken.getMessage());
        }
    }

    /** Growable byte buffer with varints. */
    static final class Bytes {
        private byte[] buf;
        private int size;

        Bytes(int capacity) { buf = new byte[capacity]; }

        void write(int b) {
            if (size == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            buf[size++] = (byte) b;
        }

        void varint(int v) {
            while ((v & ~0x7F) != 0) {
                write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            write(v);
        }

        void bytes(byte[] b) { for (byte x : b) write(x); }

        byte[] array() { return buf; }

        int size() { return size; }

        byte[] toArray() { return Arrays.copyOf(buf, size); }
    }

    /** Reader of a body. */
    static final class Reader {
        private final byte[] buf;
        private int pos;

        Reader(byte[] buf) { this.buf = buf; }

        int u8() throws FormatException {
            if (pos >= buf.length) throw new FormatException("unexpected end");
            return buf[pos++] & 0xFF;
        }

        int varint() throws FormatException {
            int v = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = u8();
                v |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    if (v < 0 && shift == 28) throw new FormatException("varint too large");
                    return v;
                }
            }
            throw new FormatException("varint too long");
        }

        int remaining() { return buf.length - pos; }
    }
}
