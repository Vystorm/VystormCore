package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.survey.SurveyJob;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

/**
 * A boss bar for every online admin ({@code vystorm_core.admin}) while a pre-compute job runs or is paused, in the
 * admin's language; a finished job stays visible for ten seconds. Server thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
final class SurveyBossBars {
    private final SurveyRuntime rt;
    private final Map<UUID, BossBar> bars = new HashMap<>();

    SurveyBossBars(SurveyRuntime rt) { this.rt = rt; }

    void update() {
        PrecomputeJob shown = null;
        long now = System.currentTimeMillis();
        for (PrecomputeJob job : rt.jobs.values()) {
            SurveyJob.State st = job.state();
            boolean visible = st == SurveyJob.State.RUNNING || st == SurveyJob.State.PAUSED
                    || (st == SurveyJob.State.FINISHED && now - job.finishedAt < 10_000);
            if (visible && (shown == null || st == SurveyJob.State.RUNNING)) shown = job;
        }
        if (shown == null) {
            clear();
            return;
        }
        SurveyJob.Progress p = shown.progress();
        Lang lang = Localization.get().core();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("vystorm_core.admin")) {
                BossBar old = bars.remove(player.getUniqueId());
                if (old != null) old.removeAll();
                continue;
            }
            String title = lang.plain(player, "survey.bossbar", "world", shown.worldName, "done", p.done(), "total", p.total(),
                    "percent", Math.round(p.fraction() * 100), "rate", String.format(java.util.Locale.ROOT, "%.1f", p.chunksPerSecond()),
                    "eta", SurveyAdmin.duration(p.etaSeconds()),
                    "state", lang.plain(player, "survey.state." + p.state().name().toLowerCase(java.util.Locale.ROOT)));
            BossBar bar = bars.computeIfAbsent(player.getUniqueId(), id -> {
                BossBar b = Bukkit.createBossBar(title, BarColor.BLUE, BarStyle.SEGMENTED_10);
                b.addPlayer(player);
                return b;
            });
            bar.setTitle(title);
            bar.setProgress(Math.max(0, Math.min(1, p.fraction())));
            bar.setColor(p.state() == SurveyJob.State.RUNNING ? BarColor.BLUE : p.state() == SurveyJob.State.PAUSED ? BarColor.YELLOW : BarColor.GREEN);
            if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
        }
        for (Iterator<Map.Entry<UUID, BossBar>> it = bars.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, BossBar> e = it.next();
            if (Bukkit.getPlayer(e.getKey()) == null) {
                e.getValue().removeAll();
                it.remove();
            }
        }
    }

    void clear() {
        for (BossBar bar : bars.values()) bar.removeAll();
        bars.clear();
    }
}
