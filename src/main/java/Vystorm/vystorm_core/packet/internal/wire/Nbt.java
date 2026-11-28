package Vystorm.vystorm_core.packet.internal.wire;

/** Skips network NBT (an unnamed root tag: type byte, then the payload; type 0 means "no tag"). */
public final class Nbt {
    private Nbt() {}

    static final int MAX_DEPTH = 512;

    /** Skips one optional root tag. */
    public static void skipRoot(Bytes.Reader r) {
        int type = r.u8();
        if (type == 0) return;
        skipPayload(r, type, 0);
    }

    static void skipPayload(Bytes.Reader r, int type, int depth) {
        if (depth > MAX_DEPTH) throw new Bytes.Malformed("nbt too deep");
        switch (type) {
            case 1 -> r.skip(1);
            case 2 -> r.skip(2);
            case 3, 5 -> r.skip(4);
            case 4, 6 -> r.skip(8);
            case 7 -> r.skip(length(r, 1));
            case 8 -> r.skip(r.u16());
            case 9 -> {
                int inner = r.u8(), n = r.s32();
                if (n < 0) throw new Bytes.Malformed("nbt list length " + n);
                if (n > 0 && inner == 0) throw new Bytes.Malformed("nbt list of end tags");
                for (int i = 0; i < n; i++) skipPayload(r, inner, depth + 1);
            }
            case 10 -> {
                for (;;) {
                    int t = r.u8();
                    if (t == 0) return;
                    r.skip(r.u16());
                    skipPayload(r, t, depth + 1);
                }
            }
            case 11 -> r.skip(length(r, 4));
            case 12 -> r.skip(length(r, 8));
            default -> throw new Bytes.Malformed("nbt tag type " + type);
        }
    }

    private static int length(Bytes.Reader r, int size) {
        int n = r.s32();
        if (n < 0 || (long) n * size > r.remaining()) throw new Bytes.Malformed("nbt array length " + n);
        return n * size;
    }
}
