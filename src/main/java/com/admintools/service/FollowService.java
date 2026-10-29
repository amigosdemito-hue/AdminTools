package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seguimiento de un jugador por parte de un miembro del staff.
 *
 * <p>Mientras dura, si el objetivo se aleja más de tres bloques (o cambia de
 * mundo) el staff se teletransporta a su posición. Se detiene solo si el
 * objetivo se desconecta, y el staff puede cortarlo con {@code /follow stop}.</p>
 */
public final class FollowService {

    private static final double MAX_DISTANCE_SQUARED = 9.0;

    private final AdminToolsPlugin plugin;
    private final Map<UUID, UUID> following = new ConcurrentHashMap<>();
    private BukkitTask task;

    public FollowService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        following.clear();
    }

    /** Empieza a seguir a un jugador (reemplaza al anterior si lo había). */
    public void follow(Player staff, Player target) {
        following.put(staff.getUniqueId(), target.getUniqueId());
    }

    /** Deja de seguir. Devuelve {@code true} si estaba siguiendo a alguien. */
    public boolean unfollow(UUID staff) {
        return following.remove(staff) != null;
    }

    /** A quién sigue este staff, o {@code null}. */
    public UUID targetOf(UUID staff) {
        return following.get(staff);
    }

    /** Limpia las entradas de un jugador que se va (como staff o como objetivo). */
    public void forget(UUID uuid) {
        following.remove(uuid);
        following.values().removeIf(target -> target.equals(uuid));
    }

    private void tick() {
        if (following.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, UUID> entry : Map.copyOf(following).entrySet()) {
            Player staff = Bukkit.getPlayer(entry.getKey());
            if (staff == null) {
                following.remove(entry.getKey());
                continue;
            }
            Player target = Bukkit.getPlayer(entry.getValue());
            if (target == null) {
                following.remove(entry.getKey());
                plugin.getMessages().send(staff, "staff.follow-target-offline");
                continue;
            }
            if (!staff.getWorld().equals(target.getWorld())) {
                staff.teleport(target.getLocation());
                continue;
            }
            if (staff.getLocation().distanceSquared(target.getLocation()) > MAX_DISTANCE_SQUARED) {
                staff.teleport(target.getLocation());
            }
        }
    }
}
