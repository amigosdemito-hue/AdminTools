package com.admintools.integration;

import com.admintools.AdminToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detecta los anticheats instalados y publica sus alertas al staff.
 *
 * <p>Cada anticheat tiene su propia API, así que en lugar de adivinar sus
 * clases el plugin expone {@link #alert} en la API pública: el anticheat
 * (o un puente propio) llama a AdminTools y las alertas llegan al chat del
 * staff junto al resto de la información del jugador.</p>
 */
public final class AnticheatHook {

    private static final List<String> KNOWN = List.of(
            "Vulcan", "Grim", "GrimAC", "Matrix", "Spartan", "Polar", "Intave", "Verus", "NCP", "Themis");

    /** Último flag recibido por jugador, para mostrarlo en /check. */
    public record Alert(String anticheat, String check, int violationLevel, String details, long at) {
    }

    private final AdminToolsPlugin plugin;
    private final Set<String> installed = new LinkedHashSet<>();
    private final java.util.Map<java.util.UUID, Alert> lastAlerts = new java.util.concurrent.ConcurrentHashMap<>();

    public AnticheatHook(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        refresh();
    }

    public void refresh() {
        installed.clear();
        for (String name : KNOWN) {
            if (Bukkit.getPluginManager().getPlugin(name) != null) {
                installed.add(name);
            }
        }
    }

    public Set<String> installed() {
        return Set.copyOf(installed);
    }

    public boolean any() {
        return !installed.isEmpty();
    }

    public String installedText() {
        return installed.isEmpty() ? "-" : String.join(", ", installed);
    }

    /** Último flag conocido de un jugador (o {@code null} si no hay). */
    public Alert lastAlert(java.util.UUID uuid) {
        return uuid == null ? null : lastAlerts.get(uuid);
    }

    /** Limpia el último flag de un jugador (p. ej. al revisarlo). */
    public void forget(java.util.UUID uuid) {
        if (uuid != null) {
            lastAlerts.remove(uuid);
        }
    }

    /** Reenvía una alerta de anticheat al staff. */
    public void alert(Player player, String anticheat, String check, int violationLevel, String details) {
        pruneAlerts();
        if (player != null) {
            lastAlerts.put(player.getUniqueId(), new Alert(
                    anticheat == null ? "anticheat" : anticheat,
                    check == null ? "-" : check,
                    violationLevel,
                    details,
                    System.currentTimeMillis()));
        }
        if (!plugin.getSettings().anticheatAlerts()) {
            return;
        }
        String name = anticheat == null ? (installed.isEmpty() ? "anticheat" : installed.iterator().next()) : anticheat;
        var message = plugin.getMessages().get("ops.watchdog-anticheat",
                "player", player == null ? "?" : player.getName(),
                "plugin", name,
                "check", check == null ? "-" : check,
                "vl", violationLevel);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("admintools.investigate.check")) {
                staff.sendMessage(message);
            }
        }
        Bukkit.getConsoleSender().sendMessage(message);
        if (plugin.getSettings().debug() && details != null) {
            plugin.getLogger().info("[AC] " + name + " " + check + " " + details);
        }
    }

    /**
     * Evita que el último flag de cada jugador crezca sin límite en servidores grandes:
     * al pasar de mil entradas se descartan las de más de una semana.
     */
    private void pruneAlerts() {
        if (lastAlerts.size() < 1000) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 7L * 86_400_000L;
        lastAlerts.entrySet().removeIf(entry -> entry.getValue().at() < cutoff);
    }

    /** Lista de anticheats conocidos pero no instalados (para /check). */
    public List<String> missing() {
        List<String> result = new ArrayList<>(KNOWN);
        result.removeAll(installed);
        return result;
    }
}
