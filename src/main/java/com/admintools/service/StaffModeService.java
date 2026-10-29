package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modo staff: vuelo, invulnerabilidad, no recoger ítems y una hotbar de herramientas.
 *
 * <p>Antes de tocar el inventario se guarda una copia <b>persistente</b> con
 * {@link SnapshotService}. Así, aunque el servidor se caiga con el modo staff
 * activo, el inventario original se puede recuperar con {@code /restore}.
 * Al salir del modo se devuelve la copia exacta en memoria.</p>
 */
public final class StaffModeService {

    /** Ranuras de la barra rápida que se reemplazan por las herramientas. */
    private static final int HOTBAR_SLOTS = 9;

    private record Saved(long snapshotId, GameMode mode, boolean allowFlight, boolean flying, boolean invulnerable,
                         boolean toolsGiven) {
    }

    private final AdminToolsPlugin plugin;
    private final Map<UUID, Saved> states = new ConcurrentHashMap<>();

    public StaffModeService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean active(UUID uuid) {
        return states.containsKey(uuid);
    }

    /** Alterna el modo staff y devuelve el estado nuevo ({@code true} = activado). */
    public boolean toggle(Player player) {
        if (active(player.getUniqueId())) {
            disable(player);
            return false;
        }
        enable(player);
        return true;
    }

    public void enable(Player player) {
        if (active(player.getUniqueId())) {
            return;
        }
        // Copia persistente antes de tocar nada: es la red de seguridad real.
        long snapshot = plugin.getSnapshotService().save(player, "Antes de /staffmode");
        // Se anota si se dieron herramientas: al salir hay que devolver el inventario según
        // lo que pasó al entrar, no según lo que diga la config en ese momento (una recarga
        // con give-tools apagado dejaría al staff sin su barra rápida para siempre).
        boolean tools = plugin.getSettings().staffModeGiveTools();
        states.put(player.getUniqueId(), new Saved(snapshot, player.getGameMode(),
                player.getAllowFlight(), player.isFlying(), player.isInvulnerable(), tools));

        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvulnerable(true);
        if (tools) {
            giveTools(player);
        }
    }

    public void disable(Player player) {
        Saved saved = states.remove(player.getUniqueId());
        if (saved == null) {
            return;
        }
        try {
            player.setInvulnerable(saved.invulnerable());
            player.setAllowFlight(saved.allowFlight());
            if (saved.allowFlight() && saved.flying()) {
                player.setFlying(true);
            }
            if (player.getGameMode() != saved.mode()) {
                player.setGameMode(saved.mode());
            }
            if (saved.toolsGiven()) {
                restoreInventory(player, saved.snapshotId());
            }
            player.updateInventory();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("No pude devolver el inventario de " + player.getName()
                    + " al salir del modo staff: " + ex.getMessage()
                    + " (copia #" + saved.snapshotId() + " disponible con /restore)");
        }
    }

    /** Desactiva el modo staff de todos los conectados (apagado del plugin). */
    public void disableAll() {
        for (UUID uuid : Map.copyOf(states).keySet()) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                disable(player);
            } else {
                states.remove(uuid);
            }
        }
    }

    private void restoreInventory(Player player, long snapshotId) {
        if (snapshotId <= 0L) {
            return;
        }
        ItemStack[] contents = plugin.getSnapshotService().readInventory(snapshotId);
        if (contents.length == 0) {
            plugin.getLogger().warning("La copia #" + snapshotId + " de " + player.getName()
                    + " está vacía; no devuelvo el inventario automáticamente.");
            return;
        }
        player.getInventory().setContents(contents);
    }

    private void giveTools(Player player) {
        for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
            player.getInventory().setItem(slot, null);
        }
        player.getInventory().setItem(0, tool(Material.COMPASS,
                "staff.mode-tool-compass", "staff.mode-tool-compass-lore"));
        player.getInventory().setItem(1, tool(Material.BLAZE_ROD,
                "staff.mode-tool-freeze", "staff.mode-tool-freeze-lore"));
        player.getInventory().setItem(2, tool(Material.CLOCK,
                "staff.mode-tool-vanish", "staff.mode-tool-vanish-lore"));
        player.getInventory().setItem(3, tool(Material.BOOK,
                "staff.mode-tool-check", "staff.mode-tool-check-lore"));
    }

    private ItemStack tool(Material material, String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.getMessages().get(nameKey));
            meta.lore(List.of(plugin.getMessages().get(loreKey)));
            item.setItemMeta(meta);
        }
        return item;
    }
}
