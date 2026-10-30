package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.PunishmentType;
import com.admintools.model.Sanction;
import com.admintools.storage.Database;
import com.admintools.util.Text;
import com.admintools.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * El corazón del plugin: aplica, levanta y consulta sanciones.
 *
 * <p>Mantiene en memoria las sanciones activas para que las comprobaciones
 * del día a día (entrar al servidor, escribir en el chat) no toquen disco.</p>
 */
public final class SanctionService {

    /** Datos necesarios para aplicar una sanción. */
    public record Request(PunishmentType type, UUID targetUuid, String targetName, String ip, String reason,
                          long duration, UUID staffUuid, String staffName, boolean silent, String ladder,
                          String template) {

        public static Request of(PunishmentType type, UUID targetUuid, String targetName, String ip, String reason,
                                 long duration, CommandSender staff, boolean silent) {
            UUID staffUuid = staff instanceof Player player ? player.getUniqueId() : null;
            String staffName = staff instanceof Player player ? player.getName() : "CONSOLE";
            return new Request(type, targetUuid, targetName, ip, reason, duration, staffUuid, staffName, silent,
                    null, null);
        }

        public Request withLadder(String value) {
            return new Request(type, targetUuid, targetName, ip, reason, duration, staffUuid, staffName, silent, value,
                    template);
        }

        public Request withTemplate(String value) {
            return new Request(type, targetUuid, targetName, ip, reason, duration, staffUuid, staffName, silent, ladder,
                    value);
        }

        public Request silent(boolean value) {
            return new Request(type, targetUuid, targetName, ip, reason, duration, staffUuid, staffName, value, ladder,
                    template);
        }
    }

    private final AdminToolsPlugin plugin;
    private final Database db;
    private final String table;
    /** uuid -> sanciones activas de ese jugador */
    private volatile Map<UUID, List<Sanction>> activeByTarget = new ConcurrentHashMap<>();
    /** nombre en minúsculas -> sanciones activas creadas sobre ese nombre (jugador nunca visto) */
    private volatile Map<String, List<Sanction>> activeByName = new ConcurrentHashMap<>();
    /** ip -> ip-ban activo */
    private volatile Map<String, Sanction> activeIpBans = new ConcurrentHashMap<>();
    /** ip -> ip-mute activo */
    private volatile Map<String, Sanction> activeIpMutes = new ConcurrentHashMap<>();

    /** Protege el intercambio de la caché y el registro de cambios en vuelo. */
    private final Object cacheLock = new Object();
    /** ¿Hay una lectura de la base en curso? */
    private boolean refreshInFlight;
    /** Se pidió otro refresco mientras uno estaba en curso. */
    private boolean refreshQueued;
    /** Sanciones puestas o quitadas mientras se leía la base. */
    private final List<Sanction> pendingPuts = new ArrayList<>();
    private final List<Long> pendingDrops = new ArrayList<>();

