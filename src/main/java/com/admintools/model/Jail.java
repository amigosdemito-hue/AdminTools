package com.admintools.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Una cárcel: un punto del mundo con un radio de fuga (0 = sólo cuenta el mundo). */
public record Jail(long id, String name, String world, double x, double y, double z,
                   float yaw, float pitch, double radius, long createdAt, String createdBy) {

    /** Ubicación de la cárcel, o {@code null} si el mundo ya no existe. */
    public Location location() {
        World found = Bukkit.getWorld(world);
        return found == null ? null : new Location(found, x, y, z, yaw, pitch);
    }

    /**
     * ¿La ubicación está dentro de la cárcel? Sin radio, basta con estar en el
     * mismo mundo: se usa para cárceles de mundo entero o para evitar escapes.
     *
     * <p>El radio se mide en horizontal como un cuadrado (2 = 5x5 bloques), que es
     * lo que se ve en la celda: una esfera dejaría las esquinas fuera y permitiría
     * escaparse por ellas. En vertical se da algo de margen para saltar o caer.</p>
     */
    public boolean inside(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().getName().equalsIgnoreCase(world)) {
            return false;
        }
        return within(x, y, z, radius, location.getX(), location.getY(), location.getZ());
    }

    /**
     * Geometría del área de la celda, sin depender de Bukkit (se puede probar sola).
     *
     * <p>Horizontal es un cuadrado centrado en el punto; en vertical se da algo de
     * margen para saltar o caer sin activar la fuga.</p>
     */
    public static boolean within(double centerX, double centerY, double centerZ, double radius,
                                 double x, double y, double z) {
        if (radius <= 0.0) {
            return true;
        }
        if (Math.abs(x - centerX) > radius || Math.abs(z - centerZ) > radius) {
            return false;
        }
        return Math.abs(y - centerY) <= Math.max(2.0, radius);
    }

    /** Texto corto de coordenadas para listados. */
    public String coords() {
        return Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
    }
}
