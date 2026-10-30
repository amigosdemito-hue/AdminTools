package com.admintools.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nombres de jugador: los de Bedrock llegan con el prefijo de Floodgate y antes se
 * rechazaban en el panel, así que nadie podía sancionar ni encarcelar a un jugador
 * de Bedrock desde los menús.
 */
class UtilTest {

    @Test
    @DisplayName("acepta nombres de Java")
    void acceptsJavaNames() {
        assertTrue(Util.isPlayerName("Steve"));
        assertTrue(Util.isPlayerName("Gabi172l"));
        assertTrue(Util.isPlayerName("jugador_rayas"));
    }

    @Test
    @DisplayName("acepta el prefijo de Floodgate de Bedrock")
    void acceptsBedrockNames() {
        assertTrue(Util.isPlayerName(".Steve"), "el prefijo de Floodgate es parte del nombre");
        assertTrue(Util.isPlayerName(".Gabi172l"));
        assertTrue(Util.isPlayerName("..Steve"), "Floodgate admite prefijos de hasta dos puntos");
        assertTrue(Util.isPlayerName("  .Steve  "), "se recorta antes de validar");
    }

    @Test
    @DisplayName("rechaza lo que no puede ser un nombre")
    void rejectsInvalidNames() {
        assertFalse(Util.isPlayerName(null));
        assertFalse(Util.isPlayerName(""));
        assertFalse(Util.isPlayerName("   "));
        assertFalse(Util.isPlayerName("Steve Smith"), "un nombre no lleva espacios");
        assertFalse(Util.isPlayerName("Steve!"), "un nombre no lleva símbolos");
        assertFalse(Util.isPlayerName(".Steve."), "el prefijo va sólo delante");
        assertFalse(Util.isPlayerName("steve.rayas"), "el punto no va en medio");
        assertFalse(Util.isPlayerName("a".repeat(17)), "más de 16 caracteres no es un nombre");
        assertFalse(Util.isPlayerName("...Steve"), "tres puntos no son un prefijo válido");
    }
}
