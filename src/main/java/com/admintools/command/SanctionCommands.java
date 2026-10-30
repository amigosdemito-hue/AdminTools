package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.LadderSpec;
import com.admintools.model.Sanction;
import com.admintools.model.TemplateSpec;
import com.admintools.service.SanctionService;
import com.admintools.util.TimeUtil;
import com.admintools.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Todos los comandos de sanciones: ban, mute, warn, kick, historial, notas, plantillas y escaleras. */
public final class SanctionCommands extends CommandSupport {

    private final SanctionService sanctions;

    public SanctionCommands(AdminToolsPlugin plugin, CommandRegistry registry) {
        super(plugin);
        this.sanctions = plugin.getSanctionService();

        registerBan(registry);
        registerMute(registry);
        registerWarnAndKick(registry);
        registerHistory(registry);
        registerNotes(registry);
        registerTemplates(registry);
        registerUnpunish(registry);
    }

    // Las utilidades comunes (resolver jugadores, motivos, exenciones, límites)
    // vienen heredadas de CommandSupport.

    /** Comprueba el límite de duración del rango y devuelve la duración a aplicar. */
    private long effectiveDuration(CommandSender sender, long requested) throws CommandException {
        if (sanctions.withinLimit(sender, requested)) {
            return requested;
        }
        long limit = sanctions.durationLimit(sender);
        if (limit <= 0L) {
            throw new CommandException(plugin.getMessages().raw(
                    requested == TimeUtil.PERMANENT ? "sanctions.limit-permanent" : "sanctions.limit",
                    "duration", sanctions.durationText(requested), "limit", sanctions.limitText(sender)));
        }
        return limit;
    }

    /** Error uniforme para una duración que no se pudo leer. */
    private CommandException invalidDuration(String input) {
        return new CommandException(plugin.getMessages().raw("generic.invalid-duration", "input", input));
    }

    // ------------------------------------------------------------
    //  Ban
    // ------------------------------------------------------------

