package com.admintools.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;

/**
 * Atajos de texto: MiniMessage, sustitución de %placeholders% y conversión a texto plano.
 * Todo el plugin pasa por aquí para que el formato sea consistente.
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    /** Parsea MiniMessage. Nunca lanza: si el formato está roto devuelve el texto tal cual. */
    public static Component mm(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        try {
            return MM.deserialize(input);
        } catch (Exception ex) {
            return Component.text(input);
        }
    }

    /** Parsea MiniMessage tras sustituir pares clave/valor: {@code mm("Hola %player%", "player", "Ana")}. */
    public static Component mm(String input, Object... kv) {
        return mm(substitute(input, kv));
    }

    /** Sustituye pares {@code %clave% -> valor} en un texto. */
    public static String substitute(String input, Object... kv) {
        if (input == null) {
            return "";
        }
        String out = input;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out = out.replace("%" + kv[i] + "%", kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]));
        }
        return out;
    }

    /** Sustituye un mapa completo de placeholders. */
    public static String substitute(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String out = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            out = out.replace("%" + entry.getKey() + "%", entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }

    /** Texto sin formato, para logs y comparaciones. */
    public static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * Escapa texto que viene del usuario (chat, motivo de un reporte...) para
     * que MiniMessage lo muestre literalmente. Sin esto un jugador podría
     * inyectar etiquetas como &lt;click:run_command:...&gt; en los mensajes del staff.
     */
    public static String safe(Object value) {
        if (value == null) {
            return "";
        }
        return MM.escapeTags(String.valueOf(value));
    }

    /** Normaliza texto para comparar: minúsculas y sin acentos. */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String lower = input.toLowerCase(java.util.Locale.ROOT).trim().replaceAll("\\s+", " ");
        return java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    /** Trunca un texto a n caracteres añadiendo "…". */
    public static String truncate(String input, int max) {
        if (input == null) {
            return "";
        }
        return input.length() <= max ? input : input.substring(0, Math.max(0, max - 1)) + "…";
    }
}
