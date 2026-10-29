package com.admintools.listener;

import com.admintools.AdminToolsPlugin;
import com.admintools.util.SpectatorOpen;
import com.admintools.util.Text;
import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Nameable;
import org.bukkit.block.Block;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.Location;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Protecciones de staff: congelados, invulnerables, espías, comandos bloqueados y vanish. */
public final class StaffListener implements Listener {

    private static final List<String> PRIVATE_MESSAGE_COMMANDS = List.of(
            "msg", "message", "tell", "whisper", "w", "m", "pm", "dm", "r", "reply");

    /** Comandos cuyo contenido no debe acabar en el command spy (contraseñas, correo, 2FA). */
    private static final List<String> SENSITIVE_COMMANDS = List.of(
            "login", "l", "register", "reg", "unregister", "changepassword", "changepass", "passwd",
            "password", "email", "auth", "2fa", "totp", "premium", "setpassword");

    private final AdminToolsPlugin plugin;

    public StaffListener(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------
    //  Congelados
    // ------------------------------------------------------------

    private boolean frozen(Player player) {
        return plugin.getPlayerService().frozen(player.getUniqueId())
                && !player.hasPermission("admintools.exempt.freeze");
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!frozen(player)) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return; // sólo está girando la cabeza: se le deja mirar
        }
        // Bloquea el movimiento pero conserva yaw/pitch para que pueda mirar al staff.
        Location locked = from.clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        event.setTo(locked);
    }

    /** Los mobs no pueden fijarse en un jugador en vanish (evita que lo delaten). */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player
                && plugin.getStaffState().vanished(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** En vanish no se recogen ítems del suelo: nada delata al staff ni se llena su inventario. */
    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player
                && plugin.getStaffState().vanished(player.getUniqueId())
                && !plugin.getSettings().vanishPickup()) {
            event.setCancelled(true);
        }
    }

    /**
     * Los orbes de experiencia desaparecen al recogerlos: igual que los ítems, delatan
     * al staff en vanish. Se controla con el mismo ajuste que la recogida de ítems.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPickupExperience(PlayerPickupExperienceEvent event) {
        Player player = event.getPlayer();
        if (plugin.getStaffState().vanished(player.getUniqueId())
                && !plugin.getSettings().vanishPickup()) {
            event.setCancelled(true);
        }
    }

    /** Un staff en vanish no debe aparecer en el mensaje de muerte del servidor. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVanishDeath(PlayerDeathEvent event) {
        if (plugin.getStaffState().vanished(event.getEntity().getUniqueId())) {
            event.deathMessage(null);
        }
    }

    /** Ni en el anuncio de logros. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVanishAdvancement(PlayerAdvancementDoneEvent event) {
        if (plugin.getStaffState().vanished(event.getPlayer().getUniqueId())) {
            event.message(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (frozen(event.getPlayer()) && event.getAction() != Action.PHYSICAL) {
            event.setCancelled(true);
        }
    }

    /**
     * Un congelado tampoco interactúa con entidades (aldeanos, botes, marcos, yunques
     * de yunque...). El clic sobre una entidad no pasa por {@link PlayerInteractEvent}.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** En vanish, ¿hay que abrir los contenedores sin animación ni sonido? */
    private boolean silentContainers(Player player) {
        return plugin.getStaffState().vanished(player.getUniqueId())
                && plugin.getSettings().vanishSilentContainers();
    }

    /**
     * ¿Este bloque se puede abrir por API sin tocar la interacción normal? Todos los
     * contenedores del juego lo permiten salvo los que no tienen interfaz propia
     * (tocadiscos y maceta decorada): ahí el clic sirve para meter un objeto y abrirlos
     * por API dejaría sacarlo sin que nadie vea el bloque abrirse.
     */
    private boolean silentCandidate(Block block) {
        Material type = block.getType();
        if (type == Material.JUKEBOX || type == Material.DECORATED_POT) {
            return false;
        }
        return switch (type) {
            case ENDER_CHEST, CRAFTING_TABLE, ENCHANTING_TABLE, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL,
                 GRINDSTONE, CARTOGRAPHY_TABLE, LOOM, SMITHING_TABLE, STONECUTTER -> true;
            // Cofres, cofres trampa, barriles, shulkers, hornos, ahumadores, tolvas,
            // dispensadores, droppers, alambiques, crafter, mesas de libros, estanterías
            // talladas, lectern y estantes: cualquier bloque con inventario propio.
            default -> block.getState(false) instanceof BlockInventoryHolder holder
                    && holder.getInventory() != null;
        };
    }

    /**
     * Abrir un contenedor en vanish delata al staff: la tapa se anima y suena para
     * todos los que estén cerca. En HIGHEST (después de las protecciones) se abre la
     * interfaz por API, que no dispara ni la animación del bloque ni su sonido.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContainerOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!silentContainers(player)) {
            return;
        }
        // Otra protección (WorldGuard y similares) puede denegar el uso del bloque
        // sin cancelar el evento: en ese caso no se abre nada.
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !silentCandidate(block)) {
            return;
        }
        // Se cancela siempre, también en la mano secundaria: así el servidor no procesa
        // ninguna de las dos y el bloque nunca reproduce su sonido.
        event.setCancelled(true);
        // El evento llega una vez por mano: la interfaz se abre solo con la principal.
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (plugin.getSettings().debug()) {
            plugin.getLogger().info("Vanish: " + player.getName() + " abre " + block.getType()
                    + " sin animación ni sonido.");
        }
        openSilently(player, block);
    }

    /**
     * Contenedores que llevan inventario encima: barcas con cofre y vagonetas con
     * cofre o tolva. La lista es cerrada a propósito porque caballos, llamas,
     * camellos y aldeanos también son InventoryHolder, pero abrirlos rompería montar
     * o comerciar.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityContainerOpen(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!silentContainers(player)) {
            return;
        }
        var clicked = event.getRightClicked();
        if (!(clicked instanceof ChestBoat)
                && !(clicked instanceof StorageMinecart)
                && !(clicked instanceof HopperMinecart)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        if (!(clicked instanceof InventoryHolder holder) || holder.getInventory() == null) {
            return;
        }
        if (plugin.getSettings().debug()) {
            plugin.getLogger().info("Vanish: " + player.getName() + " abre " + clicked.getType()
                    + " sin animación ni sonido.");
        }
        openNextTick(player, () -> {
            // La entidad puede haber muerto o desaparecido en ese tick.
            if (clicked.isValid()) {
                player.openInventory(holder.getInventory());
            }
        });
    }

    /** Abre la interfaz del bloque saltándose la interacción que dispara sonido. */
    @SuppressWarnings("deprecation") // openWorkbench/openAnvil... son la única forma de abrir estas interfaces sin tocar el bloque
    private void openSilently(Player player, Block block) {
        switch (block.getType()) {
            case ENDER_CHEST -> openNextTick(player, () -> player.openInventory(player.getEnderChest()));
            case CRAFTING_TABLE -> openNextTick(player, () -> player.openWorkbench(null, true));
            case ENCHANTING_TABLE -> openNextTick(player, () -> player.openEnchanting(null, true));
            case ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL -> openNextTick(player, () -> player.openAnvil(null, true));
            case GRINDSTONE -> openNextTick(player, () -> player.openGrindstone(null, true));
            case CARTOGRAPHY_TABLE -> openNextTick(player, () -> player.openCartographyTable(null, true));
            case LOOM -> openNextTick(player, () -> player.openLoom(null, true));
            case SMITHING_TABLE -> openNextTick(player, () -> player.openSmithingTable(null, true));
            case STONECUTTER -> openNextTick(player, () -> player.openStonecutter(null, true));
            default -> {
                if (block.getState(false) instanceof BlockInventoryHolder holder
                        && holder.getInventory() != null) {
                    // Los shulkers siempre por copia: Geyser abre el contenedor en la posición
                    // del bloque tocado y el cliente de Bedrock dibuja ahí la tapa, sin
                    // importar el tipo de menú que mande el servidor.
                    if (isShulker(block)) {
                        openShulkerMirror(player, block);
                    } else {
                        openNextTick(player, () -> player.openInventory(holder.getInventory()));
                    }
                }
            }
        }
    }

    /**
     * Abrir un inventario no se puede hacer dentro del evento: se hace al tick
     * siguiente y con el jugador marcado como espectador, que es lo único que evita
     * que el servidor anime el contenedor y reproduzca su sonido al abrirlo por API.
     */
    private void openNextTick(Player player, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                SpectatorOpen.run(player, action);
            }
        });
    }

    // ------------------------------------------------------------
    //  Shulkers en vanish (clientes de Bedrock)
    // ------------------------------------------------------------

    /** Cajas de shulker de cualquier color. */
    private boolean isShulker(Block block) {
        return block.getType().name().endsWith("SHULKER_BOX");
    }

    /**
     * Qué señal delata al cliente de Bedrock, o "ninguna" si parece un cliente de Java.
     * Se comprueban tres cosas porque de a quién se le abre la copia depende que la tapa
     * no suba. El nombre de la señal queda en el registro del servidor.
     */
    private String bedrockSignal(Player player) {
        if (floodgatePlayer(player.getUniqueId())) {
            return "floodgate";
        }
        String brand = player.getClientBrandName();
        if (brand != null) {
            String lower = brand.toLowerCase(Locale.ROOT);
            if (lower.contains("geyser") || lower.contains("bedrock")) {
                return "marca " + brand;
            }
        }
        // Geyser y Floodgate generan el UUID del jugador Bedrock con el primer long a cero.
        return player.getUniqueId().getMostSignificantBits() == 0L ? "uuid" : "ninguna";
    }

    // Floodgate, si está instalado, es la fuente fiable de "este jugador es de Bedrock".
    private static boolean floodgateResolved;
    private static Object floodgateApi;
    private static Method floodgateCheck;

    private static boolean floodgatePlayer(UUID id) {
        if (!floodgateResolved) {
            floodgateResolved = true;
            try {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateApi = api.getMethod("getInstance").invoke(null);
                for (String name : new String[]{"isFloodgatePlayer", "isFloodgateId"}) {
                    try {
                        floodgateCheck = api.getMethod(name, UUID.class);
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // Versión de Floodgate con otro nombre: se prueba el siguiente.
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                floodgateApi = null;
                floodgateCheck = null;
            }
        }
        if (floodgateApi == null || floodgateCheck == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(floodgateCheck.invoke(floodgateApi, id));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Los clientes de Bedrock abren visualmente el shulker que esté en la posición donde
     * se abre el contenedor, aunque el servidor no envíe el evento del bloque: la tapa
     * sube igual y delata al staff. Por eso el shulker no se abre nunca en su posición,
     * sino como una copia en un cofre virtual, que Geyser coloca lejos de cualquier caja
     * de shulker; cada cambio de la copia se escribe en el shulker real.
     */
    private void openShulkerMirror(Player player, Block block) {
        plugin.getLogger().info("Vanish: " + player.getName() + " abre " + block.getType()
                + " con copia de cofre (señal de cliente: " + bedrockSignal(player) + ").");
        Component title = block.getState() instanceof Nameable nameable && nameable.customName() != null
                ? nameable.customName()
                : Component.translatable("container.shulkerBox");
        ShulkerMirror mirror = new ShulkerMirror(block, title);
        if (block.getState(false) instanceof BlockInventoryHolder holder && holder.getInventory() != null) {
            mirror.view.setContents(holder.getInventory().getContents());
        }
        openNextTick(player, () -> player.openInventory(mirror.view));
    }

    /** Vuelca lo que hay en la copia en el shulker real, si el bloque sigue siendo un shulker. */
    private void syncMirror(ShulkerMirror mirror) {
        if (!isShulker(mirror.block)) {
            return;
        }
        if (mirror.block.getState(false) instanceof BlockInventoryHolder holder && holder.getInventory() != null) {
            holder.getInventory().setContents(mirror.view.getContents());
        }
    }

    /** El contenido se copia al tick siguiente al clic, para no tocar el inventario en mitad del evento. */
    private void syncMirrorLater(Inventory top) {
        if (top != null && top.getHolder() instanceof ShulkerMirror mirror) {
            Bukkit.getScheduler().runTask(plugin, () -> syncMirror(mirror));
        }
    }

    /**
     * Último volcado al cerrar. Si el shulker ya no está (alguien lo rompió mientras la
     * vista estaba abierta), lo que quedaba en la copia vuelve al jugador en vez de perderse.
     */
    private void closeMirror(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShulkerMirror mirror)) {
            return;
        }
        if (isShulker(mirror.block)) {
            syncMirror(mirror);
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        for (ItemStack stack : mirror.view.getContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMirrorClick(InventoryClickEvent event) {
        syncMirrorLater(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMirrorDrag(InventoryDragEvent event) {
        syncMirrorLater(event.getView().getTopInventory());
    }

    /** Vista de copia de un shulker en vanish. */
    private static final class ShulkerMirror implements InventoryHolder {

        private final Block block;
        private final Inventory view;

        private ShulkerMirror(Block block, Component title) {
            this.block = block;
            this.view = Bukkit.createInventory(this, 27, title);
        }

        @Override
        public Inventory getInventory() {
            return view;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (frozen(player)) {
            event.setCancelled(true);
            return;
        }
        // Copias guardadas (jugador offline): siempre de solo lectura, mover ítems las duplicaría.
        if (event.getView().getTopInventory().getHolder() instanceof com.admintools.model.SnapshotView) {
            event.setCancelled(true);
            plugin.getMessages().send(player, "staff.invsee-read-only");
            return;
        }
        // Inventarios de otros jugadores: sólo lectura sin permiso de modificar
        if (isForeignInventory(event.getView().getTopInventory(), player)) {
            if (!player.hasPermission(permissionFor(event.getView().getTopInventory(), player))) {
                event.setCancelled(true);
                plugin.getMessages().send(player, "staff.invsee-read-only");
            }
        }
    }

    /** ¿La vista abierta es el inventario o el enderchest de otro jugador? */
    private boolean isForeignInventory(org.bukkit.inventory.Inventory top, Player viewer) {
        return top != null && top.getHolder() instanceof Player target && !target.equals(viewer);
    }

    /**
     * Permiso que corresponde a la vista: primero el que anotó el comando al
     * abrirla y, si no hay anotación, se deduce por el tipo de inventario.
     */
    private String permissionFor(org.bukkit.inventory.Inventory top, Player viewer) {
        String tracked = plugin.getStaffState().inventoryPermission(viewer.getUniqueId());
        if (tracked != null) {
            return tracked;
        }
        return top != null && top.getType() == InventoryType.ENDER_CHEST
                ? "admintools.staff.enderchest.modify"
                : "admintools.staff.invsee.modify";
    }

    /** Al cerrar la vista se olvida el permiso anotado. */
    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            plugin.getStaffState().forgetInventory(player.getUniqueId());
        }
        closeMirror(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (frozen(player)) {
            event.setCancelled(true);
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof com.admintools.model.SnapshotView
                || (isForeignInventory(event.getView().getTopInventory(), player)
                && !player.hasPermission(permissionFor(event.getView().getTopInventory(), player)))) {
            event.setCancelled(true);
            plugin.getMessages().send(player, "staff.invsee-read-only");
        }
    }

    // ------------------------------------------------------------
    //  Invulnerabilidad
    // ------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && plugin.getStaffState().god(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Los inmortales no hacen daño (evita abusos). */
    @EventHandler(ignoreCancelled = true)
    public void onDamageOthers(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && plugin.getStaffState().god(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        // Atacar en vanish rompe el modo invisible (toggleVanish ya avisa al jugador).
        if (event.getDamager() instanceof Player player
                && plugin.getStaffState().vanished(player.getUniqueId())) {
            plugin.getStaffState().toggleVanish(player);
            plugin.getStaffState().sendToStaff(plugin.getMessages().get("staff.vanish-broken",
                    "player", player.getName()));
        }
    }

    // ------------------------------------------------------------
    //  Comandos: bloqueados, espía y silencios
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        String label = message.split(" ")[0].toLowerCase(Locale.ROOT);
        String bare = label.startsWith("/") ? label.substring(1) : label;
        if (bare.contains(":")) {
            bare = bare.substring(bare.indexOf(':') + 1);
        }

        // Congelado: sólo puede usar /report y los comandos de staff
        if (frozen(player) && !bare.equals("report") && !player.hasPermission("admintools.staff.freeze")) {
            event.setCancelled(true);
            plugin.getMessages().send(player, "staff.freeze-blocked");
            return;
        }

        // Silenciado: no puede usar comandos que escriben en el chat
        var muted = plugin.getSanctionService().activeMute(player.getUniqueId(), player.getName());
        var ipMuted = plugin.getSanctionService().activeIpMute(
                player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress());
        if ((muted.isPresent() || ipMuted.isPresent())
                && PRIVATE_MESSAGE_COMMANDS.contains(bare)
                && !player.hasPermission("admintools.chat.filter.bypass")) {
            event.setCancelled(true);
            plugin.getChatService().sendMuteNotice(player, muted.orElseGet(ipMuted::orElseThrow));
            return;
        }

        for (String blocked : plugin.getSettings().blockedCommands()) {
            if (blocked.isBlank()) {
                continue;
            }
            String normalized = blocked.startsWith("/") ? blocked.substring(1) : blocked;
            if (bare.equalsIgnoreCase(normalized) && !player.hasPermission("admintools.chat.filter.bypass")) {
                event.setCancelled(true);
                plugin.getMessages().send(player, "chat.blocked-command");
                return;
            }
        }

        // Espía de comandos (nunca de comandos con contraseñas o correo)
        if (!SENSITIVE_COMMANDS.contains(bare)
                && !plugin.getStaffState().commandSpy(player.getUniqueId())) {
            var spyMessage = plugin.getMessages().get("staff.spy-command-format", "player", player.getName(),
                    "command", Text.safe(message));
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (!staff.equals(player) && plugin.getStaffState().commandSpy(staff.getUniqueId())) {
                    staff.sendMessage(spyMessage);
                }
            }
        }

        // Espía de mensajes privados (/msg Ana hola, /r hola...)
        if (PRIVATE_MESSAGE_COMMANDS.contains(bare)) {
            boolean reply = bare.equals("r") || bare.equals("reply");
            String[] parts = message.split(" ", 3);
            String target = reply ? "(respuesta)" : (parts.length >= 2 ? parts[1] : "?");
            String content = reply || parts.length <= 2 ? String.join(" ",
                    java.util.Arrays.copyOfRange(parts, 1, parts.length)) : parts[2];
            if (content.isBlank()) {
                return;
            }
            var formatted = plugin.getMessages().get("staff.spy-social-format",
                    "from", player.getName(), "to", target, "message", Text.safe(content));
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (!staff.equals(player) && plugin.getStaffState().socialSpy(staff.getUniqueId())) {
                    staff.sendMessage(formatted);
                }
            }
        }
    }

    /** Texto plano de un componente (usado por la auditoría del chat). */
    public String plain(net.kyori.adventure.text.Component component) {
        return Text.plain(component);
    }
}