    private void registerBan(CommandRegistry registry) {
        registry.register("ban")
                .permission("admintools.sanction.ban")
                .usage("/ban <jugador> [motivo]")
                .completer((sender, args) -> CommandRegistry.players())
                .minArgs(1)
                .literals("-s")
                .noAudit()
                .run((sender, args) -> {
                    Args stripped = strip(args);
                    args = stripped.values();
                    boolean silent = stripped.silent() || plugin.getSettings().silentByDefault();
                    String name = args[0];
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    checkExempt(sender, uuid, display);
                    if (sanctions.activeBan(uuid, display).isPresent()) {
                        throw new CommandException(plugin.getMessages().raw("sanctions.already-banned", "player", display,
                                "id", sanctions.activeBan(uuid, display).get().id()));
                    }
                    // Con duración opcional: /ban <jugador> [duración|motivo...]
                    long requested = TimeUtil.PERMANENT;
                    int reasonFrom = 1;
                    if (args.length > 1) {
                        Long parsed = TimeUtil.parse(args[1]);
                        if (parsed != null) {
                            requested = parsed;
                            reasonFrom = 2;
                        } else if (TimeUtil.looksLikeDuration(args[1])) {
                            // Un dedazo en la duración no debe convertirse en un ban permanente
                            // con "1d30" como motivo.
                            throw invalidDuration(args[1]);
                        }
                    }
                    long duration = effectiveDuration(sender, requested);
                    Sanction applied = sanctions.apply(new SanctionService.Request(
                            com.admintools.model.PunishmentType.BAN, uuid, display, ipIfKnown(uuid, name),
                            reason(args, reasonFrom), duration, staffUuid(sender), staffName(sender), silent, null, null));
                    confirmApplied(sender, applied, display, duration);
                });

        registry.register("tempban")
                .permission("admintools.sanction.ban")
                .usage("/tempban <jugador> <duración> [motivo]")
                .completer((sender, args) -> args.length <= 1 ? CommandRegistry.players() : CommandRegistry.durations())
                .minArgs(2)
                .literals("-s")
                .noAudit()
                .run((sender, args) -> {
                    Args stripped = strip(args);
                    args = stripped.values();
                    boolean silent = stripped.silent() || plugin.getSettings().silentByDefault();
                    String name = args[0];
                    Long parsed = TimeUtil.parse(args[1]);
                    if (parsed == null || parsed == TimeUtil.PERMANENT) {
                        throw new CommandException(plugin.getMessages().raw("generic.invalid-duration",
                                "input", args[1]));
                    }
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    checkExempt(sender, uuid, display);
                    long duration = effectiveDuration(sender, parsed);
                    SanctionService.Request request = new SanctionService.Request(
                            com.admintools.model.PunishmentType.BAN, uuid, display, ipIfKnown(uuid, name),
                            reason(args, 2), duration, staffUuid(sender), staffName(sender), silent, null, null);
                    Sanction applied = sanctions.apply(request);
                    confirmApplied(sender, applied, display, duration);
                });

        registry.register("ipban")
                .permission("admintools.sanction.ipban")
                .usage("/ipban <jugador|ip> [motivo]")
                .completer((sender, args) -> CommandRegistry.players())
                .minArgs(1)
                .literals("-s")
                .noAudit().run((sender, args) -> {
                    Args stripped = strip(args);
                    args = stripped.values();
                    boolean silent = stripped.silent() || plugin.getSettings().silentByDefault();
                    String input = args[0];
                    String ip;
                    UUID uuid;
                    String display;
                    Optional<UUID> found = plugin.getPlayerService().requireUuid(input);
                    if (found.isPresent()) {
                        uuid = found.get();
                        display = targetName(input, uuid);
                        checkExempt(sender, uuid, display);
                        ip = sanctions.ipOf(uuid);
                        if (ip == null) {
                            throw new CommandException(plugin.getMessages().raw("sanctions.ip-help"));
                        }
                    } else if (input.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                        ip = input;
                        uuid = null;
                        display = input;
                    } else if (Util.isPlayerName(input)) {
                        // Parece un nombre de jugador que no conocemos en este servidor.
                        throw new CommandException(plugin.getMessages().raw("generic.player-not-found",
                                "player", input));
                    } else {
                        throw new CommandException(plugin.getMessages().raw("sanctions.ip-help"));
                    }
                    long duration = effectiveDuration(sender, TimeUtil.PERMANENT);
                    SanctionService.Request request = new SanctionService.Request(
                            com.admintools.model.PunishmentType.IP_BAN, uuid == null ? new UUID(0L, 0L) : uuid, display,
                            ip, reason(args, 1), duration, staffUuid(sender), staffName(sender), silent, null, null);
                    Sanction applied = sanctions.apply(request);
                    confirmApplied(sender, applied, display, duration);
                });

        registry.register("unban")
                .permission("admintools.sanction.unban")
                .usage("/unban <jugador|ip> [motivo]")
                .completer((sender, args) -> CommandRegistry.players())
                .minArgs(1)
                .noAudit().run((sender, args) -> {
                    String name = args[0];
                    // Un IP-ban guardado con /ipban <ip> vive sobre la IP y con UUID nulo, así
                    // que se levanta por dirección y no buscando un jugador.
                    if (name.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                        int lifted = sanctions.liftIpBan(name, sender, reason(args, 1));
                        if (lifted == 0) {
                            throw new CommandException(plugin.getMessages().raw("sanctions.not-banned", "player", name));
                        }
                        plugin.getMessages().sendCount(sender, "unpunish.player-done", lifted, "count", lifted, "player", name);
                        return;
                    }
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    int count = sanctions.lift(uuid, display, sender, reason(args, 1),
                            com.admintools.model.PunishmentType.BAN, com.admintools.model.PunishmentType.IP_BAN);
                    if (count == 0) {
                        throw new CommandException(plugin.getMessages().raw("sanctions.not-banned", "player", display));
                    }
                    plugin.getMessages().sendCount(sender, "unpunish.player-done", count, "count", count, "player", display);
                });
    }

    // ------------------------------------------------------------
    //  Mute
    // ------------------------------------------------------------

