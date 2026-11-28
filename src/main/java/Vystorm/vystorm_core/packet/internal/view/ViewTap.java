package Vystorm.vystorm_core.packet.internal.view;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The two handlers of the packet view in one player's connection (moved from VystormAntiXray's {@code platform.Tap}).
 * {@code OBJECT} sits right after the server's {@code encoder} in the pipeline (it sees packet objects right before they
 * are encoded, after the server's {@code unbundler}, so it sees single packets): it drops packets the layers judge,
 * holds chunk packets a layer is not ready for (and every packet behind them, so nothing overtakes a held chunk) for up
 * to the layer's hold time, follows the client's world through the login and respawn packets in the order the client
 * gets them, and tags the packets the byte side must rewrite. {@code BYTES} sits right before the encoder (after it in
 * the flow of a write, before compression) and sees encoded buffers: it checks every buffer's packet id, so a buffer some
 * other handler put in between passes unchanged when it is not a chunk, light or section packet, and such a packet is
 * rewritten even when it arrives without the tag.
 *
 * <p>Core's pair is the innermost pair: its object side directly after the encoder, its byte side directly before it.
 * A foreign pair that uses the same places (VystormAntiXray 0.1.x and 0.2.0 on their own path) ends up outside Core's
 * pair after {@link #repairOrder}: its object side runs before Core's, its byte side after Core's (so an old mask
 * still runs last on the bytes).
 *
 * <p>Internal: not part of the public API.
 */
public final class ViewTap {
    public static final String OBJECT = "vystorm_core_view_object", BYTES = "vystorm_core_view_bytes";
    static final String ENCODER = "encoder", UNBUNDLER = "unbundler";

    private final ViewRules rules;
    private final ViewEngine engine;
    final ViewerSlot viewer;
    /** Set by the object side during its write, read by the byte side (event loop only). */
    private Tag pending;
    private final ArrayDeque<Held> held = new ArrayDeque<>();
    private boolean drainScheduled;
    /** The world of the packets arriving now (after the last login or respawn packet that arrived); event loop only. */
    private UUID arriving;
    final ObjectSide objectSide = new ObjectSide();
    final ByteSide byteSide = new ByteSide();

    /** A packet waiting behind a held chunk, with the world it belongs to; {@code since} when its own hold started. */
    private record Held(Object msg, ChannelPromise promise, long deadline, long since, UUID world) {}

    /** What the byte side does with the next buffer of this kind, and the world of the packet. */
    private record Tag(ViewKind kind, UUID world, boolean unchanged) {}

    private ViewTap(ViewRules rules, ViewEngine engine, ViewerSlot viewer) {
        this.rules = rules;
        this.engine = engine;
        this.viewer = viewer;
        this.arriving = viewer.state.world();
    }

    /**
     * Puts a new handler pair bound to this viewer into the connection; a pair already there (an earlier join of this
     * connection, or an earlier start of Core) is replaced, and packets it still held move over in order, so the
     * connection always ends with exactly one current pair. Runs on the channel's event loop: right now when called
     * there, otherwise queued, which is before any packet written after this call. Returns why not when that is clear
     * at once; a failure found on the event loop goes to {@code onFailure}.
     */
    public static String attach(ViewRules rules, ViewEngine engine, ViewerSlot viewer, Channel ch, Consumer<String> onFailure) {
        if (!ch.pipeline().names().contains(ENCODER)) return "no encoder in the connection " + ch.pipeline().names();
        ViewTap t = new ViewTap(rules, engine, viewer);
        Runnable install = () -> {
            String why;
            try { why = t.install(ch.pipeline()); } catch (RuntimeException e) { why = e.toString(); }
            if (why != null) onFailure.accept(why);
        };
        if (ch.eventLoop().inEventLoop()) install.run(); else ch.eventLoop().execute(install);
        return null;
    }

