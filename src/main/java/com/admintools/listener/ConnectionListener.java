package com.admintools.listener;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.AltAccount;
import com.admintools.model.Sanction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Entradas y salidas: bans, mantenimiento, cuentas alternativas, copias y avisos. */
public final class ConnectionListener implements Listener {

    private final AdminToolsPlugin plugin;

    public ConnectionListener(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Antes de que el jugador entre al mundo: mantenimiento, bans (cuenta e IP)
     * y bloqueo de multicuenta. Este evento corre fuera del hilo principal, así
     * que sólo se tocan datos en memoria.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        String ip = event.getAddress() == null ? null : event.getAddress().getHostAddress();

        if (plugin.getMaintenanceService().enabled() && !hasPermission(uuid, "admintools.ops.maintenance.bypass")) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.getMessages().get("ops.maintenance-kick"));
            return;
        }

        // Por si su sanción se creó sobre el nombre: se asocia a su cuenta real.
        plugin.getSanctionService().adoptIfNeeded(uuid, name);

        Optional<Sanction> ban = plugin.getSanctionService().activeBan(uuid, name);
        if (ban.isEmpty() && ip != null) {
            ban = plugin.getSanctionService().activeIpBan(ip);
        }
        if (ban.isPresent()) {
            Sanction sanction = ban.get();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    plugin.getMessages().get("sanctions.target-banned",
                            "reason", com.admintools.util.Text.safe(sanction.reason()),
                            "duration", sanction.permanent() ? plugin.getMessages().raw("generic.permanent")
                                    : com.admintools.util.TimeUtil.remaining(sanction.expiresAt()),
                            "id", sanction.id()));
            plugin.getAuditService().log((String) null, "CONSOLE", "login.blocked", name,
                    "Sanción #" + sanction.id());
            return;
        }

        if (plugin.getSettings().altsBlockMulti() && ip != null && !plugin.getSettings().altsCgnatExempt()) {
            List<AltAccount> online = plugin.getPlayerService().accountsOnIp(ip, uuid,
                    System.currentTimeMillis() - 86_400_000L).stream().filter(AltAccount::online).toList();
            if (!online.isEmpty()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        plugin.getMessages().get("investigate.alts-multi-account-blocked"));
            }
        }
    }

/**
     * Permiso de un jugador que todavía no entró: op directo, LuckPerms
     * (si está instalado) o el valor por defecto declarado en plugin.yml.
     */
    private boolean hasPermission(UUID uuid, String node) {
        for (var operator : Bukkit.getOperators()) {
            if (operator.getUniqueId().equals(uuid)) {
                return true;
            }
        }
        Boolean fromLuckPerms = plugin.getIntegrations().luckPerms().hasPermissionOffline(uuid, node);
        if (fromLuckPerms != null) {
            return fromLuckPerms;
        }
        var permission = Bukkit.getPluginManager().getPermission(node);
        return permission != null
                && permission.getDefault() == org.bukkit.permissions.PermissionDefault.TRUE;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String ip = player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress();
        plugin.getPlayerService().onJoin(player.getUniqueId(), player.getName(), ip);
        plugin.getStaffState().onJoin(player);
        // En vanish nadie debe ver que entró al servidor.
        if (plugin.getStaffState().vanished(player.getUniqueId())) {
            event.joinMessage(null);
        }
        plugin.getSanctionService().notifyStaffOnJoin(player);

        // Cuenta nueva que comparte IP con otra: aviso al staff
        if (plugin.getSettings().altsNotifyStaff() && ip != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<AltAccount> alts = plugin.getPlayerService().alts(player.getUniqueId());
                if (alts.isEmpty()) {
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> plugin.getStaffState().sendToStaff(
                        plugin.getMessages().get("sanctions.join-notice", "player", player.getName(),
                                "type", "cuentas compartidas (" + alts.size() + ")",
                                "id", alts.getFirst().name(), "reason", "misma IP")));
            });
        }

        if (plugin.getPlayerService().frozen(player.getUniqueId())) {
            player.sendMessage(plugin.getMessages().get("staff.freeze-target"));
            plugin.getStaffState().sendToStaff(plugin.getMessages().get("staff.freeze-warn-reconnect",
                    "player", player.getName(), "server", plugin.getServerName()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Si estaba en modo staff, se devuelve el inventario ANTES de guardar la copia de
        // salida: este listener corre antes que el del modo staff y, sin esto, la copia
        // "Salida del servidor" (la más reciente, la que usa /restore) contendría la
        // hotbar de herramientas en vez de sus objetos de verdad.
        if (plugin.getStaffMode().active(player.getUniqueId())) {
            plugin.getStaffMode().disable(player);
        }
        if (plugin.getStaffState().vanished(player.getUniqueId())) {
            event.quitMessage(null);
            plugin.getStaffState().fakeQuit(player);
        }
        if (plugin.getPlayerService().frozen(player.getUniqueId())) {
            plugin.getStaffState().sendToStaff(plugin.getMessages().get("staff.freeze-quit",
                    "player", player.getName()));
        }
        String ip = player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress();
        plugin.getPlayerService().onQuit(player.getUniqueId(), player.getName(), ip);
        plugin.getSnapshotService().save(player, "Salida del servidor");
        // Antes de olvidar el estado: el brillo del vanish deja entradas en los marcadores
        // de los demás y hay que poder resolver el nombre.
        plugin.getStaffState().onQuit(player);
        plugin.getStaffState().forget(player.getUniqueId());
        plugin.getIntegrations().luckPerms().forget(player.getUniqueId());
        plugin.getActionLimiter().forget(player.getUniqueId());
        plugin.getConfirmations().forget(player);
        plugin.getFollowService().forget(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        // Se guarda el inventario y también el punto de muerte para /back.
        plugin.getSnapshotService().save(event.getEntity(), "Muerte");
        plugin.getStaffState().rememberBack(event.getEntity());
    }
}
