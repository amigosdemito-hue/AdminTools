package com.admintools.model;

import java.util.UUID;

/**
 * Copia completa de un jugador para /rollback: inventario, enderchest, posición
 * y estadísticas. {@code reason} es un código corto (DEATH, JOIN, QUIT, WORLD,
 * MANUAL, SAFETY, INTERVAL) para poder aplicar retención distinta a cada uno.
 */
public record InventoryBackup(long id, UUID uuid, String name, String reason, long createdAt,
                              byte[] inventory, byte[] enderchest, String world,
                              double x, double y, double z, float yaw, float pitch,
                              double health, int food, int xp, String gamemode, int size) {

    public String coords() {
        return Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
    }

    /** Tamaño legible para el listado. */
    public String sizeText() {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return Math.round(size / 102.4) / 10.0 + " KB";
        }
        return Math.round(size / (1024.0 * 1024.0) * 10.0) / 10.0 + " MB";
    }
}
