package Vystorm.vystorm_core.actions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.actions.internal.ActionLogFormat;
import Vystorm.vystorm_core.actions.internal.ActionRunner;
import Vystorm.vystorm_core.actions.internal.VanillaDispatch;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionsTest {
    @TempDir Path dir;

    static Player player(boolean op, String setNode, boolean granted) {
        Player p = mock(Player.class);
        when(p.isOp()).thenReturn(op);
        when(p.getName()).thenReturn("Admin1");
        when(p.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(p.isPermissionSet(anyString())).thenAnswer(a -> a.getArgument(0).equals(setNode));
        when(p.hasPermission(anyString())).thenAnswer(a -> a.getArgument(0).equals(setNode) ? granted : op);
        return p;
    }

    Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getDataFolder()).thenReturn(dir.resolve(name).toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test-" + name));
        return plugin;
    }

    @Test
    void givenNodes_whenChecked_thenSetNodeDecidesAndUnsetMeansOp() {
        assertTrue(ActionRunner.allowed(player(true, null, false), "x.y"), "op, nothing set");
        assertFalse(ActionRunner.allowed(player(false, null, false), "x.y"), "no op, nothing set");
        assertFalse(ActionRunner.allowed(player(true, "x.y", false), "x.y"), "negated node beats op");
        assertTrue(ActionRunner.allowed(player(false, "x.y", true), "x.y"), "granted node, no op");
        assertTrue(ActionRunner.allowed(mock(CommandSender.class), "x.y"), "console");
    }

    @Test
    void givenPreview_whenRun_thenPermissionConfirmAndLogApply() throws Exception {
        Plugin owner = plugin("ControlTest");
        ActionPreview preview = ActionPreview.builder("gamerule.set").text("Spawn phantoms: on -> off in world").world("world")
                .command("/execute in minecraft:overworld run gamerule spawn_phantoms false").change(true, false)
                .permission("vystormcontrol.world.rules").permission("minecraft.command.gamerule").confirm(true).build();
        AtomicInteger ran = new AtomicInteger();

        ActionResult denied = ActionRunner.run(owner, player(true, "minecraft.command.gamerule", false), preview, true, () -> { ran.incrementAndGet(); return ActionResult.ok("x"); });
        assertEquals(ActionResult.NO_PERMISSION, denied.code());
        assertEquals("minecraft.command.gamerule", denied.message());

        ActionResult unconfirmed = ActionRunner.run(owner, player(true, null, false), preview, false, () -> { ran.incrementAndGet(); return ActionResult.ok("x"); });
        assertEquals(ActionResult.NEEDS_CONFIRM, unconfirmed.code());
        assertEquals(0, ran.get());

        ActionResult done = ActionRunner.run(owner, player(true, null, false), preview, true, () -> { ran.incrementAndGet(); return ActionResult.ok("done"); });
        assertTrue(done.isOk());
        ActionResult crashed = ActionRunner.run(owner, player(true, null, false), preview, true, () -> { throw new IllegalStateException("boom"); });
        assertEquals(ActionResult.ERROR, crashed.code());
        assertEquals(1, ran.get());

        ActionLogInterface log = ActionRunner.log(owner);
        List<ActionLogEntry> recent = log.recent(10);
        assertEquals(List.of("error", "ok", "no-permission"), recent.stream().map(ActionLogEntry::result).toList());
        log.flush();
        Path file = log.folder().resolve(ActionLogFormat.fileName(Instant.now()));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).contains("gamerule spawn_phantoms false"));
        var page = new java.util.concurrent.CompletableFuture<List<ActionLogEntry>>();
        log.query(new LogQuery("phantoms", 1, 2), page::complete);
        assertEquals(2, page.get(5, java.util.concurrent.TimeUnit.SECONDS).size(), "two newest matching lines");
        ActionRunner.release(owner);
    }

    @Test
    void givenHostileValues_whenWritten_thenOneLineAndNoColourCodes() {
        String evil = "Grief\n2026-01-01T00:00:00Z\tfake§c\r\\x\u0007";
        ActionLogEntry e = new ActionLogEntry(Instant.parse("2026-11-27T17:40:00Z"), null, "CONSOLE", "P", "ban", "world", evil, "", "", "/ban Alex " + evil, "ok", evil);
        String line = ActionLogFormat.line(e);
        assertEquals(1, line.chars().filter(c -> c == '\n').count());
        assertTrue(line.endsWith("\n"));
        assertEquals(11, line.chars().filter(c -> c == '\t').count());
        assertFalse(line.contains("§"));
        assertFalse(line.contains("\r"));
        assertEquals(e, ActionLogFormat.parse(line.substring(0, line.length() - 1)));
        assertNull(ActionLogFormat.parse("broken\tline"));
    }

    @Test
    void givenMonthFiles_whenOld_thenOutdatedBeyondKept() {
        assertEquals("2026-11.log", ActionLogFormat.fileName(YearMonth.of(2026, 11)));
        assertEquals(YearMonth.of(2025, 1), ActionLogFormat.month("2025-01.log"));
        assertNull(ActionLogFormat.month("2025-13.log"));
        assertEquals(List.of("2025-11.log"), ActionLogFormat.outdated(List.of("2025-11.log", "2025-12.log", "2026-11.log", "notes.txt"), YearMonth.of(2026, 11), 12));
    }

    @Test
    void givenCommandLines_whenChecked_thenInnerCommandsCountAndDangerousRootsAreBlocked() {
        assertEquals(List.of("execute", "gamerule"), VanillaDispatch.roots("/execute in minecraft:the_nether run gamerule spawn_phantoms false"));
        assertEquals(List.of("minecraft.command.execute", "minecraft.command.gamerule"), VanillaDispatch.permissions("execute in x run minecraft:gamerule a b"));
        assertEquals("op", VanillaDispatch.blocked("/execute as @a run op Alex"));
        assertEquals("stop", VanillaDispatch.blocked("minecraft:stop"));
        assertNull(VanillaDispatch.blocked("/time set 6000"));
        assertEquals(List.of("minecraft.command.teleport"), VanillaDispatch.permissions("tp Alex Steve"));
        List<DispatchResult> out = new ArrayList<>();
        ActionRunner.dispatchVanilla(plugin("D"), player(true, null, false), "/deop Admin1", out::add);
        ActionRunner.dispatchVanilla(plugin("D"), player(false, null, false), "/time set 6000", out::add);
        ActionRunner.dispatchVanilla(plugin("D"), player(true, null, false), "  ", out::add);
        assertEquals(List.of("blocked", "no-permission", "empty"), out.stream().map(DispatchResult::refusal).toList());
    }

    @Test
    void givenBadId_whenBuilt_thenRefused() {
        assertThrows(IllegalArgumentException.class, () -> ActionPreview.builder("Bad Id").build());
    }
}
