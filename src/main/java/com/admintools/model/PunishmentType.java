package com.admintools.model;

import java.util.Locale;

/** Tipos de sanción que maneja el plugin. */
public enum PunishmentType {

    BAN("Ban", "sanciones de cuenta"),
    IP_BAN("IP-Ban", "sanciones por IP"),
    MUTE("Mute", "silencios"),
    IP_MUTE("IP-Mute", "silencios por IP"),
    SHADOW_MUTE("Shadow mute", "silencios fantasma"),
    WARN("Advertencia", "advertencias"),
    KICK("Expulsión", "expulsiones");

    private final String display;
    private final String plural;

    PunishmentType(String display, String plural) {
        this.display = display;
        this.plural = plural;
    }

    public String display() {
        return display;
    }

    public String plural() {
        return plural;
    }

    /** ¿Bloquea la entrada al servidor? */
    public boolean blocksLogin() {
        return this == BAN || this == IP_BAN;
    }

    public static PunishmentType parse(String value, PunishmentType fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
