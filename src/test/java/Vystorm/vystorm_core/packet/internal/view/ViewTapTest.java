package Vystorm.vystorm_core.packet.internal.view;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The connection handlers on a Netty test channel with a fake encoder ({@link FakeWire}: packet "objects" are strings,
 * chunk/light/section packets get their own packet id): ordering behind a held chunk, the hold from the moment of
 * writing, drops, the byte rewrite by packet id, and the robustness cases: a repeated attach, detach with held packets,
 * a world change behind a held chunk, foreign handlers between encoder and byte side, a protocol switch that puts the
 * server's unbundler back in front of the filter.
 */
class ViewTapTest {
    static UUID world(String name) { return UUID.nameUUIDFromBytes(("world:" + name).getBytes(StandardCharsets.UTF_8)); }

    static String name(UUID w) { for (String n : List.of("A", "B", "C")) if (world(n).equals(w)) return n; return String.valueOf(w); }

    /**
     * "chunk:..." is a chunk packet, "light:..." a light update, "drop:..." is dropped, "respawn:X" puts the client into
     * world X, "start_configuration" sends it back to configuration, "explode:..." makes the filter throw. The rewrite
     * writes "masked "/"closed "/"zeroed " in front and logs viewer, world and text; "unreadable" in a text is a packet
     * the filter cannot read (it answers null), "boom" makes the rewrite throw.
     */
    static final class FakeRules implements ViewRules {
        final Set<String> missing = ConcurrentHashMap.newKeySet();
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        final List<String> holdChecks = Collections.synchronizedList(new ArrayList<>());
        volatile boolean filtering = true;

        /** The hold time of the engine the channel was built with (set by {@link #channel}). */
        volatile long holdNanos;

        @Override public long hold(ViewerSlot v, Object msg, UUID w) { return holdNanos > 0 && mustHold(v, msg, w) ? holdNanos : 0; }

        @Override public boolean mustHold(ViewerSlot v, Object msg, UUID w) {
            if (!(msg instanceof String s) || !s.startsWith("chunk:")) return false;
            holdChecks.add(name(w) + ":" + s);
            return missing.contains(s);
        }

        @Override public Object decide(ViewerSlot v, Object msg, UUID w) {
            if (!(msg instanceof String s)) return msg;
            if (s.startsWith("explode:")) throw new IllegalStateException("explode");
            if (s.startsWith("drop:")) return ViewRules.DROP;
            if (s.startsWith("light:")) return ViewKind.LIGHT;
            if (s.startsWith("chunk:")) return ViewKind.CHUNK;
            if (s.startsWith("bundle:")) {
                // like the filter: the bundled packets are judged one by one, dropped ones leave the bundle
                List<String> keep = new ArrayList<>();
                for (String part : s.substring(7).split(";")) if (decide(v, part, w) != ViewRules.DROP) keep.add(part);
                String out = "bundle:" + String.join(";", keep);
                return out.equals(s) ? msg : out;
            }
            return msg;
        }

        @Override public boolean isBundle(Object msg) { return msg instanceof String s && s.startsWith("bundle:"); }

        @Override public ViewKind kindOf(int id) { return FakeWire.kind(id); }

        @Override public boolean filtering(ViewerSlot v) { return filtering; }

        @Override public byte[] rewrite(ViewerSlot v, ViewKind kind, byte[] whole, UUID w) {
            String text = FakeWire.text(whole);
            if (text.contains("boom")) throw new ArrayIndexOutOfBoundsException("boom");
            log.add(v.name + "@" + name(w) + ":" + text);
            if (text.contains("unreadable")) return null;
            return FakeWire.bytes(whole[0], (kind == ViewKind.CHUNK ? (missing.contains(text) ? "closed " : "masked ") : "zeroed ") + text);
        }

        @Override public UUID worldOf(ViewerSlot v, Object msg) { return msg instanceof String s && s.startsWith("respawn:") ? world(s.substring(8)) : null; }

