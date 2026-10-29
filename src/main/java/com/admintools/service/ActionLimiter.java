package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Límite de acciones sensibles por minuto y por miembro del staff.
 *
 * <p>Protege contra una cuenta comprometida o un script: un ban masivo en
 * segundos se corta, pero el uso normal (decenas de acciones espaciadas)
 * no se ve afectado.</p>
 */
public final class ActionLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final AdminToolsPlugin plugin;
    private final Map<UUID, Deque<Long>> history = new ConcurrentHashMap<>();

    public ActionLimiter(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /** {@code true} si la acción está permitida (y la registra); {@code false} si superó el límite. */
    public boolean allow(Player player, String action) {
        if (!plugin.getSettings().staffActionLimitEnabled()) {
            return true;
        }
        if (player.hasPermission("admintools.staff.limit.bypass")) {
            return true;
        }
        int max = plugin.getSettings().staffActionMaxPerMinute();
        if (max <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Deque<Long> times = history.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
                times.pollFirst();
            }
            if (times.size() >= max) {
                return false;
            }
            times.addLast(now);
        }
        return true;
    }

    /** Libera la memoria de un jugador que ya no está conectado. */
    public void forget(UUID uuid) {
        history.remove(uuid);
    }
}
