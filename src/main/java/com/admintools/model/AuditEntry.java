package com.admintools.model;

/** Entrada del registro de auditoría: quién hizo qué y a quién. */
public record AuditEntry(long id, String actorUuid, String actorName, String action, String target, String details,
                         long createdAt, String origin) {
}
