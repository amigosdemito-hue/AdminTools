package com.admintools.model;

import java.util.UUID;

/** Cuenta vinculada a un jugador por IP. */
public record AltAccount(UUID uuid, String name, String ip, long lastSeen, boolean online) {
}
