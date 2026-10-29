package com.admintools.listener;

import com.admintools.AdminToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Copias automáticas para /rollback (entrada, salida, muerte, cambio de mundo e
 * intervalo).
 *
 * <p>Se registra <b>después</b> de {@link ConnectionListener} a propósito: así, al
 * desconectarse, la copia se toma con el inventario ya devuelto por el modo staff
 * y no con la barra de herramientas.</p>
 */
public final class RollbackListener implements Listener {

    private final AdminToolsPlugin plugin;
    private BukkitTask task;

    public RollbackListener(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Arranca (o rearranca) las copias periódicas si están configuradas. */
    public void start() {
        stop();
        int minutes = plugin.getSettings().rollbackIntervalMinutes();
        if (!plugin.getSettings().rollbackEnabled() || minutes <= 0) {
            return;
        }
        long ticks = minutes * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::captureInterval, ticks, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void captureInterval() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getInventoryBackups().capture(player, "INTERVAL");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.getSettings().rollbackJoin()) {
            plugin.getInventoryBackups().capture(event.getPlayer(), "JOIN");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.getSettings().rollbackQuit()) {
            plugin.getInventoryBackups().capture(event.getPlayer(), "QUIT");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (plugin.getSettings().rollbackDeath()) {
            // Se toma antes de que el servidor suelte los objetos al suelo.
            plugin.getInventoryBackups().capture(event.getEntity(), "DEATH");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (plugin.getSettings().rollbackWorldChange()) {
            plugin.getInventoryBackups().capture(event.getPlayer(), "WORLD");
        }
    }
}
