package com.admintools.gui.panel;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Categoría del panel (sanciones, staff, cárcel…): un icono y sus botones. */
public record PanelGroup(String id, Material icon, List<PanelAction> actions) {

    public PanelGroup {
        actions = List.copyOf(actions);
    }

    public String labelKey() {
        return "panel.group." + id;
    }

    /**
     * Clave del texto de ayuda. Vive bajo {@code panel.group.hint}, no bajo
     * {@code panel.group.<id>.hint}: un punto dentro de una clave hermana hace que
     * Bukkit cree una sección y borre la etiqueta del grupo.
     */
    public String hintKey() {
        return "panel.group.hint." + id;
    }

    /** Acciones que este jugador puede usar: el resto no se muestra ni se responde. */
    public List<PanelAction> visible(Player player) {
        return actions.stream().filter(action -> action.allowed(player)).toList();
    }
}
