package com.admintools.util;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;

/**
 * Detecta si un jugador entra desde <b>Java</b> o desde <b>Bedrock</b> (Geyser/Floodgate).
 *
 * <p>Se mira primero Floodgate, que es la fuente fiable; si no está instalado se usan
 * dos señales del propio servidor: la marca de cliente y el UUID que genera Geyser,
 * cuyo primer long es cero.</p>
 */
public final class ClientEdition {

    private static boolean resolved;
    private static Object floodgateApi;
    private static Method floodgateCheck;

    private ClientEdition() {
    }

    /** {@code true} si el jugador viene de Bedrock. */
    public static boolean bedrock(Player player) {
        if (player == null) {
            return false;
        }
        if (floodgatePlayer(player.getUniqueId())) {
            return true;
        }
        String brand = player.getClientBrandName();
        if (brand != null) {
            String lower = brand.toLowerCase(Locale.ROOT);
            if (lower.contains("geyser") || lower.contains("bedrock")) {
                return true;
            }
        }
        return player.getUniqueId().getMostSignificantBits() == 0L;
    }

    /** Etiqueta corta para mostrar: {@code Java} o {@code Bedrock}. */
    public static String label(Player player) {
        return bedrock(player) ? "Bedrock" : "Java";
    }

    private static boolean floodgatePlayer(UUID id) {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateApi = api.getMethod("getInstance").invoke(null);
                for (String name : new String[]{"isFloodgatePlayer", "isFloodgateId"}) {
                    try {
                        floodgateCheck = api.getMethod(name, UUID.class);
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // Versión de Floodgate con otro nombre de método: se prueba el siguiente.
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                floodgateApi = null;
                floodgateCheck = null;
            }
        }
        if (floodgateApi == null || floodgateCheck == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(floodgateCheck.invoke(floodgateApi, id));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }
}