        @Override public void passing(ViewerSlot v, Object msg) {
            if ("start_configuration".equals(msg)) v.state.clear();
            UUID w = worldOf(v, msg);
            if (w != null && !w.equals(v.state.world())) v.state.world(w, 0, 16);
        }

        @Override public boolean pipelineEvent(Object msg) { return !(msg instanceof String s) || s.startsWith("bundle:"); }
    }

    /** The hold time per engine (the router asks its layers; the fake rules take it from here). */
    static final java.util.Map<ViewEngine, Long> HOLD = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static ViewEngine engine(long holdMillis) {
        ViewEngine e = new ViewEngine(new Vystorm.vystorm_core.packet.internal.wire.WireTables(new boolean[] {true, false}, new boolean[2]), new ViewCounters(), false);
        HOLD.put(e, holdMillis * 1_000_000L);
        return e;
    }

    static ViewerSlot viewer(String name) { return new ViewerSlot(new Vystorm.vystorm_core.packet.view.ViewerRef(UUID.randomUUID(), name, 0), null, world("A"), -64, 24, p -> { }); }

    static ViewerSlot viewer() { return viewer("t"); }

    static void attach(ViewRules rules, ViewEngine engine, ViewerSlot v, EmbeddedChannel ch) {
        hold(rules, engine);
        assertNull(ViewTap.attach(rules, engine, v, ch, why -> fail("attach failed on the event loop: " + why)));
    }

    /** The fake rules hold for the time the engine was built with. */
    static void hold(ViewRules rules, ViewEngine engine) { if (rules instanceof FakeRules f) f.holdNanos = HOLD.getOrDefault(engine, 0L); }

    static EmbeddedChannel channel(FakeRules rules, ViewEngine engine) { return channel(rules, engine, viewer()); }

    static EmbeddedChannel channel(FakeRules rules, ViewEngine engine, ViewerSlot v) {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("encoder", new FakeWire.Encoder());
        attach(rules, engine, v, ch);
        return ch;
    }

    static List<String> drain(EmbeddedChannel ch) { return FakeWire.drain(ch); }

    static void tick(EmbeddedChannel ch) {
        ch.advanceTimeBy(60, TimeUnit.MILLISECONDS);
        ch.runScheduledPendingTasks();
    }

    static long count(EmbeddedChannel ch, String name) { return ch.pipeline().names().stream().filter(name::equals).count(); }

    // ---- normal operation (unchanged behaviour) --------------------------------------------------------------------

    @Test
    void givenNoHold_thenPacketsPassInOrder_dropsVanish_andOnlyChunkLightAndSectionPacketsAreRewritten() {
        FakeRules rules = new FakeRules();
        EmbeddedChannel ch = channel(rules, engine(1000));
        ch.writeAndFlush("keepalive");
        ch.writeAndFlush("drop:sound");
        ch.writeAndFlush("chunk:a");
        ch.writeAndFlush("light:a");
        ch.writeAndFlush("entity");
        assertEquals(List.of("keepalive", "masked chunk:a", "zeroed light:a", "entity"), drain(ch));
    }

    @Test
    void givenAChunkWithoutSurvey_thenItAndEverythingBehindItWait_andGoOutInOrderWhenTheSurveyArrives() {
        FakeRules rules = new FakeRules();
        rules.missing.add("chunk:b");
        EmbeddedChannel ch = channel(rules, engine(1000));
        ch.writeAndFlush("chunk:a");
        ch.writeAndFlush("chunk:b");
        ch.writeAndFlush("block-update-in-b");
        ch.writeAndFlush("chunk:c");
        assertEquals(List.of("masked chunk:a"), drain(ch), "b and everything after it waits");
        rules.missing.remove("chunk:b");
        tick(ch);
        assertEquals(List.of("masked chunk:b", "block-update-in-b", "masked chunk:c"), drain(ch));
    }

