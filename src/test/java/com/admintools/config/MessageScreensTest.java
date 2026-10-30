package com.admintools.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Las pantallas que ve el jugador (ban, expulsión, silencio, cárcel, congelado,
 * mantenimiento) se tienen que poder dibujar con MiniMessage y no pueden dejar
 * placeholders sin sustituir ni etiquetas sueltas.
 *
 * <p>Un error aquí no rompe el plugin: rompe justo el mensaje que el sancionado lee,
 * y no hay forma de verlo desde la consola del servidor.</p>
 */
class MessageScreensTest {

    /** Estados de la pantalla y anuncios que se revisan en los dos idiomas. */
    private static final List<String> SCREENS = List.of(
            "sanctions.target-banned",
            "sanctions.target-kicked",
            "sanctions.target-muted",
            "sanctions.target-warned",
            "sanctions.broadcast",
            "sanctions.broadcast-no-duration",
            "sanctions.join-notice",
            "sanctions.expired",
            "jail.target",
            "staff.freeze-target",
            "ops.maintenance-kick",
            "generic.help-header",
            "generic.status-header");

    @Test
    void lasPantallasSeRendericenLimpias() throws Exception {
        for (String language : List.of("es", "en")) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new File("src/main/resources/messages_" + language + ".yml"));
            assertEquals(2, yaml.getInt("messages-version"),
                    language + ": sube messages-version al cambiar el formato de los mensajes");
            for (String key : SCREENS) {
                String raw = yaml.getString(key);
                assertNotNull(raw, language + ": falta el mensaje '" + key + "'");
                String plain = plain(fill(raw));
                assertFalse(plain.contains("%"),
                        language + ": queda un placeholder sin valor en " + key + " -> " + plain);
                assertFalse(plain.contains("<") || plain.contains(">"),
                        language + ": queda una etiqueta sin interpretar en " + key + " -> " + plain);
            }
        }
    }

    /** Sustituye cada {@code %variable%} por un valor de prueba. */
    private static String fill(String raw) {
        Matcher matcher = PLACEHOLDER.matcher(raw);
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        String out = raw;
        for (String name : names) {
            out = out.replace("%" + name + "%", "x" + name + "x");
        }
        return out;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-z_]+)%");

    private static String plain(String text) {
        Component parsed = MiniMessage.miniMessage().deserialize(text);
        return PlainTextComponentSerializer.plainText().serialize(parsed);
    }
}
