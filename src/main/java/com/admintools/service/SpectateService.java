package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.util.TimeUtil;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Espectador de vigilancia: una herramienta del modo staff con tiempo limitado.
 *
 * <p>Pone al jugador en el espectador <b>de verdad</b> (no una imitación), así que
 * atraviesa bloques, no puede tocar nada y los demás no lo ven. A cambio pierde la
 * barra rápida mientras dura, por eso el límite es corto y hay un comando para salir
 * antes de tiempo ({@code /spectate}).</p>
 *
 * <p>El modo de juego anterior, el vuelo y el momento de caducidad se guardan en
 * {@code spectate.yml}: si el servidor se cae con alguien dentro, al volver a entrar
 * se le devuelve su modo real en vez de dejarlo atrapado en espectador.</p>
 */
public final class SpectateService {

    /** Estado de una sesión activa. */
    private record Session(GameMode mode, boolean allowFlight, boolean flying, long startedAt, long expiresAt) {
    }

    private final AdminToolsPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** uuid -> instante en el que vuelve a estar disponible. */
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final File file;

    public SpectateService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spectate.yml");
        load();
    }

    // ------------------------------------------------------------
    //  Consultas
    // ------------------------------------------------------------

    public boolean enabled() {
        return plugin.getSettings().staffSpectateEnabled();
    }

    public boolean active(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    /** Milisegundos que le quedan de espectador, o 0 si no está en él. */
    public long remaining(UUID uuid) {
        Session session = sessions.get(uuid);
        return session == null ? 0L : Math.max(0L, session.expiresAt() - System.currentTimeMillis());
    }

    /** Milisegundos que faltan para poder volver a usarlo, o 0 si está disponible. */
    public long cooldownRemaining(UUID uuid) {
        Long until = cooldowns.get(uuid);
        if (until == null) {
            return 0L;
        }
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0L) {
            cooldowns.remove(uuid);
            return 0L;
        }
        return remaining;
    }

    // ------------------------------------------------------------
    //  Entrar y salir
    // ------------------------------------------------------------

    /** Alterna el modo; es lo que llaman la herramienta y {@code /spectate}. */
    public void toggle(Player player) {
        if (!enabled()) {
            plugin.getMessages().send(player, "staff.spectate-disabled");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (active(uuid)) {
            stop(player, "manual", true);
            return;
        }
        if (!player.hasPermission("admintools.staff.mode")) {
            plugin.getMessages().send(player, "generic.no-permission");
            return;
        }
        long cooldown = cooldownRemaining(uuid);
        if (cooldown > 0L) {
            plugin.getMessages().send(player, "staff.spectate-cooldown", "time", TimeUtil.format(cooldown));
            return;
        }
        // Si ya estaba en espectador por otra vía (/gamemode), salir restauraría
        // un modo que no nos consta: mejor pedir que lo deje antes.
        if (player.getGameMode() == GameMode.SPECTATOR) {
            plugin.getMessages().send(player, "staff.spectate-already");
            return;
        }
        start(player);
    }

    private void start(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long duration = plugin.getSettings().staffSpectateMillis();
        Session session = new Session(player.getGameMode(), player.getAllowFlight(), player.isFlying(),
                now, now + duration);
        sessions.put(uuid, session);

        try {
            player.setGameMode(GameMode.SPECTATOR);
        } catch (RuntimeException ex) {
            // Otro plugin puede reventar el cambio (o cancelarlo y lanzar): la sesión
            // no se queda viva con el jugador fuera del espectador.
            sessions.remove(uuid, session);
            plugin.getMessages().send(player, "staff.spectate-blocked");
            return;
        }
        // Un plugin de protecciones puede negar el cambio: si seguimos fuera del
        // espectador, no hay sesión que mantener.
        if (player.getGameMode() != GameMode.SPECTATOR) {
            sessions.remove(uuid, session);
            plugin.getMessages().send(player, "staff.spectate-blocked");
            return;
        }
        save();
        plugin.getMessages().send(player, "staff.spectate-on",
                "time", TimeUtil.format(duration),
                "cooldown", plugin.getSettings().staffSpectateCooldownMillis() <= 0L ? "0s"
                        : TimeUtil.format(plugin.getSettings().staffSpectateCooldownMillis()));
        plugin.getAuditService().log(player, "spectate.start", player.getName(),
                "limitado a " + TimeUtil.format(duration));
        plugin.getStaffLog().action(player, "spectate", "entra en espectador (" + TimeUtil.format(duration) + ")");
    }

    /**
     * Termina la sesión y devuelve el modo de juego.
     *
     * @param reason  motivo para la auditoría ({@code manual}, {@code timeout}, {@code quit}…)
     * @param notify  si se avisa al jugador (no tiene sentido al desconectarse)
     */
    public void stop(Player player, String reason, boolean notify) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            // Sin objetivo de cámara: si estaba viendo a alguien, primero se suelta.
            if (player.getSpectatorTarget() != null) {
                player.setSpectatorTarget(null);
            }
            if (player.isOnline()) {
                if (player.getGameMode() != session.mode()) {
                    player.setGameMode(session.mode());
                }
                player.setAllowFlight(session.allowFlight());
                if (session.allowFlight() && session.flying()) {
                    player.setFlying(true);
                }
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("No pude devolver el modo de juego de " + player.getName()
                    + " al salir del espectador: " + ex.getMessage());
        }

        long cooldown = plugin.getSettings().staffSpectateCooldownMillis();
        if (cooldown > 0L) {
            cooldowns.put(player.getUniqueId(), now + cooldown);
        }
        save();

        String used = TimeUtil.format(Math.max(0L, now - session.startedAt()));
        if (notify && player.isOnline()) {
            plugin.getMessages().send(player, "staff.spectate-off", "used", used);
        }
        plugin.getAuditService().log(player, "spectate.stop", player.getName(),
                reason + " · usado " + used);
        plugin.getStaffLog().action(player, "spectate", "sale del espectador (" + reason + ", usado " + used + ")");
    }

    /** El staff sale del modo staff: el espectador no debe sobrevivirle. */
    public void endForStaffMode(Player player) {
        if (active(player.getUniqueId())) {
            stop(player, "staffmode", true);
        }
    }

    /** Al desconectar: se devuelve el modo antes de que se guarde el jugador. */
    public void onQuit(Player player) {
        if (active(player.getUniqueId())) {
            stop(player, "quit", false);
        }
    }

    /**
     * El servidor se cerró con una sesión viva: se restaura el modo guardado.
     * La recarga se respeta igual, porque el turno ya se gastó.
     */
    public void onJoin(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        sessions.remove(player.getUniqueId());
        try {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                player.setGameMode(session.mode());
            }
            player.setAllowFlight(session.allowFlight());
            if (session.allowFlight() && session.flying()) {
                player.setFlying(true);
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("No pude restaurar el modo de " + player.getName()
                    + " tras un cierre en espectador: " + ex.getMessage());
        }
        long cooldown = plugin.getSettings().staffSpectateCooldownMillis();
        if (cooldown > 0L) {
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldown);
        }
        save();
        plugin.getMessages().send(player, "staff.spectate-restored");
        plugin.getStaffLog().action(player, "spectate", "sesión interrumpida por un cierre del servidor");
    }

    /** Apagado del plugin: devuelve el modo a todos los conectados. */
    public void shutdown() {
        for (UUID uuid : Map.copyOf(sessions).keySet()) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                stop(player, "shutdown", false);
            } else {
                sessions.remove(uuid);
            }
        }
        save();
    }

    /** Comprueba cada segundo si a alguna sesión se le acabó el tiempo. */
    public void tick() {
        if (sessions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (UUID uuid : Map.copyOf(sessions).keySet()) {
            Session session = sessions.get(uuid);
            if (session == null || session.expiresAt() > now) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) {
                sessions.remove(uuid);
                save();
                continue;
            }
            stop(player, "timeout", true);
        }
    }

    // ------------------------------------------------------------
    //  Persistencia
    // ------------------------------------------------------------

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cooldownSection = yaml.getConfigurationSection("cooldowns");
        if (cooldownSection != null) {
            long now = System.currentTimeMillis();
            for (String raw : cooldownSection.getKeys(false)) {
                try {
                    long until = cooldownSection.getLong(raw);
                    if (until > now) {
                        cooldowns.put(UUID.fromString(raw), until);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Entrada corrupta: se descarta en vez de romper el arranque.
                }
            }
        }
        ConfigurationSection sessionSection = yaml.getConfigurationSection("sessions");
        if (sessionSection != null) {
            for (String raw : sessionSection.getKeys(false)) {
                ConfigurationSection entry = sessionSection.getConfigurationSection(raw);
                if (entry == null) {
                    continue;
                }
                try {
                    GameMode mode = GameMode.valueOf(entry.getString("mode", "SURVIVAL"));
                    sessions.put(UUID.fromString(raw), new Session(mode,
                            entry.getBoolean("allow-flight", false),
                            entry.getBoolean("flying", false),
                            entry.getLong("started-at", System.currentTimeMillis()),
                            entry.getLong("expires-at", System.currentTimeMillis())));
                } catch (IllegalArgumentException ignored) {
                    // Modo o UUID ilegible: se ignora.
                }
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Long> entry : cooldowns.entrySet()) {
            if (entry.getValue() > System.currentTimeMillis()) {
                yaml.set("cooldowns." + entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            String base = "sessions." + entry.getKey() + ".";
            Session session = entry.getValue();
            yaml.set(base + "mode", session.mode().name());
            yaml.set(base + "allow-flight", session.allowFlight());
            yaml.set(base + "flying", session.flying());
            yaml.set(base + "started-at", session.startedAt());
            yaml.set(base + "expires-at", session.expiresAt());
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("No pude guardar spectate.yml: " + ex.getMessage());
        }
    }
}
