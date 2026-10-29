package com.admintools.gui;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.InventoryBackup;
import com.admintools.service.InventoryBackupService;
import com.admintools.service.InventoryBackupService.BackupOwner;
import com.admintools.service.InventoryBackupService.Part;
import com.admintools.util.TimeUtil;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Menús de /rollback, al estilo de InventoryRollbackPlus pero con más control.
 *
 * <p>Flujo: jugadores con copias → copias del jugador → contenido de la copia
 * (con el mismo orden que el inventario del jugador: inventario, armadura y mano
 * secundaria) → enderchest → acciones → confirmación. Nada se mueve entre
 * inventarios: todos los menús son de sólo lectura y las acciones se confirman
 * en un menú aparte.</p>
 */
public final class RollbackGui implements Listener {

    /** Tamaño de los menús con una fila de navegación. */
    private static final int SIZE = 54;
    /** Espacios de contenido antes de la fila de navegación. */
    private static final int CONTENT = 45;

    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREV = 48;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_CLOSE = 53;

    /** Botones del contenido: enderchest y restaurar todo. */
    private static final int SLOT_ENDER = 47;
    private static final int SLOT_RESTORE_ALL = 51;
    private static final int SLOT_INFO = 49;

    /** Acciones del menú de restauración. */
    private static final int ACTION_ALL = 10;
    private static final int ACTION_ITEMS = 11;
    private static final int ACTION_ENDER = 12;
    private static final int ACTION_POSITION = 13;
    private static final int ACTION_STATS = 14;
    private static final int ACTION_DELETE = 15;
    private static final int ACTION_BACK = 22;

    private static final int CONFIRM_YES = 11;
    private static final int CONFIRM_INFO = 13;
    private static final int CONFIRM_NO = 15;

    private final AdminToolsPlugin plugin;
    private final InventoryBackupService service;

    public RollbackGui(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.service = plugin.getInventoryBackups();
    }

    private enum Kind {
        PLAYERS, BACKUPS, PREVIEW, ENDER, ACTIONS, CONFIRM
    }

    // ------------------------------------------------------------
    //  Jugadores con copias
    // ------------------------------------------------------------

    /** Menú raíz: un jugador por cabeza, con su número de copias. */
    public void openPlayers(Player viewer, int page) {
        if (!viewer.hasPermission("admintools.rollback.view")) {
            plugin.getMessages().send(viewer, "generic.no-permission");
            return;
        }
        int total = service.ownerCount();
        int pages = Math.max(1, (total + CONTENT - 1) / CONTENT);
        int current = Math.min(Math.max(1, page), pages);
        List<BackupOwner> owners = service.owners(current, CONTENT);

        Menu menu = new Menu(Kind.PLAYERS, current);
        Inventory inventory = Bukkit.createInventory(menu, SIZE,
                plugin.getMessages().get("rollback.menu-players-title", "total", total));
        menu.inventory = inventory;
        if (owners.isEmpty()) {
            inventory.setItem(22, icon(Material.PAPER, plugin.getMessages().get("rollback.menu-players-empty")));
        }
        for (int i = 0; i < owners.size() && i < CONTENT; i++) {
            BackupOwner owner = owners.get(i);
            inventory.setItem(i, head(owner));
            menu.players.put(i, owner.uuid());
            menu.names.put(i, owner.name());
        }
        navigation(inventory, current, pages, false);
        viewer.openInventory(inventory);
    }

