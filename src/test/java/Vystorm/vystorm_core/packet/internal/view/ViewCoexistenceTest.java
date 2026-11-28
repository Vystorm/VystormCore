package Vystorm.vystorm_core.packet.internal.view;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Core's pair next to a foreign pair that uses the same places in the pipeline (VystormAntiXray 0.1.2 and the 0.2.0
 * own path: object side added after the encoder, byte side before it, with the names {@code vystorm_antixray_object}
 * and {@code vystorm_antixray_bytes}). Whoever attaches first, Core's pair ends innermost after its repair, so the
 * foreign object side judges before Core's and the foreign byte side rewrites after Core's (an old mask runs last), and
 * both keep their own tags.
 */
class ViewCoexistenceTest {
    static final String AX_OBJECT = "vystorm_antixray_object", AX_BYTES = "vystorm_antixray_bytes";

    /** A stand-in for the old plugin's pair: drops "axdrop:" packets, prefixes "ax " to chunk texts it was told about. */
    static final class Foreign {
        String pendingKind;
        final ChannelHandler object = new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (msg instanceof String s && s.startsWith("axdrop:")) { promise.trySuccess(); return; }
                if (msg instanceof String s && s.startsWith("chunk:")) pendingKind = "chunk";
                try { ctx.write(msg, promise); } finally { pendingKind = null; }
            }
        };
        final ChannelHandler bytes = new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (!(msg instanceof ByteBuf b) || b.getByte(b.readerIndex()) != FakeWire.CHUNK) { ctx.write(msg, promise); return; }
                byte[] a = new byte[b.readableBytes()];
                b.getBytes(b.readerIndex(), a);
                b.release();
                String text = FakeWire.text(a);
                byte[] out = FakeWire.bytes(FakeWire.CHUNK, (pendingKind != null ? "ax " : "ax-untagged ") + text);
                ctx.write(ctx.alloc().buffer(out.length).writeBytes(out), promise);
            }
        };

        void attach(EmbeddedChannel ch) {
            for (String n : List.of(AX_OBJECT, AX_BYTES)) if (ch.pipeline().get(n) != null) ch.pipeline().remove(n);
            ch.pipeline().addBefore("encoder", AX_BYTES, bytes);
            ch.pipeline().addAfter("encoder", AX_OBJECT, object);
        }
    }

    static EmbeddedChannel bare() {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("encoder", new FakeWire.Encoder());
        ch.pipeline().addLast("unbundler", new ChannelOutboundHandlerAdapter());
        return ch;
    }

    static void core(EmbeddedChannel ch, ViewTapTest.FakeRules rules) {
        ViewTapTest.attach(rules, ViewTapTest.engine(1000), ViewTapTest.viewer(), ch);
        ch.runPendingTasks();
    }

    static List<String> around(EmbeddedChannel ch) {
        List<String> names = ch.pipeline().names();
        return names.subList(0, names.indexOf("unbundler") + 1);
    }

    @Test
    void givenTheForeignPairFirst_thenCoreGoesInside_andTheOldMaskRunsLastOnTheBytes() {
        EmbeddedChannel ch = bare();
        new Foreign().attach(ch);
        ViewTapTest.FakeRules rules = new ViewTapTest.FakeRules();
        core(ch, rules);
        assertEquals(List.of(AX_BYTES, ViewTap.BYTES, "encoder", ViewTap.OBJECT, AX_OBJECT, "unbundler"), around(ch));
        ch.writeAndFlush("chunk:a");
        ch.writeAndFlush("axdrop:sound");
        ch.writeAndFlush("drop:sound");
        ch.writeAndFlush("keepalive");
        assertEquals(List.of("ax masked chunk:a", "keepalive"), FakeWire.drain(ch));
    }

    @Test
    void givenTheForeignPairAfterCore_thenTheRepairPutsCoreInsideAgain_withAHeldChunkKeptInOrder() {
        EmbeddedChannel ch = bare();
        ViewTapTest.FakeRules rules = new ViewTapTest.FakeRules();
        core(ch, rules);
        rules.missing.add("chunk:held");
        ch.writeAndFlush("chunk:held");
        ch.writeAndFlush("behind");
        new Foreign().attach(ch); // the old plugin's join handler re-inserts its pair next to the encoder
        assertEquals(List.of(ViewTap.BYTES, AX_BYTES, "encoder", AX_OBJECT, ViewTap.OBJECT, "unbundler"), around(ch));
        ViewTap.repairLater(ch);
        ch.runPendingTasks();
        assertEquals(List.of(AX_BYTES, ViewTap.BYTES, "encoder", ViewTap.OBJECT, AX_OBJECT, "unbundler"), around(ch));
        assertTrue(FakeWire.drain(ch).isEmpty(), "still held");
        rules.missing.clear();
        ViewTapTest.tick(ch);
        // the held chunk went out through Core after the foreign object side had passed it untagged earlier
        assertEquals(List.of("ax-untagged masked chunk:held", "behind"), FakeWire.drain(ch));
        ch.writeAndFlush("chunk:b");
        assertEquals(List.of("ax masked chunk:b"), FakeWire.drain(ch));
    }

    @Test
    void givenCoreLeaves_thenTheForeignPairStaysAndWorksAlone() {
        EmbeddedChannel ch = bare();
        new Foreign().attach(ch);
        core(ch, new ViewTapTest.FakeRules());
        ViewTap.detach(ch);
        ch.runPendingTasks();
        assertEquals(List.of(AX_BYTES, "encoder", AX_OBJECT, "unbundler"), around(ch));
        assertFalse(ViewTap.attached(ch));
        ch.writeAndFlush("chunk:a");
        assertEquals(List.of("ax chunk:a"), FakeWire.drain(ch));
    }
}