    private void registerMute(CommandRegistry registry) {
        registry.register("mute")
                .permission("admintools.sanction.mute")
                .usage("/mute <jugador> [motivo]")
                .completer((sender, args) -> CommandRegistry.players())
                .minArgs(1)
                .literals("-s")
                .noAudit().run((sender, args) -> {
                    Args stripped = strip(args);
                    args = stripped.values();
                    boolean silent = stripped.silent() || plugin.getSettings().silentByDefault();
                    String name = args[0];
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    checkExempt(sender, uuid, display);
                    if (sanctions.activeMute(uuid, display).isPresent()) {
                        throw new CommandException(plugin.getMessages().raw("sanctions.already-muted", "player", display,
                                "id", sanctions.activeMute(uuid, display).get().id()));
                    }
                    long duration = effectiveDuration(sender, TimeUtil.PERMANENT);
                    SanctionService.Request request = new SanctionService.Request(
                            com.admintools.model.PunishmentType.MUTE, uuid, display, null, reason(args, 1), duration,
                            staffUuid(sender), staffName(sender), silent, null, null);
                    Sanction applied = sanctions.apply(request);
                    confirmApplied(sender, applied, display, duration);
                });

        registry.register("tempmute")
                .permission("admintools.sanction.mute")
                .usage("/tempmute <jugador> <duración> [motivo]")
                .completer((sender, args) -> args.length <= 1 ? CommandRegistry.players() : CommandRegistry.durations())
                .minArgs(2)
                .literals("-s")
                .noAudit().run((sender, args) -> {
                    Args stripped = strip(args);
                    args = stripped.values();
                    boolean silent = stripped.silent() || plugin.getSettings().silentByDefault();
                    Long parsed = TimeUtil.parse(args[1]);
                    if (parsed == null || parsed == TimeUtil.PERMANENT) {
                        throw new CommandException(plugin.getMessages().raw("generic.invalid-duration",
                                "input", args[1]));
                    }
                    String name = args[0];
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    checkExempt(sender, uuid, display);
                    long duration = effectiveDuration(sender, parsed);
                    Sanction applied = sanctions.apply(new SanctionService.Request(
                            com.admintools.model.PunishmentType.MUTE, uuid, display, null, reason(args, 2), duration,
                            staffUuid(sender), staffName(sender), silent, null, null));
                    confirmApplied(sender, applied, display, duration);
                });

        registry.register("shadowmute")
                .permission("admintools.sanction.shadowmute")
                .usage("/shadowmute <jugador> [duración] [motivo]")
                .completer((sender, args) -> args.length <= 1 ? CommandRegistry.players() : CommandRegistry.durations())
                .minArgs(1)
                .noAudit().run((sender, args) -> {
                    String name = args[0];
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    checkExempt(sender, uuid, display);
                    long duration = TimeUtil.PERMANENT;
                    int reasonFrom = 1;
                    if (args.length > 1) {
                        Long parsed = TimeUtil.parse(args[1]);
                        if (parsed != null) {
                            duration = effectiveDuration(sender, parsed);
                            reasonFrom = 2;
                        } else if (TimeUtil.looksLikeDuration(args[1])) {
                            throw invalidDuration(args[1]);
                        }
                    }
                    Sanction applied = sanctions.apply(new SanctionService.Request(
                            com.admintools.model.PunishmentType.SHADOW_MUTE, uuid, display, null, reason(args, reasonFrom),
                            duration, staffUuid(sender), staffName(sender), true, null, null));
                    plugin.getMessages().send(sender, "sanctions.applied-silent", "id", applied.id(), "player", display);
                });

        registry.register("ipmute")
                .permission("admintools.sanction.ipmute")
                .usage("/ipmute <jugador|ip> [duración] [motivo]")
                .completer((sender, args) -> args.length <= 1
                        ? CommandRegistry.players() : CommandRegistry.durations())
                .minArgs(1)
                .literals("-s")
                .noAudit().run((sender, args) -> {
                    Args stripped = strip(args);
                    args = stripped.values();
                    boolean silent = stripped.silent() || plugin.getSettings().silentByDefault();
                    String input = args[0];
                    String ip;
                    UUID uuid = null;
                    String display;
                    Optional<UUID> found = plugin.getPlayerService().requireUuid(input);
                    if (found.isPresent()) {
                        uuid = found.get();
                        display = targetName(input, uuid);
                        checkExempt(sender, uuid, display);
                        ip = sanctions.ipOf(uuid);
                        if (ip == null) {
                            throw new CommandException(plugin.getMessages().raw("sanctions.ip-help"));
                        }
                    } else if (input.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                        ip = input;
                        display = input;
                    } else if (Util.isPlayerName(input)) {
                        throw new CommandException(plugin.getMessages().raw("generic.player-not-found",
                                "player", input));
                    } else {
                        throw new CommandException(plugin.getMessages().raw("sanctions.ip-help"));
                    }
                    long duration = TimeUtil.PERMANENT;
                    int reasonFrom = 1;
                    if (args.length > 1) {
                        Long parsed = TimeUtil.parse(args[1]);
                        if (parsed != null) {
                            duration = effectiveDuration(sender, parsed);
                            reasonFrom = 2;
                        } else if (TimeUtil.looksLikeDuration(args[1])) {
                            throw invalidDuration(args[1]);
                        }
                    }
                    Sanction applied = sanctions.apply(new SanctionService.Request(
                            com.admintools.model.PunishmentType.IP_MUTE,
                            uuid == null ? new UUID(0L, 0L) : uuid, display, ip, reason(args, reasonFrom),
                            duration, staffUuid(sender), staffName(sender), silent, null, null));
                    confirmApplied(sender, applied, display, duration);
                });

        registry.register("unmute")
                .permission("admintools.sanction.unmute")
                .usage("/unmute <jugador|ip> [motivo]")
                .completer((sender, args) -> CommandRegistry.players())
                .minArgs(1)
                .noAudit().run((sender, args) -> {
                    String name = args[0];
                    if (name.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                        int lifted = sanctions.liftIp(name, sender, reason(args, 1));
                        if (lifted == 0) {
                            throw new CommandException(plugin.getMessages().raw("sanctions.not-muted", "player", name));
                        }
                        plugin.getMessages().sendCount(sender, "unpunish.player-done", lifted, "count", lifted, "player", name);
                        return;
                    }
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    int count = sanctions.lift(uuid, display, sender, reason(args, 1),
                            com.admintools.model.PunishmentType.MUTE, com.admintools.model.PunishmentType.SHADOW_MUTE);
                    // Si además tenía un IP-mute (o lo heredó por su conexión), también se levanta.
                    String knownIp = sanctions.ipOf(uuid);
                    if (knownIp != null) {
                        count += sanctions.liftIp(knownIp, sender, reason(args, 1));
                    }
                    if (count == 0) {
                        throw new CommandException(plugin.getMessages().raw("sanctions.not-muted", "player", display));
                    }
                    plugin.getMessages().sendCount(sender, "unpunish.player-done", count, "count", count, "player", display);
                });
    }