    /** Event loop. */
    private String install(ChannelPipeline p) {
        // nothing is touched when the pair cannot go in (an old pair stays as it is, with what it holds)
        if (p.get(ENCODER) == null) return "no encoder in the connection " + p.names();
        ChannelHandler oldObject = p.get(OBJECT), oldBytes = p.get(BYTES);
        if (oldObject instanceof ObjectSide o && o.tap() != this) {
            held.addAll(o.tap().held);
            o.tap().held.clear();
        }
        // a pair of another load of Core (another class) is removed too; its own code releases what it held
        if (oldObject != null) try { p.remove(oldObject); } catch (RuntimeException gone) { }
        if (oldBytes != null) try { p.remove(oldBytes); } catch (RuntimeException gone) { }
        try {
            // the byte side first: an object side never runs without a byte side behind it
            p.addBefore(ENCODER, BYTES, byteSide);
            p.addAfter(ENCODER, OBJECT, objectSide);
        } catch (RuntimeException failure) {
            if (p.get(OBJECT) == objectSide) try { p.remove(objectSide); } catch (RuntimeException gone) { }
            if (p.get(BYTES) == byteSide) try { p.remove(byteSide); } catch (RuntimeException gone) { }
            releaseHeld();
            return "the handlers could not be added (" + failure + ")";
        }
        repairOrder(p);
        if (!held.isEmpty()) schedule(p.context(objectSide));
        return null;
    }

    /**
     * Takes the handlers out (the last layer left, or Core stops). Held packets go out first, in order and through the
     * layers as they decide now (a held chunk goes out as at the end of its hold time).
     */
    public static void detach(Channel ch) {
        if (ch.eventLoop().inEventLoop()) detachNow(ch); else ch.eventLoop().execute(() -> detachNow(ch));
    }

    /** {@link #detach} on the connection's event loop (the caller is there). */
    static void detachNow(Channel ch) {
        ChannelPipeline p = ch.pipeline();
        ChannelHandlerContext oc = p.context(OBJECT);
        if (oc != null && oc.handler() instanceof ObjectSide o) o.tap().flushHeld(oc);
        for (String n : List.of(OBJECT, BYTES)) try { if (p.get(n) != null) p.remove(n); } catch (RuntimeException ignored) { }
    }

    /** Sends what the pair holds now (event loop of {@code ch}): judged as at the end of the hold time; the pair stays. */
    static void flushNow(Channel ch) {
        ChannelHandlerContext oc = ch.pipeline().context(OBJECT);
        if (oc != null && oc.handler() instanceof ObjectSide o) o.tap().flushHeld(oc);
    }

    /** Whether Core's pair is in this connection (any thread; a snapshot). */
    public static boolean attached(Channel ch) { return ch.pipeline().get(OBJECT) != null || ch.pipeline().get(BYTES) != null; }

    /** Queues {@link #repairOrder} on the connection's event loop (after a foreign handler may have moved in). */
    public static void repairLater(Channel ch) {
        ch.eventLoop().execute(() -> {
            ChannelHandlerContext oc = ch.pipeline().context(OBJECT);
            if (oc != null && oc.handler() instanceof ObjectSide o) o.tap().repairOrder(ch.pipeline());
        });
    }

    /**
     * Puts the pair back to its places when something moved in between: the server puts its {@code unbundler} back
     * right after the {@code encoder} when a connection returns to play from configuration, and a foreign pair
     * (VystormAntiXray's own handlers) inserts itself next to the encoder too. The object side goes back to directly
     * after the encoder (so the unbundler is behind it again and bundled packets are taken apart before the view sees
     * them), the byte side to directly before it. The server's handlers stay where they are; what the object side holds
     * stays held. True if it moved.
     */
    boolean repairOrder(ChannelPipeline p) {
        List<String> names = p.names();
        int e = names.indexOf(ENCODER), o = names.indexOf(OBJECT), b = names.indexOf(BYTES);
        if (e < 0 || o < 0 || p.get(OBJECT) != objectSide) return false;
        boolean moved = false;
        if (o != e + 1) {
            moving = true;
            try {
                p.remove(objectSide);
                p.addAfter(ENCODER, OBJECT, objectSide);
            } finally { moving = false; }
            moved = true;
        }
        if (b >= 0 && b != e - 1 && p.get(BYTES) == byteSide) {
            p.remove(byteSide);
            p.addBefore(ENCODER, BYTES, byteSide);
            moved = true;
        }
        if (moved && !held.isEmpty()) { drainScheduled = false; schedule(p.context(objectSide)); }
        return moved;
    }

    /** Whether the server's unbundler runs before the object side (its normal place: nearer the tail). */
    static boolean unbundlerOnTailSide(ChannelPipeline p) {
        List<String> names = p.names();
        int o = names.indexOf(OBJECT), u = names.indexOf(UNBUNDLER);
        return o >= 0 && u > o;
    }

    /** True while the object side is taken out and put back by {@link #repairOrder} (its held packets stay). */
    private boolean moving;

