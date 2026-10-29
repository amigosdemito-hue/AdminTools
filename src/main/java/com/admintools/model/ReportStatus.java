package com.admintools.model;

import java.util.Locale;

/** Estados por los que pasa un reporte. */
public enum ReportStatus {

    OPEN("<yellow>", "abierto"),
    CLAIMED("<gold>", "en revisión"),
    RESOLVED("<green>", "resuelto"),
    REJECTED("<red>", "rechazado");

    private final String color;
    private final String label;

    ReportStatus(String color, String label) {
        this.color = color;
        this.label = label;
    }

    public String color() {
        return color;
    }

    public String label() {
        return label;
    }

    public boolean open() {
        return this == OPEN || this == CLAIMED;
    }

    public static ReportStatus parse(String value, ReportStatus fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
