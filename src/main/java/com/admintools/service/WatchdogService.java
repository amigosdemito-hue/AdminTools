package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.config.Settings;
import com.admintools.util.Util;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Vigila TPS, MSPT, RAM, disco y chunks cargados, y avisa cuando algo va mal. */
public final class WatchdogService {

    private final AdminToolsPlugin plugin;
    private final Map<String, Long> lastAlert = new ConcurrentHashMap<>();
    private BukkitTask task;

    public WatchdogService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!plugin.getSettings().watchdogEnabled()) {
            return;
        }
        long interval = plugin.getSettings().watchdogIntervalSeconds() * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ------------------------------------------------------------
    //  Medidas
    // ------------------------------------------------------------

    public double tps() {
        double[] tps = Bukkit.getServer().getTPS();
        return tps.length == 0 ? 20.0 : Math.min(20.0, tps[0]);
    }

    public double mspt() {
        return Bukkit.getServer().getAverageTickTime();
    }

    /** % de memoria usada respecto al máximo del proceso. */
    public double memoryPercent() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return max <= 0L ? 0.0 : used * 100.0 / max;
    }

    public String memoryText() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return Util.bytes(used) + " / " + Util.bytes(max);
    }

    /** % de disco usado donde vive el servidor. */
    public double diskPercent() {
        File folder = Bukkit.getWorldContainer();
        long total = folder.getTotalSpace();
        long free = folder.getUsableSpace();
        return total <= 0L ? 0.0 : (total - free) * 100.0 / total;
    }

    public String diskText() {
        File folder = Bukkit.getWorldContainer();
        return Util.bytes(folder.getTotalSpace() - folder.getUsableSpace()) + " / " + Util.bytes(folder.getTotalSpace());
    }

    public int loadedChunks() {
        int chunks = 0;
        for (World world : Bukkit.getWorlds()) {
            chunks += world.getLoadedChunks().length;
        }
        return chunks;
    }

    /** Datos para /watchdog status. */
    public Map<String, String> snapshot() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("tps", String.format(java.util.Locale.ROOT, "%.2f", tps()));
        data.put("mspt", String.format(java.util.Locale.ROOT, "%.2f", mspt()));
        data.put("players", Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
        data.put("memory", memoryText() + " (" + Math.round(memoryPercent()) + "%)");
        data.put("disk", diskText() + " (" + Math.round(diskPercent()) + "%)");
        data.put("chunks", String.valueOf(loadedChunks()));
        return data;
    }

    public String state(double value, double warn, double critical, boolean lowerIsWorse) {
        if (lowerIsWorse) {
            if (value <= critical) {
                return plugin.getMessages().raw("ops.watchdog-state-critical");
            }
            if (value <= warn) {
                return plugin.getMessages().raw("ops.watchdog-state-warn");
            }
        } else {
            if (value >= critical) {
                return plugin.getMessages().raw("ops.watchdog-state-critical");
            }
            if (value >= warn) {
                return plugin.getMessages().raw("ops.watchdog-state-warn");
            }
        }
        return plugin.getMessages().raw("ops.watchdog-state-ok");
    }

    // ------------------------------------------------------------
    //  Revisión periódica
    // ------------------------------------------------------------

    private void check() {
        Settings.Thresholds limits = plugin.getSettings().watchdogThresholds();
        double tps = tps();
        if (tps <= limits.tpsCritical()) {
            alert("TPS", String.format(java.util.Locale.ROOT, "%.2f", tps),
                    plugin.getMessages().raw("ops.watchdog-state-critical"),
                    "Revisa entidades y chunk-loaders con /watchdog · spark");
        } else if (tps <= limits.tpsWarn()) {
            alert("TPS", String.format(java.util.Locale.ROOT, "%.2f", tps),
                    plugin.getMessages().raw("ops.watchdog-state-warn"), "Vigila la carga del servidor");
        }

        double mspt = mspt();
        if (mspt >= limits.msptCritical()) {
            alert("MSPT", String.format(java.util.Locale.ROOT, "%.1f", mspt),
                    plugin.getMessages().raw("ops.watchdog-state-critical"), "El servidor va por detrás del reloj");
        } else if (mspt >= limits.msptWarn()) {
            alert("MSPT", String.format(java.util.Locale.ROOT, "%.1f", mspt),
                    plugin.getMessages().raw("ops.watchdog-state-warn"), "Revisa plugins y entidades");
        }

        double memory = memoryPercent();
        if (memory >= limits.memoryCritical()) {
            alert("RAM", Math.round(memory) + "%", plugin.getMessages().raw("ops.watchdog-state-critical"),
                    "Peligro de OutOfMemory: sube el heap o busca fugas");
        } else if (memory >= limits.memoryWarn()) {
            alert("RAM", Math.round(memory) + "%", plugin.getMessages().raw("ops.watchdog-state-warn"),
                    "Considera un reinicio programado");
        }

        double disk = diskPercent();
        if (disk >= limits.diskWarn()) {
            alert("Disco", Math.round(disk) + "%", plugin.getMessages().raw("ops.watchdog-state-warn"),
                    "Limpia copias de seguridad y logs antiguos");
        }
    }

    /** Envía una alerta (con cooldown por métrica) al staff y a Discord. */
    public void alert(String metric, String value, String state, String hint) {
        long cooldown = plugin.getSettings().watchdogAlertCooldownMinutes() * 60_000L;
        Long last = lastAlert.get(metric);
        if (last != null && System.currentTimeMillis() - last < cooldown) {
            return;
        }
        lastAlert.put(metric, System.currentTimeMillis());

        Component message = plugin.getMessages().get("ops.watchdog-alert",
                "metric", metric, "value", value, "state", state, "hint", hint);
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("admintools.ops.watchdog")) {
                staff.sendMessage(message);
            }
        }
        if (plugin.getSettings().watchdogDiscord()) {
            plugin.getIntegrations().discordWatchdog(metric, value, state, hint);
        }
    }

    public AdminToolsPlugin plugin() {
        return plugin;
    }
}