    @Test
    void givenABurstOfChunksWithoutSurvey_thenTheyAllGoOutClosedAfterOneHoldTime_notOneHoldEach() throws Exception {
        FakeRules rules = new FakeRules();
        for (int i = 0; i < 20; i++) rules.missing.add("chunk:" + i);
        ViewEngine e = engine(200);
        EmbeddedChannel ch = channel(rules, e);
        for (int i = 0; i < 20; i++) ch.writeAndFlush("chunk:" + i);
        assertTrue(drain(ch).isEmpty());
        Thread.sleep(260); // the deadline is real time (System.nanoTime), the drain task runs on the channel's clock
        tick(ch);
        List<String> out = drain(ch);
        assertEquals(20, out.size(), "all twenty after one hold time: " + out);
        assertEquals("closed chunk:0", out.get(0));
        assertEquals(20, e.stats.get("hold.timeouts"));
    }

    @Test
    void givenAHoldTimeOfZero_thenNothingIsHeld() {
        FakeRules rules = new FakeRules();
        rules.missing.add("chunk:a");
        EmbeddedChannel ch = channel(rules, engine(0));
        ch.writeAndFlush("chunk:a");
        assertEquals(List.of("closed chunk:a"), drain(ch));
    }

    @Test
    void givenHeldPackets_whenTheChannelCloses_thenTheirPromisesFail_andHeldBuffersAreFreed() {
        FakeRules rules = new FakeRules();
        rules.missing.add("chunk:a");
        EmbeddedChannel ch = channel(rules, engine(1000));
        var f = ch.writeAndFlush("chunk:a");
        ByteBuf raw = Unpooled.buffer().writeBytes(FakeWire.bytes(0, "raw"));
        ch.writeAndFlush(raw);
        ch.close();
        assertTrue(f.isDone() && !f.isSuccess());
        assertEquals(0, raw.refCnt(), "nothing leaks");
    }

    // ---- finding 6: failures belong to one packet; the byte side checks the packet id ------------------------------

    @Test
    void givenARewriteThatThrows_thenThatPacketIsNotSent_theWriteSucceeds_andNothingSwitchesOff() {
        FakeRules rules = new FakeRules();
        ViewEngine e = engine(1000);
        EmbeddedChannel ch = channel(rules, e);
        var f = ch.writeAndFlush("chunk:boom");
        ch.writeAndFlush("keepalive");
        ch.writeAndFlush("chunk:next");
        assertEquals(List.of("keepalive", "masked chunk:next"), drain(ch), "never the original bytes of a chunk to mask");
        assertTrue(f.isDone() && f.isSuccess(), "the write itself does not fail (no disconnect)");
        assertNull(e.broken(), "no global switch-off");
        assertEquals(1, e.stats.get("dropped.rewriteError"));
    }

    @Test
    void givenAChunkTheFilterCannotRead_thenItIsNotSent_andTheNextOnesAreMaskedAsUsual() {
        FakeRules rules = new FakeRules();
        ViewEngine e = engine(1000);
        EmbeddedChannel ch = channel(rules, e);
        ch.writeAndFlush("chunk:unreadable");
        ch.writeAndFlush("chunk:b");
        assertEquals(List.of("masked chunk:b"), drain(ch));
        assertNull(e.broken());
    }

    @Test
    void givenAFilterThatThrowsOnAPacket_thenOnlyThatPacketIsDropped() {
        FakeRules rules = new FakeRules();
        ViewEngine e = engine(1000);
        EmbeddedChannel ch = channel(rules, e);
        var f = ch.writeAndFlush("explode:block-update");
        ch.writeAndFlush("keepalive");
        assertEquals(List.of("keepalive"), drain(ch));
        assertTrue(f.isSuccess());
        assertNull(e.broken());
        assertEquals(1, e.stats.get("dropped.decideError"));
    }

