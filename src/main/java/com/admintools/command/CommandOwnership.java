package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Recupera los comandos del plugin que el propio servidor se queda.
 *
 * <p>El caso real es <code>/restart</code>: Spigot registra su comando de
 * reinicio antes que los plugins, así que cuando AdminTools declara el suyo
 * el nombre ya está ocupado y Bukkit lo deja únicamente como
 * <code>/admintools:restart</code>. El resultado es que <code>/restart</code>
 * apaga y arranca el servidor al momento, sin cuenta atrás, sin avisos y sin
 * dejar rastro en la auditoría.</p>
 *
 * <p>Esta clase hace dos cosas: al arrancar, comprueba qué etiquetas (el
 * comando y sus alias) no son realmente del plugin y las recupera en el
 * commandMap; y, para las que no se puedan recuperar, redirige la ejecución
 * hacia el manejador del plugin escuchando los eventos de comando tanto de
 * jugadores como de consola.</p>
 */
public final class CommandOwnership implements Listener {

    private final AdminToolsPlugin plugin;
    private final CommandRegistry registry;
    /** etiqueta (minúsculas) -> nombre del comando del plugin al que hay que redirigir */
    private final Map<String, String> hijacked = new LinkedHashMap<>();

    public CommandOwnership(AdminToolsPlugin plugin, CommandRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /**
     * Comprueba la propiedad de cada comando y sus alias. Se llama justo después
     * de registrarlos, cuando el commandMap ya tiene su estado definitivo.
     */
    public void verify() {
        CommandMap map = Bukkit.getCommandMap();
        for (CommandRegistry.Definition definition : registry.definitions()) {
            PluginCommand ours = plugin.getCommand(definition.name());
            if (ours == null) {
                continue;
            }
            // El comando y cada uno de sus alias.
            Map<String, PluginCommand> wanted = new LinkedHashMap<>();
            wanted.put(definition.name().toLowerCase(Locale.ROOT), ours);
            for (String alias : ours.getAliases()) {
                wanted.put(alias.toLowerCase(Locale.ROOT), ours);
            }
            for (Map.Entry<String, PluginCommand> entry : wanted.entrySet()) {
                String label = entry.getKey();
                if (map.getCommand(label) == entry.getValue()) {
                    continue;
                }
                // El servidor (u otro plugin) se quedó con la etiqueta.
                hijacked.put(label, definition.name());
                map.getKnownCommands().put(label, entry.getValue());
                if (map.getCommand(label) == entry.getValue()) {
                    plugin.getLogger().info(label.equals(definition.name())
                            ? "Comando /" + label + " recuperado: lo tenía el servidor y ahora lo gestiona el plugin."
                            : "Alias /" + label + " recuperado: apunta a /" + definition.name() + ".");
                } else {
                    plugin.getLogger().warning("Comando /" + label + " no se pudo recuperar del servidor;"
                            + " se redirigirá por evento hacia /" + definition.name() + ".");
                }
            }
        }
        if (!hijacked.isEmpty()) {
            plugin.getLogger().info("Comandos redirigidos al plugin: " + String.join(", ", hijacked.keySet()));
        }
    }

    // ------------------------------------------------------------
    //  Redirección por evento (jugadores y consola)
    // ------------------------------------------------------------

    // NORMAL para que antes se hayan aplicado las restricciones de staff
    // (congelados, silenciados y comandos bloqueados) sobre la misma orden.
    @EventHandler(priority = EventPriority.NORMAL)
    public void onConsoleCommand(ServerCommandEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (redirect(event.getCommand(), event.getSender())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) {
            return;
        }
        String message = event.getMessage();
        if (!message.startsWith("/")) {
            return;
        }
        if (redirect(message.substring(1), event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Si la línea corresponde a un comando capturado, la ejecuta en el plugin.
     * Devuelve true cuando el evento debe cancelarse (ya fue gestionado).
     */
    private boolean redirect(String commandLine, CommandSender sender) {
        if (commandLine == null || hijacked.isEmpty()) {
            return false;
        }
        String[] parts = commandLine.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            return false;
        }
        String owner = hijacked.get(parts[0].toLowerCase(Locale.ROOT));
        if (owner == null) {
            return false;
        }
        PluginCommand ours = plugin.getCommand(owner);
        if (ours == null) {
            return false;
        }
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        try {
            ours.execute(sender, owner, args);
        } catch (Throwable error) {
            plugin.getLogger().warning("No pude ejecutar /" + owner + " redirigido: " + error);
        }
        return true;
    }
}
