package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.Report;
import com.admintools.model.ReportStatus;
import com.admintools.storage.Database;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Cola de reportes: crear, reclamar, resolver y rechazar. */
public final class ReportService {

    private final AdminToolsPlugin plugin;
    private final Database db;
    private final String table;
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();

    public ReportService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        this.table = db.table("reports");
    }

    public void clearCache() {
        lastReport.clear();
    }

    private Report map(ResultSet rs) throws SQLException {
        return new Report(
                rs.getLong("id"),
                UUID.fromString(rs.getString("reporter_uuid")),
                rs.getString("reporter_name"),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                rs.getString("reason"),
                rs.getLong("created_at"),
                ReportStatus.parse(rs.getString("status"), ReportStatus.OPEN),
                rs.getString("claimed_by"),
                rs.getString("claimed_name"),
                rs.getLong("claimed_at"),
                rs.getString("resolved_by"),
                rs.getString("resolved_name"),
                rs.getLong("resolved_at"),
                rs.getString("resolution"),
                rs.getString("origin"));
    }

    // ------------------------------------------------------------
    //  Crear
    // ------------------------------------------------------------

    /** Crea un reporte y avisa al staff. */
    public Report create(Player reporter, UUID targetUuid, String targetName, String reason) {
        long now = System.currentTimeMillis();
        long id = db.insert("INSERT INTO " + table
                        + " (reporter_uuid, reporter_name, target_uuid, target_name, reason, created_at, status, origin)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                reporter.getUniqueId().toString(), reporter.getName(), targetUuid.toString(), targetName, reason, now,
                ReportStatus.OPEN.name(), plugin.getServerName());
        lastReport.put(reporter.getUniqueId(), now);

        Report report = new Report(id, reporter.getUniqueId(), reporter.getName(), targetUuid, targetName, reason,
                now, ReportStatus.OPEN, null, null, 0L, null, null, 0L, null, plugin.getServerName());
        notifyStaff(report);
        plugin.getAuditService().log(reporter.getUniqueId().toString(), reporter.getName(), "report.create", targetName,
                "Reporte #" + id + ": " + reason);
        plugin.getIntegrations().discordReport(report);
        return report;
    }

    /** Avisa a todo el staff conectado (y a la consola). */
    public void notifyStaff(Report report) {
        if (!plugin.getSettings().reportNotifyStaff()) {
            return;
        }
        Component message = plugin.getMessages().get("reports.staff-notify",
                "id", report.id(),
                "reporter", plugin.getSettings().reportAnonymous() ? "anónimo" : report.reporterName(),
                "target", report.targetName(),
                "reason", com.admintools.util.Text.safe(report.reason()));
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("admintools.report.staff")) {
                staff.sendMessage(message);
            }
        }
    }

    /** Segundos que le quedan de cooldown al jugador (0 si puede reportar ya). */
    public long cooldownRemaining(UUID reporter) {
        int cooldown = plugin.getSettings().reportCooldownSeconds();
        if (cooldown <= 0) {
            return 0L;
        }
        Long last = lastReport.get(reporter);
        if (last == null) {
            last = db.queryLong("SELECT MAX(created_at) FROM " + table + " WHERE reporter_uuid = ?",
                    reporter.toString());
            if (last > 0L) {
                lastReport.put(reporter, last);
            }
        }
        long elapsed = System.currentTimeMillis() - last;
        long remaining = cooldown * 1000L - elapsed;
        return Math.max(0L, remaining / 1000L);
    }

    public int openBy(UUID reporter) {
        return (int) db.queryLong("SELECT COUNT(*) FROM " + table
                + " WHERE reporter_uuid = ? AND status IN ('OPEN', 'CLAIMED')", reporter.toString());
    }

    // ------------------------------------------------------------
    //  Consultar y gestionar
    // ------------------------------------------------------------

    /** Reportes abiertos (sin reclamar primero, luego los reclamados). */
    public List<Report> queue(int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        return db.query("SELECT * FROM " + table + " WHERE status IN ('OPEN', 'CLAIMED')"
                + " ORDER BY id ASC LIMIT ? OFFSET ?", this::map, size, offset);
    }

    public int queueCount() {
        return (int) db.queryLong("SELECT COUNT(*) FROM " + table + " WHERE status IN ('OPEN', 'CLAIMED')");
    }

    public List<Report> byTarget(UUID target, int limit) {
        return db.query("SELECT * FROM " + table + " WHERE target_uuid = ? ORDER BY id DESC LIMIT ?", this::map,
                target.toString(), limit);
    }

    public Optional<Report> byId(long id) {
        return db.queryOne("SELECT * FROM " + table + " WHERE id = ?", this::map, id);
    }

    /** Reclama un reporte; devuelve false si otro staff se adelantó. */
    public boolean claim(Report report, Player staff) {
        int updated = db.update("UPDATE " + table
                        + " SET status = ?, claimed_by = ?, claimed_name = ?, claimed_at = ? WHERE id = ? AND status = ?",
                ReportStatus.CLAIMED.name(), staff.getUniqueId().toString(), staff.getName(),
                System.currentTimeMillis(), report.id(), ReportStatus.OPEN.name());
        if (updated == 0) {
            return false;
        }
        report.claim(staff.getUniqueId().toString(), staff.getName());
        plugin.getAuditService().log(staff, "report.claim", report.targetName(), "Reporte #" + report.id());
        return true;
    }

    /** Cierra un reporte como resuelto o rechazado. */
    public void close(Report report, ReportStatus status, Player staff, String note) {
        long now = System.currentTimeMillis();
        db.update("UPDATE " + table + " SET status = ?, resolved_by = ?, resolved_name = ?, resolved_at = ?,"
                        + " resolution = ? WHERE id = ?",
                status.name(), staff.getUniqueId().toString(), staff.getName(), now, note, report.id());
        report.close(status, staff.getUniqueId().toString(), staff.getName(), note);
        plugin.getAuditService().log(staff, "report." + status.name().toLowerCase(java.util.Locale.ROOT),
                report.targetName(), "Reporte #" + report.id() + (note == null || note.isBlank() ? "" : " - " + note));

        Player reporter = Bukkit.getPlayer(report.reporterUuid());
        if (reporter != null) {
            reporter.sendMessage(plugin.getMessages().prefixed("reports.reporter-notify",
                    "id", report.id(),
                    "status", status.label(),
                    "staff", staff.getName(),
                    "note", note == null || note.isBlank() ? "-" : com.admintools.util.Text.safe(note)));
        }
    }

    public int purgeClosed(int days) {
        long before = System.currentTimeMillis() - days * 86_400_000L;
        return db.update("DELETE FROM " + table + " WHERE status IN ('RESOLVED', 'REJECTED') AND resolved_at < ?",
                before);
    }
}
