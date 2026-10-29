package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/** Purga periódica de datos que caducan: chat, IPs, auditoría y copias. */
public final class RetentionService {

    private final AdminToolsPlugin plugin;
    private BukkitTask task;

    public RetentionService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        long interval = plugin.getSettings().purgeIntervalHours() * 72000L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::purge, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Ejecuta la limpieza y devuelve un resumen. */
    public String purge() {
        StringBuilder summary = new StringBuilder();
        int chat = plugin.getChatService().purge(plugin.getSettings().chatRetentionDays());
        int audit = plugin.getAuditService().purge(plugin.getSettings().auditRetentionDays());
        int reports = plugin.getReportService().purgeClosed(plugin.getSettings().auditRetentionDays());
        int snapshots = plugin.getSnapshotService().purge(plugin.getSettings().snapshotsPerPlayer());
        int ips = purgeIps();

        summary.append("chat=").append(chat)
                .append(" auditoría=").append(audit)
                .append(" reportes=").append(reports)
                .append(" copias=").append(snapshots)
                .append(" ips=").append(ips);
        if (plugin.getSettings().debug()) {
            plugin.getLogger().info("Purga de retención: " + summary);
        }
        return summary.toString();
    }

    private int purgeIps() {
        long before = System.currentTimeMillis() - plugin.getSettings().ipRetentionDays() * 86_400_000L;
        return plugin.getDatabase().update("DELETE FROM " + plugin.getDatabase().table("player_ips")
                + " WHERE last_seen < ?", before);
    }
}
