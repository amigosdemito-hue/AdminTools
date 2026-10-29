package com.admintools.integration;

import com.admintools.AdminToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Puente opcional con <b>Vault</b> para leer el saldo de un jugador.
 *
 * <p>Se hace por reflexión a propósito: el plugin no necesita Vault ni su API para
 * funcionar, y así el jar sigue sin dependencias externas. Si Vault (o el plugin de
 * economía que lo provee) no está instalado, {@link #balance(Player)} devuelve
 * {@code null} y quien lo use enseña otra cosa.</p>
 */
public final class EconomyHook {

    private final AdminToolsPlugin plugin;
    private Object provider;
    private Method getBalance;
    private Method format;
    private boolean resolved;

    public EconomyHook(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Vuelve a buscar el proveedor (tras un /reload o al arrancar). */
    public void refresh() {
        resolved = false;
        provider = null;
        getBalance = null;
        format = null;
    }

    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> economy = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(economy);
            if (registration == null) {
                return;
            }
            provider = registration.getProvider();
            getBalance = economy.getMethod("getBalance", org.bukkit.OfflinePlayer.class);
            try {
                format = economy.getMethod("format", double.class);
            } catch (NoSuchMethodException ignored) {
                // Proveedor sin format(): se usa un formato numérico propio.
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            provider = null;
            getBalance = null;
            format = null;
            if (plugin.getSettings().debug()) {
                plugin.getLogger().info("Vault no disponible para el saldo: " + ex.getMessage());
            }
        }
    }

    /** ¿Hay economía conectada? */
    public boolean available() {
        resolve();
        return provider != null && getBalance != null;
    }

    /** Saldo formateado listo para mostrar, o {@code null} si no hay economía. */
    public String balance(Player player) {
        if (player == null || !available()) {
            return null;
        }
        try {
            Object result = getBalance.invoke(provider, player);
            if (!(result instanceof Number number)) {
                return null;
            }
            double value = number.doubleValue();
            if (format != null) {
                Object text = format.invoke(provider, value);
                return text == null ? null : text.toString();
            }
            return String.format(Locale.ROOT, "%.2f", value);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Un proveedor que falla no debe romper el menú.
            return null;
        }
    }
}
