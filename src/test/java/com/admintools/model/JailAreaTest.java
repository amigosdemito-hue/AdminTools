package com.admintools.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Área de la celda: un cuadrado de 5x5 con radio 2. Es lo que decide si un preso
 * se ha fugado, así que conviene que las esquinas y el margen vertical estén fijados.
 */
class JailAreaTest {

    private static final double X = 100.0;
    private static final double Y = 64.0;
    private static final double Z = -200.0;
    private static final double RADIUS = 2.0;

    private static boolean inside(double dx, double dy, double dz) {
        return Jail.within(X, Y, Z, RADIUS, X + dx, Y + dy, Z + dz);
    }

    @Test
    @DisplayName("el centro y los bordes del cuadrado cuentan como dentro")
    void edgesAreInside() {
        assertTrue(inside(0, 0, 0));
        assertTrue(inside(2, 0, 0));
        assertTrue(inside(-2, 0, -2));
        assertTrue(inside(1.99, 0, -1.99));
    }

    @Test
    @DisplayName("las esquinas del cuadrado no se quedan fuera (antes eran una esfera)")
    void cornersCount() {
        assertTrue(inside(2, 0, 2));
        assertTrue(inside(-2, 0, 2));
        assertTrue(inside(2, 0, -2));
    }

    @Test
    @DisplayName("un bloque más allá del borde es fuga")
    void oneStepBeyondIsEscape() {
        assertFalse(inside(2.01, 0, 0));
        assertFalse(inside(0, 0, -2.01));
        assertFalse(inside(3, 0, 0));
    }

    @Test
    @DisplayName("saltar no es fugarse, caer tres bloques sí")
    void verticalMargin() {
        assertTrue(inside(0, 1.5, 0));
        assertTrue(inside(0, -2, 0));
        assertFalse(inside(0, 3, 0));
        assertFalse(inside(0, -3, 0));
    }

    @Test
    @DisplayName("radio 0 significa toda la zona (sólo se comprueba el mundo)")
    void zeroRadiusMeansWholeWorld() {
        assertTrue(Jail.within(X, Y, Z, 0, 50_000, -20, 50_000));
    }

    @Test
    @DisplayName("un radio grande agranda el área en las dos direcciones")
    void biggerRadiusCoversMore() {
        assertTrue(Jail.within(X, Y, Z, 10, X + 10, Y, Z - 10));
        assertFalse(Jail.within(X, Y, Z, 10, X + 11, Y, Z));
    }
}