    /** Sharable only so that {@link #repairOrder} can put the same instance back; it is in one pipeline at a time. */
    @ChannelHandler.Sharable
    final class ObjectSide extends ChannelOutboundHandlerAdapter {
        ViewTap tap() { return ViewTap.this; }

        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            UUID world = arriving;
            try { UUID next = rules.worldOf(viewer, msg); if (next != null) arriving = next; }
            catch (RuntimeException | LinkageError e) { engine.warn("world", "Packet view: the world of a " + msg.getClass().getSimpleName() + " could not be read: " + e); }
            long holdNs;
            try { holdNs = rules.hold(viewer, msg, world); }
            catch (RuntimeException | LinkageError e) { engine.warn("hold", "Packet view: the hold check failed (" + e + "); the chunk goes out without waiting"); holdNs = 0; }
            // the wait of a held chunk counts from the moment it was written, so a burst of chunks that are not ready
            // waits about the hold time in all, not the hold time per chunk
            long deadline = 0, since = 0;
            if (holdNs > 0) {
                since = System.nanoTime();
                deadline = since + holdNs;
                engine.stats.inc("hold.chunks");
            }
            if (held.isEmpty() && deadline == 0) { process(ctx, msg, promise, world, true); return; }
            held.add(new Held(msg, promise, deadline, since, world));
            schedule(ctx);
        }

        /** Removed by someone else than {@link #detach}: what is held goes out in order (a chunk only if the byte side is still there). */
        @Override public void handlerRemoved(ChannelHandlerContext ctx) { if (!moving) flushHeld(ctx); }

