package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.Note;
import com.admintools.storage.Database;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** Notas internas de staff: el contexto que no cabe en una sanción. */
public final class NoteService {

    private final AdminToolsPlugin plugin;
    private final Database db;
    private final String table;

    public NoteService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        this.table = db.table("notes");
    }

    public long add(CommandSender author, UUID targetUuid, String targetName, String text) {
        long now = System.currentTimeMillis();
        String authorUuid = author instanceof Player player ? player.getUniqueId().toString() : null;
        String authorName = author instanceof Player player ? player.getName() : "CONSOLE";
        long id = db.insert("INSERT INTO " + table
                        + " (target_uuid, target_name, author_uuid, author_name, text, created_at, origin) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                targetUuid.toString(), targetName, authorUuid, authorName, text, now, plugin.getServerName());
        plugin.getAuditService().log(author, "note.add", targetName, "Nota #" + id + ": " + text);
        return id;
    }

    public List<Note> list(UUID targetUuid) {
        return db.query("SELECT * FROM " + table + " WHERE target_uuid = ? ORDER BY id DESC LIMIT 100", rs -> new Note(
                rs.getLong("id"), UUID.fromString(rs.getString("target_uuid")), rs.getString("target_name"),
                rs.getString("author_uuid") == null ? null : UUID.fromString(rs.getString("author_uuid")),
                rs.getString("author_name"), rs.getString("text"), rs.getLong("created_at"), rs.getString("origin")),
                targetUuid.toString());
    }

    public int count(UUID targetUuid) {
        return (int) db.queryLong("SELECT COUNT(*) FROM " + table + " WHERE target_uuid = ?", targetUuid.toString());
    }

    /** Borra una nota concreta; devuelve true si existía. */
    public boolean remove(long id, CommandSender actor) {
        int removed = db.update("DELETE FROM " + table + " WHERE id = ?", id);
        if (removed > 0) {
            plugin.getAuditService().log(actor, "note.remove", "#" + id, "Nota eliminada");
        }
        return removed > 0;
    }
}
