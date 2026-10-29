package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.Report;
import com.admintools.model.ReportStatus;
import com.admintools.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Reportes: el comando de los jugadores y la cola del staff. */
public final class ReportCommands extends CommandSupport {

    public ReportCommands(AdminToolsPlugin plugin, CommandRegistry registry) {
        super(plugin);

        registry.register("report")
                .permission("admintools.report")
                .usage("/report <jugador> <motivo>")
                .minArgs(2)
                .completer((sender, args) -> args.length <= 1
                        ? CommandRegistry.playerNames(args.length == 0 ? "" : args[0])
                        : List.of())
                .run((sender, args) -> {
                    Player reporter = requirePlayer(sender);
                    String name = args[0];
                    if (reporter.getName().equalsIgnoreCase(name)) {
                        throw new CommandException(plugin.getMessages().raw("reports.self"));
                    }
                    Optional<UUID> found = plugin.getPlayerService().requireUuid(name);
                    if (found.isEmpty()) {
                        throw new CommandException(plugin.getMessages().raw("generic.player-not-found", "player", name));
                    }
                    UUID targetUuid = found.get();
                    boolean online = Bukkit.getPlayer(targetUuid) != null;
                    if (plugin.getSettings().reportRequireOnline() && !online) {
                        throw new CommandException(plugin.getMessages().raw("reports.needs-online", "player", name));
                    }
                    if (!reporter.hasPermission("admintools.report.bypass-cooldown")) {
                        long remaining = plugin.getReportService().cooldownRemaining(reporter.getUniqueId());
                        if (remaining > 0L) {
                            plugin.getMessages().send(reporter, "reports.cooldown",
                                    "time", TimeUtil.format(remaining * 1000L));
                            return;
                        }
                    }
                    int open = plugin.getReportService().openBy(reporter.getUniqueId());
                    if (open >= plugin.getSettings().reportMaxOpen()) {
                        plugin.getMessages().send(reporter, "reports.limit", "count", open);
                        return;
                    }
                    String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                    Report report = plugin.getReportService().create(reporter,
                            targetUuid, plugin.getPlayerService().nameOf(targetUuid), reason);
                    plugin.getMessages().send(reporter, "reports.created", "id", report.id());
                    // Confirmación a los demás staff que no reciben la notificación global
                    for (Player staff : Bukkit.getOnlinePlayers()) {
                        if (staff.hasPermission("admintools.report.staff")
                                && !staff.hasPermission("admintools.report.manage") && !staff.equals(reporter)) {
                            staff.sendMessage(plugin.getMessages().get("reports.staff-notify",
                                    "id", report.id(), "reporter", report.reporterName(),
                                    "target", report.targetName(),
                                    "reason", com.admintools.util.Text.safe(report.reason())));
                        }
                    }
                });

        registry.register("reports")
                .permission("admintools.report.staff")
                .usage("/reports [claim|resolve|reject|info] [id] [nota]")
                .completer((sender, args) -> args.length <= 1
                        ? java.util.List.of("claim", "resolve", "reject", "info") : java.util.List.of())
                .literals("claim", "resolve", "reject", "info")
                .run((sender, args) -> {
                    if (args.length == 0) {
                        listQueue(sender, 1);
                        return;
                    }
                    String action = args[0].toLowerCase(Locale.ROOT);
                    switch (action) {
                        case "claim" -> claim(sender, args);
                        case "resolve" -> close(sender, args, ReportStatus.RESOLVED);
                        case "reject" -> close(sender, args, ReportStatus.REJECTED);
                        case "info" -> info(sender, args);
                        default -> {
                            try {
                                listQueue(sender, Math.max(1, Integer.parseInt(action)));
                            } catch (NumberFormatException ex) {
                                throw new CommandException(plugin.getMessages().raw("reports.usage"));
                            }
                        }
                    }
                });
    }

