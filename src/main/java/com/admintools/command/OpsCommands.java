package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.AuditEntry;
import com.admintools.util.TimeUtil;
import com.admintools.util.Util;
import org.bukkit.Bukkit;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Operación del servidor: mantenimiento, reinicios, copias, auditoría y salud. */
public final class OpsCommands extends CommandSupport {

    public OpsCommands(AdminToolsPlugin plugin, CommandRegistry registry) {
        super(plugin);

        registry.register("maintenance")
                .permission("admintools.ops.maintenance")
                .usage("/maintenance [on|off|status]")
                .completer((sender, args) -> args.length <= 1
                        ? java.util.List.of("on", "off", "status") : java.util.List.of())
                .literals("on", "off", "status")
                .noAudit().run((sender, args) -> {
                    var service = plugin.getMaintenanceService();
                    if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
                        plugin.getMessages().send(sender, "ops.maintenance-status",
                                "state", service.enabled() ? "<red>activado" : "<green>desactivado");
                        return;
                    }
                    boolean enable = args[0].equalsIgnoreCase("on");
                    service.setEnabled(enable);
                    Bukkit.getServer().broadcast(plugin.getMessages().get(enable
                            ? "ops.maintenance-on" : "ops.maintenance-off"));
                    plugin.getAuditService().log(sender, "ops.maintenance", "-", enable ? "on" : "off");
                    if (enable) {
                        for (var online : Bukkit.getOnlinePlayers()) {
                            if (!online.hasPermission("admintools.ops.maintenance.bypass")) {
                                online.kick(plugin.getMessages().get("ops.maintenance-kick"));
                            }
                        }
                    }
                });

        registry.register("restart")
                .permission("admintools.ops.restart")
                .usage("/restart [cancel|<segundos>|now]")
                .completer((sender, args) -> args.length <= 1
                        ? java.util.List.of("cancel", "now", "60", "300", "900", "1800") : java.util.List.of())
                .literals("cancel", "now", "60", "300")
                .noAudit().run((sender, args) -> {
                    var service = plugin.getRestartService();
                    if (args.length == 0) {
                        if (service.scheduled()) {
                            long remaining = service.remainingMillis();
                            plugin.getMessages().send(sender, "ops.restart-pending",
                                    "time", TimeUtil.format(remaining),
                                    "remaining", TimeUtil.format(remaining));
                        } else {
                            Long next = service.nextAutomaticMillis();
                            if (next == null) {
                                plugin.getMessages().send(sender, "ops.restart-not-scheduled");
                            } else {
                                plugin.getMessages().send(sender, "ops.restart-next-auto",
                                        "time", TimeUtil.format(next));
                            }
                        }
                        return;
                    }
                    String input = args[0].toLowerCase(Locale.ROOT);
                    if (input.equals("cancel")) {
                        service.cancel();
                        plugin.getMessages().send(sender, "ops.restart-cancelled");
                        plugin.getAuditService().log(sender, "ops.restart", "-", "cancelado");
                        return;
                    }
                    if (input.equals("now")) {
                        plugin.getAuditService().log(sender, "ops.restart", "-", "inmediato");
                        service.executeNow();
                        return;
                    }
                    long seconds = parseLong(input);
                    service.scheduleIn(seconds);
                    plugin.getMessages().send(sender, "ops.restart-scheduled", "time",
                            TimeUtil.format(seconds * 1000L));
                    plugin.getAuditService().log(sender, "ops.restart", "-", "en " + seconds + "s");
                });

