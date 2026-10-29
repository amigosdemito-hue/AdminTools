package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Confirmación en dos pasos para comandos destructivos.
 *
 * <p>La primera ejecución sólo pide confirmación y deja la acción pendiente;
 * repetir el mismo comando dentro de la ventana la ejecuta. Se guarda por
 * remitente y por acción, así que confirmar {@code /clear} no confirma
 * {@code /at purge}.</p>
 */
public final class ConfirmationService {

    private static final long WINDOW_MS = 15_000L;

    private final AdminToolsPlugin plugin;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(String action, long expiresAt) {
    }

    public ConfirmationService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Comprueba o pide la confirmación de una acción destructiva.
     *
     * @return {@code true} si la acción ya estaba pendiente y sigue vigente (ejecutar);
     *         {@code false} si acaba de pedir la confirmación.
     */
    public boolean confirm(CommandSender sender, String action, String messageKey, Object... placeholders) {
        String key = key(sender);
        long now = System.currentTimeMillis();
        Pending current = pending.get(key);
        if (current != null && current.action().equals(action) && current.expiresAt() > now) {
            pending.remove(key);
            return true;
        }
        pending.put(key, new Pending(action, now + WINDOW_MS));

        Object[] values = new Object[placeholders.length + 2];
        values[0] = "seconds";
        values[1] = WINDOW_MS / 1000L;
        System.arraycopy(placeholders, 0, values, 2, placeholders.length);
        plugin.getMessages().send(sender, messageKey, values);
        return false;
    }

    /** Olvida cualquier confirmación pendiente de este remitente. */
    public void forget(CommandSender sender) {
        pending.remove(key(sender));
    }

    private String key(CommandSender sender) {
        return sender instanceof Player player ? "p:" + player.getUniqueId() : "c";
    }
}