    private void listQueue(CommandSender sender, int page) throws CommandException {
        int size = 8;
        List<Report> queue = plugin.getReportService().queue(page, size);
        int total = plugin.getReportService().queueCount();
        if (queue.isEmpty()) {
            plugin.getMessages().send(sender, "reports.empty");
            return;
        }
        int pages = com.admintools.util.Util.pageCount(total, size);
        plugin.getMessages().sendRaw(sender, "reports.header", "page", page, "pages", pages, "open", total);
        for (Report report : queue) {
            plugin.getMessages().sendRaw(sender, "reports.line",
                    "id", report.id(),
                    "status", report.status().label(),
                    "status_color", report.status().color(),
                    "date", TimeUtil.formatDate(report.createdAt()),
                    "target", report.targetName(),
                    "reason", com.admintools.util.Text.safe(report.reason()),
                    "staff", report.claimedName() == null ? "-" : report.claimedName());
        }
        plugin.getMessages().sendRaw(sender, "reports.usage");
    }

    private Report require(CommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new CommandException(plugin.getMessages().raw("reports.usage"));
        }
        long id = parseLong(args[1]);
        Optional<Report> found = plugin.getReportService().byId(id);
        if (found.isEmpty()) {
            throw new CommandException(plugin.getMessages().raw("reports.not-found", "id", id));
        }
        return found.get();
    }

    private void claim(CommandSender sender, String[] args) throws CommandException {
        Player staff = requirePlayer(sender);
        if (!staff.hasPermission("admintools.report.claim")) {
            plugin.getMessages().send(sender, "generic.no-permission");
            return;
        }
        Report report = require(sender, args);
        if (report.claimedBy() != null && !report.claimedBy().equals(staff.getUniqueId().toString())) {
            throw new CommandException(plugin.getMessages().raw("reports.already-claimed",
                    "staff", report.claimedName()));
        }
        if (plugin.getReportService().claim(report, staff)) {
            plugin.getMessages().send(staff, "reports.claimed", "id", report.id(), "target", report.targetName());
        } else {
            throw new CommandException(plugin.getMessages().raw("reports.already-claimed",
                    "staff", report.claimedName() == null ? "otro staff" : report.claimedName()));
        }
    }

    private void close(CommandSender sender, String[] args, ReportStatus status) throws CommandException {
        Player staff = requirePlayer(sender);
        Report report = require(sender, args);
        String note = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : null;
        plugin.getReportService().close(report, status, staff, note);
        plugin.getMessages().send(staff, status == ReportStatus.RESOLVED ? "reports.resolved" : "reports.rejected",
                "id", report.id());
    }

    private void info(CommandSender sender, String[] args) throws CommandException {
        Report report = require(sender, args);
        plugin.getMessages().sendRaw(sender, "reports.info-header", "id", report.id());
        plugin.getMessages().sendRaw(sender, "sanctions.info-line", "key", "Estado", "value",
                report.status().label());
        plugin.getMessages().sendRaw(sender, "sanctions.info-line", "key", "Reporta", "value", report.reporterName());
        plugin.getMessages().sendRaw(sender, "sanctions.info-line", "key", "Reportado", "value", report.targetName());
        plugin.getMessages().sendRaw(sender, "sanctions.info-line", "key", "Motivo", "value",
                com.admintools.util.Text.safe(report.reason()));
        plugin.getMessages().sendRaw(sender, "sanctions.info-line", "key", "Fecha", "value",
                TimeUtil.formatDate(report.createdAt()));
        if (report.claimedName() != null) {
            plugin.getMessages().sendRaw(sender, "sanctions.info-line", "key", "Tomado por", "value",
                    report.claimedName());
        }
        if (report.resolvedName() != null) {
            plugin.getMessages().sendRaw(sender, "sanctions.info-line", "key", "Cerrado por", "value",
                    report.resolvedName());
        }
        if (report.resolution() != null) {
            plugin.getMessages().sendRaw(sender, "sanctions.info-line", "key", "Nota", "value",
                    com.admintools.util.Text.safe(report.resolution()));
        }
        plugin.getMessages().sendRaw(sender, "sanctions.info-line", "key", "Servidor", "value",
                report.origin() == null ? "-" : report.origin());
    }
}
