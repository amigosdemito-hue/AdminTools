package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import org.bukkit.command.CommandSender;

import java.util.Locale;

/** /admintools: ayuda, estado, recarga y los atajos de CoreProtect. */
public final class RootCommand extends CommandSupport {

    private final CommandRegistry registry;

    public RootCommand(AdminToolsPlugin plugin, CommandRegistry registry) {
        super(plugin);
        this.registry = registry;

        registry.register("admintools")
                .usage("/admintools <help|status|reload|inspect|rollback|purge|acalert>")
                .completer((sender, args) -> args.length <= 1
                        ? java.util.List.of("help", "status", "reload", "inspect", "rollback", "purge", "acalert")
                        : CommandRegistry.players())
                .literals("help", "status", "reload", "inspect", "rollback", "purge", "acalert")
                .noAudit()
                .run((sender, args) -> {
                    String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
                    switch (action) {
                        case "help" -> help(sender);
                        case "status" -> status(sender);
                        case "reload" -> reload(sender);
                        case "inspect" -> inspect(sender, args);
                        case "rollback" -> rollback(sender, args);
                        case "purge" -> purge(sender);
                        case "acalert" -> acalert(sender, args);
                        default -> throw new CommandException(plugin.getMessages().raw("generic.unknown-argument",
                                "input", args[0]));
                    }
                });
    }

    private void help(CommandSender sender) {
        plugin.getMessages().sendRaw(sender, "generic.help-header", "version", plugin.getPluginMeta().getVersion());
        for (var definition : registry.definitions()) {
            if (definition.permission() != null && !sender.hasPermission(definition.permission())) {
                continue;
            }
            plugin.getMessages().sendRaw(sender, "investigate.check-line",
                    "key", definition.usage(),
                    "value", commandDescription(definition.name()));
        }
        plugin.getMessages().sendRaw(sender, "investigate.case-footer");
    }

    private String commandDescription(String name) {
        return switch (name) {
            case "helper", "moderator", "admin" -> "Panel de herramientas del rango";
            case "ban", "tempban" -> "Banea a un jugador";
            case "ipban" -> "Banea una IP";
            case "unban" -> "Levanta un ban";
            case "mute", "tempmute" -> "Silencia a un jugador";
            case "unmute" -> "Levanta un silencio";
            case "shadowmute" -> "Silencio fantasma";
            case "warn" -> "Advierte a un jugador";
            case "tempwarn" -> "Advierte a un jugador durante un tiempo";
            case "ipmute" -> "Silencia una IP";
            case "kick" -> "Expulsa a un jugador";
            case "history" -> "Historial de sanciones";
            case "notes" -> "Notas internas de staff";
            case "punish" -> "Aplica una plantilla";
            case "ladder" -> "Aplica la siguiente sanción de una escalera";
            case "unpunish" -> "Revierte sanciones activas";
            case "vanish" -> "Invisibilidad para el staff";
            case "freeze" -> "Congela a un jugador";
            case "staffchat" -> "Canal de staff";
            case "spy" -> "Espía mensajes privados";
            case "cspy" -> "Espía comandos";
            case "stafflist" -> "Staff online";
            case "staffmode" -> "Modo staff con herramientas";
            case "follow" -> "Seguir a un jugador";
            case "playtime" -> "Tiempo jugado";
            case "duty" -> "Entrar o salir de servicio";
            case "seen" -> "Última conexión y tiempo jugado";
            case "invsee" -> "Ver inventarios";
            case "enderchest" -> "Ver enderchests";
            case "heal", "feed", "fly", "god", "gamemode", "clear" -> "Utilidades de staff";
            case "tp", "tphere", "tppos", "back" -> "Teletransportes";
            case "report" -> "Reportar a un jugador";
            case "reports" -> "Cola de reportes";
            case "chatlog" -> "Registro de chat";
            case "clearchat" -> "Limpiar el chat";
            case "slowmode" -> "Modo lento";
            case "muteall" -> "Silenciar el chat global";
            case "check" -> "Ficha unificada del jugador";
            case "alts" -> "Cuentas alternativas";
            case "case" -> "Expediente completo";
            case "snapshot", "restore" -> "Copias de inventario";
            case "maintenance" -> "Modo mantenimiento";
            case "restart" -> "Reinicio programado";
            case "backup" -> "Copias de seguridad";
            case "audit" -> "Auditoría del staff";
            case "watchdog" -> "Salud del servidor";
            case "admintools" -> "Ayuda, estado, recarga y purga";
            case "jail" -> "Encarcela a un jugador";
            case "unjail" -> "Libera a un preso";
            case "jailtime" -> "Tiempo de condena restante";
            case "jaillist" -> "Presos y sus condenas";
            case "setjail" -> "Crea una cárcel en tu posición";
            case "deljail" -> "Borra una cárcel vacía";
            case "jails" -> "Cárceles configuradas";
            case "jailtp" -> "Ve a la celda de un preso";
            case "jailimport" -> "Migra celdas y presos desde xJail";
            case "rollback" -> "Copias completas del jugador (ver y restaurar)";
            default -> "";
        };
    }

