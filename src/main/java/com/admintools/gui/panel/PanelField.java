package com.admintools.gui.panel;

/**
 * Campo de una acción: qué dato pide y si es obligatorio.
 *
 * <p>Los opcionales (el motivo, el jugador de {@code /heal}) se pueden dejar vacíos:
 * el marcador simplemente desaparece del comando y el propio comando aplica su valor
 * por defecto.</p>
 */
public record PanelField(PanelInput input, boolean required) {

    public static PanelField required(PanelInput input) {
        return new PanelField(input, true);
    }

    public static PanelField optional(PanelInput input) {
        return new PanelField(input, false);
    }
}