        @Override public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            releaseHeld();
            super.close(ctx, promise);
        }
    }

    /** Judges one packet and passes it on; returns the object side's context to go on with (new after a repair). */
    private ChannelHandlerContext process(ChannelHandlerContext ctx, Object msg, ChannelPromise promise, UUID world, boolean canRewrite) {
        try { rules.passing(viewer, msg); }
        catch (RuntimeException | LinkageError e) { engine.warn("passing", "Packet view: following the world of " + viewer.name + " failed on " + msg.getClass().getSimpleName() + ": " + e); }
        Object d;
        try { d = rules.decide(viewer, msg, world); }
        catch (RuntimeException | LinkageError e) {
            // fail-closed for this packet only: the layers could not tell whether it names a hidden cell
            engine.warn("decide", "Packet view: judging a " + msg.getClass().getSimpleName() + " failed (" + e + "); that packet was not sent");
            engine.stats.inc("dropped.decideError");
            d = ViewRules.DROP;
        }
        if (d instanceof ViewKind && !canRewrite) {
            engine.stats.inc("dropped.noByteSide");
            d = ViewRules.DROP;
        }
        if (d == ViewRules.DROP) { ReferenceCountUtil.release(msg); promise.trySuccess(); return ctx; }
        if (d == ViewRules.UNMASKED) {
            // sent with its true states on purpose (sendReal): its buffer passes the byte side unchanged
            pending = new Tag(null, world, true);
            try { ctx.write(msg, promise); } finally { pending = null; }
        } else if (d instanceof ViewKind k) {
            pending = new Tag(k, world, false);
            try { ctx.write(msg, promise); } finally { pending = null; }
        } else if (rules.isBundle(d) && unbundlerOnTailSide(ctx.pipeline())) {
            // a bundle that waited behind a held chunk while the order was repaired: the unbundler is behind this
            // handler now, so the bundle (already judged) is taken apart here with the server's own unbundler
            ChannelHandler unbundler = ctx.pipeline().get(UNBUNDLER);
            try { ((io.netty.channel.ChannelOutboundHandler) unbundler).write(ctx, d, promise); }
            catch (Exception e) { ReferenceCountUtil.release(d); promise.tryFailure(e); }
        } else ctx.write(d, promise);
        if (rules.pipelineEvent(msg) && !ctx.isRemoved() && repairOrder(ctx.pipeline())) {
            engine.stats.inc("order.repaired");
            return ctx.pipeline().context(objectSide);
        }
        return ctx;
    }

    private void schedule(ChannelHandlerContext ctx) {
        if (drainScheduled) return;
        drainScheduled = true;
        ctx.executor().schedule(() -> { drainScheduled = false; drain(ctx); }, 50, TimeUnit.MILLISECONDS);
    }

    /** Sends held packets whose layers are ready now or whose time is up, in order. */
    private void drain(ChannelHandlerContext ctx) {
        if (ctx.isRemoved()) {
            ctx = ctx.pipeline().context(objectSide); // put back elsewhere by repairOrder, or gone (moved to a new pair, flushed)
            if (ctx == null) return;
        }
        boolean wrote = false;
        while (!held.isEmpty()) {
            Held h = held.peek();
            if (h.deadline != 0 && System.nanoTime() < h.deadline) {
                boolean still;
                try { still = rules.mustHold(viewer, h.msg, h.world); } catch (RuntimeException | LinkageError e) { still = false; }
                if (still) break;
            }
            held.poll();
            if (h.deadline != 0) engine.stats.sample("hold.millis", Math.max(0, (System.nanoTime() - h.since) / 1e6));
            if (h.deadline != 0 && System.nanoTime() >= h.deadline) engine.stats.inc("hold.timeouts");
            if (!ctx.channel().isActive()) { ReferenceCountUtil.release(h.msg); h.promise.tryFailure(new ClosedChannelException()); continue; }
            ctx = process(ctx, h.msg, h.promise, h.world, true);
            wrote = true;
        }
        if (wrote) ctx.flush();
        if (!held.isEmpty()) schedule(ctx);
    }

    /**
     * Everything held goes out now, in order. A packet the byte side would rewrite is dropped instead when the byte side
     * is no longer in the pipeline (nothing could rewrite it). A closed connection fails the writes and frees the packets.
     */
    void flushHeld(ChannelHandlerContext ctx) {
        if (held.isEmpty()) return;
        if (!ctx.channel().isActive()) { releaseHeld(); return; }
        boolean bytesThere = ctx.pipeline().get(BYTES) == byteSide;
        while (!held.isEmpty()) {
            Held h = held.poll();
            try { ctx = process(ctx, h.msg, h.promise, h.world, bytesThere); }
            catch (RuntimeException e) { ReferenceCountUtil.release(h.msg); h.promise.tryFailure(e); }
        }
        ctx.flush();
    }

    private void releaseHeld() {
        while (!held.isEmpty()) {
            Held h = held.poll();
            ReferenceCountUtil.release(h.msg);
            h.promise.tryFailure(new ClosedChannelException());
        }
    }

    /** Packets held now (event loop; for stats and tests). */
    int heldCount() { return held.size(); }

    /** The packet id at the start of an encoded buffer, -1 when it does not start with one. */
    static int packetId(ByteBuf b) {
        int n = 0, at = b.readerIndex(), end = b.writerIndex();
        for (int i = 0; i < 5; i++) {
            if (at + i >= end) return -1;
            int x = b.getByte(at + i);
            n |= (x & 0x7f) << (7 * i);
            if ((x & 0x80) == 0) return n;
        }
        return -1;
    }

    @ChannelHandler.Sharable
    final class ByteSide extends ChannelOutboundHandlerAdapter {
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (!(msg instanceof ByteBuf b)) { ctx.write(msg, promise); return; }
            ViewKind kind = rules.kindOf(packetId(b));
            Tag t = pending;
            if (kind == null) {
                // not a chunk, light or section packet: unchanged; a tag stays for its own buffer
                if (t != null) engine.stats.inc("bytes.otherBeforeTagged");
                ctx.write(msg, promise);
                return;
            }
            pending = null;
            if (t != null && t.unchanged) { ctx.write(msg, promise); return; }
            UUID world;
            if (t != null && t.kind == kind) world = t.world;
            else {
                // a chunk, light or section packet without the object side's tag: another handler sent it past the
                // object side or out of order; judged here like a tagged one
                if (!rules.filtering(viewer)) { ctx.write(msg, promise); return; }
                engine.stats.inc("bytes.untagged");
                world = viewer.state.world();
            }
            byte[] whole = new byte[b.readableBytes()];
            b.getBytes(b.readerIndex(), whole);
            byte[] out;
            try { out = rules.rewrite(viewer, kind, whole, world); }
            catch (RuntimeException | LinkageError failure) {
                // never the original bytes of a packet that was to be masked, never a half-rewritten one
                engine.warn("rewrite " + kind, "Packet view: rewriting a " + kind + " packet for " + viewer.name + " failed (" + failure + "); that packet was not sent");
                engine.stats.inc("dropped.rewriteError");
                out = null;
            }
            if (out != null) {
                try { rules.delivered(viewer, kind, whole, world); } catch (RuntimeException ignored) { }
            }
            if (out == whole) { ctx.write(msg, promise); return; }
            b.release();
            if (out == null) { promise.trySuccess(); return; }
            ByteBuf nb = ctx.alloc().buffer(out.length);
            nb.writeBytes(out);
            ctx.write(nb, promise);
        }
    }
}
