package com.admintools.model;

import java.util.UUID;

/** Copia de inventario guardada (al salir, al morir o a mano). */
public record Snapshot(long id, UUID uuid, String name, String reason, long createdAt, String kind, byte[] data) {
}
