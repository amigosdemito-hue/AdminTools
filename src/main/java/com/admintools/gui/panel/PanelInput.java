package com.admintools.gui.panel;

import com.admintools.util.TimeUtil;
import com.admintools.util.Util;

import java.util.List;
import java.util.Locale;

/**
 * Dato que el panel pide antes de lanzar una acción ({@code {player}}, {@code {duration}}…).
 *
 * <p>Cada entrada sabe su etiqueta traducida, su valor inicial y cómo validar lo que
 * escribe el staff. La usan las tres interfaces: el inventario la pregunta por chat,
 * los diálogos la convierten en un campo de texto nativo y los formularios Bedrock en
 * un {@code input} o {@code dropdown}.</p>
 */
public enum PanelInput {

    PLAYER("player", 18, "", List.of()),
    DURATION("duration", 12, "1h", List.of()),
    REASON("reason", 128, "", List.of()),
    TEMPLATE("template", 32, "", List.of()),
    NAME("name", 24, "", List.of()),
    MODE("mode", 16, "survival", List.of("survival", "creative", "adventure", "spectator")),
    VALUE("value", 32, "", List.of());

    private final String key;
    private final int maxLength;
    private final String initial;
    private final List<String> options;

    PanelInput(String key, int maxLength, String initial, List<String> options) {
        this.key = key;
        this.maxLength = maxLength;
        this.initial = initial;
        this.options = options;
    }

    /** Nombre del campo: clave del mensaje y clave del dato en el diálogo. */
    public String key() {
        return key;
    }

    public String labelKey() {
        return "panel.input." + key;
    }

    /** Valor con el que se precarga el campo. */
    public String initial() {
        return initial;
    }

    /** Opciones cerradas (modo de juego); vacío si el campo es libre. */
    public List<String> options() {
        return options;
    }

    public boolean hasOptions() {
        return !options.isEmpty();
    }

    public int maxLength() {
        return maxLength;
    }

    /**
     * ¿Sirve el valor tal cual? La comprobación es deliberadamente ligera: los
     * comandos vuelven a validar todo y dan el mensaje de error específico.
     */
    public boolean accept(String value) {
        if (value == null) {
            return false;
        }
        String clean = value.trim();
        if (clean.isEmpty() || clean.length() > maxLength) {
            return false;
        }
        return switch (this) {
            // Los de Java son [A-Za-z0-9_]; los de Bedrock llegan con el prefijo de
            // Floodgate (.Steve), que antes se rechazaba.
            case PLAYER -> Util.isPlayerName(clean);
            case DURATION -> TimeUtil.parse(clean) != null;
            case MODE -> options.contains(clean.toLowerCase(Locale.ROOT))
                    || clean.matches("[0-3]");
            case TEMPLATE, NAME -> clean.matches("[A-Za-z0-9_-]{1,32}");
            case REASON -> true;
            case VALUE -> clean.matches("[A-Za-z0-9_.:-]{1,32}");
        };
    }

    /** Texto de ejemplo para el campo vacío (se muestra como pista). */
    public String example() {
        return switch (this) {
            case PLAYER -> "Steve";
            case DURATION -> "1h, 7d, permanent";
            case REASON -> "";
            case TEMPLATE -> "hacking, spam...";
            case NAME -> "celda1";
            case MODE -> "survival";
            case VALUE -> "30";
        };
    }
}
