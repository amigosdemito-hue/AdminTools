package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.InventoryBackup;
import com.admintools.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Copias completas de jugador para /rollback, al estilo de InventoryRollbackPlus
 * pero con más control:
 *
 * <ul>
 *   <li>guarda inventario, enderchest, posición, vida, comida, XP y modo de juego;</li>
 *   <li>restauración <b>selectiva</b> (sólo ítems, sólo posición…);</li>
 *   <li>retención distinta por motivo (muertes, entradas, salidas, manuales);</li>
 *   <li>copia de seguridad automática antes de cada restauración;</li>
 *   <li>vista previa de sólo lectura antes de tocar nada.</li>
 * </ul>
 */
public final class InventoryBackupService {

    /** Qué partes se restauran. */
    public enum Part {
        ITEMS, ENDER, POSITION, STATS
    }

    /** Jugador con copias guardadas, para el menú raíz del rollback. */
    public record BackupOwner(UUID uuid, String name, int count, long last) {
    }

    /** Cuánto se espera entre limpiezas de retención (evita recorrer la tabla en cada copia). */
    private static final long PURGE_INTERVAL_MS = 60_000L;

    private final AdminToolsPlugin plugin;
    private final Database db;
    private final String table;
    private volatile long lastPurge;

    public InventoryBackupService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        this.table = db.table("inventory_backups");
    }

    // ------------------------------------------------------------
    //  Guardar
    // ------------------------------------------------------------

    /** Copia ya leída y serializada en el hilo del servidor, lista para escribirse aparte. */
    private record Pending(UUID uuid, String name, String reason, long createdAt, byte[] inventory,
                           byte[] enderchest, String world, double x, double y, double z,
                           float yaw, float pitch, double health, int food, int xp,
                           String gamemode, int size) {
    }

    /**
     * Guarda una copia completa del jugador sin bloquear el hilo principal.
     *
     * <p>Los datos se leen y serializan en el hilo del servidor (la API de Bukkit no
     * es segura fuera de él) y sólo el INSERT viaja al pool de la base. Es el camino
     * de los eventos automáticos (entrada, salida, muerte, cambio de mundo e intervalo),
     * donde un acceso a MySQL remoto no debe frenar el tic.</p>
     *
     * @return siempre 0: el id no se conoce hasta que la escritura termina
     */
    public long capture(Player player, String reason) {
        Pending pending = snapshot(player, reason);
        if (pending == null) {
            return 0L;
        }
        db.async(() -> {
            insert(pending);
            maybePurge();
        });
        return 0L;
    }

    /** Igual que {@link #capture} pero escribe en el momento y devuelve el id (0 si falló). */
    public long captureSync(Player player, String reason) {
        Pending pending = snapshot(player, reason);
        if (pending == null) {
            return 0L;
        }
        long id = insert(pending);
        maybePurge();
        return id;
    }

    /** Lee la copia en el hilo actual; {@code null} si el jugador no existe o falló la serialización. */
    private Pending snapshot(Player player, String reason) {
        if (!plugin.getSettings().rollbackEnabled() || player == null) {
            return null;
        }
        byte[] inventory;
        byte[] enderchest;
        try {
            inventory = ItemStack.serializeItemsAsBytes(player.getInventory().getContents());
            enderchest = ItemStack.serializeItemsAsBytes(player.getEnderChest().getContents());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("No pude serializar el inventario de " + player.getName()
                    + " para rollback: " + ex.getMessage());
            return null;
        }
        Location location = player.getLocation();
        return new Pending(player.getUniqueId(), player.getName(), reason, System.currentTimeMillis(),
                inventory, enderchest, location.getWorld() == null ? null : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(),
                player.getHealth(), player.getFoodLevel(), player.getLevel(),
                player.getGameMode().name(), inventory.length + enderchest.length);
    }

    /** Escritura real: puede ejecutarse en el pool sin tocar la API de Bukkit. */
    private long insert(Pending pending) {
        return db.insert("INSERT INTO " + table
                        + " (uuid, name, reason, created_at, inventory, enderchest, world, x, y, z, yaw, pitch,"
                        + " health, food, xp, gamemode, size)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                pending.uuid().toString(), pending.name(), pending.reason(), pending.createdAt(),
                pending.inventory(), pending.enderchest(), pending.world(), pending.x(), pending.y(),
                pending.z(), (double) pending.yaw(), (double) pending.pitch(), pending.health(),
                pending.food(), pending.xp(), pending.gamemode(), pending.size());
    }

    // ------------------------------------------------------------
    //  Consultar
    // ------------------------------------------------------------

    public List<InventoryBackup> list(UUID uuid, int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        return db.query("SELECT * FROM " + table + " WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                this::map, uuid.toString(), size, offset);
    }

    public int count(UUID uuid) {
        return (int) db.queryLong("SELECT COUNT(*) FROM " + table + " WHERE uuid = ?", uuid.toString());
    }

    public Optional<InventoryBackup> byId(long id) {
        return db.queryOne("SELECT * FROM " + table + " WHERE id = ?", this::map, id);
    }

    /** Jugadores con copias, de la más reciente a la más antigua. */
    public List<BackupOwner> owners(int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        return db.query("SELECT uuid, MAX(name) AS name, COUNT(*) AS total, MAX(created_at) AS last"
                        + " FROM " + table + " GROUP BY uuid ORDER BY last DESC LIMIT ? OFFSET ?",
                rs -> new BackupOwner(UUID.fromString(rs.getString("uuid")), rs.getString("name"),
                        rs.getInt("total"), rs.getLong("last")),
                size, offset);
    }

    /** Cuántos jugadores distintos tienen copias. */
    public int ownerCount() {
        return (int) db.queryLong("SELECT COUNT(DISTINCT uuid) FROM " + table);
    }

    /** Borra una copia concreta. Devuelve las filas borradas. */
    public int delete(long id) {
        return db.update("DELETE FROM " + table + " WHERE id = ?", id);
    }

    /** Ítems guardados, para la vista previa. */
    public ItemStack[] items(InventoryBackup backup) {
        return decode(backup == null ? null : backup.inventory());
    }

    /** Contenido del enderchest guardado, para su vista previa. */
    public ItemStack[] enderchest(InventoryBackup backup) {
        return decode(backup == null ? null : backup.enderchest());
    }

    private ItemStack[] decode(byte[] data) {
        if (data == null) {
            return new ItemStack[0];
        }
        try {
            return ItemStack.deserializeItemsFromBytes(data);
        } catch (RuntimeException ex) {
            return new ItemStack[0];
        }
    }

    /** Cuántos objetos no vacíos tenía la copia. */
    public int itemCount(InventoryBackup backup) {
        int count = 0;
        for (ItemStack stack : items(backup)) {
            if (stack != null && !stack.getType().isAir()) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------
    //  Restaurar
    // ------------------------------------------------------------

    /**
     * Restaura las partes indicadas en el jugador. Antes guarda una copia del
     * estado actual con motivo SAFETY, así que un error del staff es reversible.
     */
    public boolean restore(Player player, InventoryBackup backup, Set<Part> parts) {
        return restore(player, backup, parts, true);
    }

    /**
     * Restaura las partes indicadas.
     *
     * @param safety si se guarda antes una copia del estado actual; se desactiva
     *               al restaurar un solo espacio, que no merece una copia por clic
     */
    public boolean restore(Player player, InventoryBackup backup, Set<Part> parts, boolean safety) {
        if (backup == null || parts.isEmpty()) {
            return false;
        }
        if (safety) {
            // Síncrona a propósito: la copia de seguridad debe estar escrita antes de
            // tocar nada, para que un fallo a mitad sea reversible.
            captureSync(player, "SAFETY");
        }
        try {
            if (parts.contains(Part.ITEMS) && backup.inventory() != null) {
                player.getInventory().setContents(ItemStack.deserializeItemsFromBytes(backup.inventory()));
            }
            if (parts.contains(Part.ENDER) && backup.enderchest() != null) {
                player.getEnderChest().setContents(ItemStack.deserializeItemsFromBytes(backup.enderchest()));
            }
            if (parts.contains(Part.POSITION) && backup.world() != null) {
                World world = Bukkit.getWorld(backup.world());
                if (world != null) {
                    player.teleport(new Location(world, backup.x(), backup.y(), backup.z(),
                            backup.yaw(), backup.pitch()));
                }
            }
            if (parts.contains(Part.STATS)) {
                AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
                double max = maxHealth == null ? 20.0 : maxHealth.getValue();
                // Nunca 0: un jugador con vida 0 moriría justo al restaurar.
                player.setHealth(Math.max(1.0, Math.min(backup.health(), max)));
                player.setFoodLevel(Math.max(0, Math.min(20, backup.food())));
                player.setLevel(Math.max(0, backup.xp()));
            }
            player.updateInventory();
            return true;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("No pude restaurar la copia #" + backup.id() + " de "
                    + player.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    /**
     * Restaura un único espacio del inventario (0-35, armadura 36-39 y mano
     * secundaria 40). El espacio pasa a tener exactamente lo que tenía la copia,
     * aunque estuviera vacío: así no se duplica nada.
     */
    public boolean restoreSlot(Player player, InventoryBackup backup, int slot) {
        ItemStack[] stored = items(backup);
        if (slot < 0 || slot >= stored.length) {
            return false;
        }
        ItemStack[] current = player.getInventory().getContents();
        if (slot >= current.length) {
            return false;
        }
        current[slot] = stored[slot] == null ? null : stored[slot].clone();
        player.getInventory().setContents(current);
        player.updateInventory();
        return true;
    }

    // ------------------------------------------------------------
    //  Retención
    // ------------------------------------------------------------

    /** Limpia la retención como mucho una vez por minuto, sin bloquear al jugador. */
    private void maybePurge() {
        long now = System.currentTimeMillis();
        if (now - lastPurge < PURGE_INTERVAL_MS) {
            return;
        }
        lastPurge = now;
        db.async(this::purgePerReason);
    }

    /** Deja sólo las copias permitidas por motivo y jugador. */
    public int purgePerReason() {
        List<String[]> rows = db.query("SELECT id, uuid, reason FROM " + table + " ORDER BY id DESC",
                rs -> new String[]{String.valueOf(rs.getLong("id")), rs.getString("uuid"), rs.getString("reason")});
        Map<String, Integer> counters = new HashMap<>();
        int removed = 0;
        for (String[] row : rows) {
            String key = row[1] + "|" + row[2];
            int count = counters.merge(key, 1, Integer::sum);
            if (count > limitFor(row[2])) {
                removed += db.update("DELETE FROM " + table + " WHERE id = ?", Long.parseLong(row[0]));
            }
        }
        return removed;
    }

    /** Borra las copias más antiguas que el número de días indicado. */
    public int purgeOlderThan(int days) {
        long threshold = System.currentTimeMillis() - Math.max(1, days) * 86_400_000L;
        return db.update("DELETE FROM " + table + " WHERE created_at < ?", threshold);
    }

    private int limitFor(String reason) {
        String code = reason == null ? "" : reason.toUpperCase(Locale.ROOT);
        return switch (code) {
            case "DEATH" -> plugin.getSettings().rollbackKeepDeath();
            case "JOIN" -> plugin.getSettings().rollbackKeepJoin();
            case "QUIT" -> plugin.getSettings().rollbackKeepQuit();
            default -> plugin.getSettings().rollbackKeepManual();
        };
    }

    // ------------------------------------------------------------
    //  Mapeo
    // ------------------------------------------------------------

    private InventoryBackup map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InventoryBackup(rs.getLong("id"), UUID.fromString(rs.getString("uuid")),
                rs.getString("name"), rs.getString("reason"), rs.getLong("created_at"),
                rs.getBytes("inventory"), rs.getBytes("enderchest"), rs.getString("world"),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                (float) rs.getDouble("yaw"), (float) rs.getDouble("pitch"),
                rs.getDouble("health"), rs.getInt("food"), rs.getInt("xp"),
                rs.getString("gamemode"), rs.getInt("size"));
    }
}
