package com.admintools.gui.panel;

import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Los tres paneles maestros del plugin: {@code /helper}, {@code /moderator} y {@code /admin}.
 *
 * <p>Son acumulativos, igual que sus permisos en {@code plugin.yml}: el de moderador
 * incluye todo lo del helper y añade lo suyo, y el de admin incluye los dos anteriores.
 * Así el catálogo se declara una sola vez por nivel y no hay tres listas que se
 * desincronicen.</p>
 */
public enum PanelRank {

    HELPER("helper", "admintools.helper", Material.LANTERN),
    MODERATOR("moderator", "admintools.moderator", Material.SHIELD),
    ADMIN("admin", "admintools.admin", Material.NETHER_STAR);

    private final String id;
    private final String permission;
    private final Material icon;

    PanelRank(String id, String permission, Material icon) {
        this.id = id;
        this.permission = permission;
        this.icon = icon;
    }

    /** Etiqueta del comando y de los mensajes ({@code helper}, {@code moderator}, {@code admin}). */
    public String id() {
        return id;
    }

    /** Permiso del rango. Tenerlo implica poder abrir el panel. */
    public String permission() {
        return permission;
    }

    public Material icon() {
        return icon;
    }

    public String titleKey() {
        return "panel.title." + id;
    }

    /**
     * Clave del texto de ayuda. Vive bajo {@code panel.title.hint}, no bajo
     * {@code panel.title.<rango>.hint}: Bukkit trata el punto como separador, así que
     * una clave hermana con punto crearía una sección y borraría el título.
     */
    public String descriptionKey() {
        return "panel.title.hint." + id;
    }

    public boolean grants(Player player) {
        return player.hasPermission(permission);
    }

    /** ¿Este rango contiene el contenido del otro? Admin ⊇ Moderador ⊇ Helper. */
    public boolean covers(PanelRank other) {
        return ordinal() >= other.ordinal();
    }

    /** Rango por su etiqueta, o {@code null} si no existe. */
    public static PanelRank byId(String id) {
        for (PanelRank rank : values()) {
            if (rank.id.equalsIgnoreCase(id)) {
                return rank;
            }
        }
        return null;
    }
}
