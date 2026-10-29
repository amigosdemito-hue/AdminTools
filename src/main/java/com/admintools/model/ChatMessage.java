package com.admintools.model;

import java.util.UUID;

/** Mensaje de chat guardado para /chatlog y /case. */
public record ChatMessage(long id, UUID uuid, String name, String message, long createdAt, String origin) {
}
