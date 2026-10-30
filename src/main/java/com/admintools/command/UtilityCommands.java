package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/** Utilidades del día a día del staff: curación, vuelo, modo de juego, teletransportes. */
public final class UtilityCommands extends CommandSupport {

    public UtilityCommands(AdminToolsPlugin plugin, CommandRegistry registry) {
        super(plugin);

        registry.register("heal")
                .permission("admintools.staff.utility")
                .usage("/heal [jugador]")
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[args.length - 1]))
                .run((sender, args) -> {
                    Player target = resolve(sender, args);
                    var maxHealth = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    target.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
                    target.setFireTicks(0);
                    target.setFoodLevel(20);
                    target.setSaturation(20f);
                    plugin.getMessages().send(sender, "staff.util-healed", "player", target.getName());
                });

        registry.register("feed")
                .permission("admintools.staff.utility")
                .usage("/feed [jugador]")
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[args.length - 1]))
                .run((sender, args) -> {
                    Player target = resolve(sender, args);
                    target.setFoodLevel(20);
                    target.setSaturation(20f);
                    plugin.getMessages().send(sender, "staff.util-fed", "player", target.getName());
                });

        registry.register("fly")
                .permission("admintools.staff.utility")
                .usage("/fly [jugador]")
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[args.length - 1]))
                .run((sender, args) -> {
                    Player target = resolve(sender, args);
                    boolean enable = !target.getAllowFlight();
                    target.setAllowFlight(enable);
                    if (!enable) {
                        target.setFlying(false);
                    }
                    plugin.getMessages().send(sender, enable ? "staff.util-fly-on" : "staff.util-fly-off",
                            "player", target.getName());
                });

        registry.register("god")
                .permission("admintools.staff.utility")
                .usage("/god [jugador]")
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[args.length - 1]))
                .run((sender, args) -> {
                    Player target = resolve(sender, args);
                    boolean enable = plugin.getStaffState().toggleGod(target);
                    plugin.getMessages().send(sender, enable ? "staff.util-god-on" : "staff.util-god-off",
                            "player", target.getName());
                });

        registry.register("gamemode")
                .permission("admintools.staff.utility")
                .usage("/gamemode <modo> [jugador]")
                .completer((sender, args) -> args.length <= 1
                        ? java.util.List.of("survival", "creative", "adventure", "spectator", "0", "1", "2", "3")
                        : CommandRegistry.players())
                .minArgs(1)
                .literals("survival", "creative", "adventure", "spectator")
                .run((sender, args) -> {
                    GameMode mode = parseMode(args[0]);
                    Player target = args.length > 1 ? requireOnline(args[1])
                            : requirePlayer(sender);
                    target.setGameMode(mode);
                    plugin.getMessages().send(sender, "staff.util-gamemode", "player", target.getName(),
                            "mode", mode.name().toLowerCase(Locale.ROOT));
                });

        registry.register("clear")
                .permission("admintools.staff.utility")
                .usage("/clear [jugador]")
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[args.length - 1]))
                .run((sender, args) -> {
                    Player target = resolve(sender, args);
                    if (!plugin.getConfirmations().confirm(sender, "clear", "staff.clear-confirm",
                            "player", target.getName())) {
                        return;
                    }
                    plugin.getSnapshotService().save(target, "Antes de /clear");
                    target.getInventory().clear();
                    plugin.getMessages().send(sender, "staff.util-cleared", "player", target.getName());
                });

        registry.register("tp")
                .permission("admintools.staff.teleport")
                .usage("/tp <jugador>")
                .minArgs(1)
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[args.length - 1]))
                .run((sender, args) -> {
                    Player player = requirePlayer(sender);
                    Player target = requireOnline(args[0]);
                    checkExempt(target);
                    plugin.getStaffState().rememberBack(player);
                    player.teleport(target);
                    plugin.getMessages().send(player, "staff.tp-teleported", "player", target.getName());
                    target.sendMessage(plugin.getMessages().get("staff.tp-target-notify", "staff", player.getName()));
                });

        registry.register("tphere")
                .permission("admintools.staff.teleport")
                .usage("/tphere <jugador>")
                .minArgs(1)
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[args.length - 1]))
                .run((sender, args) -> {
                    Player player = requirePlayer(sender);
                    Player target = requireOnline(args[0]);
                    checkExempt(target);
                    plugin.getStaffState().rememberBack(target);
                    target.teleport(player);
                    plugin.getMessages().send(player, "staff.tp-here", "player", target.getName());
                });

        registry.register("tppos")
                .permission("admintools.staff.teleport")
                .usage("/tppos <x> <y> <z> [mundo]")
                .minArgs(3)
                .run((sender, args) -> {
                    Player player = requirePlayer(sender);
                    double x = number(args[0]);
                    double y = number(args[1]);
                    double z = number(args[2]);
                    World world = args.length > 3 ? Bukkit.getWorld(args[3]) : player.getWorld();
                    if (world == null) {
                        throw new CommandException(plugin.getMessages().raw("staff.tp-no-world",
                                "world", com.admintools.util.Text.safe(args[3])));
                    }
                    plugin.getStaffState().rememberBack(player);
                    player.teleport(new Location(world, x, y, z, player.getLocation().getYaw(),
                            player.getLocation().getPitch()));
                    plugin.getMessages().send(player, "staff.tp-pos", "x", x, "y", y, "z", z,
                            "world", world.getName());
                });

        registry.register("back")
                .permission("admintools.staff.teleport")
                .usage("/back")
                .run((sender, args) -> {
                    Player player = requirePlayer(sender);
                    Location location = plugin.getStaffState().back(player.getUniqueId())
                            .orElseThrow(() -> new CommandException(plugin.getMessages().raw("staff.tp-no-back")));
                    player.teleport(location);
                    plugin.getMessages().send(player, "staff.tp-back");
                });
    }

    private void checkExempt(Player target) throws CommandException {
        if (target.hasPermission("admintools.staff.teleport.exempt")) {
            throw new CommandException(plugin.getMessages().raw("staff.tp-exempt", "player", target.getName()));
        }
    }

    private Player resolve(CommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            return requirePlayer(sender);
        }
        return requireOnline(args[0]);
    }

    /** Acepta 0-3, s/c/a/sp, survival/creative/spectator... (spectator antes que "s"). */
    private GameMode parseMode(String input) throws CommandException {
        String value = input.toLowerCase(Locale.ROOT);
        switch (value) {
            case "0" -> {
                return GameMode.SURVIVAL;
            }
            case "1" -> {
                return GameMode.CREATIVE;
            }
            case "2" -> {
                return GameMode.ADVENTURE;
            }
            case "3" -> {
                return GameMode.SPECTATOR;
            }
            default -> {
                // sigue abajo con los nombres
            }
        }
        if (value.startsWith("sp")) {
            return GameMode.SPECTATOR;
        }
        if (value.startsWith("s")) {
            return GameMode.SURVIVAL;
        }
        if (value.startsWith("c")) {
            return GameMode.CREATIVE;
        }
        if (value.startsWith("a")) {
            return GameMode.ADVENTURE;
        }
        try {
            return GameMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new CommandException(plugin.getMessages().raw("generic.unknown-argument", "input", input));
        }
    }

    private double number(String input) throws CommandException {
        try {
            return Double.parseDouble(input);
        } catch (NumberFormatException ex) {
            throw new CommandException(plugin.getMessages().raw("generic.invalid-number", "input", input));
        }
    }
}
