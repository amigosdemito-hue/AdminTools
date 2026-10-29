package com.admintools.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Duraciones y fechas: parsea "7d", "12h30m", "permanent" y formatea tanto
 * duraciones como marcas de tiempo en la zona horaria del servidor.
 */
public final class TimeUtil {

    /** Valor usado para sanciones sin fecha de expiración. */
    public static final long PERMANENT = -1L;

    private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*(mo|[ywdhms])", Pattern.CASE_INSENSITIVE);
    /** La cadena entera tiene que ser duraciones: "1d30" o "1.5h" ya no cuelan. */
    private static final Pattern DURATION = Pattern.compile("^(?:\\d+\\s*(?:mo|[ywdhms])\\s*)+$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PERMANENT_WORD = Pattern.compile("(?i)^(perm|perma|permanent|permanente|forever|never|nunca|∞)$");
    /** Tope de duración: 100 años. Más que eso es un error de tecleo, no una sanción. */
    private static final long MAX_DURATION = 100L * 365L * 86_400_000L;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private TimeUtil() {
    }

    /**
     * Parsea una duración compuesta: "30m", "12h", "7d", "1d12h30m", "permanent".
     *
     * @return milisegundos, {@link #PERMANENT} si es permanente, {@code null} si es inválida.
     */
    public static Long parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (PERMANENT_WORD.matcher(value).matches()) {
            return PERMANENT;
        }
        // Antes se aceptaba cualquier cosa con un trozo válido dentro: "1d30" era un día
        // y "1.5h" se leía como 5 horas, así que el staff creía haber puesto otra cosa.
        if (!DURATION.matcher(value).matches()) {
            return null;
        }
        Matcher matcher = TOKEN.matcher(value);
        long total = 0L;
        while (matcher.find()) {
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ex) {
                return null; // más dígitos de los que caben en un long
            }
            long unit = unitMillis(matcher.group(2));
            // Comprobación antes de multiplicar: un número enorme desbordaría el long
            // y se guardaría una expiración absurda en vez de rechazar la duración.
            if (unit <= 0L || amount > MAX_DURATION / unit) {
                return null;
            }
            total += unit * amount;
            if (total > MAX_DURATION) {
                return null;
            }
        }
        return total <= 0L ? null : total;
    }

    /**
     * ¿Parece una duración mal escrita? Sirve para que {@code /ban <jugador> 1d30}
     * avise del error en vez de banear para siempre y usar "1d30" como motivo.
     *
     * <p>Pide dígito <b>y</b> letra para no confundir motivos como "2 cuentas" o un
     * número suelto: sólo los intentos de duración llevan unidad.</p>
     */
    public static boolean looksLikeDuration(String input) {
        if (input == null) {
            return false;
        }
        String value = input.trim();
        if (value.contains(" ")) {
            return false;
        }
        boolean digit = false;
        boolean letter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                digit = true;
            } else if (Character.isLetter(c)) {
                letter = true;
            }
        }
        return digit && letter;
    }

    private static long unitMillis(String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "s" -> 1_000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 604_800_000L;
            case "mo" -> 2_592_000_000L; // 30 días
            case "y" -> 31_536_000_000L; // 365 días
            default -> 0L;
        };
    }

    /** Duración en milisegundos -> "1d 2h 30m". Vacío si es 0 o negativa. */
    public static String format(long millis) {
        if (millis <= 0L) {
            return "";
        }
        long seconds = millis / 1000L;
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long secs = seconds % 60L;

        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append("d ");
        }
        if (hours > 0) {
            out.append(hours).append("h ");
        }
        // Los minutos se muestran también cuando hay días: antes "1d30m" se anunciaba
        // como "1d" y el staff veía una duración distinta de la aplicada.
        if (minutes > 0) {
            out.append(minutes).append("m ");
        }
        if (out.isEmpty()) {
            out.append(secs).append("s");
        }
        return out.toString().trim();
    }

    /** Formatea una fecha (epoch millis) en la zona del servidor. */
    public static String formatDate(long epochMillis) {
        return DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** Tiempo transcurrido desde una marca (en formato corto). */
    public static String ago(long epochMillis) {
        long diff = System.currentTimeMillis() - epochMillis;
        String formatted = format(Math.max(diff, 0L));
        return formatted.isEmpty() ? "0s" : formatted;
    }

    /** Suma una duración a ahora, respetando el valor permanente. */
    public static long expiresAt(long now, long durationMillis) {
        if (durationMillis == PERMANENT) {
            return PERMANENT;
        }
        return now + durationMillis;
    }

    /**
     * Texto legible del tiempo que queda: "3d 4h", "permanente" o "0s".
     *
     * <p>Los minutos se redondean al más cercano porque {@link #format} trunca: sin
     * esto, un ban de 1d30m recién aplicado se leía como "1d 29m" y parecía otro.</p>
     */
    public static String remaining(long expiresAt) {
        if (expiresAt == PERMANENT) {
            return "permanent";
        }
        long diff = expiresAt - System.currentTimeMillis();
        if (diff <= 0L) {
            return "0s";
        }
        return format(roundToMinute(diff));
    }

    /** Redondea al minuto más cercano, sin tocar lo que dura menos de un minuto. */
    private static long roundToMinute(long millis) {
        if (millis < 60_000L) {
            return millis;
        }
        return ((millis + 30_000L) / 60_000L) * 60_000L;
    }

    public static Duration of(long millis) {
        return Duration.ofMillis(Math.max(0L, millis));
    }
}
