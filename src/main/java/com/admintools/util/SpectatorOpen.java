package com.admintools.util;

import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Abre un inventario mientras el servidor considera al jugador espectador.
 *
 * <p>Minecraft mueve la tapa y reproduce el sonido del contenedor dentro de
 * {@code startOpen}, y lo primero que hace ese método es salir si el jugador es
 * espectador. Como CraftBukkit llama a {@code startOpen} en cualquier apertura de
 * menú (también las que hace un plugin con {@code openInventory}), la única forma de
 * abrir sin animación ni sonido es estar en ese estado durante la llamada.</p>
 *
 * <p>Se cambia solo el modo de juego interno (el mismo campo que consulta el bloque)
 * y se restaura al instante: no se envían paquetes al cliente, no se dispara
 * {@code PlayerGameModeChangeEvent} y no se tocan vuelo, monturas ni efectos. Si el
 * servidor cambia esos nombres internos, la apertura se hace normal en vez de fallar.</p>
 */
public final class SpectatorOpen {

    private static boolean resolved;
    private static Method getHandle;
    private static Field gameModeField;
    private static Field currentModeField;
    private static Object spectatorValue;

    private SpectatorOpen() {
    }

    /** ¿Este servidor permite abrir contenedores sin animación? */
    public static boolean available() {
        resolve();
        return spectatorValue != null;
    }

    /**
     * Ejecuta la apertura marcando al jugador como espectador durante la llamada.
     * La acción se ejecuta exactamente una vez, haya o no soporte interno.
     */
    public static void run(Player player, Runnable open) {
        resolve();
        if (spectatorValue == null) {
            open.run();
            return;
        }
        Object gameMode;
        Object previous;
        try {
            Object handle = getHandle.invoke(player);
            gameMode = gameModeField.get(handle);
            previous = currentModeField.get(gameMode);
            if (previous == spectatorValue) {
                open.run();
                return;
            }
            currentModeField.set(gameMode, spectatorValue);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Servidor con otra estructura interna: se abre normal en vez de no abrir.
            open.run();
            return;
        }
        try {
            open.run();
        } finally {
            try {
                currentModeField.set(gameMode, previous);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Sin acceso al campo no hay nada que restaurar.
            }
        }
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> gameModeClass = Class.forName("net.minecraft.server.level.ServerPlayerGameMode");
            Class<?> gameTypeClass = Class.forName("net.minecraft.world.level.GameType");

            Object spectator = null;
            for (Object constant : gameTypeClass.getEnumConstants()) {
                if (constant instanceof Enum<?> value && value.name().equals("SPECTATOR")) {
                    spectator = constant;
                    break;
                }
            }
            if (spectator == null) {
                return;
            }
            Field current = gameModeClass.getDeclaredField("gameModeForPlayer");
            current.setAccessible(true);

            getHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
            gameModeField = serverPlayer.getField("gameMode");

            currentModeField = current;
            spectatorValue = spectator;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            currentModeField = null;
            spectatorValue = null;
        }
    }
}