    // ------------------------------------------------------------
    //  Warn y kick
    // ------------------------------------------------------------

    private void registerWarnAndKick(CommandRegistry registry) {
        registry.register("warn")
                .permission("admintools.sanction.warn")
                .usage("/warn <jugador> [motivo]")
                .completer((sender, args) -> CommandRegistry.players())
                .minArgs(1)
                .literals("-s")
                .noAudit().run((sender, args) -> {
                    Args stripped = strip(args);
                    args = stripped.values();
                    boolean silent = stripped.silent() || plugin.getSettings().silentByDefault();
                    String name = args[0];
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    checkExempt(sender, uuid, display);
                    Sanction applied = sanctions.apply(new SanctionService.Request(
                            com.admintools.model.PunishmentType.WARN, uuid, display, null, reason(args, 1),
                            TimeUtil.PERMANENT, staffUuid(sender), staffName(sender), silent, null, null));
                    confirmApplied(sender, applied, display, 0L);
                });

        registry.register("tempwarn")
                .permission("admintools.sanction.warn")
                .usage("/tempwarn <jugador> <duración> [motivo]")
                .completer((sender, args) -> args.length <= 1 ? CommandRegistry.players() : CommandRegistry.durations())
                .minArgs(2)
                .literals("-s")
                .noAudit().run((sender, args) -> {
                    Args stripped = strip(args);
                    args = stripped.values();
                    boolean silent = stripped.silent() || plugin.getSettings().silentByDefault();
                    Long parsed = TimeUtil.parse(args[1]);
                    if (parsed == null || parsed == TimeUtil.PERMANENT) {
                        throw new CommandException(plugin.getMessages().raw("generic.invalid-duration",
                                "input", args[1]));
                    }
                    String name = args[0];
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    checkExempt(sender, uuid, display);
                    long duration = effectiveDuration(sender, parsed);
                    Sanction applied = sanctions.apply(new SanctionService.Request(
                            com.admintools.model.PunishmentType.WARN, uuid, display, null, reason(args, 2),
                            duration, staffUuid(sender), staffName(sender), silent, null, null));
                    confirmApplied(sender, applied, display, duration);
                });

        registry.register("kick")
                .permission("admintools.sanction.kick")
                .usage("/kick <jugador> [motivo]")
                .completer((sender, args) -> CommandRegistry.players())
                .minArgs(1)
                .literals("-s")
                .noAudit().run((sender, args) -> {
                    Args stripped = strip(args);
                    args = stripped.values();
                    boolean silent = stripped.silent() || plugin.getSettings().silentByDefault();
                    String name = args[0];
                    UUID uuid = target(name);
                    Player online = Bukkit.getPlayer(uuid);
                    if (online == null) {
                        throw new CommandException(plugin.getMessages().raw("reports.needs-online", "player", name));
                    }
                    checkExempt(sender, uuid, online.getName());
                    Sanction applied = sanctions.apply(new SanctionService.Request(
                            com.admintools.model.PunishmentType.KICK, uuid, online.getName(), null, reason(args, 1),
                            TimeUtil.PERMANENT, staffUuid(sender), staffName(sender), silent, null, null));
                    confirmApplied(sender, applied, online.getName(), 0L);
                });
    }

