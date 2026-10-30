package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.PlayerRecord;
import com.admintools.model.Snapshot;
import com.admintools.service.StaffStateService;
import com.admintools.util.SpectatorOpen;
import com.admintools.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Herramientas de staff: vanish, freeze, chat de staff, espías, inventarios y utilidades de sesión. */
public final class StaffCommands extends CommandSupport {

    private final StaffStateService state;

    public StaffCommands(AdminToolsPlugin plugin, CommandRegistry registry) {
        super(plugin);
        this.state = plugin.getStaffState();

        registry.register("vanish")
                .permission("admintools.staff.vanish")
                .usage("/vanish [jugador]")
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[args.length - 1]))
                .run((sender, args) -> {
                    if (args.length == 0) {
                        Player player = requirePlayer(sender);
                        state.toggleVanish(player);
                        return;
                    }
                    // Ocultar a otro miembro del staff no es lo mismo que ocultarte a ti:
                    // con el permiso básico, un helper podía apagar la vigilancia de un admin.
                    requirePermission(sender, "admintools.staff.vanish.others");
                    Player other = requireOnline(args[0]);
                    boolean vanished = state.toggleVanish(other);
                    plugin.getMessages().send(sender, vanished ? "staff.vanish-other-on" : "staff.vanish-other-off",
                            "player", other.getName());
                });

        registry.register("freeze")
                .permission("admintools.staff.freeze")
                .usage("/freeze <jugador> [motivo]")
                .completer((sender, args) -> CommandRegistry.players())
                .minArgs(1)
                .noAudit().run((sender, args) -> {
                    Player target = requireOnline(args[0]);
                    if (target.hasPermission("admintools.exempt.freeze")) {
                        throw new CommandException(plugin.getMessages().raw("staff.freeze-exempt",
                                "player", target.getName()));
                    }
                    UUID uuid = target.getUniqueId();
                    boolean frozen = !plugin.getPlayerService().frozen(uuid);
                    // Motivo opcional: se usa en el anuncio de Discord y en la auditoría.
                    String reason = args.length > 1
                            ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
                    plugin.getPlayerService().setFrozen(uuid, frozen);
                    plugin.getSanctionAnnounce().freeze(target, frozen, staffName(sender), reason, sender);
                    if (frozen) {
                        target.sendMessage(plugin.getMessages().get("staff.freeze-target"));
                        plugin.getMessages().send(sender, "staff.freeze-staff", "player", target.getName());
                        state.sendToStaff(plugin.getMessages().get("staff.freeze-on", "player", target.getName(),
                                "staff", staffName(sender)));
                    } else {
                        target.sendMessage(plugin.getMessages().get("staff.freeze-unfrozen-target"));
                        plugin.getMessages().send(sender, "staff.freeze-staff-off", "player", target.getName());
                        state.sendToStaff(plugin.getMessages().get("staff.freeze-off", "player", target.getName()));
                    }
                    plugin.getAuditService().log(sender, frozen ? "freeze.on" : "freeze.off", target.getName(),
                            frozen ? reason : "");
                });

        registry.register("staffchat")
                .permission("admintools.staff.staffchat")
                .usage("/staffchat [mensaje]")
                .noAudit()
                .run((sender, args) -> {
                    if (args.length == 0) {
                        Player player = requirePlayer(sender);
                        boolean toggled = state.toggleStaffChat(player);
                        plugin.getMessages().send(player, toggled ? "staff.staffchat-on" : "staff.staffchat-off");
                        return;
                    }
                    // La consola también puede escribir en el canal de staff.
                    sendStaffChat(staffName(sender), String.join(" ", args));
                });

        registry.register("spy")
                .permission("admintools.staff.spy")
                .usage("/spy")
                .noAudit()
                .run((sender, args) -> state.toggleSocialSpy(requirePlayer(sender)));

        registry.register("cspy")
                .permission("admintools.staff.spy.command")
                .usage("/cspy")
                .noAudit()
                .run((sender, args) -> state.toggleCommandSpy(requirePlayer(sender)));

        registry.register("stafflist")
                .permission("admintools.staff.list")
                .usage("/stafflist")
                .noAudit()
                .run((sender, args) -> {
                    List<Player> staff = Bukkit.getOnlinePlayers().stream()
                            .filter(online -> online.hasPermission("admintools.staff.staffchat")
                                    || online.hasPermission("admintools.staff.vanish"))
                            .map(online -> (Player) online)
                            .toList();
                    if (staff.isEmpty()) {
                        plugin.getMessages().send(sender, "staff.stafflist-empty");
                        return;
                    }
                    plugin.getMessages().sendRaw(sender, "staff.stafflist-header", "count", staff.size());
                    for (Player member : staff) {
                        plugin.getMessages().sendRaw(sender, "staff.stafflist-line",
                                "player", member.getName(),
                                "rank", plugin.getIntegrations().rankOf(member),
                                "duty", plugin.getPlayerService().duty(member.getUniqueId()) ? "<green>sí" : "<red>no",
                                "vanish", state.vanished(member.getUniqueId()) ? "<green>sí" : "<red>no");
                    }
                });

        registry.register("duty")
                .permission("admintools.staff.duty")
                .usage("/duty")
                .run((sender, args) -> {
                    Player player = requirePlayer(sender);
                    boolean duty = !plugin.getPlayerService().duty(player.getUniqueId());
                    plugin.getPlayerService().setDuty(player.getUniqueId(), duty);
                    plugin.getMessages().send(player, duty ? "staff.duty-on" : "staff.duty-off");
                });

        registry.register("staffmode")
                .permission("admintools.staff.mode")
                .usage("/staffmode [jugador|on|off]")
                .literals("on", "off")
                .completer((sender, args) -> CommandRegistry.players())
                .noAudit().run((sender, args) -> {
                    if (args.length == 0) {
                        Player player = requirePlayer(sender);
                        boolean enabled = plugin.getStaffMode().toggle(player);
                        plugin.getMessages().send(player, enabled ? "staff.mode-on" : "staff.mode-off");
                        plugin.getAuditService().log(player, enabled ? "staffmode.on" : "staffmode.off",
                                player.getName(), "");
                        return;
                    }
                    String input = args[0];
                    if (input.equalsIgnoreCase("on") || input.equalsIgnoreCase("off")) {
                        Player player = requirePlayer(sender);
                        boolean want = input.equalsIgnoreCase("on");
                        if (want == plugin.getStaffMode().active(player.getUniqueId())) {
                            plugin.getMessages().send(player,
                                    want ? "staff.mode-already-on" : "staff.mode-already-off");
                            return;
                        }
                        plugin.getStaffMode().toggle(player);
                        plugin.getMessages().send(player, want ? "staff.mode-on" : "staff.mode-off");
                        plugin.getAuditService().log(player, want ? "staffmode.on" : "staffmode.off",
                                player.getName(), "");
                        return;
                    }
                    requirePermission(sender, "admintools.staff.mode.others");
                    Player target = requireOnline(input);
                    boolean enabled = plugin.getStaffMode().toggle(target);
                    plugin.getMessages().send(sender,
                            enabled ? "staff.mode-other-on" : "staff.mode-other-off",
                            "player", target.getName());
                    if (!target.equals(sender)) {
                        plugin.getMessages().send(target, enabled ? "staff.mode-on" : "staff.mode-off");
                    }
                });

        registry.register("seen")
                .permission("admintools.staff.seen")
                .usage("/seen <jugador>")
                .completer((sender, args) -> CommandRegistry.players())
                .minArgs(1)
                .noAudit()
                .run((sender, args) -> {
                    Optional<UUID> found = plugin.getPlayerService().requireUuid(args[0]);
                    if (found.isEmpty()) {
                        plugin.getMessages().send(sender, "generic.player-never-seen", "player", args[0]);
                        return;
                    }
                    PlayerRecord record = plugin.getPlayerService().get(found.get());
                    long lastSeen = record.lastSeen();
                    plugin.getMessages().sendRaw(sender, "staff.seen-info",
                            "player", record.name() == null ? args[0] : record.name(),
                            "last_seen", lastSeen <= 0L ? plugin.getMessages().raw("generic.never")
                                    : TimeUtil.formatDate(lastSeen),
                            "ago", lastSeen <= 0L ? "-" : TimeUtil.ago(lastSeen),
                            "playtime", record.playtimeText().isEmpty() ? "0s" : record.playtimeText(),
                            "sessions", record.sessions(),
                            "first_seen", TimeUtil.formatDate(record.firstSeen()));
                });

        registry.register("spectate")
                .permission("admintools.staff.mode")
                .usage("/spectate [jugador]")
                .completer((sender, args) -> CommandRegistry.players())
                .noAudit().run((sender, args) -> {
                    // También sirve desde la consola para cortar una sesión a mano.
                    if (args.length > 0) {
                        requirePermission(sender, "admintools.staff.spectate.others");
                        Player target = requireOnline(args[0]);
                        if (!plugin.getSpectate().active(target.getUniqueId())) {
                            throw new CommandException(plugin.getMessages().raw("staff.spectate-not-active",
                                    "player", target.getName()));
                        }
                        plugin.getSpectate().stop(target, "manual", true);
                        return;
                    }
                    plugin.getSpectate().toggle(requirePlayer(sender));
                });

        registry.register("follow")
                .permission("admintools.staff.follow")
                .usage("/follow <jugador|stop>")
                .literals("stop")
                .completer((sender, args) -> {
                    java.util.List<String> names = new java.util.ArrayList<>(CommandRegistry.players());
                    names.add("stop");
                    return names;
                })
                .noAudit().run((sender, args) -> {
                    Player player = requirePlayer(sender);
                    if (args.length == 0) {
                        UUID current = plugin.getFollowService().targetOf(player.getUniqueId());
                        if (current == null) {
                            plugin.getMessages().send(player, "staff.follow-none");
                            return;
                        }
                        plugin.getMessages().send(player, "staff.follow-status", "player",
                                plugin.getPlayerService().nameOf(current));
                        return;
                    }
                    if (args[0].equalsIgnoreCase("stop")) {
                        boolean stopped = plugin.getFollowService().unfollow(player.getUniqueId());
                        plugin.getMessages().send(player, stopped ? "staff.follow-stop" : "staff.follow-none");
                        return;
                    }
                    Player target = requireOnline(args[0]);
                    if (target.equals(player)) {
                        throw new CommandException(plugin.getMessages().raw("staff.follow-self"));
                    }
                    if (target.hasPermission("admintools.staff.teleport.exempt")) {
                        throw new CommandException(plugin.getMessages().raw("staff.tp-exempt",
                                "player", target.getName()));
                    }
                    plugin.getFollowService().follow(player, target);
                    player.teleport(target.getLocation());
                    plugin.getMessages().send(player, "staff.follow-start", "player", target.getName());
                });

        registry.register("playtime")
                .permission("admintools.staff.seen")
                .usage("/playtime [jugador]")
                .completer((sender, args) -> CommandRegistry.players())
                .noAudit()
                .run((sender, args) -> {
                    String name = args.length > 0 ? args[0] : requirePlayer(sender).getName();
                    Optional<UUID> found = plugin.getPlayerService().requireUuid(name);
                    if (found.isEmpty()) {
                        plugin.getMessages().send(sender, "generic.player-never-seen", "player", name);
                        return;
                    }
                    PlayerRecord record = plugin.getPlayerService().get(found.get());
                    boolean self = sender instanceof Player player && player.getUniqueId().equals(found.get());
                    plugin.getMessages().sendRaw(sender, self ? "staff.playtime-self" : "staff.playtime-other",
                            "player", record.name() == null ? name : record.name(),
                            "playtime", record.playtimeText().isEmpty() ? "0s" : record.playtimeText(),
                            "sessions", record.sessions(),
                            "first_seen", TimeUtil.formatDate(record.firstSeen()),
                            "last_seen", record.lastSeen() <= 0L ? plugin.getMessages().raw("generic.never")
                                    : TimeUtil.formatDate(record.lastSeen()));
                });

        registry.register("invsee")
                .permission("admintools.staff.invsee")
                .usage("/invsee <jugador>")
                .minArgs(1)
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[args.length - 1]))
                .noAudit()
                .run((sender, args) -> openInventory(sender, args[0], false));

        registry.register("enderchest")
                .permission("admintools.staff.enderchest")
                .usage("/enderchest <jugador>")
                .minArgs(1)
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[args.length - 1]))
                .noAudit()
                .run((sender, args) -> openInventory(sender, args[0], true));
    }

    /** Abre el inventario (o enderchest) de un jugador, en vivo si está online. */
    private void openInventory(CommandSender sender, String name, boolean enderchest) {
        Player viewer = requirePlayer(sender);
        Optional<UUID> found = plugin.getPlayerService().requireUuid(name);
        if (found.isEmpty()) {
            throw new CommandException(plugin.getMessages().raw("generic.player-not-found", "player", name));
        }
        UUID uuid = found.get();
        String display = plugin.getPlayerService().nameOf(uuid);
        Player online = Bukkit.getPlayer(uuid);
        String modifyPermission = enderchest ? "admintools.staff.enderchest.modify"
                : "admintools.staff.invsee.modify";
        if (online != null) {
            // Se anota el permiso exacto: el listener lo usa para permitir o bloquear clics.
            state.trackInventory(viewer.getUniqueId(), modifyPermission);
            // Sin animación: abrir el enderchest ajeno movería la tapa del bloque real.
            SpectatorOpen.run(viewer, () ->
                    viewer.openInventory(enderchest ? online.getEnderChest() : online.getInventory()));
            plugin.getMessages().send(viewer, enderchest ? "staff.enderchest-opened" : "staff.invsee-opened",
                    "player", display);
            if (!viewer.hasPermission(enderchest ? "admintools.staff.enderchest.modify"
                    : "admintools.staff.invsee.modify")) {
                plugin.getMessages().send(viewer, "staff.invsee-read-only");
            }
            plugin.getAuditService().log(sender, enderchest ? "enderchest.open" : "invsee.open", display, "en vivo");
            return;
        }

        String kind = enderchest ? com.admintools.service.SnapshotService.KIND_ENDERCHEST
                : com.admintools.service.SnapshotService.KIND_INVENTORY;
        Optional<Snapshot> snapshot = plugin.getSnapshotService().latest(uuid, kind);
        if (snapshot.isEmpty() || snapshot.get().data() == null) {
            plugin.getMessages().send(viewer, "staff.invsee-no-data", "player", display);
            return;
        }
        ItemStack[] items;
        try {
            items = plugin.getSnapshotService().readInventory(snapshot.get().id());
        } catch (Exception ex) {
            plugin.getMessages().send(viewer, "staff.invsee-no-data", "player", display);
            return;
        }
        int size = Math.min(54, Math.max(9, ((items.length + 8) / 9) * 9));
        // Vista de solo lectura: es una copia en memoria, mover ítems aquí los duplicaría.
        com.admintools.model.SnapshotView holder = new com.admintools.model.SnapshotView();
        Inventory inventory = Bukkit.createInventory(holder, Math.max(9, size),
                Component.text((enderchest ? "Enderchest de " : "Copia de ") + display));
        holder.inventory(inventory);
        for (int i = 0; i < Math.min(items.length, inventory.getSize()); i++) {
            inventory.setItem(i, items[i]);
        }
        viewer.openInventory(inventory);
        plugin.getMessages().send(viewer, "staff.invsee-snapshot", "player", display,
                "date", TimeUtil.formatDate(snapshot.get().createdAt()));
        plugin.getAuditService().log(sender, enderchest ? "enderchest.open" : "invsee.open", display,
                "copia #" + snapshot.get().id());
    }

    /** Envía un mensaje al canal de staff. */
    public void sendStaffChat(String author, String message) {
        Component component = plugin.getMessages().get("staff.staffchat-format", "player", author,
                "message", com.admintools.util.Text.safe(message));
        plugin.getStaffState().sendToStaff(component);
    }
}
