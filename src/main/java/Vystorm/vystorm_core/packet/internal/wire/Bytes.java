package Vystorm.vystorm_core.packet.internal.wire;

import java.util.Arrays;

/** Minecraft wire primitives over plain byte arrays (big endian, varints as in the protocol). No Netty, no server. */
public final class Bytes {
    private Bytes() {}

    /** Thrown when a buffer does not have the expected layout. */
    public static final class Malformed extends RuntimeException {
        public Malformed(String what) { super(what); }
    }

    public static final class Reader {
        public final byte[] b;
        public int p;
        private final int end;

        public Reader(byte[] b, int p) { this(b, p, b.length); }

        public Reader(byte[] b, int p, int end) { this.b = b; this.p = p; this.end = end; }

        public int remaining() { return end - p; }

        private void need(int n) { if (n < 0 || p + n > end) throw new Malformed("needs " + n + " bytes at " + p + ", has " + (end - p)); }

        public int u8() { need(1); return b[p++] & 255; }

        public int s8() { need(1); return b[p++]; }

        public int s16() { need(2); int v = (short) (((b[p] & 255) << 8) | (b[p + 1] & 255)); p += 2; return v; }

        public int u16() { return s16() & 0xFFFF; }

        public int s32() { need(4); int v = ((b[p] & 255) << 24) | ((b[p + 1] & 255) << 16) | ((b[p + 2] & 255) << 8) | (b[p + 3] & 255); p += 4; return v; }

        public long s64() { need(8); long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (b[p + i] & 255); p += 8; return v; }

        public int varint() {
            int v = 0;
            for (int s = 0; s < 35; s += 7) {
                int x = u8();
                v |= (x & 127) << s;
                if ((x & 128) == 0) return v;
            }
            throw new Malformed("varint too long at " + p);
        }

        public long varlong() {
            long v = 0;
            for (int s = 0; s < 70; s += 7) {
                int x = u8();
                v |= (long) (x & 127) << s;
                if ((x & 128) == 0) return v;
            }
            throw new Malformed("varlong too long at " + p);
        }

        public void skip(int n) { need(n); p += n; }

        /** A count read from the wire, checked against a sane upper limit. */
        public int count(int max) {
            int n = varint();
            if (n < 0 || n > max) throw new Malformed("count " + n + " out of range at " + p);
            return n;
        }

        public long longAt(int off) {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 255);
            return v;
        }
    }

    public static final class Writer {
        public byte[] b;
        public int p;

        public Writer(int cap) { b = new byte[Math.max(64, cap)]; }

        public void ensure(int n) { if (p + n > b.length) b = Arrays.copyOf(b, Math.max(b.length * 2, p + n)); }

        public void u8(int v) { ensure(1); b[p++] = (byte) v; }

        public void s16(int v) { ensure(2); b[p++] = (byte) (v >> 8); b[p++] = (byte) v; }

        public void s32(int v) { ensure(4); for (int i = 3; i >= 0; i--) b[p++] = (byte) (v >>> (i * 8)); }

        public void s64(long v) { ensure(8); for (int i = 7; i >= 0; i--) b[p++] = (byte) (v >>> (i * 8)); }

        public void varint(int v) { ensure(5); while ((v & ~127) != 0) { b[p++] = (byte) ((v & 127) | 128); v >>>= 7; } b[p++] = (byte) v; }

        public void varlong(long v) { ensure(10); while ((v & ~127L) != 0) { b[p++] = (byte) ((v & 127) | 128); v >>>= 7; } b[p++] = (byte) v; }

        public void bytes(byte[] src, int off, int len) { ensure(len); System.arraycopy(src, off, b, p, len); p += len; }

        public byte[] done() { return Arrays.copyOf(b, p); }
    }

    public static int varintSize(int v) { int n = 1; while ((v & ~127) != 0) { n++; v >>>= 7; } return n; }
}
