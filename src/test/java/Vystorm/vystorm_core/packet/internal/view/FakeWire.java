package Vystorm.vystorm_core.packet.internal.view;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The wire of the handler tests: packet "objects" are strings, the fake encoder writes a one-byte packet id (1 for
 * "chunk:...", 2 for "light:...", 3 for "section:...", 0 for everything else) plus the text, synchronously like the
 * server's encoder.
 */
public final class FakeWire {
    private FakeWire() {}

    public static final int OTHER = 0, CHUNK = 1, LIGHT = 2, SECTION = 3;

    public static int id(String text) {
        return text.startsWith("chunk:") ? CHUNK : text.startsWith("light:") ? LIGHT : text.startsWith("section:") ? SECTION : OTHER;
    }

    public static ViewKind kind(int id) {
        return id == CHUNK ? ViewKind.CHUNK : id == LIGHT ? ViewKind.LIGHT : id == SECTION ? ViewKind.SECTION : null;
    }

    public static byte[] bytes(int id, String text) {
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[t.length + 1];
        out[0] = (byte) id;
        System.arraycopy(t, 0, out, 1, t.length);
        return out;
    }

    public static String text(byte[] whole) { return new String(whole, 1, whole.length - 1, StandardCharsets.UTF_8); }

    /** The server's encoder: strings to id + text; other objects pass on. */
    public static final class Encoder extends ChannelOutboundHandlerAdapter {
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (!(msg instanceof String s)) { ctx.write(msg, promise); return; }
            byte[] b = bytes(id(s), s);
            ByteBuf buf = ctx.alloc().buffer(b.length);
            buf.writeBytes(b);
            ctx.write(buf, promise);
        }
    }

    /** The texts of every buffer that left the channel, in order. */
    public static List<String> drain(EmbeddedChannel ch) {
        List<String> out = new ArrayList<>();
        for (Object o; (o = ch.readOutbound()) != null; ) {
            ByteBuf b = (ByteBuf) o;
            byte[] a = new byte[b.readableBytes()];
            b.readBytes(a);
            b.release();
            out.add(text(a));
        }
        return out;
    }
}
