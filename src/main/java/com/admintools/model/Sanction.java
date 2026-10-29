package com.admintools.model;

import java.util.UUID;

/** Una sanción tal como vive en la base de datos. */
public final class Sanction {

    private long id;
    private final PunishmentType type;
    private final UUID targetUuid;
    private final String targetName;
    private final String ip;
    private final String reason;
    private final UUID staffUuid;
    private final String staffName;
    private final long createdAt;
    private final long expiresAt;
    private boolean active;
    private boolean silent;
    private String liftedBy;
    private String liftedName;
    private long liftedAt;
    private String liftReason;
    private final String ladder;
    private final String template;
    private final String origin;

    public Sanction(long id, PunishmentType type, UUID targetUuid, String targetName, String ip, String reason,
                    UUID staffUuid, String staffName, long createdAt, long expiresAt, boolean active, boolean silent,
                    String liftedBy, String liftedName, long liftedAt, String liftReason, String ladder, String template,
                    String origin) {
        this.id = id;
        this.type = type;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.ip = ip;
        this.reason = reason;
        this.staffUuid = staffUuid;
        this.staffName = staffName;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.active = active;
        this.silent = silent;
        this.liftedBy = liftedBy;
        this.liftedName = liftedName;
        this.liftedAt = liftedAt;
        this.liftReason = liftReason;
        this.ladder = ladder;
        this.template = template;
        this.origin = origin;
    }

    public long id() {
        return id;
    }

    public void id(long value) {
        this.id = value;
    }

    public PunishmentType type() {
        return type;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public String targetName() {
        return targetName;
    }

    public String ip() {
        return ip;
    }

    public String reason() {
        return reason == null || reason.isBlank() ? "-" : reason;
    }

    public UUID staffUuid() {
        return staffUuid;
    }

    public String staffName() {
        return staffName == null ? "Consola" : staffName;
    }

    public long createdAt() {
        return createdAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public boolean permanent() {
        return expiresAt <= 0L;
    }

    public boolean active() {
        return active;
    }

    public void active(boolean value) {
        this.active = value;
    }

    public boolean silent() {
        return silent;
    }

    public String liftedBy() {
        return liftedBy;
    }

    public String liftedName() {
        return liftedName;
    }

    public long liftedAt() {
        return liftedAt;
    }

    public void lifted(String byName, String reason) {
        this.active = false;
        this.liftedName = byName;
        this.liftedAt = System.currentTimeMillis();
        this.liftReason = reason;
    }

    public void liftedBy(String uuid) {
        this.liftedBy = uuid;
    }

    public String liftReason() {
        return liftReason;
    }

    public String ladder() {
        return ladder;
    }

    public String template() {
        return template;
    }

    public String origin() {
        return origin;
    }

    /** ¿Quedó sin efecto (levantada o expirada)? */
    public boolean expired() {
        return expiresAt > 0L && expiresAt <= System.currentTimeMillis();
    }

    public boolean activeNow() {
        return active && !expired();
    }
}
