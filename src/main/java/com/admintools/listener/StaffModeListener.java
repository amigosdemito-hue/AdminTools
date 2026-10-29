package com.admintools.listener;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.PlayerRecord;
import com.admintools.model.Sanction;
import com.admintools.util.ClientEdition;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Herramientas y restricciones del modo staff.
 *
 * <p>Las herramientas actúan de dos formas: con <b>clic derecho sobre un jugador</b>
 * ({@link PlayerInteractEntityEvent}, que es el evento que de verdad emite Bukkit al
 * pulsar una entidad) y, para congelar e inspeccionar, con clic derecho al aire
 * apuntando a alguien. La brújula ya no exige apuntar: abre la lista de jugadores.</p>
 */
public final class StaffModeListener implements Listener {

    /** Ángulo máximo en coseno para considerar que el staff "mira" a alguien (~30°). */
    private static final double AIM_DOT = 0.86;
    private static final double AIM_RANGE = 8.0;

    /** Filas 0-4: jugadores (9 por fila); fila 5: flechas de página. */
    private static final int PAGE_SIZE = 45;
    private static final int MENU_SIZE = 54;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 53;

    private final AdminToolsPlugin plugin;

    public StaffModeListener(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------
    //  Herramientas
    // ------------------------------------------------------------

    /**
     * Clic derecho sobre un jugador. Sin este manejador las herramientas sólo
     * respondían al golpear un bloque cercano: al pulsar una entidad Bukkit no
     * dispara {@link PlayerInteractEvent}.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getStaffMode().active(player.getUniqueId())) {
            return;
        }
        ItemStack item = player.getInventory().getItem(event.getHand());
        if (item == null) {
            return;
        }
        boolean onPlayer = event.getRightClicked() instanceof Player;
        switch (item.getType()) {
            case COMPASS, BLAZE_ROD, BOOK -> {
                // Sólo jugadores: la brújula no debe impedir comerciar con un aldeano.
                if (!onPlayer) {
                    return;
                }
            }
            case CLOCK -> {
                // El reloj cambia el vanish tanto sobre una entidad como sobre un bloque.
            }
            default -> {
                return;
            }
        }
        event.setCancelled(true);
        // El evento llega una vez por mano: se actúa sólo con la principal.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (item.getType() == Material.CLOCK) {
            plugin.getStaffState().toggleVanish(player);
            return;
        }
        Player target = (Player) event.getRightClicked();
        switch (item.getType()) {
            case COMPASS -> teleportTo(player, target.getName());
            case BLAZE_ROD -> toggleFreeze(player, target.getName());
            case BOOK -> inspect(player, target.getName());
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.getStaffMode().active(player.getUniqueId())) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        switch (item.getType()) {
            case COMPASS -> {
                event.setCancelled(true);
                if (event.getHand() != EquipmentSlot.HAND) {
                    return;
                }
                openMenu(player, 0);
            }
            case BLAZE_ROD -> {
                event.setCancelled(true);
                if (event.getHand() != EquipmentSlot.HAND) {
                    return;
                }
                Player target = lookedAt(player);
                if (target == null) {
                    plugin.getMessages().send(player, "staff.mode-no-target");
                    return;
                }
                toggleFreeze(player, target.getName());
            }
            case CLOCK -> {
                event.setCancelled(true);
                if (event.getHand() != EquipmentSlot.HAND) {
                    return;
                }
                plugin.getStaffState().toggleVanish(player);
            }
            case BOOK -> {
                event.setCancelled(true);
                if (event.getHand() != EquipmentSlot.HAND) {
                    return;
                }
                Player target = lookedAt(player);
                if (target == null) {
                    plugin.getMessages().send(player, "staff.mode-no-target");
                    return;
                }
                inspect(player, target.getName());
            }
            default -> {
                // Cualquier otro objeto se comporta normal.
            }
        }
    }

    // ------------------------------------------------------------
    //  Acciones (por comando, para respetar permisos, mensajes y auditoría)
    // ------------------------------------------------------------

    private void teleportTo(Player player, String name) {
        // /tp anota el /back, comprueba la exención y avisa al destino.
        Bukkit.dispatchCommand(player, "admintools:tp " + name);
    }

    private void toggleFreeze(Player player, String name) {
        Bukkit.dispatchCommand(player, "admintools:freeze " + name);
    }

    private void inspect(Player player, String name) {
        Bukkit.dispatchCommand(player, "admintools:check " + name);
    }

    // ------------------------------------------------------------
    //  Menú de teletransporte (brújula)
    // ------------------------------------------------------------

    private void openMenu(Player player, int page) {
        List<UUID> targets = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            // canSee respeta el vanish: no se delata a quien está invisible.
            if (!other.equals(player) && player.canSee(other)) {
                targets.add(other.getUniqueId());
            }
        }
        if (targets.isEmpty()) {
            plugin.getMessages().send(player, "staff.mode-menu-empty");
            return;
        }
        targets.sort(Comparator.comparing(uuid -> {
            String name = plugin.getPlayerService().nameOf(uuid);
            return name == null ? "" : name;
        }, String.CASE_INSENSITIVE_ORDER));

        int pages = (targets.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.max(0, Math.min(page, pages - 1));
        TeleportMenu menu = new TeleportMenu(targets, current);
        Component title = plugin.getMessages().get("staff.mode-menu-title",
                "page", current + 1, "pages", pages, "count", targets.size());
        Inventory inventory = Bukkit.createInventory(menu, MENU_SIZE, title);
        menu.inventory = inventory;

        int start = current * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = start + slot;
            if (index >= targets.size()) {
                break;
            }
            Player target = Bukkit.getPlayer(targets.get(index));
            if (target == null || !target.isOnline()) {
                continue;
            }
            inventory.setItem(slot, entry(target));
            menu.bySlot.put(slot, target.getUniqueId());
        }
        if (current > 0) {
            inventory.setItem(SLOT_PREV, arrow("staff.mode-menu-prev"));
        }
        if (current + 1 < pages) {
            inventory.setItem(SLOT_NEXT, arrow("staff.mode-menu-next"));
        }
        player.openInventory(inventory);
    }

    private ItemStack entry(Player target) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skull) {
            // La skin del jugador conectado ya está cargada: el perfil no cuesta una consulta.
            skull.setPlayerProfile(target.getPlayerProfile());
            skull.displayName(plugin.getMessages().get("staff.mode-menu-name", "player", target.getName()));
            skull.lore(profile(target));
            item.setItemMeta(skull);
        }
        return item;
    }

    /**
     * Ficha del jugador en el lore de su cabeza (el tooltip del inventario).
     *
     * <p>Se lee de arriba abajo en orden de importancia y cabe en cinco líneas: quién es
     * y cómo juega, dónde está, cómo va, cuánto lleva y qué se le ha sancionado. Las
     * insignias (vanish, modo staff, servicio) y las sanciones sólo aparecen si existen,
     * para que la ficha de un jugador limpio no se llene de ruido. Todo son lecturas en
     * memoria más una llamada barata a Vault, así que abrir el menú no cuesta.</p>
     */
    private List<Component> profile(Player target) {
        var messages = plugin.getMessages();
        List<Component> lines = new ArrayList<>();
        UUID id = target.getUniqueId();

        // 1) Edición, ping y modo de juego, con insignias si procede.
        Component summary = messages.get("staff.mode-menu-line-summary",
                "edition", ClientEdition.label(target),
                "ping", target.getPing(),
                "gamemode", messages.raw("staff.mode-gamemode-"
                        + target.getGameMode().name().toLowerCase(Locale.ROOT)));
        if (plugin.getStaffState().vanished(id)) {
            summary = summary.append(messages.get("staff.mode-menu-badge-vanish"));
        }
        if (plugin.getStaffMode().active(id)) {
            summary = summary.append(messages.get("staff.mode-menu-badge-staff"));
        }
        if (plugin.getPlayerService().duty(id)) {
            summary = summary.append(messages.get("staff.mode-menu-badge-duty"));
        }
        lines.add(summary);

        // 2) Dónde está.
        var location = target.getLocation();
        lines.add(messages.get("staff.mode-menu-line-location",
                "world", location.getWorld() == null ? "-" : location.getWorld().getName(),
                "x", location.getBlockX(),
                "y", location.getBlockY(),
                "z", location.getBlockZ()));

        // 3) Cómo va: vida, comida y saldo (o la línea sin economía si no hay Vault).
        String money = plugin.getIntegrations().economy().balance(target);
        int health = (int) Math.ceil(target.getHealth());
        lines.add(money == null
                ? messages.get("staff.mode-menu-line-vitals-nomoney",
                        "health", health, "food", target.getFoodLevel())
                : messages.get("staff.mode-menu-line-vitals",
                        "health", health, "food", target.getFoodLevel(), "money", money));

        // 4) Cuánto lleva.
        PlayerRecord record = plugin.getPlayerService().get(id);
        lines.add(messages.get("staff.mode-menu-line-playtime",
                "playtime", record.playtimeText().isEmpty() ? "0s" : record.playtimeText(),
                "sessions", record.sessions()));

        // 5) Sanciones activas, sólo si hay alguna y sin ceros de relleno.
        int bans = 0;
        int mutes = 0;
        int warns = 0;
        for (Sanction sanction : plugin.getSanctionService().activeAll(id, target.getName())) {
            switch (sanction.type()) {
                case BAN, IP_BAN -> bans++;
                case MUTE, IP_MUTE, SHADOW_MUTE -> mutes++;
                case WARN -> warns++;
                case KICK -> {
                    // Las expulsiones no quedan activas: no cuentan aquí.
                }
            }
        }
        List<Component> sanctions = new ArrayList<>(3);
        if (bans > 0) {
            sanctions.add(messages.get("staff.mode-menu-sanction-ban", "count", bans));
        }
        if (mutes > 0) {
            sanctions.add(messages.get("staff.mode-menu-sanction-mute", "count", mutes));
        }
        if (warns > 0) {
            sanctions.add(messages.get("staff.mode-menu-sanction-warn", "count", warns));
        }
        if (!sanctions.isEmpty()) {
            Component line = messages.get("staff.mode-menu-line-sanctions");
            Component separator = messages.get("staff.mode-menu-separator");
            for (int i = 0; i < sanctions.size(); i++) {
                if (i > 0) {
                    line = line.append(separator);
                }
                line = line.append(sanctions.get(i));
            }
            lines.add(line);
        }

        lines.add(messages.get("staff.mode-menu-entry-click"));
        return lines;
    }

    private ItemStack arrow(String nameKey) {
        ItemStack item = new ItemStack(Material.ARROW);
        item.editMeta(meta -> meta.displayName(plugin.getMessages().get(nameKey)));
        return item;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TeleportMenu menu)) {
            return;
        }
        // El menú es sólo de navegación: nada se puede mover ni sacar de él.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == SLOT_PREV) {
            reopen(player, menu.page - 1);
            return;
        }
        if (slot == SLOT_NEXT) {
            reopen(player, menu.page + 1);
            return;
        }
        UUID targetId = menu.bySlot.get(slot);
        if (targetId == null) {
            return;
        }
        player.closeInventory();
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            plugin.getMessages().send(player, "staff.mode-menu-gone");
            return;
        }
        teleportTo(player, target.getName());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TeleportMenu) {
            event.setCancelled(true);
        }
    }

    /** Abrir un inventario dentro de un clic se hace al tick siguiente. */
    private void reopen(Player player, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                openMenu(player, page);
            }
        });
    }

    /** Menú de teletransporte: jugadores visibles de la página y flechas. */
    private static final class TeleportMenu implements InventoryHolder {

        private final List<UUID> targets;
        private final Map<Integer, UUID> bySlot = new HashMap<>();
        private final int page;
        private Inventory inventory;

        private TeleportMenu(List<UUID> targets, int page) {
            this.targets = targets;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ------------------------------------------------------------
    //  Restricciones del modo staff
    // ------------------------------------------------------------

    /** Bloquea recoger ítems del suelo mientras el modo staff está activo. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        if (!plugin.getSettings().staffModeBlockPickup()) {
            return;
        }
        if (plugin.getStaffMode().active(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Evita tirar las herramientas por accidente. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.getStaffMode().active(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Al desconectarse devuelve el inventario: nadie debe quedar con la hotbar
     * de herramientas ni perder sus objetos por cerrar el juego.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (plugin.getStaffMode().active(uuid)) {
            plugin.getStaffMode().disable(event.getPlayer());
        }
    }

    /** Jugador al que apunta la vista, dentro del alcance y con línea de visión. */
    private Player lookedAt(Player player) {
        Player best = null;
        double bestDot = AIM_DOT;
        Vector direction = player.getEyeLocation().getDirection().normalize();
        for (Player other : player.getWorld().getPlayers()) {
            if (other.equals(player)) {
                continue;
            }
            Vector to = other.getEyeLocation().toVector().subtract(player.getEyeLocation().toVector());
            if (to.lengthSquared() > AIM_RANGE * AIM_RANGE) {
                continue;
            }
            double dot = to.normalize().dot(direction);
            if (dot > bestDot && player.hasLineOfSight(other)) {
                bestDot = dot;
                best = other;
            }
        }
        return best;
    }
}
