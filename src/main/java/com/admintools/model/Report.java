package com.admintools.model;

import java.util.UUID;

/** Un reporte de un jugador contra otro, con su ciclo de vida. */
public final class Report {

    private final long id;
    private final UUID reporterUuid;
    private final String reporterName;
    private final UUID targetUuid;
    private final String targetName;
    private final String reason;
    private final long createdAt;
    private ReportStatus status;
    private String claimedBy;
    private String claimedName;
    private long claimedAt;
    private String resolvedBy;
    private String resolvedName;
    private long resolvedAt;
    private String resolution;
    private final String origin;

    public Report(long id, UUID reporterUuid, String reporterName, UUID targetUuid, String targetName, String reason,
                  long createdAt, ReportStatus status, String claimedBy, String claimedName, long claimedAt,
                  String resolvedBy, String resolvedName, long resolvedAt, String resolution, String origin) {
        this.id = id;
        this.reporterUuid = reporterUuid;
        this.reporterName = reporterName;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.reason = reason;
        this.createdAt = createdAt;
        this.status = status;
        this.claimedBy = claimedBy;
        this.claimedName = claimedName;
        this.claimedAt = claimedAt;
        this.resolvedBy = resolvedBy;
        this.resolvedName = resolvedName;
        this.resolvedAt = resolvedAt;
        this.resolution = resolution;
        this.origin = origin;
    }

    public long id() {
        return id;
    }

    public UUID reporterUuid() {
        return reporterUuid;
    }

    public String reporterName() {
        return reporterName;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public String targetName() {
        return targetName;
    }

    public String reason() {
        return reason;
    }

    public long createdAt() {
        return createdAt;
    }

    public ReportStatus status() {
        return status;
    }

    public void status(ReportStatus value) {
        this.status = value;
    }

    public String claimedBy() {
        return claimedBy;
    }

    public String claimedName() {
        return claimedName;
    }

    public long claimedAt() {
        return claimedAt;
    }

    public void claim(String uuid, String name) {
        this.claimedBy = uuid;
        this.claimedName = name;
        this.claimedAt = System.currentTimeMillis();
        this.status = ReportStatus.CLAIMED;
    }

    public String resolvedName() {
        return resolvedName;
    }

    public String resolvedBy() {
        return resolvedBy;
    }

    public long resolvedAt() {
        return resolvedAt;
    }

    public void close(ReportStatus newStatus, String uuid, String name, String note) {
        this.status = newStatus;
        this.resolvedBy = uuid;
        this.resolvedName = name;
        this.resolvedAt = System.currentTimeMillis();
        this.resolution = note;
    }

    public String resolution() {
        return resolution;
    }

    public String origin() {
        return origin;
    }
}
