package com.admintools.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lectura del tipo guardado en la base: si se confunde, un ban se lee como aviso. */
class PunishmentTypeTest {

    @Test
    @DisplayName("lee el formato moderno y el antiguo con guion")
    void parsesStoredValues() {
        assertEquals(PunishmentType.IP_BAN, PunishmentType.parse("IP_BAN", PunishmentType.BAN));
        assertEquals(PunishmentType.IP_BAN, PunishmentType.parse("ip-ban", PunishmentType.BAN));
        assertEquals(PunishmentType.WARN, PunishmentType.parse(" warn ", PunishmentType.BAN));
        assertEquals(PunishmentType.MUTE, PunishmentType.parse("Mute", PunishmentType.BAN));
    }

    @Test
    @DisplayName("un valor desconocido no se convierte en ban por accidente")
    void fallsBackOnUnknown() {
        assertEquals(PunishmentType.KICK, PunishmentType.parse("no-existe", PunishmentType.KICK));
        assertEquals(PunishmentType.WARN, PunishmentType.parse(null, PunishmentType.WARN));
        assertEquals(PunishmentType.WARN, PunishmentType.parse("   ", PunishmentType.WARN));
    }

    @Test
    @DisplayName("sólo ban e ip-ban bloquean la entrada")
    void onlyBansBlockLogin() {
        assertTrue(PunishmentType.BAN.blocksLogin());
        assertTrue(PunishmentType.IP_BAN.blocksLogin());
        assertFalse(PunishmentType.MUTE.blocksLogin());
        assertFalse(PunishmentType.KICK.blocksLogin());
        assertFalse(PunishmentType.WARN.blocksLogin());
        assertFalse(PunishmentType.SHADOW_MUTE.blocksLogin());
        assertFalse(PunishmentType.IP_MUTE.blocksLogin());
    }
}