    // ------------------------------------------------------------
    //  Historial y notas
    // ------------------------------------------------------------

    private void registerHistory(CommandRegistry registry) {
        registry.register("history")
                .permission("admintools.sanction.history")
                .usage("/history <jugador> [página]")
                .completer((sender, args) -> args.length <= 1 ? CommandRegistry.players() : List.of())
                .minArgs(1)
                .run((sender, args) -> {
                    String name = args[0];
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    int page = args.length > 1 ? parsePage(args[1]) : 1;
                    int size = 8;
                    List<Sanction> entries = sanctions.history(uuid, page, size);
                    int total = sanctions.historyCount(uuid);
                    if (total == 0) {
                        plugin.getMessages().send(sender, "history.empty", "player", display);
                        return;
                    }
                    plugin.getMessages().sendRawCount(sender, "history.header", total, "player", display, "total", total);
                    for (Sanction sanction : entries) {
                        plugin.getMessages().sendRaw(sender, "history.line",
                                "id", sanction.id(),
                                "date", TimeUtil.formatDate(sanction.createdAt()),
                                "type", sanction.type().display(),
                                "staff", sanction.staffName(),
                                "reason", com.admintools.util.Text.safe(sanction.reason()),
                                "status", statusText(sanction));
                    }
                    int pages = com.admintools.util.Util.pageCount(total, size);
                    if (pages > 1) {
                        plugin.getMessages().sendRaw(sender, "history.footer", "page", page, "pages", pages,
                                "player", display, "next", Math.min(page + 1, pages));
                    }
                    int warns = sanctions.warnCount(uuid, display);
                    plugin.getMessages().sendRawCount(sender, "sanctions.warns-count", warns,
                            "player", display, "count", warns);
                });
    }

    /** Estado legible de una sanción, con su color. */
    private String statusText(Sanction sanction) {
        if (sanction.type() == com.admintools.model.PunishmentType.KICK) {
            return plugin.getMessages().raw("sanctions.status-kick");
        }
        // La expiración va antes que "inactiva": expireDue cierra la sanción en la base
        // y, si se comprobaba después, una temporal vencida aparecía como "levantada por
        // Expiración automática" en vez de "expirada".
        if (sanction.expired()) {
            return plugin.getMessages().raw("sanctions.status-expired");
        }
        if (!sanction.active()) {
            return sanction.liftedName() == null ? plugin.getMessages().raw("sanctions.status-closed")
                    : plugin.getMessages().raw("sanctions.status-lifted", "staff", sanction.liftedName());
        }
        if (sanction.permanent()) {
            return plugin.getMessages().raw("sanctions.status-active-permanent");
        }
        return plugin.getMessages().raw("sanctions.status-active",
                "time", TimeUtil.remaining(sanction.expiresAt()));
    }