    public SanctionService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        this.table = db.table("sanctions");
    }

    /**
     * UUID derivado del nombre (igual que el que usa el servidor en modo offline).
     * Es el que se guarda cuando el sancionado nunca ha entrado: así el registro
     * existe desde el primer momento y se asocia a su cuenta real cuando por fin conecta.
     */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Sanction map(ResultSet rs) throws SQLException {
        return new Sanction(
                rs.getLong("id"),
                PunishmentType.parse(rs.getString("type"), PunishmentType.BAN),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                rs.getString("ip"),
                rs.getString("reason"),
                rs.getString("staff_uuid") == null ? null : UUID.fromString(rs.getString("staff_uuid")),
                rs.getString("staff_name"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                rs.getInt("active") == 1,
                rs.getInt("silent") == 1,
                rs.getString("lifted_by"),
                rs.getString("lifted_name"),
                rs.getLong("lifted_at"),
                rs.getString("lift_reason"),
                rs.getString("ladder"),
                rs.getString("template"),
                rs.getString("origin"));
    }

    // ------------------------------------------------------------
    //  Caché de sanciones activas
    // ------------------------------------------------------------

    /** Relee de disco todas las sanciones activas (llamado al arrancar y cada minuto). */
    public void refreshCache() {
        synchronized (cacheLock) {
            if (refreshInFlight) {
                // Ya hay una lectura en curso: se encadena otra al terminar en vez de
                // descartarla, para que el último estado acabe publicado igualmente.
                refreshQueued = true;
                return;
            }
            refreshInFlight = true;
        }
        db.async(this::readCache);
    }

    /**
     * Lee las sanciones activas y publica los cuatro índices de golpe.
     *
     * <p>Antes se vaciaban y rellenaban los mapas en vivo: un {@code /ban} aplicado
     * justo entre la lectura y el relleno se quedaba fuera de la caché hasta el
     * siguiente refresco (hasta 20 s sin ban efectivo). Ahora lo que se sanciona o se
     * levanta durante la lectura se reaplica sobre el resultado antes de publicarlo.</p>
     */
    private void readCache() {
        List<Sanction> active;
        try {
            active = db.query("SELECT * FROM " + table + " WHERE active = 1", this::map);
        } catch (RuntimeException ex) {
            // Si la lectura falla se conserva la caché anterior y se libera el turno:
            // dejar el refresco marcado como "en curso" congelaría la caché para siempre.
            synchronized (cacheLock) {
                refreshInFlight = false;
                refreshQueued = false;
            }
            plugin.getLogger().warning("No pude refrescar la caché de sanciones: " + ex.getMessage());
            return;
        }
        Map<UUID, List<Sanction>> byTarget = new ConcurrentHashMap<>();
        Map<String, List<Sanction>> byName = new ConcurrentHashMap<>();
        Map<String, Sanction> ipBans = new ConcurrentHashMap<>();
        Map<String, Sanction> ipMutes = new ConcurrentHashMap<>();
        for (Sanction sanction : active) {
            // Las expulsiones y lo ya vencido no cuentan como restricción activa.
            if (sanction.expired() || sanction.type() == PunishmentType.KICK) {
                continue;
            }
            put(byTarget, byName, ipBans, ipMutes, sanction);
        }
        boolean again;
        synchronized (cacheLock) {
            for (Sanction sanction : pendingPuts) {
                put(byTarget, byName, ipBans, ipMutes, sanction);
            }
            for (Long id : pendingDrops) {
                dropById(byTarget, byName, ipBans, ipMutes, id);
            }
            pendingPuts.clear();
            pendingDrops.clear();
            activeByTarget = byTarget;
            activeByName = byName;
            activeIpBans = ipBans;
            activeIpMutes = ipMutes;
            refreshInFlight = false;
            again = refreshQueued;
            refreshQueued = false;
        }
        if (again) {
            refreshCache();
        }
    }

    /** Añade una sanción a los índices indicados, sin duplicarla si ya estaba. */
    private static void put(Map<UUID, List<Sanction>> byTarget, Map<String, List<Sanction>> byName,
                            Map<String, Sanction> ipBans, Map<String, Sanction> ipMutes, Sanction sanction) {
        List<Sanction> byUuid = byTarget.computeIfAbsent(sanction.targetUuid(),
                key -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (byUuid) {
            for (Sanction existing : byUuid) {
                if (existing.id() == sanction.id()) {
                    return; // repetirla contaría doble en advertencias y listados
                }
            }
            byUuid.add(sanction);
        }
        String name = sanction.targetName();
        if (name != null && !name.isBlank()) {
            List<Sanction> byNameList = byName.computeIfAbsent(name.toLowerCase(Locale.ROOT),
                    key -> Collections.synchronizedList(new ArrayList<>()));
            synchronized (byNameList) {
                byNameList.add(sanction);
            }
        }
        // La más reciente manda: con putIfAbsent, una entrada caducada de la misma IP
        // impediría que el ban nuevo llegara a la caché.
        if (sanction.type() == PunishmentType.IP_BAN && sanction.ip() != null) {
            ipBans.put(sanction.ip(), sanction);
        }
        if (sanction.type() == PunishmentType.IP_MUTE && sanction.ip() != null) {
            ipMutes.put(sanction.ip(), sanction);
        }
    }

    private void cachePut(Sanction sanction) {
        synchronized (cacheLock) {
            if (refreshInFlight) {
                pendingPuts.add(sanction);
            }
            put(activeByTarget, activeByName, activeIpBans, activeIpMutes, sanction);
        }
    }

    /**
     * Quita una sola sanción de la caché, sin tocar el resto de las del jugador.
     *
     * <p>Es lo que hace falta al levantar un tipo concreto (un warn tras el escalado,
     * un ban sin tocar el mute): borrar la entrada entera del jugador dejaría sus
     * demás sanciones activas fuera de la caché y, hasta el siguiente refresco, un
     * silenciado podría volver a escribir o un ban dejaría de verse.</p>
     */
    private void cacheDrop(Sanction sanction) {
        synchronized (cacheLock) {
            if (refreshInFlight) {
                pendingDrops.add(sanction.id());
            }
            dropById(activeByTarget, activeByName, activeIpBans, activeIpMutes, sanction.id());
        }
    }

    /** Quita de los índices la sanción con ese id (por UUID, por nombre y por IP). */
    private static void dropById(Map<UUID, List<Sanction>> byTarget, Map<String, List<Sanction>> byName,
                                 Map<String, Sanction> ipBans, Map<String, Sanction> ipMutes, long id) {
        ipBans.entrySet().removeIf(entry -> entry.getValue().id() == id);
        ipMutes.entrySet().removeIf(entry -> entry.getValue().id() == id);
        for (Map.Entry<UUID, List<Sanction>> entry : byTarget.entrySet()) {
            List<Sanction> list = entry.getValue();
            synchronized (list) {
                list.removeIf(existing -> existing.id() == id);
            }
            if (list.isEmpty()) {
                byTarget.remove(entry.getKey(), list);
            }
        }
        for (Map.Entry<String, List<Sanction>> entry : byName.entrySet()) {
            List<Sanction> list = entry.getValue();
            synchronized (list) {
                list.removeIf(existing -> existing.id() == id);
            }
            if (list.isEmpty()) {
                byName.remove(entry.getKey(), list);
            }
        }
    }

    /** Ban activo de una cuenta (por UUID y, si nunca entró, por nombre). */
    public Optional<Sanction> activeBan(UUID uuid) {
        return activeBan(uuid, null);
    }

    /** Ban activo de una cuenta, con nombre para las sanciones creadas sin ficha. */
    public Optional<Sanction> activeBan(UUID uuid, String name) {
        return firstActive(uuid, name, PunishmentType.BAN, PunishmentType.IP_BAN);
    }

    /** IP-ban activo para la IP indicada. */
    public Optional<Sanction> activeIpBan(String ip) {
        if (ip == null) {
            return Optional.empty();
        }
        Sanction sanction = activeIpBans.get(ip);
        return sanction != null && sanction.activeNow() ? Optional.of(sanction) : Optional.empty();
    }

    /** IP-mute activo para la IP indicada (silencio de todas las cuentas de esa conexión). */
    public Optional<Sanction> activeIpMute(String ip) {
        if (ip == null) {
            return Optional.empty();
        }
        Sanction sanction = activeIpMutes.get(ip);
        return sanction != null && sanction.activeNow() ? Optional.of(sanction) : Optional.empty();
    }

    /** Levanta los IP-mute activos de una dirección. */
    public int liftIp(String ip, CommandSender actor, String reason) {
        return liftIpFrom(activeIpMutes, ip, actor, reason, "IP-mute");
    }

    /**
     * Levanta los IP-ban activos de una dirección.
     *
     * <p>Hace falta un camino propio: las sanciones creadas con {@code /ipban <ip>} se
     * guardan sobre una IP y con UUID nulo, así que {@code /unban <ip>} no las encuentra
     * buscando por jugador y quedaban imposibles de quitar desde el juego.</p>
     */
    public int liftIpBan(String ip, CommandSender actor, String reason) {
        return liftIpFrom(activeIpBans, ip, actor, reason, "IP-ban");
    }

    /** Levanta las sanciones activas de una IP del índice indicado. */
    private int liftIpFrom(Map<String, Sanction> index, String ip, CommandSender actor, String reason,
                           String label) {
        int count = 0;
        for (Sanction sanction : List.copyOf(index.values())) {
            if (!ip.equals(sanction.ip()) || !sanction.activeNow()) {
                continue;
            }
            liftOne(sanction, actor, reason);
            cacheDrop(sanction);
            plugin.getSanctionAnnounce().sanctionEnded(sanction);
            count++;
        }
        if (count > 0) {
            refreshCache();
            plugin.getAuditService().log(actor, "sanction.lift", ip, count + " " + label);
        }
        return count;
    }

    /** Mute activo (normal o fantasma). */
    public Optional<Sanction> activeMute(UUID uuid) {
        return activeMute(uuid, null);
    }

    /** Mute activo (normal o fantasma), con nombre para las sanciones creadas sin ficha. */
    public Optional<Sanction> activeMute(UUID uuid, String name) {
        return firstActive(uuid, name, PunishmentType.MUTE, PunishmentType.SHADOW_MUTE);
    }

    public Optional<Sanction> firstActive(UUID uuid, PunishmentType... types) {
        return firstActive(uuid, null, types);
    }

    /** Primera sanción activa de los tipos indicados, por cuenta o por nombre. */
    public Optional<Sanction> firstActive(UUID uuid, String name, PunishmentType... types) {
        for (Sanction sanction : candidates(uuid, name)) {
            if (!sanction.activeNow()) {
                continue;
            }
            for (PunishmentType type : types) {
                if (sanction.type() == type) {
                    return Optional.of(sanction);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Sanciones que pueden aplicarle a quien entra o escribe: las de su UUID y,
     * si el jugador nunca ha tenido ficha, las creadas sobre su nombre con el
     * UUID provisional. Así un ban hecho a un nombre desconocido sí se aplica
     * en servidores en modo online, donde el UUID real es otro.
     */
    private List<Sanction> candidates(UUID uuid, String name) {
        List<Sanction> result = new ArrayList<>();
        List<Sanction> byUuid = activeByTarget.get(uuid);
        if (byUuid != null) {
            synchronized (byUuid) {
                result.addAll(byUuid);
            }
        }
        if (name != null && !name.isBlank()) {
            List<Sanction> byName = activeByName.get(name.toLowerCase(Locale.ROOT));
            if (byName != null) {
                synchronized (byName) {
                    for (Sanction sanction : byName) {
                        if (!sanction.targetUuid().equals(offlineUuid(sanction.targetName()))
                                || result.stream().anyMatch(existing -> existing.id() == sanction.id())) {
                            continue;
                        }
                        result.add(sanction);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Cuando un jugador sancionado por nombre entra por primera vez, su sanción
     * se asocia al UUID real de su cuenta para que historial, ficha y comprobaciones
     * posteriores funcionen con su cuenta de verdad.
     */
    public void adoptIfNeeded(UUID uuid, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        List<Sanction> byName = activeByName.get(name.toLowerCase(Locale.ROOT));
        if (byName == null) {
            return;
        }
        UUID placeholder = offlineUuid(name);
        boolean pending;
        synchronized (byName) {
            pending = byName.stream().anyMatch(sanction -> sanction.targetUuid().equals(placeholder));
        }
        if (!pending) {
            return;
        }
        db.async(() -> {
            int updated = db.update("UPDATE " + table + " SET target_uuid = ? WHERE LOWER(target_name) = ?"
                    + " AND target_uuid = ?", uuid.toString(), name.toLowerCase(Locale.ROOT),
                    placeholder.toString());
            if (updated > 0) {
                refreshCache();
                plugin.getLogger().info("Sanciones de " + name + " asociadas a su cuenta real (" + updated + ").");
            }
        });
    }

    public List<Sanction> activeAll(UUID uuid) {
        return activeAll(uuid, null);
    }

    /** Sanciones activas de la cuenta (incluidas las creadas sobre su nombre). */
    public List<Sanction> activeAll(UUID uuid, String name) {
        return candidates(uuid, name).stream().filter(Sanction::activeNow).toList();
    }

    // ------------------------------------------------------------
    //  Aplicar sanciones
    // ------------------------------------------------------------

    /** Aplica una sanción: la guarda, la anuncia, notifica al sancionado y audita. */
    public Sanction apply(Request request) {
        long now = System.currentTimeMillis();
        long expires = TimeUtil.expiresAt(now, request.duration());
        // La IP se guarda en las sanciones que actúan sobre ella (ban y mute).
        String ip = request.type() == PunishmentType.IP_BAN || request.type() == PunishmentType.IP_MUTE
                ? request.ip() : null;

        // Una expulsión se archiva ya cerrada: no es una restricción permanente.
        boolean active = request.type() != PunishmentType.KICK;
        long id = db.insert("INSERT INTO " + table
                        + " (type, target_uuid, target_name, ip, reason, staff_uuid, staff_name, created_at, expires_at,"
                        + " active, silent, ladder, template, origin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                request.type().name(), request.targetUuid().toString(), request.targetName(), ip, request.reason(),
                request.staffUuid() == null ? null : request.staffUuid().toString(), request.staffName(), now, expires,
                active ? 1 : 0, request.silent() ? 1 : 0, request.ladder(), request.template(), plugin.getServerName());

        Sanction sanction = new Sanction(id, request.type(), request.targetUuid(), request.targetName(), ip,
                request.reason(), request.staffUuid(), request.staffName(), now, expires, active, request.silent(),
                null, null, 0L, null, request.ladder(), request.template(), plugin.getServerName());
        cachePut(sanction);

        announce(sanction);
        notifyTarget(sanction);
        plugin.getAuditService().log(request.staffUuid() == null ? null : request.staffUuid().toString(),
                request.staffName(), "sanction." + request.type().name().toLowerCase(Locale.ROOT),
                request.targetName(), "#" + id + " " + request.reason());
        plugin.getIntegrations().discordSanction(sanction);
        plugin.getSanctionAnnounce().sanction(sanction);

        if (request.type() == PunishmentType.WARN) {
            plugin.getSanctionService().checkAutoEscalation(request.targetUuid(), request.targetName());
        }
        return sanction;
    }

    /** Mensaje público (o para el staff, si es leve o silenciosa). */
    private void announce(Sanction sanction) {
        if (sanction.silent() || !plugin.getSettings().sanctionsBroadcast()) {
            return;
        }
        // Una advertencia o una expulsión no tienen duración: anunciarlas con
        // "(permanente)" hacía pensar que eran un ban de la cuenta.
        boolean timed = sanction.type() != PunishmentType.WARN && sanction.type() != PunishmentType.KICK;
        Component message = plugin.getMessages().get(
                timed ? "sanctions.broadcast" : "sanctions.broadcast-no-duration",
                "type", sanction.type().display(),
                "player", sanction.targetName(),
                "reason", Text.safe(sanction.reason()),
                "duration", durationText(sanction),
                "staff", sanction.staffName());
        if (plugin.getSettings().showIpInBroadcast() && sanction.ip() != null) {
            message = message.append(Text.mm(" <dark_gray>[<gray>" + sanction.ip() + "<dark_gray>]"));
        }
        boolean loud = sanction.type().blocksLogin() || sanction.type() == PunishmentType.KICK;
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (loud || player.hasPermission("admintools.sanction.history")) {
                player.sendMessage(message);
            }
        }
    }

    /** Mensaje directo al sancionado (y expulsión si corresponde). */
    private void notifyTarget(Sanction sanction) {
        Player player = Bukkit.getPlayer(sanction.targetUuid());
        if (player == null) {
            return;
        }
        switch (sanction.type()) {
            case KICK -> player.kick(plugin.getMessages().get("sanctions.target-kicked",
                    "reason", Text.safe(sanction.reason()), "staff", sanction.staffName(),
                    "server", plugin.getServerName()));
            case BAN, IP_BAN -> player.kick(plugin.getMessages().get("sanctions.target-banned",
                    "reason", Text.safe(sanction.reason()), "duration", durationText(sanction),
                    "id", sanction.id(), "type", sanction.type().display(),
                    "server", plugin.getServerName()));
            case MUTE, IP_MUTE, SHADOW_MUTE -> player.sendMessage(plugin.getMessages().prefixed("sanctions.target-muted",
                    "reason", Text.safe(sanction.reason()), "duration", durationText(sanction),
                    "id", sanction.id()));
            case WARN -> {
                boolean escalating = plugin.getSettings().autoEscalateEnabled()
                        && plugin.getSettings().autoEscalateThreshold() > 0;
                int remaining = escalating
                        ? Math.max(0, plugin.getSettings().autoEscalateThreshold()
                                - warnCount(sanction.targetUuid(), sanction.targetName()))
                        : 0;
                // Sin escalado configurado no se puede prometer "te quedan N": se avisa y ya.
                String key = !escalating ? "sanctions.target-warned-plain"
                        : remaining == 0 ? "sanctions.target-warned-none"
                        : remaining == 1 ? "sanctions.target-warned-one"
                        : "sanctions.target-warned";
                player.sendMessage(plugin.getMessages().prefixed(key,
                        "reason", Text.safe(sanction.reason()), "warns", remaining));
            }
        }
    }

    /**
     * Duración *pedida* de la sanción, no lo que queda.
     *
     * <p>Calculada desde el tiempo restante, un ban de dos días se anunciaba como
     * "1d 23h 59m" un segundo después de aplicarlo, y un "1d30m" como "1d 29m".
     * Con las dos marcas guardadas (creación y expiración) el texto es exacto.</p>
     */
    private String durationText(Sanction sanction) {
        if (sanction.permanent()) {
            return plugin.getMessages().raw("generic.permanent");
        }
        long requested = sanction.expiresAt() - sanction.createdAt();
        return TimeUtil.format(requested > 0L ? requested : sanction.expiresAt() - System.currentTimeMillis());
    }

    /** Texto de duración listo para mostrar a partir de milisegundos. */
    public String durationText(long duration) {
        return duration == TimeUtil.PERMANENT ? plugin.getMessages().raw("generic.permanent") : TimeUtil.format(duration);
    }

    /** Aviso al staff cuando el jugador sancionado entra al servidor. */
    public void notifyStaffOnJoin(Player player) {
        if (!plugin.getSettings().notifyStaffOnJoin()) {
            return;
        }
        for (Sanction sanction : activeAll(player.getUniqueId(), player.getName())) {
            if (sanction.type() == PunishmentType.WARN) {
                continue; // las advertencias se ven en /check, no en el aviso de entrada
            }
            Component message = plugin.getMessages().get("sanctions.join-notice",
                    "player", player.getName(),
                    "type", sanction.type().display(),
                    "id", sanction.id(),
                    "reason", Text.safe(sanction.reason()));
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("admintools.sanction.history")) {
                    staff.sendMessage(message);
                }
            }
            Bukkit.getConsoleSender().sendMessage(message);
        }
    }

    // ------------------------------------------------------------
    //  Levantar sanciones
    // ------------------------------------------------------------

    /** Levanta todas las sanciones activas de los tipos indicados. Devuelve cuántas. */
    public int lift(UUID targetUuid, String targetName, CommandSender actor, String reason, PunishmentType... types) {
        List<Sanction> active = db.query("SELECT * FROM " + table + " WHERE active = 1 AND target_uuid = ?",
                this::map, targetUuid.toString());
        int count = 0;
        for (Sanction sanction : active) {
            boolean match = types.length == 0;
            for (PunishmentType type : types) {
                if (sanction.type() == type) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                continue;
            }
            liftOne(sanction, actor, reason);
            cacheDrop(sanction);
            // Levantarla antes de tiempo también es el fin de la sanción: se anuncia.
            plugin.getSanctionAnnounce().sanctionEnded(sanction);
            count++;
        }
        refreshCache();
        if (count > 0) {
            plugin.getAuditService().log(actor, "sanction.lift", targetName,
                    count + " sanciones · " + (reason == null || reason.isBlank() ? "sin motivo" : reason));
            boolean single = types.length == 1;
            Component message = single
                    ? plugin.getMessages().get("sanctions.unban-broadcast",
                            "staff", actor instanceof Player player ? player.getName() : "CONSOLE",
                            "type", types[0].display(), "player", targetName,
                            "reason", reason == null || reason.isBlank() ? "-" : Text.safe(reason))
                    : plugin.getMessages().get(
                            plugin.getMessages().plural("sanctions.lift-broadcast-multi", count),
                            "staff", actor instanceof Player player ? player.getName() : "CONSOLE",
                            "player", targetName, "count", count,
                            "reason", reason == null || reason.isBlank() ? "-" : Text.safe(reason));
            Bukkit.getConsoleSender().sendMessage(message);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("admintools.sanction.history")) {
                    online.sendMessage(message);
                }
            }
        }
        return count;
    }

    private void liftOne(Sanction sanction, CommandSender actor, String reason) {
        long now = System.currentTimeMillis();
        String uuid = actor instanceof Player player ? player.getUniqueId().toString() : null;
        String name = actor instanceof Player player ? player.getName() : "CONSOLE";
        db.update("UPDATE " + table + " SET active = 0, lifted_by = ?, lifted_name = ?, lifted_at = ?, lift_reason = ?"
                + " WHERE id = ?", uuid, name, now, reason, sanction.id());
        sanction.active(false);
        sanction.lifted(name, reason);
        sanction.liftedBy(uuid);
    }

    /** Revierte todo lo aplicado por un miembro del staff (cuenta comprometida). */
    public int liftByStaff(String staffName, CommandSender actor, String reason) {
        List<Sanction> active = db.query("SELECT * FROM " + table + " WHERE active = 1 AND LOWER(staff_name) = ?",
                this::map, staffName.toLowerCase(Locale.ROOT));
        for (Sanction sanction : active) {
            liftOne(sanction, actor, reason == null ? "Revisión de sanciones del staff" : reason);
        }
        refreshCache();
        for (Sanction sanction : active) {
            plugin.getIntegrations().discordLift(sanction, actor instanceof Player player ? player.getName() : "CONSOLE");
        }
        if (!active.isEmpty()) {
            plugin.getAuditService().log(actor, "sanction.rollback-staff", staffName,
                    active.size() + " sanciones revertidas");
        }
        return active.size();
    }

    // ------------------------------------------------------------
    //  Consultas
    // ------------------------------------------------------------

    public Optional<Sanction> byId(long id) {
        return db.queryOne("SELECT * FROM " + table + " WHERE id = ?", this::map, id);
    }

    public List<Sanction> history(UUID targetUuid, int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        return db.query("SELECT * FROM " + table + " WHERE target_uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                this::map, targetUuid.toString(), size, offset);
    }

    public List<Sanction> recent(UUID targetUuid, int limit) {
        return db.query("SELECT * FROM " + table + " WHERE target_uuid = ? ORDER BY id DESC LIMIT ?",
                this::map, targetUuid.toString(), limit);
    }

    public int historyCount(UUID targetUuid) {
        return (int) db.queryLong("SELECT COUNT(*) FROM " + table + " WHERE target_uuid = ?", targetUuid.toString());
    }

    public int countAllWithin(UUID targetUuid, long since) {
        return (int) db.queryLong("SELECT COUNT(*) FROM " + table + " WHERE target_uuid = ? AND created_at >= ?",
                targetUuid.toString(), since);
    }

    public int warnCount(UUID targetUuid) {
        return warnCount(targetUuid, null);
    }

    /** Advertencias activas que aún cuentan (sin las caducadas por decaimiento). */
    public int warnCount(UUID targetUuid, String targetName) {
        long since = warnDecaySince();
        return (int) activeAll(targetUuid, targetName).stream()
                .filter(sanction -> sanction.type() == PunishmentType.WARN)
                .filter(sanction -> since <= 0L || sanction.createdAt() >= since)
                .count();
    }

    /**
     * Momento desde el que una advertencia cuenta para el escalado.
     * 0 significa que no caducan (config {@code sanctions.warn-decay-days: 0}).
     */
    private long warnDecaySince() {
        int days = plugin.getSettings().warnDecayDays();
        return days <= 0 ? 0L : System.currentTimeMillis() - days * 86_400_000L;
    }

    // ------------------------------------------------------------
    //  Límites por rango y escalado automático
    // ------------------------------------------------------------

    /** Duración máxima que puede aplicar quien ejecuta el comando. */
    public long durationLimit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return TimeUtil.PERMANENT;
        }
        long best = 0L;
        for (Map.Entry<String, String> entry : plugin.getSettings().durationLimits().entrySet()) {
            if (!player.hasPermission(entry.getKey())) {
                continue;
            }
            Long parsed = TimeUtil.parse(entry.getValue());
            if (parsed == null) {
                continue;
            }
            if (parsed == TimeUtil.PERMANENT) {
                return TimeUtil.PERMANENT;
            }
            best = Math.max(best, parsed);
        }
        return best;
    }

    /** ¿Puede aplicar una duración concreta? */
    public boolean withinLimit(CommandSender sender, long duration) {
        long limit = durationLimit(sender);
        if (limit == TimeUtil.PERMANENT) {
            return true;
        }
        if (duration == TimeUtil.PERMANENT) {
            return false;
        }
        return duration <= limit;
    }

    public String limitText(CommandSender sender) {
        return durationText(durationLimit(sender));
    }

    /**
     * Escalado automático: al llegar al umbral de advertencias aplica la acción configurada.
     *
     * <p>Sólo cuentan las advertencias que siguen activas. Las que ya se canjearon por una
     * sanción quedan levantadas y no pueden volver a contar: si se contaran (como hacía la
     * consulta por fecha), la siguiente advertencia dentro de la ventana volvería a escalar
     * y el jugador encadenaría sanciones por cada warn nuevo.</p>
     */
    public void checkAutoEscalation(UUID targetUuid, String targetName) {
        if (!plugin.getSettings().autoEscalateEnabled()) {
            return;
        }
        int threshold = plugin.getSettings().autoEscalateThreshold();
        if (threshold <= 0) {
            return;
        }
        PunishmentType type = plugin.getSettings().autoEscalateType();
        if (type == PunishmentType.WARN) {
            return; // escalar a otra advertencia sería un bucle
        }
        long since = escalationWindowStart();
        List<Sanction> counting = activeAll(targetUuid, targetName).stream()
                .filter(sanction -> sanction.type() == PunishmentType.WARN)
                .filter(sanction -> sanction.createdAt() >= since)
                .toList();
        if (counting.size() < threshold) {
            return;
        }
        long duration = plugin.getSettings().autoEscalateDuration();
        // Se canjean las advertencias por la sanción: así el contador vuelve a cero.
        for (Sanction warn : counting) {
            liftOne(warn, Bukkit.getConsoleSender(), "Escalado automático");
            cacheDrop(warn);
        }
        apply(new Request(type, targetUuid, targetName, ipOf(targetUuid), plugin.getSettings()
                .autoEscalateReason(), duration, null, "Servidor", false, null, null));
    }

    /**
     * Inicio de la ventana de escalado: la más estricta entre la del propio escalado y
     * el decaimiento de advertencias.
     */
    private long escalationWindowStart() {
        long since = System.currentTimeMillis() - plugin.getSettings().autoEscalateWindowDays() * 86_400_000L;
        return Math.max(since, warnDecaySince());
    }

    /** IP conocida del jugador (online o de su última sesión). */
    public String ipOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.getAddress() != null) {
            return online.getAddress().getAddress().getHostAddress();
        }
        var record = plugin.getPlayerService().get(uuid);
        if (record.lastIp() != null) {
            return record.lastIp();
        }
        List<String> ips = plugin.getPlayerService().ips(uuid);
        return ips.isEmpty() ? null : ips.getFirst();
    }

    // ------------------------------------------------------------
    //  Expiración
    // ------------------------------------------------------------

    /**
     * Marca como inactivas las sanciones temporales ya vencidas.
     *
     * <p>Corre cada 30 segundos: la consulta y las bajas van al pool de la base y
     * sólo el aviso al staff vuelve al hilo del servidor, así que la tabla puede
     * crecer sin que esto se note en el tic.</p>
     */
    public void expireDue() {
        long now = System.currentTimeMillis();
        db.async(() -> {
            List<Sanction> due = db.query("SELECT * FROM " + table
                            + " WHERE active = 1 AND expires_at > 0 AND expires_at <= ?",
                    this::map, now);
            if (due.isEmpty()) {
                return;
            }
            List<Sanction> expired = new ArrayList<>(due.size());
            for (Sanction sanction : due) {
                // "AND active = 1" hace la baja idempotente: si dos pasadas de
                // expireDue se solapan (una base lenta tarda más que el intervalo),
                // sólo la primera actualiza y sólo se avisa una vez.
                int updated = db.update("UPDATE " + table
                                + " SET active = 0, lifted_name = ?, lifted_at = ?, lift_reason = ?"
                                + " WHERE id = ? AND active = 1",
                        "Expiración automática", now, "Sanción expirada", sanction.id());
                if (updated > 0) {
                    expired.add(sanction);
                }
            }
            refreshCache();
            if (!expired.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> notifyExpired(expired));
            }
        });
    }

    /** Aviso de las sanciones que acaban de expirar (siempre en el hilo del servidor). */
    private void notifyExpired(List<Sanction> expired) {
        for (Sanction sanction : expired) {
            // Anuncio público del fin: sólo llega aquí la pasada que de verdad cerró la
            // sanción, así que no se repite tras un reinicio ni con dos pasadas solapadas.
            plugin.getSanctionAnnounce().sanctionEnded(sanction);
            Component message = plugin.getMessages().get("sanctions.expired",
                    "id", sanction.id(), "player", sanction.targetName(), "type", sanction.type().display());
            Bukkit.getConsoleSender().sendMessage(message);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("admintools.sanction.history")) {
                    staff.sendMessage(message);
                }
            }
        }
    }
}
