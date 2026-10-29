package com.admintools.integration;

import com.admintools.AdminToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * Puente hacia CoreProtect: no reimplementamos el registro de bloques,
 * lo consultamos por sus propios comandos para que respete sus permisos.
 */
public final class CoreProtectHook {

    private final AdminToolsPlugin plugin;
    private final Plugin coreProtect;

    public CoreProtectHook(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.coreProtect = Bukkit.getPluginManager().getPlugin("CoreProtect");
    }

    public boolean installed() {
        return coreProtect != null && coreProtect.isEnabled();
    }

    public String version() {
        return coreProtect == null ? "-" : coreProtect.getPluginMeta().getVersion();
    }

    /**
     * Ejecuta un comando de CoreProtect como el propio staff, así se respetan
     * sus permisos y su configuración. Ejemplo: {@code lookup("lookup u:Ana t:1d")}.
     */
    public boolean dispatch(CommandSender sender, String arguments) {
        if (!installed() || arguments == null || arguments.isBlank()) {
            return false;
        }
        String command = "co " + arguments.trim();
        return Bukkit.dispatchCommand(sender, command);
    }

    /** Atajo de investigación: /at inspect <jugador> [radio] */
    public boolean inspect(CommandSender sender, String playerName, int radius, String time) {
        return dispatch(sender, "lookup u:" + playerName + " r:" + radius + " t:" + time);
    }

    public boolean rollback(CommandSender sender, String playerName, String time) {
        return dispatch(sender, "rollback u:" + playerName + " t:" + time);
    }
}
