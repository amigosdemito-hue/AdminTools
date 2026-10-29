package com.admintools.integration;

import com.admintools.AdminToolsPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Placeholders para otros plugins y tableros:
 * %admintools_sanciones%, %admintools_baneados%, %admintools_silenciados%,
 * %admintools_reportes%, %admintools_staff_online%, %admintools_tps%,
 * %admintools_mspt%, %admintools_rango%.
 */
public final class Placeholders extends PlaceholderExpansion {

    private final AdminToolsPlugin plugin;

    public Placeholders(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "admintools";
    }

    @Override
    public @NotNull String getAuthor() {
        return "David Garcia";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "sanciones", "sanctions" -> player == null ? "0"
                    : String.valueOf(plugin.getSanctionService().historyCount(player.getUniqueId()));
            case "baneados", "bans" -> String.valueOf(plugin.getDatabase().queryLong("SELECT COUNT(*) FROM "
                    + plugin.getDatabase().table("sanctions") + " WHERE active = 1 AND type IN ('BAN', 'IP_BAN')"));
            case "silenciados", "mutes" -> String.valueOf(plugin.getDatabase().queryLong("SELECT COUNT(*) FROM "
                    + plugin.getDatabase().table("sanctions") + " WHERE active = 1 AND type IN ('MUTE', 'SHADOW_MUTE')"));
            case "reportes", "reports" -> String.valueOf(plugin.getReportService().queueCount());
            case "staff_online", "staffonline" -> String.valueOf(Bukkit.getOnlinePlayers().stream()
                    .filter(online -> online.hasPermission("admintools.staff.staffchat")).count());
            case "tps" -> String.format(Locale.ROOT, "%.2f", plugin.getWatchdogService().tps());
            case "mspt" -> String.format(Locale.ROOT, "%.1f", plugin.getWatchdogService().mspt());
            case "rango", "rank" -> player instanceof Player online
                    ? plugin.getIntegrations().rankOf(online) : "-";
            default -> null;
        };
    }
}
