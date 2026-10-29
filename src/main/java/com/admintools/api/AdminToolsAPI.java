package com.admintools.api;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.PunishmentType;
import com.admintools.model.Sanction;
import com.admintools.service.SanctionService;
import com.admintools.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API pública de AdminTools para otros plugins.
 *
 * <pre>{@code
 * AdminToolsAPI api = AdminToolsAPI.get();
 * if (api.isMuted(uuid)) { ... }
 * api.anticheatAlert(player, "Vulcan", "KillAura", 12, "detalles");
 * }</pre>
 */
public final class AdminToolsAPI {

    private final AdminToolsPlugin plugin;

    private AdminToolsAPI(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Devuelve la API, o null si AdminTools no está instalado. */
    public static AdminToolsAPI get() {
        var installed = Bukkit.getPluginManager().getPlugin("AdminTools");
        return installed instanceof AdminToolsPlugin adminTools ? new AdminToolsAPI(adminTools) : null;
    }

    // ------------------------------------------------------------
    //  Consultas
    // ------------------------------------------------------------

    public boolean isBanned(UUID uuid) {
        return plugin.getSanctionService().activeBan(uuid).isPresent();
    }

    public boolean isMuted(UUID uuid) {
        return plugin.getSanctionService().activeMute(uuid).isPresent();
    }

    public boolean isFrozen(UUID uuid) {
        return plugin.getPlayerService().frozen(uuid);
    }

    public List<Sanction> activeSanctions(UUID uuid) {
        return plugin.getSanctionService().activeAll(uuid);
    }

    public int totalSanctions(UUID uuid) {
        return plugin.getSanctionService().historyCount(uuid);
    }

    /** Rango del jugador según LuckPerms (o deducido por permisos). */
    public String rank(Player player) {
        return plugin.getIntegrations().rankOf(player);
    }

    public int openReports() {
        return plugin.getReportService().queueCount();
    }

    public boolean maintenance() {
        return plugin.getMaintenanceService().enabled();
    }

    public void maintenance(boolean value) {
        plugin.getMaintenanceService().setEnabled(value);
    }

    // ------------------------------------------------------------
    //  Sanciones desde otros plugins
    // ------------------------------------------------------------

    /**
     * Aplica una sanción como si la hubiera puesto la consola.
     *
     * @param duration milisegundos, o {@link TimeUtil#PERMANENT} para permanente
     */
    public Sanction punish(PunishmentType type, UUID targetUuid, String targetName, String reason, long duration,
                           String staffName) {
        String ip = plugin.getSanctionService().ipOf(targetUuid);
        return plugin.getSanctionService().apply(new SanctionService.Request(type, targetUuid, targetName, ip, reason,
                duration, null, staffName == null ? "API" : staffName, false, null, null));
    }

    public int lift(UUID targetUuid, String targetName, String reason, PunishmentType... types) {
        return plugin.getSanctionService().lift(targetUuid, targetName, Bukkit.getConsoleSender(), reason, types);
    }

    /** Levanta el ban o mute activo de un jugador. */
    public boolean unban(UUID targetUuid, String targetName, String reason) {
        return plugin.getSanctionService().lift(targetUuid, targetName, Bukkit.getConsoleSender(), reason,
                PunishmentType.BAN, PunishmentType.IP_BAN) > 0;
    }

    public boolean unmute(UUID targetUuid, String targetName, String reason) {
        return plugin.getSanctionService().lift(targetUuid, targetName, Bukkit.getConsoleSender(), reason,
                PunishmentType.MUTE, PunishmentType.SHADOW_MUTE) > 0;
    }

    // ------------------------------------------------------------
    //  Integración con anticheats
    // ------------------------------------------------------------

    /** Publica una alerta de anticheat al staff, como si viniera de nuestro /check. */
    public void anticheatAlert(Player player, String anticheat, String check, int violationLevel, String details) {
        plugin.getIntegrations().anticheat().alert(player, anticheat, check, violationLevel, details);
    }

    public Optional<Sanction> sanctionById(long id) {
        return plugin.getSanctionService().byId(id);
    }
}
