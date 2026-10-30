package com.admintools.listener;

import com.admintools.AdminToolsPlugin;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.Locale;

/**
 * Intercepta <b>cualquier</b> comando que teclee un miembro del staff, no sólo los del plugin.
 *
 * <p>El registro central de comandos sólo ve los que despacha AdminTools; {@code /give},
 * {@code /gamemode}, {@code /essentials:...} y el resto se iban sin dejar rastro. Aquí se
 * escucha {@link PlayerCommandPreprocessEvent} —que salta antes de que el comando llegue a
 * cualquier plugin— y se anota la acción en la auditoría y en el canal privado de Discord.</p>
 *
 * <p>Los comandos propios no se anotan aquí: el registro central ya los guarda con su nombre
 * real, y anotarlos dos veces llenaría el historial de líneas repetidas. La consola tampoco
 * pasa por este evento, así que sólo queda registrado lo que hacen las personas del staff.</p>
 */
public final class StaffCommandListener implements Listener {

    private final AdminToolsPlugin plugin;

    public StaffCommandListener(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getSettings().staffActionLogAllCommands()) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.getStaffLog().isStaff(player)) {
            return;
        }
        String[] parsed = parse(event.getMessage());
        if (parsed == null) {
            return;
        }
        String label = parsed[0];
        // Los del propio plugin ya los anota el registro central (con su nombre real).
        if (plugin.getCommandRegistry() != null && plugin.getCommandRegistry().owns(label)) {
            return;
        }
        // Un comando que no existe (una errata) no es una acción del staff: no se anota.
        Command command = plugin.getServer().getCommandMap().getCommand(label);
        if (command == null) {
            return;
        }
        plugin.getStaffLog().foreignCommand(player, label, Arrays.copyOfRange(parsed, 1, parsed.length));
    }

    /**
     * Separa el mensaje tecleado: el primer elemento es la etiqueta en minúsculas y
     * sin espacio de nombres ({@code /essentials:give} → {@code give}), el resto son
     * los argumentos. Devuelve {@code null} si no hay comando que anotar.
     */
    static String[] parse(String message) {
        if (message == null) {
            return null;
        }
        // Primero se recorta: con espacios delante, «   /give» no empezaba por '/' y
        // la barra acababa dentro de la etiqueta.
        String body = message.trim();
        if (body.startsWith("/")) {
            body = body.substring(1).trim();
        }
        if (body.isEmpty()) {
            return null;
        }
        String[] parts = body.split("\\s+");
        String label = parts[0];
        int colon = label.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < label.length()) {
            label = label.substring(colon + 1);
        }
        label = label.toLowerCase(Locale.ROOT);
        if (label.isEmpty()) {
            return null;
        }
        String[] result = new String[parts.length];
        result[0] = label;
        System.arraycopy(parts, 1, result, 1, parts.length - 1);
        return result;
    }
}
