package com.admintools.gui.panel;

import com.admintools.AdminToolsPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Panel en forma de inventario Bukkit (la interfaz básica, la que entiende todo el
 * mundo, incluidos los clientes Bedrock a través de Geyser).
 *
 * <p>Flujo: rango → grupos → acciones. Un clic en una acción sin datos la ejecuta al
 * momento; si necesita un jugador, una duración o un motivo, el menú se cierra y se
 * piden por chat. Nada se puede arrastrar ni sacar: todos los menús son de sólo
 * lectura.</p>
 */
public final class InventoryPanel implements Listener {

    private static final int SIZE = 54;
    /** Espacios útiles antes de la fila de navegación. */
    private static final int CONTENT = 45;

    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREV = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_CLOSE = 53;

    /** Espacios de la primera fila de grupos (centrada). */
    private static final int[] SINGLE_ROW = {10, 11, 12, 13, 14, 15, 16};
    /** Dos filas cuando hay más de siete grupos. */
    private static final int[] DOUBLE_ROW = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private final AdminToolsPlugin plugin;
    private final PanelService service;

    public InventoryPanel(AdminToolsPlugin plugin, PanelService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private enum Kind {
        ROOT, GROUP
    }

    // ------------------------------------------------------------
    //  Menú raíz: un icono por grupo
    // ------------------------------------------------------------

    public void openRoot(Player viewer, PanelRank rank, List<PanelGroup> groups) {
        Menu menu = new Menu(Kind.ROOT, rank, null, 1);
        Inventory inventory = Bukkit.createInventory(menu, SIZE,
                plugin.getMessages().get(rank.titleKey()));
        menu.inventory = inventory;
        fill(inventory);

        int[] slots = groups.size() <= SINGLE_ROW.length ? SINGLE_ROW
                : groups.size() <= DOUBLE_ROW.length ? DOUBLE_ROW : sequential();
        for (int i = 0; i < groups.size() && i < slots.length; i++) {
            PanelGroup group = groups.get(i);
            inventory.setItem(slots[i], icon(group.icon(),
                    plugin.getMessages().get(group.labelKey()),
                    List.of(plugin.getMessages().get(group.hintKey()),
                            plugin.getMessages().get("panel.click-open"),
                            plugin.getMessages().get("panel.group-count",
                                    "count", group.actions().size()))));
            menu.groups.put(slots[i], group);
        }
        inventory.setItem(SLOT_INFO, icon(rank.icon(),
                plugin.getMessages().get(rank.titleKey()),
                List.of(plugin.getMessages().get(rank.descriptionKey()))));
        inventory.setItem(SLOT_CLOSE, icon(Material.BARRIER,
                plugin.getMessages().get("panel.close")));
        viewer.openInventory(inventory);
    }

    // ------------------------------------------------------------
    //  Menú de grupo: todas las acciones permitidas
    // ------------------------------------------------------------

    public void openGroup(Player viewer, PanelRank rank, PanelGroup group, int page) {
        List<PanelAction> actions = group.visible(viewer);
        if (actions.isEmpty()) {
            plugin.getMessages().send(viewer, "panel.empty");
            return;
        }
        int pages = Math.max(1, (actions.size() + CONTENT - 1) / CONTENT);
        int current = Math.min(Math.max(1, page), pages);
        int from = (current - 1) * CONTENT;
        int to = Math.min(from + CONTENT, actions.size());

        Menu menu = new Menu(Kind.GROUP, rank, group, current);
        Inventory inventory = Bukkit.createInventory(menu, SIZE,
                plugin.getMessages().get("panel.group-title",
                        "group", plugin.getMessages().raw(group.labelKey())));
        menu.inventory = inventory;
        fill(inventory);

        for (int index = from; index < to; index++) {
            PanelAction action = actions.get(index);
            int slot = index - from;
            inventory.setItem(slot, icon(action.icon(),
                    plugin.getMessages().get(action.labelKey()),
                    lore(action)));
            menu.actions.put(slot, action);
        }
        navigation(inventory, current, pages, actions.size());
        viewer.openInventory(inventory);
    }

    private List<Component> lore(PanelAction action) {
        return List.of(
                plugin.getMessages().get("panel.action-hint",
                        "command", "/" + action.plainCommand()),
                plugin.getMessages().get(action.needsInput()
                        ? "panel.click-input" : "panel.click-run"));
    }

    private void navigation(Inventory inventory, int page, int pages, int total) {
        inventory.setItem(SLOT_BACK, icon(Material.ARROW,
                plugin.getMessages().get("panel.back")));
        if (page > 1) {
            inventory.setItem(SLOT_PREV, icon(Material.ARROW,
                    plugin.getMessages().get("panel.prev")));
        }
        inventory.setItem(SLOT_INFO, icon(Material.PAPER,
                plugin.getMessages().get("panel.page", "page", page, "pages", pages),
                List.of(plugin.getMessages().get("panel.action-count", "count", total))));
        if (page < pages) {
            inventory.setItem(SLOT_NEXT, icon(Material.ARROW,
                    plugin.getMessages().get("panel.next")));
        }
        inventory.setItem(SLOT_CLOSE, icon(Material.BARRIER,
                plugin.getMessages().get("panel.close")));
    }

    // ------------------------------------------------------------
    //  Clics
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
            return;
        }
        switch (menu.kind) {
            case ROOT -> clickRoot(viewer, menu, slot);
            case GROUP -> clickGroup(viewer, menu, slot);
        }
    }

    private void clickRoot(Player viewer, Menu menu, int slot) {
        PanelGroup group = menu.groups.get(slot);
        if (group == null) {
            return;
        }
        reopen(viewer, () -> openGroup(viewer, menu.rank, group, 1));
    }

    private void clickGroup(Player viewer, Menu menu, int slot) {
        if (slot == SLOT_BACK) {
            reopen(viewer, () -> openRoot(viewer, menu.rank, service.visibleGroups(viewer, menu.rank)));
            return;
        }
        if (slot == SLOT_PREV) {
            reopen(viewer, () -> openGroup(viewer, menu.rank, menu.group, menu.page - 1));
            return;
        }
        if (slot == SLOT_NEXT) {
            reopen(viewer, () -> openGroup(viewer, menu.rank, menu.group, menu.page + 1));
            return;
        }
        PanelAction action = menu.actions.get(slot);
        if (action == null) {
            return;
        }
        if (action.needsInput()) {
            // El servicio cierra el menú y pide los datos por chat.
            reopen(viewer, () -> service.collect(viewer, action));
        } else {
            service.run(viewer, action, Map.of());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------

    /** Abrir o cerrar un inventario dentro de un clic se hace al tick siguiente. */
    private void reopen(Player viewer, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                action.run();
            }
        });
    }

    /** Todos los espacios de contenido en orden: para catálogos que no caben en dos filas. */
    private static int[] sequential() {
        int[] slots = new int[CONTENT];
        for (int i = 0; i < CONTENT; i++) {
            slots[i] = i;
        }
        return slots;
    }

    /**
     * Rellena los huecos con cristal gris. Cada espacio recibe su propia instancia:
     * compartir un ItemStack entre espacios es legal pero cualquier mutación futura
     * los cambiaría todos a la vez.
     */
    private void fill(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, icon(Material.GRAY_STAINED_GLASS_PANE, Component.empty()));
            }
        }
    }

    private ItemStack icon(Material material, Component name) {
        return icon(material, name, List.of());
    }

    private ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name);
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
        });
        return item;
    }

    /** Menú abierto: qué se está viendo y qué hay en cada espacio. */
    private static final class Menu implements InventoryHolder {

        private final Kind kind;
        private final PanelRank rank;
        private final PanelGroup group;
        private final int page;
        private final Map<Integer, PanelGroup> groups = new HashMap<>();
        private final Map<Integer, PanelAction> actions = new HashMap<>();
        private Inventory inventory;

        private Menu(Kind kind, PanelRank rank, PanelGroup group, int page) {
            this.kind = kind;
            this.rank = rank;
            this.group = group;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