    /** Another plugin's byte handler, added before the encoder after this plugin: writes an extra buffer before each chunk. */
    static final class ExtraBeforeChunks extends ChannelOutboundHandlerAdapter {
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof ByteBuf b && b.getByte(b.readerIndex()) == FakeWire.CHUNK)
                ctx.write(Unpooled.buffer().writeBytes(FakeWire.bytes(FakeWire.OTHER, "extra")));
            ctx.write(msg, promise);
        }
    }

    @Test
    void givenAForeignHandlerThatWritesAnotherPacketFirst_thenThatPacketPassesUnchanged_andTheChunkIsStillMasked() {
        FakeRules rules = new FakeRules();
        ViewEngine e = engine(1000);
        EmbeddedChannel ch = channel(rules, e);
        ch.pipeline().addBefore("encoder", "foreign", new ExtraBeforeChunks());
        ch.writeAndFlush("chunk:a");
        assertEquals(List.of("extra", "masked chunk:a"), drain(ch), "the tag waits for the chunk's own buffer");
        assertNull(e.broken());
    }

    /** Another plugin's byte handler that sends buffers later (outside the write that tagged them). */
    static final class Later extends ChannelOutboundHandlerAdapter {
        final List<Object[]> queued = new ArrayList<>();
        ChannelHandlerContext ctx;

        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) { this.ctx = ctx; queued.add(new Object[] {msg, promise}); }

        void release() { for (Object[] q : queued) ctx.write(q[0], (ChannelPromise) q[1]); queued.clear(); ctx.flush(); }
    }

    @Test
    void givenAForeignHandlerThatSendsTheChunkLater_thenTheChunkArrivesWithoutTag_andIsMaskedAnyway() {
        FakeRules rules = new FakeRules();
        ViewEngine e = engine(1000);
        EmbeddedChannel ch = channel(rules, e);
        Later later = new Later();
        ch.pipeline().addBefore("encoder", "foreign", later);
        ch.writeAndFlush("chunk:a");
        ch.writeAndFlush("keepalive");
        later.release();
        assertEquals(List.of("masked chunk:a", "keepalive"), drain(ch));
        assertEquals(1, e.stats.get("bytes.untagged"));
        rules.filtering = false;
        ch.writeAndFlush("chunk:real");
        later.release();
        assertEquals(List.of("chunk:real"), drain(ch), "a viewer who is not masked gets the real chunk");
    }

    @Test
    void givenARawChunkBufferWrittenPastTheEncoder_thenItIsMaskedLikeAnyOther() {
        FakeRules rules = new FakeRules();
        EmbeddedChannel ch = channel(rules, engine(1000));
        ch.writeAndFlush(Unpooled.buffer().writeBytes(FakeWire.bytes(FakeWire.CHUNK, "chunk:wire")));
        ch.writeAndFlush(Unpooled.buffer().writeBytes(FakeWire.bytes(FakeWire.OTHER, "wire-other")));
        assertEquals(List.of("masked chunk:wire", "wire-other"), drain(ch));
    }

    // ---- finding 2: attach always ends with one current pair ---------------------------------------------------------

    @Test
    void givenAPairAlready_whenAttachedAgain_thenExactlyOnePairRemains_boundToTheNewViewer_andHeldPacketsMoveOverInOrder() {
        FakeRules rules = new FakeRules();
        ViewEngine e = engine(1000);
        ViewerSlot first = viewer("first"), second = viewer("second");
        EmbeddedChannel ch = channel(rules, e, first);
        rules.missing.add("chunk:h");
        ch.writeAndFlush("chunk:h");
        ch.writeAndFlush("teleport");
        assertTrue(drain(ch).isEmpty());
        attach(rules, e, second, ch);
        assertEquals(1, count(ch, ViewTap.OBJECT));
        assertEquals(1, count(ch, ViewTap.BYTES));
        rules.missing.clear();
        tick(ch);
        assertEquals(List.of("masked chunk:h", "teleport"), drain(ch), "nothing lost, nothing reordered");
        ch.writeAndFlush("chunk:x");
        assertEquals(List.of("masked chunk:x"), drain(ch));
        assertEquals(List.of("second@A:chunk:h", "second@A:chunk:x"), rules.log, "the new viewer's state decides");
    }

    @Test
    void givenAPairOfAnotherLoadOfThePlugin_whenAttached_thenItIsReplaced() {
        FakeRules rules = new FakeRules();
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("encoder", new FakeWire.Encoder());
        ch.pipeline().addAfter("encoder", ViewTap.OBJECT, new ChannelOutboundHandlerAdapter());
        ch.pipeline().addBefore("encoder", ViewTap.BYTES, new ChannelOutboundHandlerAdapter());
        attach(rules, engine(1000), viewer(), ch);
        assertInstanceOf(ViewTap.ObjectSide.class, ch.pipeline().get(ViewTap.OBJECT));
        assertInstanceOf(ViewTap.ByteSide.class, ch.pipeline().get(ViewTap.BYTES));
        assertEquals(1, count(ch, ViewTap.OBJECT));
        ch.writeAndFlush("chunk:a");
        assertEquals(List.of("masked chunk:a"), drain(ch));
    }

    @Test
    void givenNoEncoder_thenAttachSaysWhyAtOnce() {
        EmbeddedChannel ch = new EmbeddedChannel();
        assertNotNull(ViewTap.attach(new FakeRules(), engine(1000), viewer(), ch, why -> fail(why)));
        assertNull(ch.pipeline().get(ViewTap.OBJECT));
    }

    // ---- finding 3: detach sends what it holds, in order --------------------------------------------------------------

    @Test
    void givenHeldPackets_whenDetached_thenEverythingGoesOutInOrder_theChunkFailClosed_andNoBufferLeaks() {
        FakeRules rules = new FakeRules();
        rules.missing.add("chunk:b");
        EmbeddedChannel ch = channel(rules, engine(1000));
        ch.writeAndFlush("chunk:a");
        ch.writeAndFlush("chunk:b");
        ch.writeAndFlush("teleport");
        ByteBuf raw = Unpooled.buffer().writeBytes(FakeWire.bytes(FakeWire.OTHER, "raw"));
        ch.writeAndFlush(raw);
        ch.writeAndFlush("keepalive");
        assertEquals(List.of("masked chunk:a"), drain(ch));
        ViewTap.detach(ch);
        assertEquals(List.of("closed chunk:b", "teleport", "raw", "keepalive"), drain(ch));
        assertEquals(0, raw.refCnt());
        assertNull(ch.pipeline().get(ViewTap.OBJECT));
        assertNull(ch.pipeline().get(ViewTap.BYTES));
    }

    @Test
    void givenHeldPackets_whenSomeoneElseRemovesBothHandlers_thenOtherPacketsGoOutButNoChunkUnmasked() {
        FakeRules rules = new FakeRules();
        rules.missing.add("chunk:b");
        EmbeddedChannel ch = channel(rules, engine(1000));
        ch.writeAndFlush("chunk:b");
        ch.writeAndFlush("teleport");
        ch.pipeline().remove(ViewTap.BYTES);
        ch.pipeline().remove(ViewTap.OBJECT);
        assertEquals(List.of("teleport"), drain(ch), "nothing could mask the chunk any more: not sent");
    }

    // ---- finding 4: held packets keep the world they were written for ---------------------------------------------------

    @Test
    void givenAHeldChunk_whenTheClientChangesWorldBehindIt_thenEachChunkIsJudgedForItsOwnWorld() {
        FakeRules rules = new FakeRules();
        ViewerSlot v = viewer();
        EmbeddedChannel ch = channel(rules, engine(1000), v);
        rules.missing.add("chunk:a1");
        ch.writeAndFlush("chunk:a1");
        ch.writeAndFlush("respawn:B");
        ch.writeAndFlush("chunk:b1");
        assertTrue(drain(ch).isEmpty());
        assertEquals(world("A"), v.state.world(), "the client is still in A until the respawn packet goes out");
        rules.missing.clear();
        tick(ch);
        assertEquals(List.of("masked chunk:a1", "respawn:B", "masked chunk:b1"), drain(ch));
        assertEquals(List.of("t@A:chunk:a1", "t@B:chunk:b1"), rules.log);
        assertTrue(rules.holdChecks.contains("A:chunk:a1") && !rules.holdChecks.contains("B:chunk:a1"), rules.holdChecks.toString());
        assertEquals(world("B"), v.state.world());
    }

    // ---- finding 1: the server's unbundler after configuration ---------------------------------------------------------

    /** The server's unbundler: takes "bundle:x;y" apart; removes itself after a terminal packet (as the server's does). */
    static final class Unbundler extends ChannelOutboundHandlerAdapter {
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof String s && s.startsWith("bundle:")) {
                for (String part : s.substring(7).split(";")) ctx.write(part);
                promise.setSuccess();
                return;
            }
            ctx.write(msg, promise);
            if ("start_configuration".equals(msg)) ctx.pipeline().remove(this);
        }
    }

    /** A protocol switch task, as the server writes it into the pipeline (runs in the handler in the encoder's place). */
    static final class Task implements Consumer<ChannelHandlerContext> {
        @Override public void accept(ChannelHandlerContext ctx) {
            ctx.pipeline().replace(ctx.name(), "encoder", new ServerEncoder());
            ctx.pipeline().addAfter("encoder", "unbundler", new Unbundler());
        }
    }

    /** The server's encoder: after the terminal packet it puts an outbound-configuration handler in its place. */
    static final class ServerEncoder extends ChannelOutboundHandlerAdapter {
        final FakeWire.Encoder enc = new FakeWire.Encoder();

        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            enc.write(ctx, msg, promise);
            if ("start_configuration".equals(msg)) {
                ctx.pipeline().addAfter(ctx.name(), "outbound_config", new OutboundConfig());
                ctx.pipeline().remove(ctx.name());
            }
        }
    }

    static final class OutboundConfig extends ChannelOutboundHandlerAdapter {
        @Override @SuppressWarnings("unchecked") public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof Consumer<?> task) { ((Consumer<ChannelHandlerContext>) task).accept(ctx); promise.setSuccess(); return; }
            ctx.write(msg, promise);
        }
    }

    @Test
    void givenAReturnFromConfiguration_whenTheServerPutsItsUnbundlerInFrontOfTheFilter_thenItIsMovedBack_andBundledPacketsAreFiltered() {
        FakeRules rules = new FakeRules();
        ViewEngine e = engine(1000);
        ViewerSlot v = viewer();
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("encoder", new ServerEncoder());
        ch.pipeline().addLast("unbundler", new Unbundler());
        attach(rules, e, v, ch);
        ch.writeAndFlush("bundle:drop:spawn-in-cave;spawn-outside");
        assertEquals(List.of("spawn-outside"), drain(ch), "play: bundles are taken apart before the filter");
        ch.writeAndFlush("start_configuration");
        assertNull(ch.pipeline().get("unbundler"));
        assertNotNull(ch.pipeline().get("outbound_config"));
        ch.writeAndFlush(new Task()); // back to play: the server adds its unbundler right after the encoder
        List<String> names = ch.pipeline().names();
        assertTrue(names.indexOf(ViewTap.BYTES) < names.indexOf("encoder") && names.indexOf("encoder") < names.indexOf(ViewTap.OBJECT)
                && names.indexOf(ViewTap.OBJECT) < names.indexOf("unbundler"), names.toString());
        assertEquals(1, e.stats.get("order.repaired"));
        ch.writeAndFlush("bundle:drop:spawn-in-cave;spawn-outside;chunk:c");
        assertEquals(List.of("start_configuration", "spawn-outside", "masked chunk:c"), drain(ch), "after the return the bundle is still filtered");
    }

    @Test
    void givenTheUnbundlerInFrontOfTheFilterWithoutAProtocolSwitch_thenTheFirstBundleMovesItBack() {
        FakeRules rules = new FakeRules();
        ViewEngine e = engine(1000);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("encoder", new FakeWire.Encoder());
        attach(rules, e, viewer(), ch);
        ch.pipeline().addAfter("encoder", "unbundler", new Unbundler()); // wrong place
        ch.writeAndFlush("bundle:drop:in-cave;entity");
        List<String> names = ch.pipeline().names();
        assertTrue(names.indexOf(ViewTap.OBJECT) < names.indexOf("unbundler"), names.toString());
        assertEquals(List.of("entity"), drain(ch), "the first bundle is filtered too, before the move");
    }

    @Test
    void givenBundlesHeldBehindAChunk_whenTheOrderIsRepairedOnTheFirst_thenTheLaterOnesAreStillTakenApartAndFiltered() {
        FakeRules rules = new FakeRules();
        ViewEngine e = engine(1000);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("encoder", new FakeWire.Encoder());
        attach(rules, e, viewer(), ch);
        ch.pipeline().addAfter("encoder", "unbundler", new Unbundler()); // wrong place
        rules.missing.add("chunk:b");
        ch.writeAndFlush("chunk:b");
        ch.writeAndFlush("bundle:drop:x;one");
        ch.writeAndFlush("bundle:drop:y;two;chunk:c");
        ch.writeAndFlush("keepalive");
        assertTrue(drain(ch).isEmpty());
        rules.missing.clear();
        tick(ch);
        assertEquals(List.of("masked chunk:b", "one", "two", "masked chunk:c", "keepalive"), drain(ch), "in order, nothing hidden, no bundle reached the encoder whole");
        List<String> names = ch.pipeline().names();
        assertTrue(names.indexOf(ViewTap.OBJECT) < names.indexOf("unbundler"), names.toString());
        assertEquals(1, e.stats.get("order.repaired"));
    }

    @Test
    void givenAPairAlready_whenTheEncoderIsMissing_thenAttachSaysWhy_andTheOldPairStaysWithWhatItHolds() {
        FakeRules rules = new FakeRules();
        ViewEngine e = engine(1000);
        EmbeddedChannel ch = channel(rules, e, viewer("first"));
        rules.missing.add("chunk:h");
        ch.writeAndFlush("chunk:h");
        ch.pipeline().remove("encoder");
        assertNotNull(ViewTap.attach(rules, e, viewer("second"), ch, why -> fail(why)));
        assertInstanceOf(ViewTap.ObjectSide.class, ch.pipeline().get(ViewTap.OBJECT));
        assertEquals(1, count(ch, ViewTap.OBJECT));
        assertEquals("first", ((ViewTap.ObjectSide) ch.pipeline().get(ViewTap.OBJECT)).tap().viewer.name);
    }

    @Test
    void givenAReturnToConfiguration_thenTheViewerStateIsEmptied() {
        FakeRules rules = new FakeRules();
        ViewerSlot v = viewer();
        EmbeddedChannel ch = channel(rules, engine(1000), v);
        v.state.sent(0, 0);
        ch.writeAndFlush("start_configuration");
        assertFalse(v.state.layer().holds(0, 0), "the client drops its world in configuration");
        drain(ch);
    }

    @Test
    void packetIdsAreReadFromTheBufferStartWithoutConsumingIt() {
        ByteBuf b = Unpooled.buffer().writeBytes(new byte[] {(byte) 0xAE, 0x02, 9});
        assertEquals(302, ViewTap.packetId(b));
        assertEquals(0, b.readerIndex());
        assertEquals(-1, ViewTap.packetId(Unpooled.buffer()));
        assertEquals(-1, ViewTap.packetId(Unpooled.buffer().writeBytes(new byte[] {(byte) 0x80})));
    }
}
