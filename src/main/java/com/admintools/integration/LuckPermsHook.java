package com.admintools.integration;

import com.admintools.AdminToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puente hacia LuckPerms por reflexión, para no crear una dependencia
 * obligatoria: si LuckPerms está instalado se usan sus datos (grupo y prefijo)
 * y si no, el plugin deduce el rango por los permisos que tiene el jugador.
 */
public final class LuckPermsHook {

    private final AdminToolsPlugin plugin;
    private final Map<UUID, String> ranks = new ConcurrentHashMap<>();
    private Object userManager;
    private Method getUser;
    private boolean available;

    public LuckPermsHook(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        setup();
    }

    private void setup() {
        Plugin luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null || !luckPerms.isEnabled() || !plugin.getSettings().hookLuckPerms()) {
            return;
        }
        try {
            userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            getUser = userManager.getClass().getMethod("getUser", UUID.class);
            available = true;
            plugin.getLogger().info("LuckPerms detectado: rangos y prefijos conectados.");
        } catch (Exception ex) {
            available = false;
            plugin.getLogger().warning("No pude engancharme a LuckPerms: " + ex.getMessage());
        }
    }

    public boolean available() {
        return available;
    }

    /** Grupo principal del jugador (LuckPerms) o el rango deducido por permisos. */
    public String rankOf(Player player) {
        String cached = ranks.get(player.getUniqueId());
        if (cached != null) {
            return cached;
        }
        String rank = fromLuckPerms(player);
        if (rank == null) {
            rank = fallbackRank(player);
        }
        ranks.put(player.getUniqueId(), rank);
        return rank;
    }

    public void forget(UUID uuid) {
        ranks.remove(uuid);
    }

    /** Limpia la caché de rangos (al recargar). */
    public void forgetAll() {
        ranks.clear();
    }

    /**
     * Permiso de un jugador que todavía no entró (por ejemplo, para el modo
     * mantenimiento antes del login).
     *
     * @return true/false según LuckPerms, o null si LuckPerms no está disponible
     */
    public Boolean hasPermissionOffline(UUID uuid, String node) {
        if (!available || getUser == null) {
            return null;
        }
        try {
            Object result = getUser.invoke(userManager, uuid);
            Object user = unwrap(result);
            if (user == null) {
                return null;
            }
            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object permissionData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);
            Object triState = permissionData.getClass().getMethod("checkPermission", String.class)
                    .invoke(permissionData, node);
            try {
                return (Boolean) triState.getClass().getMethod("asBoolean").invoke(triState);
            } catch (NoSuchMethodException ex) {
                return Boolean.parseBoolean(String.valueOf(triState));
            }
        } catch (Exception ex) {
            if (plugin.getSettings().debug()) {
                plugin.getLogger().info("LuckPerms (permiso offline): " + ex.getMessage());
            }
            return null;
        }
    }

    private String fromLuckPerms(Player player) {
        if (!available || getUser == null) {
            return null;
        }
        try {
            Object result = getUser.invoke(userManager, player.getUniqueId());
            Object user = unwrap(result);
            if (user == null) {
                return null;
            }
            Object group = user.getClass().getMethod("getPrimaryGroup").invoke(user);
            return group == null ? null : String.valueOf(group);
        } catch (Exception ex) {
            if (plugin.getSettings().debug()) {
                plugin.getLogger().info("LuckPerms: " + ex.getMessage());
            }
            available = false;
            return null;
        }
    }

    /** Si el método devuelve un CompletableFuture, saca el valor ya resuelto. */
    private Object unwrap(Object result) throws Exception {
        if (result instanceof java.util.concurrent.CompletableFuture<?> future) {
            if (future.isDone()) {
                return future.getNow(null);
            }
            return future.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        if (result instanceof java.util.concurrent.Future<?> future) {
            return future.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        return result;
    }

    /** Sin LuckPerms: deducimos el rango por los permisos del plugin. */
    private String fallbackRank(Player player) {
        if (player.hasPermission("admintools.admin")) {
            return "admin";
        }
        if (player.hasPermission("admintools.moderator")) {
            return "moderador";
        }
        if (player.hasPermission("admintools.helper")) {
            return "helper";
        }
        if (player.isOp()) {
            return "op";
        }
        return "jugador";
    }

    /** Prefijo configurado en LuckPerms (vía PlaceholderAPI si está disponible). */
    public String prefixOf(Player player) {
        if (plugin.getIntegrations().placeholderApi() && plugin.getSettings().hookPlaceholderApi()) {
            String value = plugin.getIntegrations().placeholder("luckperms_prefix", player);
            if (value != null && !value.isBlank() && !value.equals("luckperms_prefix")) {
                return value;
            }
        }
        return "";
    }
}
