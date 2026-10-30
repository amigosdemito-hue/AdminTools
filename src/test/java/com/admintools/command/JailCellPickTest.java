package com.admintools.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Elección de la celda cuando /jail no indica ninguna. */
class JailCellPickTest {

    @Test
    @DisplayName("Con una sola celda siempre devuelve esa")
    void singleCell() {
        assertEquals("spawn", JailCommands.pickRandom(List.of("spawn")));
    }

    @Test
    @DisplayName("Devuelve siempre una de las celdas de la lista")
    void alwaysFromList() {
        List<String> cells = List.of("a", "b", "c", "d");
        for (int i = 0; i < 200; i++) {
            assertTrue(cells.contains(JailCommands.pickRandom(cells)));
        }
    }

    @Test
    @DisplayName("No se queda siempre en la misma: reparte entre todas")
    void reachesEveryCell() {
        List<String> cells = List.of("a", "b", "c");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 300 && seen.size() < cells.size(); i++) {
            seen.add(JailCommands.pickRandom(cells));
        }
        assertEquals(cells.size(), seen.size());
    }
}
