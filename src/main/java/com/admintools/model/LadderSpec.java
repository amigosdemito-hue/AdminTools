package com.admintools.model;

import com.admintools.util.TimeUtil;

import java.util.List;

/** Escalera de sanciones: sube un paso por cada sanción previa dentro de la ventana. */
public record LadderSpec(String name, int windowDays, List<LadderStep> steps) {

    /** Un paso de la escalera. */
    public record LadderStep(PunishmentType type, long duration, String reason) {

        public boolean permanent() {
            return duration == TimeUtil.PERMANENT;
        }

        public String durationText() {
            return permanent() ? "permanent" : TimeUtil.format(duration);
        }
    }

    /** Paso correspondiente a la cantidad de sanciones previas (0 = primera vez). */
    public LadderStep stepFor(int previousSanctions) {
        int index = Math.min(previousSanctions, steps.size() - 1);
        return steps.get(Math.max(0, index));
    }

    public int stepNumberFor(int previousSanctions) {
        return Math.min(previousSanctions + 1, steps.size());
    }
}
