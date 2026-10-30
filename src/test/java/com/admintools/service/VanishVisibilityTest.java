package com.admintools.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reglas del vanish que no dependen de Bukkit: quién ve a quién y qué rango tiene cada
 * miembros del staff para el color del brillo.
 */
class VanishVisibilityTest {

    @Test
    @DisplayName("Con la regla simétrica, sólo el staff en vanish ve a los invisibles")
    void symmetricRule() {
        // Un moderador en vanish ve a otro en vanish.
        assertTrue(StaffStateService.seesThroughVanish(true, false, true));
        // Un moderador visible no lo ve, ni siquiera con el permiso de ver.
        assertFalse(StaffStateService.seesThroughVanish(false, true, true));
        assertFalse(StaffStateService.seesThroughVanish(false, false, true));
        // El que está en vanish siempre ve.
        assertTrue(StaffStateService.seesThroughVanish(true, true, true));
    }

    @Test
    @DisplayName("Con la regla antigua, el permiso basta aunque no estés en vanish")
    void permissionRule() {
        assertTrue(StaffStateService.seesThroughVanish(false, true, false));
        assertFalse(StaffStateService.seesThroughVanish(false, false, false));
        assertTrue(StaffStateService.seesThroughVanish(true, false, false));
    }

    @Test
    @DisplayName("El rango más alto manda: admin, después moderador, después helper")
    void rankOrder() {
        assertEquals("admin", StaffStateService.rankKey(true, true));
        assertEquals("admin", StaffStateService.rankKey(true, false));
        assertEquals("moderator", StaffStateService.rankKey(false, true));
        assertEquals("helper", StaffStateService.rankKey(false, false));
    }
}