        registry.register("backup")
                .permission("admintools.ops.backup")
                .usage("/backup [list|now]")
                .completer((sender, args) -> args.length <= 1
                        ? java.util.List.of("list", "now") : java.util.List.of())
                .literals("list", "now")
                .noAudit().run((sender, args) -> {
                    var service = plugin.getBackupService();
                    if (args.length > 0 && args[0].equalsIgnoreCase("now")) {
                        plugin.getAuditService().log(sender, "ops.backup", "-", "manual");
                        service.runAsync(sender);
                        return;
                    }
                    List<File> files = service.list();
                    if (files.isEmpty()) {
                        plugin.getMessages().send(sender, "ops.backup-empty");
                        return;
                    }
                    plugin.getMessages().sendRaw(sender, "ops.backup-list-header", "count", files.size());
                    for (File file : files) {
                        plugin.getMessages().sendRaw(sender, "ops.backup-line",
                                "date", TimeUtil.formatDate(file.lastModified()),
                                "file", file.getName(),
                                "size", service.sizeText(file));
                    }
                });

        registry.register("audit")
                .permission("admintools.ops.audit")
                .usage("/audit [jugador] [página]")
                .completer((sender, args) -> CommandRegistry.players())
                .noAudit()
                .run((sender, args) -> {
                    int page = 1;
                    String filter = null;
                    for (String arg : args) {
                        if (arg.matches("\\d+")) {
                            page = Math.max(1, (int) parseLong(arg));
                        } else {
                            filter = arg;
                        }
                    }
                    int size = 10;
                    List<AuditEntry> entries;
                    int total;
                    if (filter == null) {
                        entries = plugin.getAuditService().page(page, size);
                        total = plugin.getAuditService().count();
                    } else {
                        entries = plugin.getAuditService().pageByTarget(filter, page, size);
                        total = plugin.getAuditService().countByTarget(filter);
                    }
                    if (entries.isEmpty()) {
                        plugin.getMessages().send(sender, "ops.audit-empty");
                        return;
                    }
                    int pages = Util.pageCount(total, size);
                    plugin.getMessages().sendRaw(sender, "ops.audit-header", "page", page, "pages", pages,
                            "total", total);
                    for (AuditEntry entry : entries) {
                        plugin.getMessages().sendRaw(sender, "ops.audit-line",
                                "date", TimeUtil.formatDate(entry.createdAt()),
                                "actor", entry.actorName() == null ? "CONSOLE" : entry.actorName(),
                                "action", entry.action(),
                                "target", com.admintools.util.Text.safe(entry.target()),
                                "details", com.admintools.util.Text.safe(
                                        entry.details() == null ? "-" : entry.details()));
                    }
                });

        registry.register("watchdog")
                .permission("admintools.ops.watchdog")
                .usage("/watchdog [status|test]")
                .completer((sender, args) -> args.length <= 1
                        ? java.util.List.of("status", "test") : java.util.List.of())
                .literals("status", "test")
                .noAudit()
                .run((sender, args) -> {
                    if (!plugin.getSettings().watchdogEnabled()) {
                        plugin.getMessages().send(sender, "ops.watchdog-disabled");
                        return;
                    }
                    if (args.length > 0 && args[0].equalsIgnoreCase("test")) {
                        plugin.getIntegrations().discordWatchdog("Prueba", "OK", "test", "Alerta de prueba");
                        plugin.getWatchdogService().alert("PRUEBA", "100%", "test", "Alerta de prueba");
                        plugin.getMessages().send(sender, "ops.watchdog-test-sent", "channels",
                                plugin.getIntegrations().discord().enabled() ? "staff + Discord" : "solo staff");
                        return;
                    }
                    Map<String, String> data = plugin.getWatchdogService().snapshot();
                    plugin.getMessages().sendRaw(sender, "ops.watchdog-status",
                            "tps", data.get("tps") + " " + plugin.getWatchdogService().state(
                                    plugin.getWatchdogService().tps(),
                                    plugin.getSettings().watchdogThresholds().tpsWarn(),
                                    plugin.getSettings().watchdogThresholds().tpsCritical(), true),
                            "mspt", data.get("mspt"),
                            "players", data.get("players"),
                            "memory", data.get("memory"),
                            "disk", data.get("disk"),
                            "chunks", data.get("chunks"));
                });
    }
}
