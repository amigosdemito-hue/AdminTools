package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.AuditEntry;
import com.admintools.storage.Database;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** Registro de auditoría: deja constancia de cada acción del staff. */
public final class AuditService {

    private final AdminToolsPlugin plugin;
    private final Database db;
    private final String table;

    public AuditService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        this.table = db.table("audit_log");
    }

    /** Guarda una acción (se ejecuta en segundo plano). */
    public void log(CommandSender actor, String action, String target, String details) {
        String uuid = actor instanceof Player player ? player.getUniqueId().toString() : null;
        String name = actor instanceof Player player ? player.getName() : "CONSOLE";
        log(uuid, name, action, target, details);
    }

    public void log(String actorUuid, String actorName, String action, String target, String details) {
        String server = plugin.getServerName();
        db.async(() -> db.update("INSERT INTO " + table
                        + " (actor_uuid, actor_name, action, target, details, created_at, origin) VALUES (?, ?, ?, ?, ?, ?, ?)",
                actorUuid, actorName, action, target, details, System.currentTimeMillis(), server));
    }

    public List<AuditEntry> page(int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        return db.query("SELECT * FROM " + table + " ORDER BY id DESC LIMIT ? OFFSET ?", rs -> new AuditEntry(
                rs.getLong("id"), rs.getString("actor_uuid"), rs.getString("actor_name"), rs.getString("action"),
                rs.getString("target"), rs.getString("details"), rs.getLong("created_at"), rs.getString("origin")),
                size, offset);
    }

    public int count() {
        return (int) db.queryLong("SELECT COUNT(*) FROM " + table);
    }

    /** Registros que afectan a un jugador concreto. */
    public List<AuditEntry> pageByTarget(String target, int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        return db.query("SELECT * FROM " + table + " WHERE LOWER(target) = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                rs -> new AuditEntry(rs.getLong("id"), rs.getString("actor_uuid"), rs.getString("actor_name"),
                        rs.getString("action"), rs.getString("target"), rs.getString("details"),
                        rs.getLong("created_at"), rs.getString("origin")),
                target.toLowerCase(java.util.Locale.ROOT), size, offset);
    }

    public int countByTarget(String target) {
        return (int) db.queryLong("SELECT COUNT(*) FROM " + table + " WHERE LOWER(target) = ?",
                target.toLowerCase(java.util.Locale.ROOT));
    }

    public int purge(int days) {
        long before = System.currentTimeMillis() - days * 86_400_000L;
        return db.update("DELETE FROM " + table + " WHERE created_at < ?", before);
    }
}
