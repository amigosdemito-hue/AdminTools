package com.admintools.gui.panel;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Una acción del panel: un botón que termina ejecutando un comando del plugin.
 *
 * <p>El comando se escribe como plantilla ({@code tempban {player} {duration} {reason}})
 * y los marcadores se sustituyen con lo que devuelvan los campos. Se ejecuta siempre
 * <b>como el jugador</b> con {@code Bukkit.dispatchCommand}, así que el registro de
 * comandos aplica permisos, límite de acciones y auditoría: el panel no es una puerta
 * trasera ni puede hacer nada que el jugador no pudiera teclear a mano.</p>
 */
public record PanelAction(String id, Material icon, String permission, String command,
                          List<PanelField> fields) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)\\}");

    public PanelAction {
        fields = List.copyOf(fields);
    }

    public static PanelAction of(String id, Material icon, String permission, String command,
                                 PanelField... fields) {
        return new PanelAction(id.toLowerCase(Locale.ROOT), icon, permission, command, List.of(fields));
    }

    public String labelKey() {
        return "panel.action." + id;
    }

    /** El comando tal cual, sin marcadores: lo que se muestra en la ayuda del botón. */
    public String plainCommand() {
        return PLACEHOLDER.matcher(command).replaceAll("").replaceAll(" {2,}", " ").trim();
    }

    public boolean needsInput() {
        return !fields.isEmpty();
    }

    /** ¿Puede usar esta acción? Sin permiso el botón ni siquiera existe. */
    public boolean allowed(Player player) {
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    /** ¿Están todos los campos obligatorios? Se comprueba antes de lanzar el comando. */
    public boolean complete(Map<PanelInput, String> values) {
        for (PanelField field : fields) {
            String value = values.get(field.input());
            if (field.required() && (value == null || value.isBlank())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sustituye los marcadores en una sola pasada: así un motivo que contenga
     * literalmente "{player}" no se vuelve a sustituir.
     */
    public String render(Map<PanelInput, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(command);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            PanelInput input = byKey(matcher.group(1));
            String value = input == null ? null : values.get(input);
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    value == null ? "" : value.trim()));
        }
        matcher.appendTail(result);
        return result.toString().trim().replaceAll(" {2,}", " ");
    }

    private static PanelInput byKey(String key) {
        for (PanelInput input : PanelInput.values()) {
            if (input.key().equals(key)) {
                return input;
            }
        }
        return null;
    }
}
