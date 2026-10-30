package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.PunishmentType;
import com.admintools.model.Sanction;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Utilidades que comparten todos los comandos: resolver jugadores, límites y formato. */
public abstract class CommandSupport {

    protected final AdminToolsPlugin plugin;

    protected CommandSupport(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------
    //  Resolución de jugadores
    // ------------------------------------------------------------

    /** UUID de un jugador, online o guardado en la base. */
    protected UUID target(String name) throws CommandException {
        return plugin.getPlayerService().requireUuid(name)
                .orElseThrow(() -> new CommandException(plugin.getMessages().raw("generic.player-not-found",
                        "player", name)));
    }

    /**
     * UUID sobre el que se aplicará una sanción: el de la cuenta si conocemos al
     * jugador o, si nunca ha entrado, el derivado de su nombre. Así se puede banear
     * (silenciar, advertir...) a alguien que todavía no tiene ficha en el servidor.
     */
    protected UUID sanctionTarget(String name) {
        return plugin.getPlayerService().requireUuid(name)
                .orElseGet(() -> com.admintools.service.SanctionService.offlineUuid(name));
    }

    /** ¿El jugador ya tiene ficha en este servidor (o está online)? */
    protected boolean known(String name) {
        return plugin.getPlayerService().requireUuid(name).isPresent();
    }

    /** IP conocida del jugador, o null si todavía no ha entrado aquí. */
    protected String ipIfKnown(UUID uuid, String name) {
        return known(name) ? plugin.getSanctionService().ipOf(uuid) : null;
    }

    /** Nombre a mostrar: el de ahora si está online, si no el que escribió el staff. */
    protected String targetName(String name, UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        return online != null ? online.getName() : name;
    }

    protected Player requirePlayer(CommandSender sender) throws CommandException {
        if (sender instanceof Player player) {
            return player;
        }
        throw new CommandException(plugin.getMessages().raw("generic.player-only"));
    }

    protected Player requireOnline(String name) throws CommandException {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            throw new CommandException(plugin.getMessages().raw("reports.needs-online", "player", name));
        }
        return player;
    }

    // ------------------------------------------------------------
    //  Sanciones: motivos, exenciones y datos del staff
    // ------------------------------------------------------------

    /** Motivo escrito tras los argumentos indicados (o el genérico). */
    protected String reason(String[] args, int from) {
        if (args.length <= from) {
            return plugin.getMessages().raw("sanctions.no-reason");
        }
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    /** ¿El jugador está blindado contra sanciones? (devuelve true si está exento). */
    protected boolean canSanction(Player target) {
        return target.hasPermission("admintools.exempt.sanctions");
    }

    /**
     * Peso administrativo derivado de los permisos de rango.
     * Funciona sin LuckPerms porque los tres rangos ya se declaran en plugin.yml.
     */
    protected int rankWeight(Player player) {
        if (player.hasPermission("admintools.admin")) {
            return 3;
        }
        if (player.hasPermission("admintools.moderator")) {
            return 2;
        }
        if (player.hasPermission("admintools.helper")) {
            return 1;
        }
        return 0;
    }

    /**
     * Impide que un miembro del staff sancione a alguien de su mismo rango o superior.
     * Quedan fuera la consola, uno mismo y quien tenga {@code admintools.sanction.force}.
     */
    protected void checkRank(CommandSender sender, Player target) throws CommandException {
        if (!(sender instanceof Player staff) || !plugin.getSettings().protectHigherRanks()) {
            return;
        }
        if (staff.getUniqueId().equals(target.getUniqueId())) {
            return;
        }
        if (staff.hasPermission("admintools.sanction.force")) {
            return;
        }
        if (rankWeight(target) >= rankWeight(staff)) {
            throw new CommandException(plugin.getMessages().raw("sanctions.higher-rank",
                    "player", target.getName()));
        }
    }

    /** Comprueba exención y jerarquía de rangos antes de aplicar una sanción. */
    protected void checkExempt(CommandSender sender, UUID uuid, String name) throws CommandException {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            if (canSanction(online)) {
                throw new CommandException(plugin.getMessages().raw("sanctions.exempt", "player", online.getName()));
            }
            checkRank(sender, online);
            return;
        }
        // Desconectado: sin LuckPerms no hay forma de leer sus permisos, pero con el
        // hook instalado se respeta igual la jerarquía. Sin esto, un moderador podía
        // banear a un admin offline y saltarse la protección de rangos.
        if (!(sender instanceof Player staff) || !plugin.getSettings().protectHigherRanks()
                || staff.hasPermission("admintools.sanction.force")) {
            return;
        }
        if (Boolean.TRUE.equals(plugin.getIntegrations().luckPerms()
                .hasPermissionOffline(uuid, "admintools.exempt.sanctions"))) {
            throw new CommandException(plugin.getMessages().raw("sanctions.exempt", "player", name));
        }
        if (rankWeightOffline(uuid) >= rankWeight(staff)) {
            throw new CommandException(plugin.getMessages().raw("sanctions.higher-rank", "player", name));
        }
    }

    /**
     * Peso de rango de un jugador desconectado. Devuelve 0 si LuckPerms no está o no
     * sabe responder, para no bloquear sanciones legítimas por falta de datos.
     */
    private int rankWeightOffline(UUID uuid) {
        if (!plugin.getIntegrations().luckPerms().available()) {
            return 0;
        }
        if (Boolean.TRUE.equals(hasOffline(uuid, "admintools.admin"))) {
            return 3;
        }
        if (Boolean.TRUE.equals(hasOffline(uuid, "admintools.moderator"))) {
            return 2;
        }
        if (Boolean.TRUE.equals(hasOffline(uuid, "admintools.helper"))) {
            return 1;
        }
        return 0;
    }

    private Boolean hasOffline(UUID uuid, String node) {
        return plugin.getIntegrations().luckPerms().hasPermissionOffline(uuid, node);
    }

    /**
     * Confirmación al staff que aplicó la sanción.
     *
     * <p>Una advertencia o una expulsión no tienen duración: con la misma línea
     * quedaba un separador suelto ("· -"), así que cada tipo tiene la suya.</p>
     */
    protected void confirmApplied(CommandSender sender, Sanction sanction, String display, long duration) {
        boolean timed = sanction.type() != PunishmentType.WARN && sanction.type() != PunishmentType.KICK;
        plugin.getMessages().send(sender, timed ? "sanctions.applied" : "sanctions.applied-no-duration",
                "id", sanction.id(), "player", display, "type", sanction.type().display(),
                "duration", plugin.getSanctionService().durationText(duration));
    }

    protected UUID staffUuid(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    protected String staffName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "CONSOLE";
    }

    // ------------------------------------------------------------
    //  Números y permisos
    // ------------------------------------------------------------

    protected int parsePage(String input) throws CommandException {
        try {
            return Math.max(1, Integer.parseInt(input));
        } catch (NumberFormatException ex) {
            throw new CommandException(plugin.getMessages().raw("generic.invalid-number", "input", input));
        }
    }

    protected long parseLong(String input) throws CommandException {
        try {
            return Long.parseLong(input);
        } catch (NumberFormatException ex) {
            throw new CommandException(plugin.getMessages().raw("generic.invalid-number", "input", input));
        }
    }

    protected void requirePermission(CommandSender sender, String node) throws CommandException {
        if (!sender.hasPermission(node)) {
            throw new CommandException(plugin.getMessages().raw("generic.no-permission"));
        }
    }
}
