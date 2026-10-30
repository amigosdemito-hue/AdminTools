package com.admintools.util;

import java.util.UUID;

/**
 * URL de la cabeza de la skin de un jugador, para los embeds de Discord.
 *
 * <p>Se resuelve con el UUID siempre que se conozca: el avatar no cambia aunque el
 * jugador se cambie de nombre. Si no hay UUID (consola, sanción puesta por nombre antes
 * de que la cuenta entre) se usa el nombre, que el servicio de cabezas también entiende
 * —incluidos los jugadores de Bedrock con Floodgate—.</p>
 */
public final class Heads {

    private Heads() {
    }

    /**
     * @param template plantilla configurada; admite {@code {uuid}} y {@code {name}}
     * @return la URL, o {@code null} si no hay plantilla (no se pinta cabeza)
     */
    public static String url(String template, UUID uuid, String name) {
        if (template == null || template.isBlank()) {
            return null;
        }
        String fallback = name == null || name.isBlank() ? "MHF_Steve" : name;
        String url = template;
        if (url.contains("{uuid}")) {
            url = url.replace("{uuid}", uuid != null ? uuid.toString() : fallback);
        }
        return url.replace("{name}", fallback);
    }
}
