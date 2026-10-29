package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Reinicios programados con cuenta atrás y avisos. */
public final class RestartService {

    private final AdminToolsPlugin plugin;
    private BukkitTask task;
    private long restartAt;
    /** Hora del próximo reinicio automático (0 si no hay ninguno previsto). */
    private long nextAutomaticAt;
    /** Reinicio automático cancelado a mano: no se vuelve a programar esa misma hora. */
    private long skipAutomaticUntil;
    private int nextWarningIndex;
    private List<Integer> warnings = List.of();

    public RestartService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!plugin.getSettings().restartEnabled() || plugin.getSettings().restartTimes().isEmpty()) {
            return;
        }
        // Cada 20 s comprobamos si toca programar el próximo reinicio automático.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 400L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (restartAt == 0L) {
            scheduleNextAutomatic();
            return;
        }
        long remaining = restartAt - System.currentTimeMillis();
        if (remaining <= 0L) {
            executeNow();
            return;
        }
        long seconds = remaining / 1000L;
        while (nextWarningIndex < warnings.size() && seconds <= warnings.get(nextWarningIndex)) {
            int warning = warnings.get(nextWarningIndex);
            nextWarningIndex++;
            Bukkit.getServer().broadcast(plugin.getMessages().get("ops.restart-warning",
                    "time", TimeUtil.format(warning * 1000L)));
        }
    }

    private void scheduleNextAutomatic() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime best = null;
        for (String time : plugin.getSettings().restartTimes()) {
            try {
                LocalTime parsed = LocalTime.parse(time.trim(), DateTimeFormatter.ofPattern("HH:mm"));
                LocalDateTime candidate = LocalDate.now().atTime(parsed);
                if (candidate.isBefore(now) || candidate.isBefore(now.plusSeconds(30))) {
                    candidate = candidate.plusDays(1);
                }
                // Si esa hora se canceló a mano, se salta a la del día siguiente.
                while (candidate.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() <= skipAutomaticUntil) {
                    candidate = candidate.plusDays(1);
                }
                if (best == null || candidate.isBefore(best)) {
                    best = candidate;
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Hora de reinicio inválida: " + time);
            }
        }
        if (best != null) {
            nextAutomaticAt = best.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            schedule(nextAutomaticAt);
            plugin.getLogger().info("Próximo reinicio automático: " + best);
        }
    }

    /** Programa un reinicio dentro de N segundos. */
    public void scheduleIn(long seconds) {
        schedule(System.currentTimeMillis() + seconds * 1000L);
    }

    private void schedule(long epochMillis) {
        restartAt = epochMillis;
        if (epochMillis != nextAutomaticAt) {
            nextAutomaticAt = 0L; // programación manual: no cuenta como la automática
        }
        warnings = plugin.getSettings().restartWarnings().stream().sorted(java.util.Comparator.reverseOrder()).toList();
        nextWarningIndex = 0;
        // Redondeo hacia arriba: 900 s deben anunciarse como 15m, no como 14m.
        long seconds = Math.max(0L, (restartAt - System.currentTimeMillis() + 999L) / 1000L);
        Bukkit.getServer().broadcast(plugin.getMessages().get("ops.restart-scheduled",
                "time", TimeUtil.format(seconds * 1000L)));
    }

    public boolean scheduled() {
        return restartAt > 0L;
    }

    /** Milisegundos que faltan para el reinicio programado (0 si no hay). */
    public long remainingMillis() {
        return restartAt == 0L ? 0L : Math.max(0L, restartAt - System.currentTimeMillis());
    }

    /** Milisegundos hasta el próximo reinicio automático, o null si no está previsto. */
    public Long nextAutomaticMillis() {
        if (nextAutomaticAt == 0L) {
            return null;
        }
        return Math.max(0L, nextAutomaticAt - System.currentTimeMillis());
    }

    /** Cancela el reinicio programado (si era el automático, no se repite esa hora). */
    public void cancel() {
        if (restartAt > 0L && restartAt == nextAutomaticAt) {
            skipAutomaticUntil = restartAt;
            nextAutomaticAt = 0L;
        }
        restartAt = 0L;
        warnings = List.of();
        nextWarningIndex = 0;
    }

    /** Reinicia ya: usa el comando configurado o apaga el servidor. */
    public void executeNow() {
        cancel();
        Component message = plugin.getMessages().get("ops.restart-now");
        Bukkit.getServer().broadcast(message);
        Bukkit.getConsoleSender().sendMessage(message);
        String command = plugin.getSettings().restartCommand();
        if (command != null && !command.isBlank()) {
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(console, command));
        } else {
            Bukkit.getScheduler().runTask(plugin, Bukkit::shutdown);
        }
    }
}
