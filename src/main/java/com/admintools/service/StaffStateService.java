package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado en memoria de las herramientas de staff: vanish, espías, chat de staff,
 * invulnerabilidad y la última posición para /back.
 */
public final class StaffStateService {

    private final AdminToolsPlugin plugin;
    private final Set<UUID> vanished = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<UUID> socialSpy = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<UUID> commandSpy = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<UUID> staffChat = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<UUID> god = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, Location> back = new ConcurrentHashMap<>();
    /** vista abierta -> permiso que hace falta para modificarla (invsee/enderchest) */
    private final Map<UUID, String> inventoryPermission = new ConcurrentHashMap<>();
    /** Dónde se guarda el vanish para que sobreviva a reconexiones y reinicios. */
    private final File vanishFile;

    public StaffStateService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.vanishFile = new File(plugin.getDataFolder(), "vanish.yml");
        loadVanish();
    }

    /** Lee los jugadores que quedaron en vanish la última vez que el servidor los vio. */
    private void loadVanish() {
        if (!vanishFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(vanishFile);
        for (String raw : yaml.getStringList("vanished")) {
            try {
                vanished.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // Entrada corrupta: se ignora en vez de romper el arranque.
            }
        }
    }

    /** Escribe el estado actual. Se llama sólo cuando cambia, no en cada entrada. */
    private void saveVanish() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        for (UUID id : vanished) {
            ids.add(id.toString());
        }
        yaml.set("vanished", ids);
        try {
            yaml.save(vanishFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar vanish.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------
    //  Vanish
    // ------------------------------------------------------------

    public boolean vanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    /** Alterna el vanish y ajusta la visibilidad para todos los conectados. */
    public boolean toggleVanish(Player player) {
        UUID uuid = player.getUniqueId();
        if (vanished.remove(uuid)) {
            // Vuelve a ser visible también para los que entren a partir de ahora.
            player.setVisibleByDefault(true);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) {
                    online.showPlayer(plugin, player);
                }
            }
            saveVanish();
            fakeJoin(player);
            plugin.getMessages().send(player, "staff.vanish-off");
            return false;
        }
        vanished.add(uuid);
        saveVanish();
        // Los que entren después tampoco deben verlo, sin depender del orden de los eventos.
        player.setVisibleByDefault(false);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player) && !online.hasPermission("admintools.staff.vanish.see")) {
                online.hidePlayer(plugin, player);
            }
        }
        fakeQuit(player);
        plugin.getMessages().send(player, "staff.vanish-on");
        return true;
    }

    /**
     * Reaplica el vanish cuando el jugador entra (o lo activa para otros al entrar alguien nuevo).
     *
     * <p>La visibilidad se reconcilia en los dos sentidos: si consta en vanish se vuelve a
     * ocultar, y si no consta se vuelve a mostrar. Los clientes guardan por UUID a quién
     * tienen oculto y ese registro no se borra al reconectar, así que sin esta segunda mitad
     * un staff podría quedar invisible sin estar en vanish: ni los demás lo verían ni el
     * plugin abriría los contenedores en silencio.</p>
     */
    public void onJoin(Player joiner) {
        UUID uuid = joiner.getUniqueId();
        boolean invisible = vanished(uuid);
        joiner.setVisibleByDefault(!invisible);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(joiner)) {
                continue;
            }
            if (invisible) {
                if (!online.hasPermission("admintools.staff.vanish.see")) {
                    online.hidePlayer(plugin, joiner);
                }
            } else {
                online.showPlayer(plugin, joiner);
            }
        }
        if (invisible) {
            plugin.getMessages().send(joiner, "staff.vanish-restored");
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(joiner) && vanished(online.getUniqueId())
                    && !joiner.hasPermission("admintools.staff.vanish.see")) {
                joiner.hidePlayer(plugin, online);
            }
        }
    }

    /** Mensaje falso de desconexión para el vanish (sólo lo ven los jugadores normales). */
    public void fakeQuit(Player player) {
        Component message = plugin.getMessages().get("staff.vanish-fake-quit", "player", player.getName());
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player) && !online.hasPermission("admintools.staff.vanish.see")) {
                online.sendMessage(message);
            }
        }
    }

    /** Mensaje falso de conexión para el vanish. */
    public void fakeJoin(Player player) {
        Component message = plugin.getMessages().get("staff.vanish-fake-join", "player", player.getName());
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player) && !online.hasPermission("admintools.staff.vanish.see")) {
                online.sendMessage(message);
            }
        }
    }
    /** Aviso a todo el staff conectado + consola. */
    public void sendToStaff(Component message) {
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("admintools.staff.staffchat") || online.hasPermission("admintools.staff.vanish")) {
                online.sendMessage(message);
            }
        }
    }

    // ------------------------------------------------------------
    //  Espías y chat de staff
    // ------------------------------------------------------------

    public boolean toggleSocialSpy(Player player) {
        UUID uuid = player.getUniqueId();
        if (socialSpy.remove(uuid)) {
            plugin.getMessages().send(player, "staff.spy-off");
            return false;
        }
        socialSpy.add(uuid);
        plugin.getMessages().send(player, "staff.spy-on");
        return true;
    }

    public boolean socialSpy(UUID uuid) {
        return socialSpy.contains(uuid);
    }

    public boolean toggleCommandSpy(Player player) {
        UUID uuid = player.getUniqueId();
        if (commandSpy.remove(uuid)) {
            plugin.getMessages().send(player, "staff.cspy-off");
            return false;
        }
        commandSpy.add(uuid);
        plugin.getMessages().send(player, "staff.cspy-on");
        return true;
    }

    public boolean commandSpy(UUID uuid) {
        return commandSpy.contains(uuid);
    }

    /** Alterna el canal de staff permanente (los mensajes normales van al staff). */
    public boolean toggleStaffChat(Player player) {
        UUID uuid = player.getUniqueId();
        if (staffChat.remove(uuid)) {
            return false;
        }
        staffChat.add(uuid);
        return true;
    }

    public boolean staffChat(UUID uuid) {
        return staffChat.contains(uuid);
    }

    // ------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------

    public boolean toggleGod(Player player) {
        UUID uuid = player.getUniqueId();
        if (god.remove(uuid)) {
            return false;
        }
        god.add(uuid);
        return true;
    }

    public boolean god(UUID uuid) {
        return god.contains(uuid);
    }

    public void rememberBack(Player player) {
        back.put(player.getUniqueId(), player.getLocation().clone());
    }

    // ------------------------------------------------------------
    //  Inventarios abiertos por el staff
    // ------------------------------------------------------------

    /** Recuerda qué permiso corresponde a la vista que acaba de abrir el staff. */
    public void trackInventory(UUID viewer, String permission) {
        inventoryPermission.put(viewer, permission);
    }

    /** Permiso de la vista abierta (o null si no la abrió el plugin). */
    public String inventoryPermission(UUID viewer) {
        return inventoryPermission.get(viewer);
    }

    public void forgetInventory(UUID viewer) {
        inventoryPermission.remove(viewer);
    }

    public Optional<Location> back(UUID uuid) {
        return Optional.ofNullable(back.get(uuid));
    }

    /**
     * Estado de sesión que se olvida al salir: espías, /back y las vistas abiertas.
     *
     * <p>El <b>vanish sí se conserva</b>, también en disco ({@code vanish.yml}), así que
     * sobrevive a la reconexión, a una recarga del plugin y a un reinicio del servidor. El
     * bloqueo de visibilidad entre clientes no se resetea al reconectar (cada jugador
     * guarda por UUID a quién le ha ocultado), así que si se borrara el estado quedaría el
     * peor de los mundos: el staff seguiría invisible para los demás mientras el plugin
     * cree que está visible, con los contenedores abriéndose con animación y sonido. Se
     * apaga con {@code /vanish}.</p>
     */
    public void forget(UUID uuid) {
        back.remove(uuid);
        inventoryPermission.remove(uuid);
        socialSpy.remove(uuid);
        commandSpy.remove(uuid);
        staffChat.remove(uuid);
        god.remove(uuid);
    }
}
