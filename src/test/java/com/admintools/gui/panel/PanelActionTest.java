package com.admintools.gui.panel;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelActionTest {

    private final PanelAction tempban = PanelAction.of("tempban", Material.DIAMOND_AXE,
            "admintools.sanction.ban", "tempban {player} {duration} {reason}",
            PanelField.required(PanelInput.PLAYER),
            PanelField.required(PanelInput.DURATION),
            PanelField.optional(PanelInput.REASON));

    @Test
    void renderReplacesEveryMarker() {
        Map<PanelInput, String> values = new EnumMap<>(PanelInput.class);
        values.put(PanelInput.PLAYER, "Steve");
        values.put(PanelInput.DURATION, "7d");
        values.put(PanelInput.REASON, "xray");

        assertEquals("tempban Steve 7d xray", tempban.render(values));
    }

    @Test
    void renderDropsEmptyOptionalAndCollapsesSpaces() {
        Map<PanelInput, String> values = new EnumMap<>(PanelInput.class);
        values.put(PanelInput.PLAYER, "Steve");
        values.put(PanelInput.DURATION, "7d");

        assertEquals("tempban Steve 7d", tempban.render(values));
    }

    @Test
    void renderDoesNotResubstituteTheReason() {
        Map<PanelInput, String> values = new EnumMap<>(PanelInput.class);
        values.put(PanelInput.PLAYER, "Steve");
        values.put(PanelInput.DURATION, "7d");
        values.put(PanelInput.REASON, "{player} spoof");

        assertEquals("tempban Steve 7d {player} spoof", tempban.render(values));
    }

    @Test
    void completenessRequiresOnlyMandatoryFields() {
        Map<PanelInput, String> values = new EnumMap<>(PanelInput.class);
        assertFalse(tempban.complete(values));

        values.put(PanelInput.PLAYER, "Steve");
        assertFalse(tempban.complete(values));

        values.put(PanelInput.DURATION, "7d");
        assertTrue(tempban.complete(values));
    }

    @Test
    void inputsValidateTheirOwnFormat() {
        assertTrue(PanelInput.PLAYER.accept("Steve_99"));
        assertFalse(PanelInput.PLAYER.accept("Steve 99"));
        assertFalse(PanelInput.PLAYER.accept(""));
        assertTrue(PanelInput.DURATION.accept("7d"));
        assertFalse(PanelInput.DURATION.accept("siete días"));
        assertTrue(PanelInput.MODE.accept("creative"));
        assertFalse(PanelInput.MODE.accept("volar"));
        assertTrue(PanelInput.REASON.accept("rompió la granja"));
    }

    @Test
    void plainCommandHidesMarkers() {
        assertEquals("tempban", tempban.plainCommand());
    }

    @Test
    void ranksAreCumulativeAndIdsUnique() {
        Set<String> helper = ids(PanelRank.HELPER);
        Set<String> moderator = ids(PanelRank.MODERATOR);
        Set<String> admin = ids(PanelRank.ADMIN);

        assertTrue(moderator.containsAll(helper), "moderador debe incluir el panel de helper");
        assertTrue(admin.containsAll(moderator), "admin debe incluir el panel de moderador");
        assertTrue(admin.size() > helper.size());
        assertTrue(helper.contains("warn"));
        assertTrue(moderator.contains("ban"));
        assertTrue(admin.contains("reload"));
    }

    @Test
    void everyActionDeclaresAPermission() {
        for (PanelRank rank : PanelRank.values()) {
            for (PanelGroup group : PanelCatalog.groups(rank)) {
                for (PanelAction action : group.actions()) {
                    assertTrue(action.permission() != null && action.permission().startsWith("admintools."),
                            action.id() + " sin permiso admintools.*");
                }
            }
        }
    }

    /** Ids únicos: si se repiten, dos botones distintos comparten etiqueta. */
    private static Set<String> ids(PanelRank rank) {
        Set<String> result = new HashSet<>();
        for (PanelGroup group : PanelCatalog.groups(rank)) {
            for (PanelAction action : group.actions()) {
                assertTrue(result.add(action.id()), "acción repetida: " + action.id());
            }
        }
        return result;
    }
}
