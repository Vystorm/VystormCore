package Vystorm.vystorm_core.licensing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.lifecycle.CoreLifecycle;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

public class ModuleSessionTest {
    public static int starts, stops;
    public static boolean failStart;
    public static BooleanSupplier authorization;

    static final class Harness implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<CoreApi> coreApi = mockStatic(CoreApi.class);
        final MockedStatic<ModuleTransport> transport = mockStatic(ModuleTransport.class);
        final Plugin owner = mock(Plugin.class), core = mock(Plugin.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
        final Semaphore scheduled = new Semaphore(0);
        final List<String> actions = new ArrayList<>();
        final List<CompletableFuture<ModuleTransport.Reply>> replies = new ArrayList<>();
        final List<LicensedModules.State> states = new ArrayList<>();
        Runnable tick;
        LicensedModules.Session session;

        Harness() {
            starts = stops = 0; failStart = false; authorization = () -> false;
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            coreApi.when(CoreApi::plugin).thenReturn(core);
            when(owner.isEnabled()).thenReturn(true); when(core.isEnabled()).thenReturn(true);
            when(owner.getName()).thenReturn("PaidTest");
            Server server = mock(Server.class);
            when(owner.getServer()).thenReturn(server); when(server.getScheduler()).thenReturn(scheduler);
            when(scheduler.runTask(eq(core), any(Runnable.class))).thenAnswer(i -> {
                queued.add(i.getArgument(1)); scheduled.release(); return mock(BukkitTask.class);
            });
            when(scheduler.runTaskTimer(eq(core), any(Runnable.class), eq(20L), eq(20L))).thenAnswer(i -> {
                tick = i.getArgument(1); return mock(BukkitTask.class);
            });
            transport.when(() -> ModuleTransport.request(any(), anyString(), anyString(), anyString())).thenAnswer(i -> {
                actions.add(i.getArgument(2));
                CompletableFuture<ModuleTransport.Reply> reply = new CompletableFuture<>(); replies.add(reply);
                return new ModuleTransport.Call(reply, () -> reply.cancel(false));
            });
        }

        void start() {
            ModuleRequest request = new ModuleRequest(URI.create("https://licenses.example/modules"), "trees", "1.0",
                    "CUSTOMER-TEST", ModuleTransportTest.keys().getPublic(), "paid.example.", "paid.example.Entry");
            session = LicensedModules.start(owner, request, states::add);
        }

        void grant() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes);
                 var fixture = getClass().getResourceAsStream("/paid/example/Entry.class")) {
                zip.putNextEntry(new ZipEntry("paid/example/Entry.class"));
                zip.write(fixture.readAllBytes()); zip.closeEntry();
            }
            replies.getFirst().complete(new ModuleTransport.Reply(true, "random-lease-identifier",
                    System.nanoTime() + 90_000_000_000L, bytes.toByteArray()));
            awaitCallback();
        }

        void setTimeField(String name, long value) throws Exception {
            var field = session.getClass().getDeclaredField(name); field.setAccessible(true); field.setLong(session, value);
        }

        void drain() { while (!queued.isEmpty()) queued.remove().run(); }
        void awaitCallback() {
            try { assertTrue(scheduled.tryAcquire(5, TimeUnit.SECONDS), "Main-thread callback must arrive"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            drain();
        }
        public void close() {
            LicensedModules.shutdown(); drain();
            transport.close(); coreApi.close(); bukkit.close();
        }
    }

    @Test void givenGrantedModule_whenOwnerDisabled_thenStopRunsAndAuthorizationCloses() throws Exception {
        try (Harness h = new Harness()) {
            h.start(); h.grant();
            assertEquals(1, starts); assertTrue(authorization.getAsBoolean());
            assertEquals(List.of(LicensedModules.State.ACTIVE), h.states);
            when(h.owner.isEnabled()).thenReturn(false);
            CoreLifecycle.releaseAll(h.owner);
            assertFalse(authorization.getAsBoolean()); assertEquals(1, stops);
            assertEquals(List.of("acquire", "release"), h.actions);
            h.session.close(); assertEquals(1, stops);
        }
    }

    @Test void givenExpiredLease_whenTickDelayed_thenGuardClosesBeforeStopCallback() throws Exception {
        try (Harness h = new Harness()) {
            h.start(); h.grant(); h.setTimeField("deadline", System.nanoTime() - 1);
            assertFalse(authorization.getAsBoolean()); assertEquals(0, stops);
            h.tick.run();
            assertEquals(LicensedModules.State.EXPIRED, h.session.state()); assertEquals(1, stops);
        }
    }

    @Test void givenRenewalFailure_whenProcessed_thenOriginalDeadlineIsNotExtended() throws Exception {
        try (Harness h = new Harness()) {
            h.start(); h.grant(); h.setTimeField("nextRenew", System.nanoTime() - 1); h.tick.run();
            assertEquals(List.of("acquire", "renew"), h.actions);
            h.replies.get(1).completeExceptionally(new IllegalStateException()); h.awaitCallback();
            assertTrue(h.session.active());
            h.setTimeField("deadline", System.nanoTime() - 1); h.tick.run();
            assertEquals(LicensedModules.State.EXPIRED, h.session.state());
        }
    }

    @Test void givenClosedOwnerBeforeDownload_whenCallbackRuns_thenModuleNeverStarts() {
        try (Harness h = new Harness()) {
            h.start(); h.session.close(); h.awaitCallback();
            assertEquals(0, starts); assertTrue(h.replies.getFirst().isCancelled());
            assertEquals(LicensedModules.State.CLOSED, h.session.state());
        }
    }

    @Test void givenSignedDenialDuringRenewal_whenReceived_thenActiveModuleStops() throws Exception {
        try (Harness h = new Harness()) {
            h.start(); h.grant(); h.setTimeField("nextRenew", System.nanoTime() - 1); h.tick.run();
            h.replies.get(1).complete(new ModuleTransport.Reply(false, "", 0, new byte[0])); h.awaitCallback();
            assertEquals(LicensedModules.State.DENIED, h.session.state());
            assertFalse(authorization.getAsBoolean()); assertEquals(1, stops);
        }
    }

    @Test void givenRenewalArrivingAfterExpiry_whenReceived_thenItCannotResurrectSession() throws Exception {
        try (Harness h = new Harness()) {
            h.start(); h.grant(); h.setTimeField("nextRenew", System.nanoTime() - 1); h.tick.run();
            h.setTimeField("deadline", System.nanoTime() - 1);
            h.replies.get(1).complete(new ModuleTransport.Reply(true, "random-lease-identifier",
                    System.nanoTime() + 90_000_000_000L, new byte[0])); h.awaitCallback();
            assertEquals(LicensedModules.State.EXPIRED, h.session.state());
            assertFalse(authorization.getAsBoolean()); assertEquals(1, stops);
        }
    }

    @Test void givenInitialDenial_whenReceived_thenNoProtectedClassStarts() {
        try (Harness h = new Harness()) {
            h.start();
            h.replies.getFirst().complete(new ModuleTransport.Reply(false, "", 0, new byte[0])); h.awaitCallback();
            assertEquals(LicensedModules.State.DENIED, h.session.state());
            assertFalse(h.session.active()); assertEquals(0, starts);
        }
    }

    @Test void givenPartiallyStartedModule_whenStartThrows_thenStopAndReleaseStillRun() throws Exception {
        try (Harness h = new Harness()) {
            failStart = true; h.start(); h.grant();
            assertEquals(LicensedModules.State.FAILED, h.session.state());
            assertFalse(authorization.getAsBoolean()); assertEquals(1, stops);
            assertEquals(List.of("acquire", "release"), h.actions);
        }
    }
}
