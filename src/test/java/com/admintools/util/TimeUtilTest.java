package com.admintools.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Duraciones de sanción: es lo que más se teclea en un comando de moderación y
 * donde un error se paga con un ban mal puesto.
 */
class TimeUtilTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;

    @Test
    @DisplayName("acepta las unidades y las combinaciones habituales")
    void parsesCommonDurations() {
        assertEquals(30 * MINUTE, TimeUtil.parse("30m"));
        assertEquals(12 * HOUR, TimeUtil.parse("12h"));
        assertEquals(7 * DAY, TimeUtil.parse("7d"));
        assertEquals(DAY + 12 * HOUR + 30 * MINUTE, TimeUtil.parse("1d12h30m"));
        assertEquals(2 * 604_800_000L, TimeUtil.parse("2w"));
        assertEquals(TimeUtil.PERMANENT, TimeUtil.parse("permanent"));
        assertEquals(TimeUtil.PERMANENT, TimeUtil.parse("  PERMANENT  "));
        assertEquals(TimeUtil.PERMANENT, TimeUtil.parse("perm"));
        assertEquals(TimeUtil.PERMANENT, TimeUtil.parse("nunca"));
        // El separador interno se admite: "1d 2h" es una duración, no un motivo.
        assertEquals(DAY + 2 * HOUR, TimeUtil.parse("1d 2h"));
    }

    @Test
    @DisplayName("rechaza duraciones ambiguas o inventadas en vez de adivinar")
    void rejectsAmbiguousDurations() {
        assertNull(TimeUtil.parse("1.5h"), "1.5h se leía como 5 horas");
        assertNull(TimeUtil.parse("1d30"), "un día con un número suelto no es válido");

        assertNull(TimeUtil.parse("30"));
        assertNull(TimeUtil.parse("h"));
        assertNull(TimeUtil.parse("0m"));
        assertNull(TimeUtil.parse(""));
        assertNull(TimeUtil.parse(null));
    }

    @Test
    @DisplayName("no desborda con números enormes")
    void rejectsOverflow() {
        assertNull(TimeUtil.parse("99999999999999999999d"));
        assertNull(TimeUtil.parse("999999y"));
    }

    @Test
    @DisplayName("distingue un intento de duración de un motivo")
    void looksLikeDuration() {
        assertTrue(TimeUtil.looksLikeDuration("1d30"));
        assertTrue(TimeUtil.looksLikeDuration("30m"));
        assertFalse(TimeUtil.looksLikeDuration("2 cuentas"));
        assertFalse(TimeUtil.looksLikeDuration("insultos"));
        assertFalse(TimeUtil.looksLikeDuration("123"));
        assertFalse(TimeUtil.looksLikeDuration(null));
    }

    @Test
    @DisplayName("formatea sin perder los minutos cuando hay días")
    void formatsDurations() {
        assertEquals("1d 2h 30m", TimeUtil.format(DAY + 2 * HOUR + 30 * MINUTE));
        assertEquals("45s", TimeUtil.format(45_000L));
        assertEquals("", TimeUtil.format(0L));
        assertEquals("", TimeUtil.format(-1L));
    }

    @Test
    @DisplayName("permanente no genera fecha de expiración")
    void expiryRespectsPermanent() {
        assertEquals(TimeUtil.PERMANENT, TimeUtil.expiresAt(1_000L, TimeUtil.PERMANENT));
        assertEquals(61_000L, TimeUtil.expiresAt(1_000L, MINUTE));
    }
}
