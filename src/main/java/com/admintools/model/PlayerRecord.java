package com.admintools.model;

import com.admintools.util.TimeUtil;

import java.util.UUID;

/** Ficha persistente de un jugador: visitas, tiempo jugado y estado (congelado, de servicio). */
public final class PlayerRecord {

    private final UUID uuid;
    private String name;
    private final long firstSeen;
    private long lastSeen;
    private long lastLogin;
    private long lastLogout;
    private long playtimeSeconds;
    private int sessions;
    private boolean frozen;
    private boolean duty;
    private String lastIp;

    public PlayerRecord(UUID uuid, String name, long firstSeen, long lastSeen, long lastLogin, long lastLogout,
                        long playtimeSeconds, int sessions, boolean frozen, boolean duty, String lastIp) {
        this.uuid = uuid;
        this.name = name;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.lastLogin = lastLogin;
        this.lastLogout = lastLogout;
        this.playtimeSeconds = playtimeSeconds;
        this.sessions = sessions;
        this.frozen = frozen;
        this.duty = duty;
        this.lastIp = lastIp;
    }

    public static PlayerRecord fresh(UUID uuid, String name, long now, String ip) {
        return new PlayerRecord(uuid, name, now, now, now, 0L, 0L, 0, false, false, ip);
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String value) {
        this.name = value;
    }

    public long firstSeen() {
        return firstSeen;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void lastSeen(long value) {
        this.lastSeen = value;
    }

    public long lastLogin() {
        return lastLogin;
    }

    public void lastLogin(long value) {
        this.lastLogin = value;
    }

    public long lastLogout() {
        return lastLogout;
    }

    public void lastLogout(long value) {
        this.lastLogout = value;
    }

    public long playtimeSeconds() {
        return playtimeSeconds;
    }

    public void addPlaytimeSeconds(long seconds) {
        this.playtimeSeconds += Math.max(0L, seconds);
    }

    public int sessions() {
        return sessions;
    }

    public void sessions(int value) {
        this.sessions = value;
    }

    public boolean frozen() {
        return frozen;
    }

    public void frozen(boolean value) {
        this.frozen = value;
    }

    public boolean duty() {
        return duty;
    }

    public void duty(boolean value) {
        this.duty = value;
    }

    public String lastIp() {
        return lastIp;
    }

    public void lastIp(String value) {
        this.lastIp = value;
    }

    public String playtimeText() {
        return TimeUtil.format(playtimeSeconds * 1000L);
    }
}
