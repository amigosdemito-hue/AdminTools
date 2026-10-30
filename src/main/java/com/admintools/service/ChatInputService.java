package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Pide un dato por chat a un jugador y espera su respuesta.
 *
 * <p>La usa el panel en modo inventario: al pulsar un botón que necesita un jugador,
 * una duración o un motivo, el menú se cierra y el siguiente mensaje de chat del staff
 * se toma como respuesta. Ese mensaje <b>no se difunde</b>: el evento se cancela antes
 * de que salga del servidor, así que nadie ve lo que escribe el staff.</p>
 */
public final class ChatInputService implements Listener {

    /** Palabras que abandonan la pregunta en lugar de responderla. */
    private static final Set<String> CANCEL = Set.of("cancelar", "cancel");

    private record Pending(Consumer<String> answer, long expiresAt) {
    }

    private final AdminToolsPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatInputService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Pregunta algo al jugador; {@code answer} se ejecuta en el hilo del servidor. */
    public void ask(Player player, Component question, Consumer<String> answer) {
        long seconds = Math.max(5, plugin.getSettings().panelChatSeconds());
        UUID uuid = player.getUniqueId();
        Pending entry = new Pending(answer, System.currentTimeMillis() + seconds * 1000L);
        pending.put(uuid, entry);
        player.closeInventory();
        player.sendMessage(question);
        player.sendMessage(plugin.getMessages().get("panel.input.cancel-hint"));
        // Caduca sola: si el staff se distrae, la pregunta no queda viva comiéndose
        // su siguiente mensaje de chat media hora después.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(uuid, entry)) {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    plugin.getMessages().send(online, "panel.input.expired");
                }
            }
        }, seconds * 20L + 1L);
    }

    /** Cancela la pregunta pendiente, si la hay. */
    public void cancel(Player player) {
        if (pending.remove(player.getUniqueId()) != null) {
            plugin.getMessages().send(player, "panel.input.cancelled");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Pending entry = pending.get(uuid);
        if (entry == null) {
            return;
        }
        // La respuesta nunca llega al chat público ni al resto del staff.
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (System.currentTimeMillis() > entry.expiresAt()) {
            pending.remove(uuid);
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.getMessages().send(event.getPlayer(), "panel.input.expired"));
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                pending.remove(uuid);
                return;
            }
            if (CANCEL.contains(text.toLowerCase(Locale.ROOT))) {
                pending.remove(uuid);
                plugin.getMessages().send(player, "panel.input.cancelled");
                return;
            }
            pending.remove(uuid);
            entry.answer().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        // Al expulsar a alguien no debe quedar una pregunta viva esperando respuesta.
        pending.remove(event.getPlayer().getUniqueId());
    }
}
