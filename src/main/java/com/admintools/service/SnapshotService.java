package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.Snapshot;
import com.admintools.storage.Database;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Copias de inventario: se guardan al desconectarse, al morir y a mano.
 * Sirven para /invsee de jugadores offline y para restaurar tras un grief
 * o un error de un staff.
 */
public final class SnapshotService {

    public static final String KIND_INVENTORY = "INVENTORY";
    public static final String KIND_ENDERCHEST = "ENDERCHEST";

    private static final int FORMAT_VERSION = 1;

    private final AdminToolsPlugin plugin;
    private final Database db;
    private final String table;

    public SnapshotService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        this.table = db.table("snapshots");
    }

    // ------------------------------------------------------------
    //  Guardar
    // ------------------------------------------------------------

    /**
     * Guarda el inventario y el enderchest actuales del jugador.
     *
     * @return id de la copia del inventario (0 si no se pudo guardar)
     */
    public long save(Player player, String reason) {
        byte[] inventory;
        byte[] enderchest;
        try {
            inventory = encode(player.getInventory().getContents(), null, 0);
            enderchest = encode(player.getEnderChest().getContents(), null, 0);
        } catch (IOException ex) {
            plugin.getLogger().warning("No pude serializar el inventario de " + player.getName() + ": "
                    + ex.getMessage());
            return 0L;
        }
        long now = System.currentTimeMillis();
        long id = db.insert("INSERT INTO " + table
                        + " (uuid, name, reason, created_at, kind, size, data) VALUES (?, ?, ?, ?, ?, ?, ?)",
                player.getUniqueId().toString(), player.getName(), reason, now, KIND_INVENTORY,
                inventory.length, inventory);
        db.insert("INSERT INTO " + table
                        + " (uuid, name, reason, created_at, kind, size, data) VALUES (?, ?, ?, ?, ?, ?, ?)",
                player.getUniqueId().toString(), player.getName(), reason, now, KIND_ENDERCHEST,
                enderchest.length, enderchest);
        db.async(() -> purge(plugin.getSettings().snapshotsPerPlayer()));
        return id;
    }

    // ------------------------------------------------------------
    //  Consultar
    // ------------------------------------------------------------

    public List<Snapshot> list(UUID uuid, String kind, int limit) {
        return db.query("SELECT id, uuid, name, reason, created_at, kind FROM " + table
                + " WHERE uuid = ? AND kind = ? ORDER BY id DESC LIMIT ?", rs -> new Snapshot(
                rs.getLong("id"), UUID.fromString(rs.getString("uuid")), rs.getString("name"),
                rs.getString("reason"), rs.getLong("created_at"), rs.getString("kind"), null),
                uuid.toString(), kind, limit);
    }

    public Optional<Snapshot> byId(long id) {
        return db.queryOne("SELECT * FROM " + table + " WHERE id = ?", rs -> new Snapshot(
                rs.getLong("id"), UUID.fromString(rs.getString("uuid")), rs.getString("name"),
                rs.getString("reason"), rs.getLong("created_at"), rs.getString("kind"), rs.getBytes("data")), id);
    }

    /** Última copia guardada de un jugador. */
    public Optional<Snapshot> latest(UUID uuid, String kind) {
        return db.queryOne("SELECT * FROM " + table + " WHERE uuid = ? AND kind = ? ORDER BY id DESC LIMIT 1",
                rs -> new Snapshot(rs.getLong("id"), UUID.fromString(rs.getString("uuid")), rs.getString("name"),
                        rs.getString("reason"), rs.getLong("created_at"), rs.getString("kind"),
                        rs.getBytes("data")), uuid.toString(), kind);
    }

    // ------------------------------------------------------------
    //  Restaurar
    // ------------------------------------------------------------

    /** Restaura una copia en un jugador online, guardando antes su estado actual. */
    public boolean restore(Player player, long snapshotId) {
        Optional<Snapshot> found = byId(snapshotId);
        if (found.isEmpty()) {
            return false;
        }
        Snapshot snapshot = found.get();
        if (snapshot.data() == null || snapshot.data().length == 0) {
            return false;
        }
        // Red de seguridad: guardamos lo que hay ahora antes de pisarlo.
        save(player, "Antes de restaurar #" + snapshotId);

        try {
            ItemStack[] contents = decode(snapshot.data());
            if (KIND_ENDERCHEST.equals(snapshot.kind())) {
                player.getEnderChest().setContents(contents);
            } else {
                player.getInventory().setContents(contents);
            }
            player.updateInventory();
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("Copia #" + snapshotId + " dañada: " + ex.getMessage());
            return false;
        }
    }

    /** Deja sólo las N copias más nuevas por jugador y tipo (inventario y enderchest por separado). */
    public int purge(int keepPerPlayer) {
        var rows = db.query("SELECT id, uuid, kind FROM " + table + " ORDER BY id DESC",
                rs -> new String[]{String.valueOf(rs.getLong("id")), rs.getString("uuid"), rs.getString("kind")});
        java.util.Map<String, Integer> counters = new java.util.HashMap<>();
        int removed = 0;
        for (String[] row : rows) {
            String key = row[1] + "|" + row[2];
            int count = counters.merge(key, 1, Integer::sum);
            if (count > keepPerPlayer) {
                removed += db.update("DELETE FROM " + table + " WHERE id = ?", Long.parseLong(row[0]));
            }
        }
        return removed;
    }

    // ------------------------------------------------------------
    //  Serialización
    // ------------------------------------------------------------

    private byte[] encode(ItemStack[] contents, ItemStack[] extra, int xpLevel) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(FORMAT_VERSION);
            out.writeInt(xpLevel);
            byte[] items = ItemStack.serializeItemsAsBytes(contents);
            out.writeInt(items.length);
            out.write(items);
            byte[] extraBytes = extra == null ? new byte[0] : ItemStack.serializeItemsAsBytes(extra);
            out.writeInt(extraBytes.length);
            out.write(extraBytes);
        }
        return buffer.toByteArray();
    }

    private ItemStack[] decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Versión de copia no soportada: " + version);
            }
            in.readInt(); // nivel de experiencia (reservado)
            byte[] items = new byte[in.readInt()];
            in.readFully(items);
            byte[] extra = new byte[in.readInt()];
            if (extra.length > 0) {
                in.readFully(extra);
            }
            return ItemStack.deserializeItemsFromBytes(items);
        }
    }

    /** Copia el inventario de un jugador a otro (útil para revisar offline). */
    public ItemStack[] readInventory(long snapshotId) {
        Optional<Snapshot> found = byId(snapshotId);
        if (found.isEmpty() || found.get().data() == null) {
            return new ItemStack[0];
        }
        try {
            return decode(found.get().data());
        } catch (IOException ex) {
            return new ItemStack[0];
        }
    }
}
