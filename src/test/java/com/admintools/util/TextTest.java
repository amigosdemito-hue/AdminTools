package com.admintools.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Texto: placeholders, comparación de nombres y escape de MiniMessage. */
class TextTest {

    @Test
    @DisplayName("sustituye placeholders, incluidos los nulos")
    void substitutes() {
        assertEquals("Hola Ana", Text.substitute("Hola %player%", "player", "Ana"));
        assertEquals("Ana tiene 3", Text.substitute("%player% tiene %n%", "player", "Ana", "n", 3));
        assertEquals("Hola ", Text.substitute("Hola %player%", "player", null));
        assertEquals("%desconocido%", Text.substitute("%desconocido%", "player", "Ana"));
        assertEquals("", Text.substitute(null, "player", "Ana"));
    }

    @Test
    @DisplayName("normaliza mayúsculas, acentos y espacios")
    void normalizes() {
        assertEquals("ana", Text.normalize("  ÁNÁ  "));
        assertEquals("jose luis", Text.normalize("José   Luis"));
        assertEquals("", Text.normalize(null));
    }

    @Test
    @DisplayName("trunca sin partir el texto")
    void truncates() {
        assertEquals("abc…", Text.truncate("abcdef", 4));
        assertEquals("abc", Text.truncate("abc", 4));
        assertEquals("", Text.truncate(null, 4));
    }

    @Test
    @DisplayName("escapa etiquetas que vienen del usuario")
    void escapesMiniMessageTags() {
        String input = "<red>hola</red> <click:run_command:/op>x</click>";
        String escaped = Text.safe(input);
        assertFalse(escaped.equals(input), "el texto del usuario debe quedar escapado");

        // Lo importante: al renderizar se ve tal cual, no se aplica el formato ni
        // el clic que intentó inyectar el jugador.
        Component shown = Text.mm(escaped);
        String plain = PlainTextComponentSerializer.plainText().serialize(shown);
        assertEquals(input, plain);
        assertTrue(shown.children().isEmpty(), "no debe generar componentes hijos");
    }

    @Test
    @DisplayName("un formato roto no rompe el mensaje")
    void brokenFormatFallsBack() {
        String plain = PlainTextComponentSerializer.plainText().serialize(Text.mm("<red>sin cerrar"));
        assertTrue(plain.contains("sin cerrar"));
    }
}
