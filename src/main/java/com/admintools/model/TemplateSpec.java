package com.admintools.model;

import com.admintools.util.TimeUtil;

/** Plantilla de sanción definida en config.yml. */
public record TemplateSpec(String name, PunishmentType type, long duration, String reason) {

    public boolean permanent() {
        return duration == TimeUtil.PERMANENT;
    }

    public String durationText() {
        return permanent() ? "permanent" : TimeUtil.format(duration);
    }
}
