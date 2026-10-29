package com.admintools.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Una condena de cárcel.
 *
 * <p>{@code remainingSeconds} es el tiempo que le queda <b>conectado</b>; -1 es
 * cadena perpetua. La posición original se guarda para devolverlo al salir.</p>
 */
public final class JailSentence {

    public static final long PERMANENT = -1L;

    private final long id;
    private final UUID targetUuid;
    private final String targetName;
    private final String jail;
    private final String reason;
    private final UUID staffUuid;
    private final String staffName;
    private final long createdAt;
    private long remainingSeconds;
    private final String originWorld;
    private final double originX;
    private final double originY;
    private final double originZ;
    private final float originYaw;
    private final float originPitch;

    public JailSentence(long id, UUID targetUuid, String targetName, String jail, String reason,
                        UUID staffUuid, String staffName, long createdAt, long remainingSeconds,
                        String originWorld, double originX, double originY, double originZ,
                        float originYaw, float originPitch) {
        this.id = id;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.jail = jail;
        this.reason = reason;
        this.staffUuid = staffUuid;
        this.staffName = staffName;
        this.createdAt = createdAt;
        this.remainingSeconds = remainingSeconds;
        this.originWorld = originWorld;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.originYaw = originYaw;
        this.originPitch = originPitch;
    }

    public long id() {
        return id;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public String targetName() {
        return targetName;
    }

    public String jail() {
        return jail;
    }

    public String reason() {
        return reason == null || reason.isBlank() ? "-" : reason;
    }

    public UUID staffUuid() {
        return staffUuid;
    }

    public String staffName() {
        return staffName == null ? "CONSOLE" : staffName;
    }

    public long createdAt() {
        return createdAt;
    }

    public long remainingSeconds() {
        return remainingSeconds;
    }

    public void remainingSeconds(long value) {
        this.remainingSeconds = value;
    }

    public boolean permanent() {
        return remainingSeconds < 0L;
    }

    /** Ubicación desde la que se le encarceló, o {@code null} si ese mundo ya no existe. */
    public Location origin() {
        if (originWorld == null) {
            return null;
        }
        World world = Bukkit.getWorld(originWorld);
        return world == null ? null : new Location(world, originX, originY, originZ, originYaw, originPitch);
    }
}
