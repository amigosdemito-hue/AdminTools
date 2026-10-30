package com.admintools.service;

import org.bukkit.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Modo de juego al encarcelar y al salir.
 *
 * <p>Lo que se comprueba aquí es el fallo que dejaba al preso en aventura para siempre:
 * cuando no había un modo guardado fiable, no se le devolvía nada y cada nuevo
 * encarcelamiento guardaba "aventura" como si fuera su modo original.</p>
 */
class JailGamemodeTest {

    @Test
    @DisplayName("al salir se respeta siempre el modo guardado")
    void restoresSavedMode() {
        assertEquals(GameMode.SURVIVAL, JailService.restoreTarget(
                GameMode.SURVIVAL, GameMode.ADVENTURE, GameMode.SURVIVAL));
        assertEquals(GameMode.CREATIVE, JailService.restoreTarget(
                GameMode.CREATIVE, GameMode.ADVENTURE, GameMode.SURVIVAL));
    }

    @Test
    @DisplayName("sin modo guardado, el preso no se queda en aventura")
    void neverStuckInAdventure() {
        // Condena importada de xJail, fila perdida o recarga a mitad del encarcelamiento.
        assertEquals(GameMode.SURVIVAL, JailService.restoreTarget(
                null, GameMode.ADVENTURE, GameMode.SURVIVAL));
        assertEquals(GameMode.CREATIVE, JailService.restoreTarget(
                null, GameMode.ADVENTURE, GameMode.CREATIVE));
    }

    @Test
    @DisplayName("sin modo guardado y sin estar en aventura no se le toca nada")
    void leavesOtherModesAlone() {
        assertEquals(GameMode.SURVIVAL, JailService.restoreTarget(
                null, GameMode.SURVIVAL, GameMode.CREATIVE));
        assertEquals(GameMode.SPECTATOR, JailService.restoreTarget(
                null, GameMode.SPECTATOR, GameMode.SURVIVAL));
    }

    @Test
    @DisplayName("nunca se guarda aventura como modo original")
    void neverSavesAdventureAsOriginal() {
        assertEquals(GameMode.SURVIVAL, JailService.originalMode(GameMode.ADVENTURE, GameMode.SURVIVAL));
        assertEquals(GameMode.SURVIVAL, JailService.originalMode(GameMode.SURVIVAL, GameMode.CREATIVE));
        assertEquals(GameMode.SPECTATOR, JailService.originalMode(GameMode.SPECTATOR, GameMode.SURVIVAL));
    }

    @Test
    @DisplayName("encarcelar a alguien que ya estaba en aventura no lo deja atrapado")
    void adventureBeforeJailDoesNotTrap() {
        // Se guarda el modo del servidor, así que al salir recupera uno jugable.
        GameMode saved = JailService.originalMode(GameMode.ADVENTURE, GameMode.SURVIVAL);
        assertEquals(GameMode.SURVIVAL, JailService.restoreTarget(
                saved, GameMode.ADVENTURE, GameMode.SURVIVAL));
    }
}