    private void status(CommandSender sender) throws CommandException {
        // TPS, RAM, disco y contadores internos no son información para cualquiera.
        requirePermission(sender, "admintools.helper");
        plugin.getMessages().sendRaw(sender, "generic.status-header", "version", plugin.getPluginMeta().getVersion());
        line(sender, "Almacenamiento", plugin.getSettings().mysql() ? "MySQL" : "SQLite");
        line(sender, "Idioma", plugin.getSettings().language());
        line(sender, "Servidor", plugin.getServerName());
        line(sender, "Sanciones activas", String.valueOf(plugin.getDatabase().queryLong("SELECT COUNT(*) FROM "
                + plugin.getDatabase().table("sanctions") + " WHERE active = 1")));
        line(sender, "Reportes abiertos", String.valueOf(plugin.getReportService().queueCount()));
        line(sender, "Notas guardadas", String.valueOf(plugin.getDatabase()
                .queryLong("SELECT COUNT(*) FROM " + plugin.getDatabase().table("notes"))));
        line(sender, "Mantenimiento", plugin.getMaintenanceService().enabled() ? "<red>activado" : "<green>no");
        line(sender, "Chat global", plugin.getChatService().muteAll() ? "<red>silenciado" : "<green>abierto");
        var integrations = plugin.getIntegrations().activeTexts();
        line(sender, "Integraciones", integrations.isEmpty() ? "-" : String.join(", ", integrations));
        // Dónde acaba el registro de staff: sin esto, "no funciona" no decía nada.
        line(sender, "Registro de staff", plugin.getStaffLog().usable()
                ? "<green>ON <gray>· " + plugin.getSettings().staffActionLogTarget()
                : (plugin.getSettings().staffActionLogDiscord()
                        ? "<red>activado pero sin webhook" : "<gray>desactivado"));
        var data = plugin.getWatchdogService().snapshot();
        plugin.getMessages().sendRaw(sender, "ops.watchdog-status",
                "tps", data.get("tps"), "mspt", data.get("mspt"), "players", data.get("players"),
                "memory", data.get("memory"), "disk", data.get("disk"), "chunks", data.get("chunks"));
    }

    private void line(CommandSender sender, String key, String value) {
        plugin.getMessages().sendRaw(sender, "investigate.check-line", "key", key, "value", value);
    }

    private void reload(CommandSender sender) throws CommandException {
        requirePermission(sender, "admintools.ops.reload");
        long started = System.currentTimeMillis();
        try {
            plugin.reloadAll();
            plugin.getMessages().send(sender, "generic.reloaded", "ms", System.currentTimeMillis() - started);
            plugin.getAuditService().log(sender, "ops.reload", "-", "Recarga completa");
        } catch (Exception ex) {
            plugin.getMessages().send(sender, "generic.reload-failed", "error", String.valueOf(ex.getMessage()));
            plugin.getLogger().warning("Fallo al recargar: " + ex);
        }
    }

