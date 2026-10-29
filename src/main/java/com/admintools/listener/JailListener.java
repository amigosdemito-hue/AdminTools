package com.admintools.listener;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.Jail;
import com.admintools.model.JailSentence;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Restricciones de los presos: no salen de la celda, no rompen ni colocan bloques,
 * no interactúan, no se pegan entre ellos y sólo usan los comandos permitidos.
 *
 * <p>El bypass es {@code admintools.jail.bypass}: pensado para un admin que cae
 * preso por error, nunca para el preso normal.</p>
 */
public final class JailListener implements Listener {

    /** Comandos que nunca se bloquean: sin ellos el preso no puede ni reportar. */
    private static final java.util.Set<String> ALWAYS_ALLOWED = java.util.Set.of(
            "report", "jailtime", "help", "msg", "tell", "w", "r", "reply");

    private final AdminToolsPlugin plugin;
    /** Último aviso de "no puedes construir" por jugador: evita spam al picar. */
    private final Map<java.util.UUID, Long> buildNotices = new ConcurrentHashMap<>();

    public JailListener(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    private Optional<JailSentence> sentence(Player player) {
        if (player.hasPermission("admintools.jail.bypass")) {
            return Optional.empty();
        }
        return plugin.getJailService().active(player.getUniqueId());
    }

    private boolean blocked(Player player) {
        return sentence(player).isPresent();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getJailService().onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        buildNotices.remove(event.getPlayer().getUniqueId());
        plugin.getJailService().onQuit(event.getPlayer());
    }

    /** Al morir se reaparece en la celda, no en el spawn del mundo. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Optional<JailSentence> active = sentence(event.getPlayer());
        if (active.isEmpty()) {
            return;
        }
        Optional<Jail> cell = plugin.getJailService().jail(active.get().jail());
        Location location = cell.map(Jail::location).orElse(null);
        if (location != null) {
            event.setRespawnLocation(location);
        }
    }

    /**
     * Aviso de "no puedes construir" limitado a uno cada 3 segundos: al picar un
     * bloque el cliente puede mandar varios eventos seguidos.
     */
    private void warnBuild(Player player) {
        long now = System.currentTimeMillis();
        Long last = buildNotices.get(player.getUniqueId());
        if (last != null && now - last < 3_000L) {
            return;
        }
        buildNotices.put(player.getUniqueId(), now);
        plugin.getMessages().send(player, "jail.cannot-build");
    }

    /**
     * Se cancela el inicio del picado, antes de que el cliente empiece a animar.
     *
     * <p>Junto con el modo aventura (que el cliente respeta por sí solo), esto es lo
     * que hace que el preso ni siquiera pueda intentarlo: no llega a ver el bloque
     * roto ni se queda trabado dentro de un hueco que sólo existe en su pantalla.</p>
     */
    @EventHandler(ignoreCancelled = true)
    public void onStartBreak(BlockDamageEvent event) {
        if (!blocked(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        warnBuild(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!blocked(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        // Respaldo si el cliente llegó a completar la rotura (modo aventura desactivado):
        // se le devuelve el bloque real.
        repaint(event.getPlayer(), event.getBlock(), event.getBlock().getBlockData());
        warnBuild(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!blocked(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        // Igual que al romper: se repinta el bloque anterior donde intentó colocar.
        repaint(event.getPlayer(), event.getBlockPlaced(), event.getBlockReplacedState().getBlockData());
        warnBuild(event.getPlayer());
    }

    /**
     * Corrige el bloque en el cliente tras cancelar una rotura o colocación.
     *
     * <p>Los clientes de Java esperan la respuesta del servidor y revierten solos,
     * pero Bedrock (Geyser) pinta la rotura en cuanto la completa y no la revierte
     * al llegar el bloque restaurado: el preso ve el bloque roto aunque el servidor
     * lo conserve. Se reenvía el bloque real y, por si la predicción del cliente
     * llega tarde, se repite en los dos ticks siguientes.</p>
     */
    private void repaint(Player player, Block block, BlockData data) {
        if (block == null || data == null) {
            return;
        }
        Location location = block.getLocation();
        player.sendBlockChange(location, data);
        for (int delay : new int[]{1, 3}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.sendBlockChange(location, data);
                }
            }, delay);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL) {
            return;
        }
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
            // En modo aventura el clic izquierdo no rompe nada: se avisa igualmente
            // para que sepa por qué el bloque no se rompe.
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                warnBuild(event.getPlayer());
            }
        }
    }

    /**
     * Fuga al instante: en cuanto cambia de bloque y sale del área de la celda se le
     * devuelve dentro, sin esperar al tic de cada segundo. Así una puerta abierta no
     * es una vía de escape.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        Optional<JailSentence> active = plugin.getJailService().active(player.getUniqueId());
        if (active.isEmpty() || player.hasPermission("admintools.jail.bypass")) {
            return;
        }
        Optional<Jail> cell = plugin.getJailService().jail(active.get().jail());
        if (cell.isEmpty() || cell.get().inside(event.getTo())) {
            return;
        }
        Location back = cell.get().location();
        if (back == null) {
            return;
        }
        if (event.getFrom().getWorld() == null || !event.getFrom().getWorld().equals(back.getWorld())) {
            // Cruce de mundo (raro en un movimiento): se cancela y se manda al tick
            // siguiente, que es cuando un teletransporte entre mundos es válido.
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.teleport(back);
                }
            });
            return;
        }
        event.setTo(back);
        // Si otro plugin reescribe el destino del movimiento, al tic siguiente se
        // corrige igualmente: el tic de la cárcel comprueba la posición real.
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Sin recoger objetos del suelo: nada de contrabando dentro de la celda. */
    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    /** Sin daño para el preso ni del preso: evita empujones y peleas dentro. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getSettings().jailBlockDamage()) {
            return;
        }
        if (event.getEntity() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageOthers(EntityDamageByEntityEvent event) {
        if (!plugin.getSettings().jailBlockDamage()) {
            return;
        }
        if (event.getDamager() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * Cualquier teletransporte que saque al preso de la celda: perlas, fruta del
     * coro, portales, camas o comandos de otros plugins.
     *
     * <p>Los teletransportes de plugin se dejan pasar a propósito: son los del
     * propio staff ({@code /jailtp}, {@code /tp}) y no debe impedirlos.</p>
     */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }
        Optional<JailSentence> active = sentence(event.getPlayer());
        if (active.isEmpty() || event.getTo() == null) {
            return;
        }
        Optional<Jail> cell = plugin.getJailService().jail(active.get().jail());
        if (cell.isPresent() && !cell.get().inside(event.getTo())) {
            event.setCancelled(true);
            plugin.getMessages().send(event.getPlayer(), "jail.cannot-leave");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!blocked(player)) {
            return;
        }
        String label = event.getMessage().split(" ")[0].toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        if (label.contains(":")) {
            label = label.substring(label.indexOf(':') + 1);
        }
        if (ALWAYS_ALLOWED.contains(label) || plugin.getSettings().jailAllowedCommands().contains(label)) {
            return;
        }
        event.setCancelled(true);
        plugin.getMessages().send(player, "jail.cannot-use-command");
    }
}
