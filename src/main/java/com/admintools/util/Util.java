package com.admintools.util;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Utilidades varias: paginación, unidades legibles y comparaciones de nombres. */
public final class Util {

    /**
     * Nombre de jugador tal como lo guarda el servidor.
     *
     * <p>Los jugadores de Bedrock llegan a través de Floodgate con un prefijo de puntos
     * (por defecto {@code .Steve}), así que el nombre válido no es sólo
     * {@code [A-Za-z0-9_]}: exigir eso rechazaba a todos los de Bedrock en cualquier
     * campo que validara el nombre.</p>
     */
    private static final Pattern PLAYER_NAME = Pattern.compile("\\.{0,2}[A-Za-z0-9_]{1,16}");

    private Util() {
    }

    /** ¿Es un nombre de jugador aceptable (Java o Bedrock con prefijo de Floodgate)? */
    public static boolean isPlayerName(String name) {
        return name != null && PLAYER_NAME.matcher(name.trim()).matches();
    }

    /** Recorta una lista para mostrar sólo la página pedida (1-based). */
    public static <T> List<T> page(Collection<T> source, int page, int size) {
        List<T> list = List.copyOf(source);
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(list.size(), from + size);
        if (from >= list.size()) {
            return List.of();
        }
        return list.subList(from, to);
    }

    public static int pageCount(int total, int size) {
        return Math.max(1, (int) Math.ceil(total / (double) size));
    }

    /** 1536 -> "1.5 KB" */
    public static String bytes(long value) {
        if (value < 1024L) {
            return value + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double size = value / 1024.0;
        int unit = 0;
        while (size >= 1024.0 && unit < units.length - 1) {
            size /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", size, units[unit]);
    }

    /** Une una colección con comas. */
    public static String join(Collection<String> values, String separator) {
        return String.join(separator, values);
    }

    /** Porcentaje entero de mayúsculas de un texto (letras). */
    public static int uppercasePercent(String text) {
        int letters = 0;
        int upper = 0;
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        if (letters == 0) {
            return 0;
        }
        return (int) Math.round(upper * 100.0 / letters);
    }

    /** Quita espacios y normaliza separadores: "mc . ejemplo . com" -> "mc.ejemplo.com" */
    public static String compact(String text) {
        return text.toLowerCase(Locale.ROOT).replace(" ", "").replace("(dot)", ".").replace("[.]", ".");
    }

    /** Reemplaza caracteres típicos usados para disfrazar palabras. */
    public static String leet(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace('0', 'o')
                .replace('1', 'i')
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't')
                .replace('@', 'a')
                .replace('$', 's')
                .replace('!', 'i');
    }
}
