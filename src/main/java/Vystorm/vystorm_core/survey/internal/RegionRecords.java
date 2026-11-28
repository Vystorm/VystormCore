package Vystorm.vystorm_core.survey.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * The records of one region (32 x 32 chunks) in one file {@code r.<x>.<z>.vsurvey}:
 *
 * <pre>
 * "VSRV" u8 version u8 0 u16 count
 * per record: u16 index (z * 32 + x inside the region), i32 length, bytes (see {@link SurveyCodec})
 * i32 CRC32 of everything before
 * </pre>
 *
 * Every record has its own CRC as well. Thread safe: all methods synchronize on the instance.
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class RegionRecords {
    static final byte[] MAGIC = "VSRV".getBytes(StandardCharsets.US_ASCII);
    static final int VERSION = 1;

    private final TreeMap<Integer, byte[]> records = new TreeMap<>();
    private boolean dirty;

    /** File name of the region of a chunk. */
    public static String fileName(int chunkX, int chunkZ) { return "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".vsurvey"; }

    /** Index of a chunk inside its region. */
    public static int index(int chunkX, int chunkZ) { return (chunkZ & 31) * 32 + (chunkX & 31); }

    public synchronized byte[] get(int index) { return records.get(index); }

    /** Sets (or with null removes) a record; marks the region dirty if it changed. */
    public synchronized void put(int index, byte[] record) {
        byte[] old = record == null ? records.remove(index) : records.put(index, record);
        if (old != record) dirty = true;
    }

    public synchronized int size() { return records.size(); }

    /** Bytes of all records (memory of a region kept for chunk loads). */
    public synchronized long bytes() {
        long n = 0;
        for (byte[] r : records.values()) n += r.length + 48;
        return n;
    }

    public synchronized boolean dirty() { return dirty; }

    /** The bytes to write; clears the dirty mark (set it again with {@link #markDirty()} if the write fails). */
    public synchronized byte[] encodeAndClean() {
        dirty = false;
        return encode(records);
    }

    public synchronized void markDirty() { dirty = true; }

    public synchronized Map<Integer, byte[]> snapshot() { return new TreeMap<>(records); }

    static byte[] encode(Map<Integer, byte[]> records) {
        int size = 8 + 4;
        for (byte[] r : records.values()) size += 6 + r.length;
        ByteBuffer out = ByteBuffer.allocate(size);
        out.put(MAGIC).put((byte) VERSION).put((byte) 0).putShort((short) records.size());
        for (var e : records.entrySet()) out.putShort(e.getKey().shortValue()).putInt(e.getValue().length).put(e.getValue());
        CRC32 crc = new CRC32();
        crc.update(out.array(), 0, size - 4);
        out.putInt((int) crc.getValue());
        return out.array();
    }

    /** Reads a region file; throws {@link SurveyCodec.FormatException} if it is damaged in any way. */
    public static RegionRecords decode(byte[] data) throws SurveyCodec.FormatException {
        if (data.length < 12) throw new SurveyCodec.FormatException("region file too short");
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length - 4);
        ByteBuffer in = ByteBuffer.wrap(data);
        if ((int) crc.getValue() != in.getInt(data.length - 4)) throw new SurveyCodec.FormatException("region CRC mismatch");
        for (byte b : MAGIC) if (in.get() != b) throw new SurveyCodec.FormatException("not a survey region file");
        int version = in.get() & 0xFF;
        if (version != VERSION) throw new SurveyCodec.FormatException("region version " + version);
        in.get();
        int count = in.getShort() & 0xFFFF;
        if (count > 1024) throw new SurveyCodec.FormatException("records " + count);
        RegionRecords r = new RegionRecords();
        for (int i = 0; i < count; i++) {
            if (in.remaining() < 4 + 6) throw new SurveyCodec.FormatException("truncated region");
            int index = in.getShort() & 0xFFFF;
            int length = in.getInt();
            if (index >= 1024 || length < 0 || length > in.remaining() - 4) throw new SurveyCodec.FormatException("bad record entry");
            byte[] rec = new byte[length];
            in.get(rec);
            r.records.put(index, rec);
        }
        if (in.remaining() != 4) throw new SurveyCodec.FormatException("trailing bytes in region");
        return r;
    }
}
