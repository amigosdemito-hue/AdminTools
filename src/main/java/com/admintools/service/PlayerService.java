package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.AltAccount;
import com.admintools.model.PlayerRecord;
import com.admintools.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ficha de jugadores: visitas, tiempo jugado, IPs usadas, cuentas
 * alternativas y estado persistente (congelado / de servicio).
 */
public final class PlayerService {

    private final AdminToolsPlugin plugin;
    private final Database db;
    private final Map<UUID, PlayerRecord> cache = new ConcurrentHashMap<>();
    private final String players;
    private final String playerIps;

    public PlayerService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        this.players = db.table("players");
        this.playerIps = db.table("player_ips");
    }

    private PlayerRecord map(ResultSet rs) throws SQLException {
        return new PlayerRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getLong("first_seen"),
                rs.getLong("last_seen"),
                rs.getLong("last_login"),
                rs.getLong("last_logout"),
                rs.getLong("playtime_seconds"),
                rs.getInt("sessions"),
                rs.getInt("frozen") == 1,
                rs.getInt("duty") == 1,
                rs.getString("last_ip"));
    }

    /** Devuelve la ficha del jugador (de caché, de disco o vacía si es nuevo). */
    public PlayerRecord get(UUID uuid) {
        PlayerRecord cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        Optional<PlayerRecord> stored = db.queryOne("SELECT * FROM " + players + " WHERE uuid = ?", this::map,
                uuid.toString());
        // Sin ficha guardada no se consulta el perfil al servicio de Mojang: eso
        // puede bloquear el hilo principal. El nombre se completa al entrar.
        PlayerRecord record = stored.orElseGet(() -> PlayerRecord.fresh(uuid, "?",
                System.currentTimeMillis(), null));
        cache.put(uuid, record);
        return record;
    }

    /** Busca el UUID por nombre: primero online, luego en la base. */
    public Optional<UUID> findUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        Optional<String> stored = db.queryOne("SELECT uuid FROM " + players + " WHERE " + nameMatch(),
                rs -> rs.getString(1), name);
        if (stored.isPresent()) {
            return Optional.of(UUID.fromString(stored.get()));
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        return offline == null ? Optional.empty() : Optional.of(offline.getUniqueId());
    }

    /**
     * Comparación de nombre que usa el índice de la tabla.
     *
     * <p>SQLite distingue mayúsculas por defecto (y {@code LOWER(name)} obligaba a
     * recorrer toda la tabla), así que allí se usa {@code COLLATE NOCASE} con un
     * índice creado a propósito. MySQL ya es insensible a mayúsculas con su
     * colación habitual, así que basta con la igualdad y el índice de siempre.</p>
     */
    private String nameMatch() {
        return db.dialect() == Database.Dialect.SQLITE ? "name = ? COLLATE NOCASE" : "name = ?";
    }

    /** Igual que {@link #findUuid} pero exigiendo que el jugador exista. */
    public Optional<UUID> requireUuid(String name) {
        return findUuid(name);
    }

    /** Nombre actual (de la base o el que tenía al salir). */
    public String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        PlayerRecord record = get(uuid);
        return record.name() == null ? "?" : record.name();
    }

    /**
     * Nombre para mostrar cuando puede no haber ficha.
     *
     * <p>{@link #nameOf} devuelve {@code "?"} para un UUID sin ficha; en mensajes
     * eso se veía como "? se conectó" en vez del nombre que el staff escribió.</p>
     */
    public String nameOrDefault(UUID uuid, String fallback) {
        String name = nameOf(uuid);
        if (name == null || name.isBlank() || name.equals("?")) {
            return fallback == null || fallback.isBlank() ? "?" : fallback;
        }
        return name;
    }

    /** Registra la entrada: crea o actualiza la ficha y guarda la IP. */
    public void onJoin(UUID uuid, String name, String ip) {
        long now = System.currentTimeMillis();
        PlayerRecord record = get(uuid);
        record.name(name);
        record.lastSeen(now);
        record.lastLogin(now);
        record.sessions(record.sessions() + 1);
        record.lastIp(ip);
        db.async(() -> {
            save(record);
            touchIp(uuid, ip, now);
        });
    }

    /** Registra la salida: suma tiempo jugado y actualiza la última visita. */
    public void onQuit(UUID uuid, String name, String ip) {
        long now = System.currentTimeMillis();
        PlayerRecord record = get(uuid);
        record.name(name);
        record.lastSeen(now);
        record.lastLogout(now);
        record.lastIp(ip);
        if (record.lastLogin() > 0L && record.lastLogin() <= now) {
            record.addPlaytimeSeconds((now - record.lastLogin()) / 1000L);
        }
        db.async(() -> {
            save(record);
            touchIp(uuid, ip, now);
        });
    }

    private void save(PlayerRecord record) {
        db.update(db.upsert(players,
                        List.of("uuid", "name", "first_seen", "last_seen", "last_login", "last_logout",
                                "playtime_seconds", "sessions", "frozen", "duty", "last_ip"),
                        List.of("uuid")),
                record.uuid().toString(), record.name(), record.firstSeen(), record.lastSeen(), record.lastLogin(),
                record.lastLogout(), record.playtimeSeconds(), record.sessions(), record.frozen() ? 1 : 0,
                record.duty() ? 1 : 0, record.lastIp());
    }

    private void touchIp(UUID uuid, String ip, long now) {
        int updated = db.update("UPDATE " + playerIps + " SET last_seen = ?, logins = logins + 1 WHERE uuid = ? AND ip = ?",
                now, uuid.toString(), ip);
        if (updated == 0) {
            db.update("INSERT INTO " + playerIps + " (uuid, ip, first_seen, last_seen, logins) VALUES (?, ?, ?, ?, 1)",
                    uuid.toString(), ip, now, now);
        }
    }

    // ------------------------------------------------------------
    //  Estado persistente
    // ------------------------------------------------------------

    public boolean frozen(UUID uuid) {
        return get(uuid).frozen();
    }

    public void setFrozen(UUID uuid, boolean value) {
        PlayerRecord record = get(uuid);
        record.frozen(value);
        db.async(() -> save(record));
    }

    public boolean duty(UUID uuid) {
        return get(uuid).duty();
    }

    public void setDuty(UUID uuid, boolean value) {
        PlayerRecord record = get(uuid);
        record.duty(value);
        db.async(() -> save(record));
    }

    // ------------------------------------------------------------
    //  IPs y cuentas alternativas
    // ------------------------------------------------------------

    /** IPs usadas por el jugador dentro de la retención configurada. */
    public List<String> ips(UUID uuid) {
        long since = System.currentTimeMillis() - plugin.getSettings().ipRetentionDays() * 86_400_000L;
        return db.query("SELECT ip FROM " + playerIps + " WHERE uuid = ? AND last_seen >= ? ORDER BY last_seen DESC LIMIT 25",
                rs -> rs.getString(1), uuid.toString(), since);
    }

    /** Cuentas que comparten alguna IP con este jugador. */
    public List<AltAccount> alts(UUID uuid) {
        if (!plugin.getSettings().altsEnabled()) {
            return List.of();
        }
        long since = System.currentTimeMillis() - plugin.getSettings().ipRetentionDays() * 86_400_000L;
        List<AltAccount> result = new ArrayList<>();
        for (String ip : ips(uuid)) {
            List<AltAccount> matches = accountsOnIp(ip, uuid, since);
            for (AltAccount match : matches) {
                if (result.stream().noneMatch(existing -> existing.uuid().equals(match.uuid()))) {
                    result.add(match);
                }
            }
        }
        return result;
    }

    /** Cuentas (distintas de {@code exclude}) que han usado una IP concreta. */
    public List<AltAccount> accountsOnIp(String ip, UUID exclude, long since) {
        return db.query("SELECT p.uuid, p.name, p.last_seen, i.ip FROM " + playerIps + " i "
                        + "JOIN " + players + " p ON p.uuid = i.uuid "
                        + "WHERE i.ip = ? AND i.last_seen >= ? ORDER BY p.last_seen DESC LIMIT 50",
                rs -> {
                    UUID id = UUID.fromString(rs.getString("uuid"));
                    String name = rs.getString("name");
                    return new AltAccount(id, name, rs.getString("ip"), rs.getLong("last_seen"),
                            Bukkit.getPlayer(id) != null);
                }, ip, since).stream().filter(alt -> exclude == null || !alt.uuid().equals(exclude)).toList();
    }
}