    private ItemStack head(BackupOwner owner) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skull) {
            try {
                // El perfil sale de la caché del servidor: no hay consulta de red.
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(owner.uuid()));
            } catch (RuntimeException ignored) {
                // Sin perfil la cabeza sale sin skin; el menú funciona igual.
            }
            List<Component> lore = List.of(
                    plugin.getMessages().get("rollback.menu-player-count", "count", owner.count()),
                    plugin.getMessages().get("rollback.menu-player-last",
                            "date", TimeUtil.formatDate(owner.last())),
                    Component.empty(),
                    plugin.getMessages().get("rollback.menu-player-click"));
            skull.displayName(plugin.getMessages().get("rollback.menu-player-name",
                    "player", owner.name() == null ? "-" : owner.name()));
            skull.lore(lore);
            item.setItemMeta(skull);
        }
        return item;
    }

    // ------------------------------------------------------------
    //  Copias de un jugador
    // ------------------------------------------------------------

    /** Copias de un jugador, de la más reciente a la más antigua. */
    public void openBackups(Player viewer, UUID target, String name, int page) {
        if (!viewer.hasPermission("admintools.rollback.view")) {
            plugin.getMessages().send(viewer, "generic.no-permission");
            return;
        }
        int total = service.count(target);
        int pages = Math.max(1, (total + CONTENT - 1) / CONTENT);
        int current = Math.min(Math.max(1, page), pages);
        List<InventoryBackup> backups = service.list(target, current, CONTENT);

        Menu menu = new Menu(Kind.BACKUPS, current);
        menu.target = target;
        menu.targetName = name;
        Inventory inventory = Bukkit.createInventory(menu, SIZE,
                plugin.getMessages().get("rollback.menu-backups-title", "player", name, "total", total));
        menu.inventory = inventory;
        if (backups.isEmpty()) {
            inventory.setItem(22, icon(Material.PAPER,
                    plugin.getMessages().get("rollback.empty", "player", name)));
        }
        for (int i = 0; i < backups.size() && i < CONTENT; i++) {
            InventoryBackup backup = backups.get(i);
            inventory.setItem(i, backupIcon(backup, viewer));
            menu.backups.put(i, backup.id());
        }
        navigation(inventory, current, pages, true);
        viewer.openInventory(inventory);
    }

    private ItemStack backupIcon(InventoryBackup backup, Player viewer) {
        List<Component> lore = new ArrayList<>(7);
        lore.add(plugin.getMessages().get("rollback.menu-backup-date",
                "date", TimeUtil.formatDate(backup.createdAt())));
        lore.add(plugin.getMessages().get("rollback.menu-backup-items",
                "items", service.itemCount(backup), "size", backup.sizeText()));
        lore.add(plugin.getMessages().get("rollback.menu-backup-place",
                "world", backup.world() == null ? "-" : backup.world(),
                "coords", backup.coords()));
        lore.add(plugin.getMessages().get("rollback.menu-backup-stats",
                "health", Math.round(backup.health()), "food", backup.food(), "xp", backup.xp()));
        lore.add(Component.empty());
        lore.add(plugin.getMessages().get("rollback.menu-backup-left"));
        if (viewer.hasPermission("admintools.rollback.restore")) {
            lore.add(plugin.getMessages().get("rollback.menu-backup-right"));
            lore.add(plugin.getMessages().get("rollback.menu-backup-delete"));
        }
        return icon(Material.CHEST, plugin.getMessages().get("rollback.menu-backup-name",
                "id", backup.id(), "reason", reason(backup.reason())), lore);
    }

    // ------------------------------------------------------------
    //  Contenido de una copia
    // ------------------------------------------------------------

    /** Vista previa con el mismo orden que el inventario del jugador. */
    public void openPreview(Player viewer, InventoryBackup backup, String name, int page) {
        if (!viewer.hasPermission("admintools.rollback.view")) {
            plugin.getMessages().send(viewer, "generic.no-permission");
            return;
        }
        Menu menu = new Menu(Kind.PREVIEW, page);
        menu.target = backup.uuid();
        menu.targetName = name;
        menu.backup = backup;
        Inventory inventory = Bukkit.createInventory(menu, SIZE,
                plugin.getMessages().get("rollback.menu-preview-title",
                        "id", backup.id(), "player", name));
        menu.inventory = inventory;

        ItemStack[] stored = service.items(backup);
        boolean canRestore = viewer.hasPermission("admintools.rollback.restore");
        for (int index = 0; index < Math.min(stored.length, 41); index++) {
            int slot = guiSlot(index);
            if (slot < 0) {
                continue;
            }
            ItemStack stack = stored[index];
            if (stack != null && !stack.getType().isAir()) {
                // Se clona: el ítem del menú no debe compartir instancia con la copia.
                inventory.setItem(slot, stack.clone());
            }
            menu.slots.put(slot, index);
        }
        for (int slot = 41; slot <= 44; slot++) {
            inventory.setItem(slot, separator());
        }

        inventory.setItem(SLOT_BACK, icon(Material.ARROW, plugin.getMessages().get("rollback.menu-back")));
        inventory.setItem(SLOT_ENDER, icon(Material.ENDER_CHEST,
                plugin.getMessages().get("rollback.menu-preview-ender")));
        inventory.setItem(SLOT_INFO, info(backup, name));
        if (canRestore) {
            inventory.setItem(SLOT_RESTORE_ALL, icon(Material.NETHER_STAR,
                    plugin.getMessages().get("rollback.menu-restore-all"),
                    List.of(plugin.getMessages().get("rollback.menu-restore-all-lore"),
                            plugin.getMessages().get("rollback.menu-preview-click-item"))));
        }
        inventory.setItem(SLOT_CLOSE, icon(Material.BARRIER, plugin.getMessages().get("rollback.menu-close")));
        viewer.openInventory(inventory);
    }

    /** Índice de inventario (0-40) a espacio del menú; -1 si no se muestra. */
    private int guiSlot(int index) {
        if (index >= 0 && index <= 8) {
            return 27 + index;
        }
        if (index >= 9 && index <= 35) {
            return index - 9;
        }
        if (index >= 36 && index <= 40) {
            return index;
        }
        return -1;
    }

    private ItemStack info(InventoryBackup backup, String name) {
        List<Component> lore = List.of(
                plugin.getMessages().get("rollback.menu-backup-date",
                        "date", TimeUtil.formatDate(backup.createdAt())),
                plugin.getMessages().get("rollback.menu-backup-reason",
                        "reason", reason(backup.reason())),
                plugin.getMessages().get("rollback.menu-backup-items",
                        "items", service.itemCount(backup), "size", backup.sizeText()),
                plugin.getMessages().get("rollback.menu-backup-place",
                        "world", backup.world() == null ? "-" : backup.world(),
                        "coords", backup.coords()),
                plugin.getMessages().get("rollback.menu-backup-stats",
                        "health", Math.round(backup.health()), "food", backup.food(), "xp", backup.xp()));
        return icon(Material.PAPER, plugin.getMessages().get("rollback.menu-backup-name",
                "id", backup.id(), "reason", reason(backup.reason())), lore);
    }

    /** Enderchest de la copia, con su propio botón de restauración. */
    public void openEnder(Player viewer, InventoryBackup backup, String name, int page) {
        Menu menu = new Menu(Kind.ENDER, page);
        menu.target = backup.uuid();
        menu.targetName = name;
        menu.backup = backup;
        Inventory inventory = Bukkit.createInventory(menu, SIZE,
                plugin.getMessages().get("rollback.menu-ender-title",
                        "id", backup.id(), "player", name));
        menu.inventory = inventory;

        ItemStack[] stored = service.enderchest(backup);
        for (int index = 0; index < Math.min(stored.length, 27); index++) {
            ItemStack stack = stored[index];
            if (stack != null && !stack.getType().isAir()) {
                inventory.setItem(index, stack.clone());
            }
        }
        for (int slot = 27; slot <= 44; slot++) {
            inventory.setItem(slot, separator());
        }
        inventory.setItem(SLOT_BACK, icon(Material.ARROW, plugin.getMessages().get("rollback.menu-back")));
        if (viewer.hasPermission("admintools.rollback.restore")) {
            inventory.setItem(SLOT_RESTORE_ALL, icon(Material.ENDER_EYE,
                    plugin.getMessages().get("rollback.menu-restore-ender")));
        }
        inventory.setItem(SLOT_CLOSE, icon(Material.BARRIER, plugin.getMessages().get("rollback.menu-close")));
        viewer.openInventory(inventory);
    }

    // ------------------------------------------------------------
    //  Acciones y confirmación
    // ------------------------------------------------------------

    private void openActions(Player viewer, InventoryBackup backup, String name, int page) {
        Menu menu = new Menu(Kind.ACTIONS, page);
        menu.target = backup.uuid();
        menu.targetName = name;
        menu.backup = backup;
        Inventory inventory = Bukkit.createInventory(menu, 27,
                plugin.getMessages().get("rollback.menu-restore-title", "id", backup.id()));
        menu.inventory = inventory;

        inventory.setItem(ACTION_ALL, icon(Material.NETHER_STAR,
                plugin.getMessages().get("rollback.menu-restore-all"),
                List.of(plugin.getMessages().get("rollback.menu-restore-all-lore"))));
        inventory.setItem(ACTION_ITEMS, icon(Material.CHEST,
                plugin.getMessages().get("rollback.menu-restore-items"),
                List.of(plugin.getMessages().get("rollback.menu-restore-items-lore"))));
        inventory.setItem(ACTION_ENDER, icon(Material.ENDER_CHEST,
                plugin.getMessages().get("rollback.menu-restore-ender")));
        inventory.setItem(ACTION_POSITION, icon(Material.COMPASS,
                plugin.getMessages().get("rollback.menu-restore-position")));
        inventory.setItem(ACTION_STATS, icon(Material.GOLDEN_APPLE,
                plugin.getMessages().get("rollback.menu-restore-stats")));
        inventory.setItem(ACTION_DELETE, icon(Material.LAVA_BUCKET,
                plugin.getMessages().get("rollback.menu-restore-delete"),
                List.of(plugin.getMessages().get("rollback.menu-restore-delete-lore"))));
        inventory.setItem(ACTION_BACK, icon(Material.ARROW, plugin.getMessages().get("rollback.menu-back")));
        viewer.openInventory(inventory);
    }

    private void openConfirm(Player viewer, InventoryBackup backup, String name, int page, String action) {
        Menu menu = new Menu(Kind.CONFIRM, page);
        menu.target = backup.uuid();
        menu.targetName = name;
        menu.backup = backup;
        menu.action = action;
        Inventory inventory = Bukkit.createInventory(menu, 27,
                plugin.getMessages().get("rollback.menu-confirm-title"));
        menu.inventory = inventory;

        inventory.setItem(CONFIRM_YES, icon(Material.LIME_CONCRETE,
                plugin.getMessages().get("rollback.menu-confirm-yes"),
                List.of(plugin.getMessages().get("rollback.menu-confirm-yes-lore",
                        "action", actionText(backup, action)))));
        inventory.setItem(CONFIRM_INFO, icon(Material.PAPER, plugin.getMessages().get("rollback.menu-backup-name",
                "id", backup.id(), "reason", reason(backup.reason())),
                List.of(plugin.getMessages().get("rollback.menu-backup-date",
                        "date", TimeUtil.formatDate(backup.createdAt())))));
        inventory.setItem(CONFIRM_NO, icon(Material.RED_CONCRETE,
                plugin.getMessages().get("rollback.menu-confirm-no"),
                List.of(plugin.getMessages().get("rollback.menu-confirm-no-lore"))));
        viewer.openInventory(inventory);
    }

    private String actionText(InventoryBackup backup, String action) {
        if (action.startsWith("slot:")) {
            int slot = Integer.parseInt(action.substring(5));
            return plugin.getMessages().raw("rollback.menu-action-slot", "slot", slot + 1, "id", backup.id());
        }
        return plugin.getMessages().raw("rollback.menu-action-" + action, "id", backup.id());
    }

    // ------------------------------------------------------------
    //  Clics
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        // Todos los menús son de sólo lectura: nada se coge, se mueve ni se suelta.
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
            case PLAYERS -> clickPlayers(viewer, menu, slot);
            case BACKUPS -> clickBackups(viewer, menu, slot, event);
            case PREVIEW -> clickPreview(viewer, menu, slot);
            case ENDER -> clickEnder(viewer, menu, slot);
            case ACTIONS -> clickActions(viewer, menu, slot);
            case CONFIRM -> clickConfirm(viewer, menu, slot);
        }
    }

    private void clickPlayers(Player viewer, Menu menu, int slot) {
        if (slot == SLOT_PREV) {
            int page = menu.page - 1;
            reopen(viewer, () -> openPlayers(viewer, page));
            return;
        }
        if (slot == SLOT_NEXT) {
            int page = menu.page + 1;
            reopen(viewer, () -> openPlayers(viewer, page));
            return;
        }
        UUID target = menu.players.get(slot);
        if (target == null) {
            return;
        }
        String name = menu.names.getOrDefault(slot, "-");
        reopen(viewer, () -> openBackups(viewer, target, name, 1));
    }

    private void clickBackups(Player viewer, Menu menu, int slot, InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            reopen(viewer, () -> openPlayers(viewer, 1));
            return;
        }
        if (slot == SLOT_PREV) {
            int page = menu.page - 1;
            reopen(viewer, () -> openBackups(viewer, menu.target, menu.targetName, page));
            return;
        }
        if (slot == SLOT_NEXT) {
            int page = menu.page + 1;
            reopen(viewer, () -> openBackups(viewer, menu.target, menu.targetName, page));
            return;
        }
        Long id = menu.backups.get(slot);
        if (id == null) {
            return;
        }
        InventoryBackup backup = service.byId(id).orElse(null);
        if (backup == null) {
            plugin.getMessages().send(viewer, "rollback.not-found", "id", id, "player", menu.targetName);
            reopen(viewer, () -> openBackups(viewer, menu.target, menu.targetName, menu.page));
            return;
        }
        if (event.isRightClick() && event.isShiftClick()) {
            if (!requireRestore(viewer)) {
                return;
            }
            reopen(viewer, () -> openConfirm(viewer, backup, menu.targetName, menu.page, "delete"));
            return;
        }
        if (event.isRightClick()) {
            reopen(viewer, () -> openActions(viewer, backup, menu.targetName, menu.page));
            return;
        }
        reopen(viewer, () -> openPreview(viewer, backup, menu.targetName, menu.page));
    }

    private void clickPreview(Player viewer, Menu menu, int slot) {
        if (slot == SLOT_BACK) {
            reopen(viewer, () -> openBackups(viewer, menu.target, menu.targetName, menu.page));
            return;
        }
        if (slot == SLOT_ENDER) {
            reopen(viewer, () -> openEnder(viewer, menu.backup, menu.targetName, menu.page));
            return;
        }
        if (slot == SLOT_RESTORE_ALL) {
            if (!requireRestore(viewer)) {
                return;
            }
            reopen(viewer, () -> openConfirm(viewer, menu.backup, menu.targetName, menu.page, "all"));
            return;
        }
        Integer index = menu.slots.get(slot);
        if (index == null || !requireRestore(viewer)) {
            return;
        }
        reopen(viewer, () -> openConfirm(viewer, menu.backup, menu.targetName, menu.page, "slot:" + index));
    }

    private void clickEnder(Player viewer, Menu menu, int slot) {
        if (slot == SLOT_BACK) {
            reopen(viewer, () -> openPreview(viewer, menu.backup, menu.targetName, menu.page));
            return;
        }
        if (slot == SLOT_RESTORE_ALL) {
            if (!requireRestore(viewer)) {
                return;
            }
            reopen(viewer, () -> openConfirm(viewer, menu.backup, menu.targetName, menu.page, "ender"));
        }
    }

    private void clickActions(Player viewer, Menu menu, int slot) {
        String action = switch (slot) {
            case ACTION_ALL -> "all";
            case ACTION_ITEMS -> "items";
            case ACTION_ENDER -> "ender";
            case ACTION_POSITION -> "position";
            case ACTION_STATS -> "stats";
            case ACTION_DELETE -> "delete";
            default -> null;
        };
        if (slot == ACTION_BACK) {
            reopen(viewer, () -> openPreview(viewer, menu.backup, menu.targetName, menu.page));
            return;
        }
        if (action == null || !requireRestore(viewer)) {
            return;
        }
        reopen(viewer, () -> openConfirm(viewer, menu.backup, menu.targetName, menu.page, action));
    }

    private void clickConfirm(Player viewer, Menu menu, int slot) {
        if (slot == CONFIRM_YES) {
            apply(viewer, menu);
            return;
        }
        if (slot == CONFIRM_NO) {
            reopen(viewer, () -> openBackups(viewer, menu.target, menu.targetName, menu.page));
        }
    }

    /** Ejecuta la acción confirmada. Nunca se ejecuta sin esta llamada. */
    private void apply(Player viewer, Menu menu) {
        InventoryBackup backup = menu.backup;
        String player = menu.targetName;
        String action = menu.action;
        if (action.equals("delete")) {
            service.delete(backup.id());
            plugin.getMessages().send(viewer, "rollback.menu-deleted", "id", backup.id(), "player", player);
            plugin.getAuditService().log(viewer, "rollback.delete", player, "#" + backup.id());
            reopen(viewer, () -> openBackups(viewer, menu.target, player, menu.page));
            return;
        }

        Player target = Bukkit.getPlayer(menu.target);
        if (target == null) {
            plugin.getMessages().send(viewer, "rollback.offline", "player", player);
            reopen(viewer, () -> openBackups(viewer, menu.target, player, menu.page));
            return;
        }

        boolean done;
        if (action.startsWith("slot:")) {
            int index = Integer.parseInt(action.substring(5));
            done = service.restoreSlot(target, backup, index);
        } else {
            Set<Part> parts = switch (action) {
                case "items" -> EnumSet.of(Part.ITEMS);
                case "ender" -> EnumSet.of(Part.ENDER);
                case "position" -> EnumSet.of(Part.POSITION);
                case "stats" -> EnumSet.of(Part.STATS);
                default -> EnumSet.allOf(Part.class);
            };
            done = service.restore(target, backup, parts);
        }
        if (!done) {
            plugin.getMessages().send(viewer, "rollback.failed", "id", backup.id());
            reopen(viewer, () -> openBackups(viewer, menu.target, player, menu.page));
            return;
        }
        plugin.getMessages().send(viewer, "rollback.menu-restored-part", "player", player,
                "action", actionText(backup, action));
        plugin.getAuditService().log(viewer, "rollback.restore", player,
                "#" + backup.id() + " (" + actionText(backup, action) + ")");
        reopen(viewer, () -> openBackups(viewer, menu.target, player, menu.page));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    private boolean requireRestore(Player viewer) {
        if (viewer.hasPermission("admintools.rollback.restore")) {
            return true;
        }
        plugin.getMessages().send(viewer, "generic.no-permission");
        return false;
    }

    // ------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------

    /** Abrir un inventario dentro de un clic se hace al tick siguiente. */
    private void reopen(Player viewer, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                action.run();
            }
        });
    }

    private void navigation(Inventory inventory, int page, int pages, boolean back) {
        if (back) {
            inventory.setItem(SLOT_BACK, icon(Material.ARROW,
                    plugin.getMessages().get("rollback.menu-back")));
        }
        if (page > 1) {
            inventory.setItem(SLOT_PREV, icon(Material.ARROW,
                    plugin.getMessages().get("rollback.menu-prev")));
        }
        inventory.setItem(SLOT_PAGE, icon(Material.PAPER,
                plugin.getMessages().get("rollback.menu-page", "page", page, "pages", pages)));
        if (page < pages) {
            inventory.setItem(SLOT_NEXT, icon(Material.ARROW,
                    plugin.getMessages().get("rollback.menu-next")));
        }
        inventory.setItem(SLOT_CLOSE, icon(Material.BARRIER,
                plugin.getMessages().get("rollback.menu-close")));
    }

    private String reason(String code) {
        return plugin.getMessages().raw("rollback.reason-"
                + (code == null ? "manual" : code.toLowerCase(Locale.ROOT)));
    }

    private ItemStack separator() {
        return icon(Material.GRAY_STAINED_GLASS_PANE, Component.empty());
    }

    private ItemStack icon(Material material, Component name) {
        return icon(material, name, List.of());
    }

    private ItemStack icon(Material material, String nameKey, Object... placeholders) {
        return icon(material, plugin.getMessages().get(nameKey, placeholders), List.of());
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

    /** Menú abierto: qué se está viendo y con qué datos. */
    private static final class Menu implements InventoryHolder {

        private final Kind kind;
        private final int page;
        private final Map<Integer, UUID> players = new HashMap<>();
        private final Map<Integer, String> names = new HashMap<>();
        private final Map<Integer, Long> backups = new HashMap<>();
        private final Map<Integer, Integer> slots = new HashMap<>();
        private UUID target;
        private String targetName = "-";
        private InventoryBackup backup;
        private String action = "";
        private Inventory inventory;

        private Menu(Kind kind, int page) {
            this.kind = kind;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