    private void inspect(CommandSender sender, String[] args) throws CommandException {
        // CoreProtect aplica sus propios permisos, pero el resto de /at los declara aquí:
        // sin esta línea la suborden quedaba como la única sin control propio.
        requirePermission(sender, "admintools.investigate.inspect");
        if (args.length < 2) {
            throw new CommandException(plugin.getMessages().raw("generic.usage",
                    "usage", "/at inspect <jugador> [radio] [tiempo]"));
        }
        if (!plugin.getIntegrations().coreProtect().installed()) {
            throw new CommandException(plugin.getMessages().raw("investigate.coreprotect-missing"));
        }
        int radius = args.length > 2 ? (int) Math.max(1L, Math.min(100L, parseLong(args[2]))) : 10;
        String time = args.length > 3 ? args[3] : "1d";
        if (!plugin.getIntegrations().coreProtect().inspect(sender, args[1], radius, time)) {
            throw new CommandException(plugin.getMessages().raw("investigate.coreprotect-failed",
                    "command", "/co lookup u:" + args[1]));
        }
    }

    private void rollback(CommandSender sender, String[] args) throws CommandException {
        requirePermission(sender, "admintools.admin");
        if (args.length < 3) {
            throw new CommandException(plugin.getMessages().raw("generic.usage",
                    "usage", "/at rollback <jugador> <tiempo> (ej: /at rollback Ana 1d)"));
        }
        if (!plugin.getIntegrations().coreProtect().installed()) {
            throw new CommandException(plugin.getMessages().raw("investigate.coreprotect-missing"));
        }
        if (!plugin.getIntegrations().coreProtect().rollback(sender, args[1], args[2])) {
            throw new CommandException(plugin.getMessages().raw("investigate.coreprotect-failed",
                    "command", "/co rollback u:" + args[1]));
        }
        plugin.getAuditService().log(sender, "ops.rollback", args[1], "t:" + args[2]);
    }

    /**
     * Puente genérico para anticheats: cualquier plugin que pueda ejecutar un
     * comando al detectar un flag puede avisar al staff por aquí, sin que
     * AdminTools dependa de cinco APIs distintas.
     *
     * <pre>/at acalert &lt;jugador&gt; &lt;anticheat&gt; &lt;check&gt; &lt;vl&gt; [detalles]</pre>
     */
    private void acalert(CommandSender sender, String[] args) throws CommandException {
        requirePermission(sender, "admintools.anticheat.report");
        if (args.length < 5) {
            throw new CommandException(plugin.getMessages().raw("generic.usage",
                    "usage", "/at acalert <jugador> <anticheat> <check> <vl> [detalles]"));
        }
        org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            // El staff no necesita un error por un jugador que ya se fue.
            return;
        }
        int vl;
        try {
            vl = Integer.parseInt(args[4]);
        } catch (NumberFormatException ex) {
            throw new CommandException(plugin.getMessages().raw("generic.invalid-number", "input", args[4]));
        }
        String details = args.length > 5 ? String.join(" ",
                java.util.Arrays.copyOfRange(args, 5, args.length)) : null;
        plugin.getIntegrations().anticheat().alert(target, args[2], args[3], vl, details);
    }

    private void purge(CommandSender sender) throws CommandException {
        requirePermission(sender, "admintools.admin");
        if (!plugin.getConfirmations().confirm(sender, "purge", "root.purge-confirm")) {
            return;
        }
        String summary = plugin.getRetentionService().purge();
        plugin.getMessages().sendRaw(sender, "investigate.check-line", "key", "Purga", "value", summary);
    }
}
