package com.admintools.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qué se guarda de un comando interceptado. Lo importante aquí es que una
 * contraseña tecleada por el staff no acabe ni en la base ni en el canal de Discord.
 */
class StaffLogFieldsTest {

    @Test
    @DisplayName("guarda objetivo y detalles de un comando normal")
    void keepsTargetAndDetails() {
        StaffLogService.Fields fields = StaffLogService.fields("give",
                new String[]{"Steve", "diamond", "64"});
        assertEquals("Steve", fields.target());
        assertEquals("diamond 64", fields.details());
    }

    @Test
    @DisplayName("un comando sin argumentos no inventa objetivo")
    void handlesNoArguments() {
        StaffLogService.Fields fields = StaffLogService.fields("spawn", new String[0]);
        assertEquals("-", fields.target());
        assertEquals("", fields.details());
    }

    @Test
    @DisplayName("nunca guarda los argumentos de login, contraseña o correo")
    void hidesCredentials() {
        for (String command : new String[]{"login", "register", "changepassword", "email", "2fa", "auth"}) {
            StaffLogService.Fields fields = StaffLogService.fields(command,
                    new String[]{"MiClaveSecreta123", "correo@ejemplo.com"});
            assertEquals("-", fields.target(), command);
            assertEquals("(argumentos ocultos)", fields.details(), command);
        }
    }

    @Test
    @DisplayName("corta los argumentos kilométricos sin romper la línea")
    void truncatesLongArguments() {
        String longMessage = "a".repeat(400);
        StaffLogService.Fields fields = StaffLogService.fields("msg", new String[]{"Steve", longMessage});
        assertEquals("Steve", fields.target());
        assertTrue(fields.details().length() <= 200, "detalles de " + fields.details().length() + " caracteres");
        assertTrue(fields.details().endsWith("…"));
    }
}