    private void registerNotes(CommandRegistry registry) {
        registry.register("notes")
                .permission("admintools.sanction.notes")
                .usage("/notes <jugador|add|remove> [texto|id]")
                .completer((sender, args) -> args.length <= 1 ? java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("add", "remove"), CommandRegistry.players().stream()).toList()
                        : CommandRegistry.players())
                .minArgs(1)
                .literals("add", "remove")
                .noAudit().run((sender, args) -> {
                    String first = args[0].toLowerCase(Locale.ROOT);
                    if (first.equals("add")) {
                        if (!sender.hasPermission("admintools.sanction.notes.add")) {
                            plugin.getMessages().send(sender, "generic.no-permission");
                            return;
                        }
                        if (args.length < 3) {
                            throw new CommandException(plugin.getMessages().raw("notes.usage"));
                        }
                        String name = args[1];
                        UUID uuid = target(name);
                        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                        long id = plugin.getNoteService().add(sender, uuid, targetName(name, uuid), text);
                        plugin.getMessages().send(sender, "notes.added", "id", id, "player", targetName(name, uuid));
                        return;
                    }
                    if (first.equals("remove")) {
                        if (!sender.hasPermission("admintools.sanction.notes.add")) {
                            plugin.getMessages().send(sender, "generic.no-permission");
                            return;
                        }
                        if (args.length < 2) {
                            throw new CommandException(plugin.getMessages().raw("notes.usage"));
                        }
                        long id = parseLong(args[1]);
                        if (!plugin.getNoteService().remove(id, sender)) {
                            throw new CommandException(plugin.getMessages().raw("notes.not-found", "id", id));
                        }
                        plugin.getMessages().send(sender, "notes.removed", "id", id);
                        return;
                    }
                    String name = args[0];
                    UUID uuid = target(name);
                    String display = targetName(name, uuid);
                    var notes = plugin.getNoteService().list(uuid);
                    if (notes.isEmpty()) {
                        plugin.getMessages().send(sender, "notes.empty", "player", display);
                        return;
                    }
                    plugin.getMessages().sendRaw(sender, "notes.header", "player", display, "total", notes.size());
                    for (var note : notes) {
                        plugin.getMessages().sendRaw(sender, "notes.line",
                                "id", note.id(), "date", TimeUtil.formatDate(note.createdAt()),
                                "staff", note.authorName(), "text", com.admintools.util.Text.safe(note.text()));
                    }
                });
    }

    // ------------------------------------------------------------
    //  Plantillas, escaleras y reversión
    // ------------------------------------------------------------

    private void registerTemplates(CommandRegistry registry) {
        registry.register("punish")
                .permission("admintools.sanction.templates")
                .usage("/punish <plantilla> <jugador> [motivo]")
                .minArgs(2)
                .completer((sender, args) -> args.length <= 1
                        ? List.copyOf(plugin.getSettings().templates().keySet())
                        : CommandRegistry.playerNames(args[args.length - 1]))
                .noAudit().run((sender, args) -> {
                    String templateKey = args[0].toLowerCase(Locale.ROOT);
                    TemplateSpec template = plugin.getSettings().templates().get(templateKey);
                    if (template == null) {
                        throw new CommandException(plugin.getMessages().raw("template.unknown", "template", templateKey,
                                "available", String.join(", ", plugin.getSettings().templates().keySet())));
                    }
                    String name = args[1];
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    checkExempt(sender, uuid, display);
                    long duration = effectiveDuration(sender, template.duration());
                    String reason = args.length > 2 ? reason(args, 2) : template.reason();
                    Sanction applied = sanctions.apply(new SanctionService.Request(template.type(), uuid, display,
                            ipIfKnown(uuid, name), reason, duration, staffUuid(sender), staffName(sender),
                            plugin.getSettings().silentByDefault(), null, template.name()));
                    plugin.getMessages().send(sender, "template.applied", "template", template.name(), "player", display,
                            "id", applied.id());
                });

        registry.register("ladder")
                .permission("admintools.sanction.ladder")
                .usage("/ladder <escalera> <jugador> [motivo]")
                .minArgs(2)
                .completer((sender, args) -> args.length <= 1
                        ? List.copyOf(plugin.getSettings().ladders().keySet())
                        : CommandRegistry.playerNames(args[args.length - 1]))
                .noAudit().run((sender, args) -> {
                    String ladderKey = args[0].toLowerCase(Locale.ROOT);
                    LadderSpec ladder = plugin.getSettings().ladders().get(ladderKey);
                    if (ladder == null) {
                        throw new CommandException(plugin.getMessages().raw("ladder.unknown", "ladder", ladderKey,
                                "available", String.join(", ", plugin.getSettings().ladders().keySet())));
                    }
                    String name = args[1];
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    checkExempt(sender, uuid, display);
                    long window = ladder.windowDays() * 86_400_000L;
                    int previous = sanctions.countAllWithin(uuid, System.currentTimeMillis() - window);
                    var step = ladder.stepFor(previous);
                    long duration = effectiveDuration(sender, step.duration());
                    String reason = args.length > 2 ? reason(args, 2) : step.reason();
                    Sanction applied = sanctions.apply(new SanctionService.Request(step.type(), uuid, display,
                            ipIfKnown(uuid, name), reason, duration, staffUuid(sender), staffName(sender),
                            plugin.getSettings().silentByDefault(), ladder.name(), null));
                    plugin.getMessages().send(sender, "ladder.applied", "ladder", ladder.name(),
                            "step", ladder.stepNumberFor(previous), "total", ladder.steps().size(),
                            "player", display, "id", applied.id());
                });
    }

    private void registerUnpunish(CommandRegistry registry) {
        registry.register("unpunish")
                .permission("admintools.sanction.rollback")
                .usage("/unpunish <jugador|staff> <nombre> [motivo]")
                .completer((sender, args) -> args.length <= 1 ? List.of("jugador", "staff") : CommandRegistry.players())
                .minArgs(2)
                .literals("jugador", "staff")
                .noAudit().run((sender, args) -> {
                    String mode = args[0].toLowerCase(Locale.ROOT);
                    if (mode.equals("staff")) {
                        String staffName = args[1];
                        if (!plugin.getConfirmations().confirm(sender, "unpunish-staff",
                                "unpunish.staff-confirm", "staff", staffName)) {
                            return;
                        }
                        int count = sanctions.liftByStaff(staffName, sender, reason(args, 2));
                        if (count == 0) {
                            plugin.getMessages().send(sender, "unpunish.none");
                            return;
                        }
                        plugin.getMessages().sendCount(sender, "unpunish.staff-done", count, "count", count, "staff", staffName);
                        plugin.getLogger().warning(sender.getName() + " revirtió " + count
                                + " sanciones aplicadas por " + staffName);
                        return;
                    }
                    String name = mode.equals("jugador") ? args[1] : args[0];
                    int reasonFrom = mode.equals("jugador") ? 2 : 1;
                    UUID uuid = sanctionTarget(name);
                    String display = targetName(name, uuid);
                    int count = sanctions.lift(uuid, display, sender, reason(args, reasonFrom));
                    if (count == 0) {
                        plugin.getMessages().send(sender, "unpunish.none");
                        return;
                    }
                    plugin.getMessages().sendCount(sender, "unpunish.player-done", count, "count", count, "player", display);
                });
    }

    // ------------------------------------------------------------
    //  Helpers comunes a varios comandos
    // ------------------------------------------------------------

    /** Argumentos ya limpios, con la marca de "silencioso" aparte. */
    record Args(boolean silent, String[] values) {
    }

    /** Extrae el flag -s (silencioso) de los argumentos, en cualquier posición. */
    static Args strip(String[] input) {
        boolean silent = false;
        List<String> values = new java.util.ArrayList<>();
        for (String value : input) {
            if (value.equalsIgnoreCase("-s") || value.equalsIgnoreCase("--silent")) {
                silent = true;
                continue;
            }
            values.add(value);
        }
        return new Args(silent, values.toArray(new String[0]));
    }

}
