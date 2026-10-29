package com.admintools.model;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marca las vistas de inventario que son una <b>copia</b> guardada (jugador offline).
 * A diferencia de /invsee en vivo, aquí nada se escribe de vuelta al jugador, así
 * que el staff no puede mover ni sacar ítems: sería un duplicado.
 */
public final class SnapshotView implements InventoryHolder {

    private Inventory inventory;

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void inventory(Inventory value) {
        this.inventory = value;
    }
}
