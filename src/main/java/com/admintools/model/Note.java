package com.admintools.model;

import java.util.UUID;

/** Nota interna de staff sobre un jugador. */
public record Note(long id, UUID targetUuid, String targetName, UUID authorUuid, String authorName, String text,
                   long createdAt, String origin) {
}
