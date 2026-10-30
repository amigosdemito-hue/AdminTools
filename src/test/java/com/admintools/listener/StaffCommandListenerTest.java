package com.admintools.listener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Lectura del comando que teclea el staff. Si esto se equivoca, el registro de
 * staff anota el comando equivocado o se come los argumentos, que es justo lo que
 * se consulta después.
 */
class StaffCommandListenerTest {

    @Test
    @DisplayName("separa el comando de los argumentos")
    void splitsCommandFromArguments() {
        assertArrayEquals(new String[]{"give", "Steve", "diamond", "64"}, StaffCommandListener.parse("/give Steve diamond 64"));
        assertArrayEquals(new String[]{"gamemode", "creative"}, StaffCommandListener.parse("/gamemode creative"));
        assertArrayEquals(new String[]{"spawn"}, StaffCommandListener.parse("/spawn"));
    }

    @Test
    @DisplayName("quita el espacio de nombres y pasa a minúsculas")
    void stripsNamespace() {
        assertArrayEquals(new String[]{"give", "Steve"}, StaffCommandListener.parse("/essentials:give Steve"));
        assertArrayEquals(new String[]{"ban", "Steve"}, StaffCommandListener.parse("/ADMINTOOLS:Ban Steve"));
        // Un nombre de jugador con dos puntos no debe partirse por el último ':'
        // del mensaje, sólo por el de la etiqueta.
        assertArrayEquals(new String[]{"msg", "Steve:x", "hola"}, StaffCommandListener.parse("/msg Steve:x hola"));
    }

    @Test
    @DisplayName("tolera espacios de más y el prefijo ausente")
    void toleratesWhitespace() {
        assertArrayEquals(new String[]{"give", "Steve"}, StaffCommandListener.parse("   /give    Steve   "));
        assertArrayEquals(new String[]{"give", "Steve"}, StaffCommandListener.parse("give Steve"));
        assertArrayEquals(new String[]{"give", "Steve"}, StaffCommandListener.parse("/give\tSteve"));
    }

    @Test
    @DisplayName("no inventa comandos donde no los hay")
    void rejectsEmptyInput() {
        assertNull(StaffCommandListener.parse(null));
        assertNull(StaffCommandListener.parse(""));
        assertNull(StaffCommandListener.parse("/"));
        assertNull(StaffCommandListener.parse("   /   "));
    }
}
