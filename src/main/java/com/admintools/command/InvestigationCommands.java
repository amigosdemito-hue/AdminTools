package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.AltAccount;
import com.admintools.model.Note;
import com.admintools.model.PlayerRecord;
import com.admintools.model.Report;
import com.admintools.model.Sanction;
import com.admintools.model.Snapshot;
import com.admintools.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Investigación: ficha del jugador, cuentas alternativas, expediente y copias de inventario. */
public final class InvestigationCommands extends CommandSupport {

    public InvestigationCommands(AdminToolsPlugin plugin, CommandRegistry registry) {
        super(plugin);

        registry.register("check")
                .permission("admintools.investigate.check")
                .usage("/check <jugador>")
                .minArgs(1)
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[0]))
                .noAudit()
                .run((sender, args) -> handleCheck(sender, args[0]));

        registry.register("alts")
                .permission("admintools.investigate.alts")
                .usage("/alts <jugador|ip> [ip]")
                .minArgs(1)
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[0]))
                .noAudit()
                .run((sender, args) -> {
                    boolean ipMode = args[0].equalsIgnoreCase("ip");
                    if (ipMode) {
                        if (args.length < 2) {
                            throw new CommandException(plugin.getMessages().raw("generic.usage",
                                    "usage", "/alts ip <ip>"));
                        }
                        requirePermission(sender, "admintools.investigate.alts.ip");
                        String ip = args[1];
                        List<AltAccount> accounts = plugin.getPlayerService().accountsOnIp(ip, null,
                                System.currentTimeMillis() - plugin.getSettings().ipRetentionDays() * 86_400_000L);
                        if (accounts.isEmpty()) {
                            plugin.getMessages().send(sender, "investigate.alts-empty", "player", ip);
                            return;
                        }
                        plugin.getMessages().sendRaw(sender, "investigate.alts-ip-header", "ip", ip, "count", accounts.size());
                        for (AltAccount account : accounts) {
                            renderAlt(sender, account, ip);
                        }
                        return;
                    }
                    UUID uuid = target(args[0]);
                    String display = targetName(args[0], uuid);
                    List<AltAccount> accounts = plugin.getPlayerService().alts(uuid);
                    if (accounts.isEmpty()) {
                        plugin.getMessages().send(sender, "investigate.alts-empty", "player", display);
                        return;
                    }
                    plugin.getMessages().sendRaw(sender, "investigate.alts-header", "player", display, "count", accounts.size());
                    for (AltAccount account : accounts) {
                        renderAlt(sender, account, account.ip());
                    }
                });

        registry.register("case")
                .permission("admintools.investigate.case")
                .usage("/case <jugador>")
                .minArgs(1)
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[0]))
                .noAudit()
                .run((sender, args) -> handleCase(sender, args[0]));

        registry.register("snapshot")
                .permission("admintools.investigate.snapshot")
                .usage("/snapshot <jugador> [save|list|view|restore] [id]")
                .completer((sender, args) -> args.length <= 1 ? CommandRegistry.players()
                        : java.util.List.of("save", "list", "view", "restore"))
                .minArgs(1)
                .literals("save", "list", "view", "restore")
                .run((sender, args) -> {
                    Optional<UUID> found = plugin.getPlayerService().requireUuid(args[0]);
                    if (found.isEmpty()) {
                        throw new CommandException(plugin.getMessages().raw("generic.player-not-found",
                                "player", args[0]));
                    }
                    UUID uuid = found.get();
                    String display = plugin.getPlayerService().nameOf(uuid);
                    String action = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "list";
                    switch (action) {
                        case "save" -> {
                            Player online = Bukkit.getPlayer(uuid);
                            if (online == null) {
                                throw new CommandException(plugin.getMessages().raw("reports.needs-online",
                                        "player", display));
                            }
                            long savedId = plugin.getSnapshotService().save(online, "Manual por " + staffName(sender));
                            if (savedId <= 0L) {
                                throw new CommandException(plugin.getMessages().raw("investigate.snapshot-no-data",
                                        "id", "-"));
                            }
                            plugin.getMessages().send(sender, "investigate.snapshot-saved", "player", display,
                                    "id", savedId);
                        }
                        case "view" -> {
                            if (args.length < 3) {
                                throw new CommandException(plugin.getMessages().raw("generic.usage",
                                        "usage", "/snapshot <jugador> view <id>"));
                            }
                            viewSnapshot(sender, parseLong(args[2]));
                        }
                        case "restore" -> {
                            requirePermission(sender, "admintools.investigate.restore");
                            if (args.length < 3) {
                                throw new CommandException(plugin.getMessages().raw("generic.usage",
                                        "usage", "/snapshot <jugador> restore <id>"));
                            }
                            long id = parseLong(args[2]);
                            if (!plugin.getConfirmations().confirm(sender, "restore:" + id,
                                    "investigate.restore-confirm", "player", display)) {
                                return;
                            }
                            restore(sender, uuid, display, id);
                        }
                        default -> listSnapshots(sender, uuid, display);
                    }
                });

        registry.register("restore")
                .permission("admintools.investigate.restore")
                .usage("/restore <jugador> <id>")
                .completer((sender, args) -> CommandRegistry.players())
                .minArgs(2)
                .noAudit().run((sender, args) -> {
                    UUID uuid = target(args[0]);
                    String display = targetName(args[0], uuid);
                    long id = parseLong(args[1]);
                    if (!plugin.getConfirmations().confirm(sender, "restore:" + id,
                            "investigate.restore-confirm", "player", display)) {
                        return;
                    }
                    restore(sender, uuid, display, id);
                });
    }

    // ------------------------------------------------------------
    //  /check
    // ------------------------------------------------------------

    private void handleCheck(CommandSender sender, String name) throws CommandException {
        UUID uuid = target(name);
        String display = targetName(name, uuid);
        PlayerRecord record = plugin.getPlayerService().get(uuid);
        boolean online = Bukkit.getPlayer(uuid) != null;

        plugin.getMessages().sendRaw(sender, "investigate.check-header", "player", display);
        line(sender, "Estado", online ? "<green>online" : "<gray>offline");
        line(sender, "Tiempo jugado", record.playtimeText().isEmpty() ? "0s" : record.playtimeText());
        line(sender, "Conexiones", String.valueOf(record.sessions()));
        line(sender, "Primera vez", TimeUtil.formatDate(record.firstSeen()));
        line(sender, "Última vez", record.lastSeen() <= 0L ? "-" : TimeUtil.formatDate(record.lastSeen())
                + " (" + TimeUtil.ago(record.lastSeen()) + ")");
        line(sender, "Sanciones totales", String.valueOf(plugin.getSanctionService().historyCount(uuid)));

        List<Sanction> active = plugin.getSanctionService().activeAll(uuid, display);
        line(sender, "Sanciones activas", active.isEmpty() ? "<green>ninguna" : String.valueOf(active.size()));
        for (Sanction sanction : active) {
            line(sender, "  #" + sanction.id(), sanction.type().display() + " · "
                    + com.admintools.util.Text.safe(sanction.reason())
                    + (sanction.permanent() ? "" : " · " + TimeUtil.remaining(sanction.expiresAt())));
        }
        line(sender, "Advertencias activas", String.valueOf(plugin.getSanctionService().warnCount(uuid, display)));
        var flag = plugin.getIntegrations().anticheat().lastAlert(uuid);
        line(sender, "Último flag", flag == null ? "<gray>ninguno"
                : flag.anticheat() + " · " + flag.check() + " (VL " + flag.violationLevel() + ") · "
                + TimeUtil.ago(flag.at()));
        line(sender, "Notas de staff", String.valueOf(plugin.getNoteService().count(uuid)));
        line(sender, "Reportes recibidos", String.valueOf(plugin.getReportService()
                .byTarget(uuid, 100).size()));

        List<AltAccount> alts = plugin.getPlayerService().alts(uuid);
        int shared = alts.size();
        line(sender, "Cuentas con IP compartida", String.valueOf(shared));
        if (sender.hasPermission("admintools.investigate.alts.ip")) {
            List<String> ips = plugin.getPlayerService().ips(uuid);
            line(sender, "IPs registradas", ips.isEmpty() ? "-" : String.join(", ", ips));
        } else {
            plugin.getMessages().sendRaw(sender, "investigate.alts-hidden");
        }

        if (plugin.getIntegrations().anticheat().any()) {
            plugin.getMessages().sendRaw(sender, "investigate.check-anticheat",
                    "list", plugin.getIntegrations().anticheat().installedText());
        } else {
            plugin.getMessages().sendRaw(sender, "investigate.check-no-anticheat");
        }
        if (plugin.getIntegrations().coreProtect().installed()) {
            line(sender, "CoreProtect", "<green>disponible</green> (/at inspect " + display + ")");
        }
        plugin.getMessages().sendRaw(sender, "investigate.check-actions", "player", display);
    }

    private void line(CommandSender sender, String key, String value) {
        plugin.getMessages().sendRaw(sender, "investigate.check-line", "key", key, "value", value);
    }

    // ------------------------------------------------------------
    //  /alts
    // ------------------------------------------------------------

    private void renderAlt(CommandSender sender, AltAccount account, String ip) {
        boolean showIp = sender.hasPermission("admintools.investigate.alts.ip");
        plugin.getMessages().sendRaw(sender, "investigate.alts-line",
                "name", account.name() == null ? "?" : account.name(),
                "state", account.online() ? "<green>online" : "<gray>offline",
                "ip", showIp ? (ip == null ? "-" : ip) : plugin.getMessages().raw("investigate.alts-hidden"),
                "last_seen", TimeUtil.formatDate(account.lastSeen()));
    }

    // ------------------------------------------------------------
    //  /case
    // ------------------------------------------------------------

    private void handleCase(CommandSender sender, String name) throws CommandException {
        UUID uuid = target(name);
        String display = targetName(name, uuid);
        PlayerRecord record = plugin.getPlayerService().get(uuid);

        plugin.getMessages().sendRaw(sender, "investigate.case-header", "player", display);
        section(sender, "Resumen");
        line(sender, "Tiempo jugado", record.playtimeText().isEmpty() ? "0s" : record.playtimeText());
        line(sender, "Última conexión", record.lastSeen() <= 0L ? "-" : TimeUtil.formatDate(record.lastSeen()));
        line(sender, "Sanciones", String.valueOf(plugin.getSanctionService().historyCount(uuid)));

        section(sender, "Últimas sanciones");
        List<Sanction> sanctions = plugin.getSanctionService().recent(uuid, 5);
        if (sanctions.isEmpty()) {
            empty(sender);
        }
        for (Sanction sanction : sanctions) {
            line(sender, "#" + sanction.id() + " " + sanction.type().display(),
                    TimeUtil.formatDate(sanction.createdAt()) + " · " + sanction.staffName() + " · "
                            + com.admintools.util.Text.safe(sanction.reason()));
        }

        section(sender, "Notas de staff");
        List<Note> notes = plugin.getNoteService().list(uuid);
        if (notes.isEmpty()) {
            empty(sender);
        }
        for (Note note : notes.subList(0, Math.min(5, notes.size()))) {
            line(sender, "#" + note.id(), TimeUtil.formatDate(note.createdAt()) + " · " + note.authorName()
                    + " · " + com.admintools.util.Text.safe(note.text()));
        }

        section(sender, "Reportes");
        List<Report> reports = plugin.getReportService().byTarget(uuid, 5);
        if (reports.isEmpty()) {
            empty(sender);
        }
        for (Report report : reports) {
            line(sender, "#" + report.id() + " " + report.status().label(),
                    report.reporterName() + " · " + TimeUtil.formatDate(report.createdAt()) + " · "
                            + com.admintools.util.Text.safe(report.reason()));
        }

        section(sender, "Últimos mensajes de chat");
        var messages = plugin.getChatService().history(uuid, 5);
        if (messages.isEmpty()) {
            empty(sender);
        }
        for (var message : messages) {
            line(sender, TimeUtil.formatDate(message.createdAt()),
                    com.admintools.util.Text.safe(message.message()));
        }

        section(sender, "Copias de inventario");
        List<Snapshot> snapshots = plugin.getSnapshotService().list(uuid,
                com.admintools.service.SnapshotService.KIND_INVENTORY, 5);
        if (snapshots.isEmpty()) {
            empty(sender);
        }
        for (Snapshot snapshot : snapshots) {
            line(sender, "#" + snapshot.id(), TimeUtil.formatDate(snapshot.createdAt()) + " · " + snapshot.reason());
        }

        section(sender, "Cuentas alternativas");
        List<AltAccount> alts = plugin.getPlayerService().alts(uuid);
        if (alts.isEmpty()) {
            empty(sender);
        }
        for (AltAccount alt : alts) {
            renderAlt(sender, alt, alt.ip());
        }

        plugin.getMessages().sendRaw(sender, "investigate.case-footer");
    }

    private void section(CommandSender sender, String title) {
        plugin.getMessages().sendRaw(sender, "investigate.case-section", "title", title);
    }

    private void empty(CommandSender sender) {
        plugin.getMessages().sendRaw(sender, "investigate.case-none");
    }

    // ------------------------------------------------------------
    //  Copias de inventario
    // ------------------------------------------------------------

    private void listSnapshots(CommandSender sender, UUID uuid, String display) {
        List<Snapshot> snapshots = plugin.getSnapshotService().list(uuid,
                com.admintools.service.SnapshotService.KIND_INVENTORY, 10);
        if (snapshots.isEmpty()) {
            plugin.getMessages().send(sender, "investigate.snapshot-empty", "player", display);
            return;
        }
        plugin.getMessages().sendRaw(sender, "investigate.snapshot-list-header", "player", display, "count", snapshots.size());
        for (Snapshot snapshot : snapshots) {
            plugin.getMessages().sendRaw(sender, "investigate.snapshot-line", "id", snapshot.id(),
                    "date", TimeUtil.formatDate(snapshot.createdAt()),
                    "reason", com.admintools.util.Text.safe(snapshot.reason()));
        }
    }

    private void viewSnapshot(CommandSender sender, long id) throws CommandException {
        Player viewer = requirePlayer(sender);
        Optional<Snapshot> snapshot = plugin.getSnapshotService().byId(id);
        if (snapshot.isEmpty()) {
            throw new CommandException(plugin.getMessages().raw("investigate.snapshot-not-found", "id", id));
        }
        var items = plugin.getSnapshotService().readInventory(id);
        if (items.length == 0) {
            throw new CommandException(plugin.getMessages().raw("investigate.snapshot-no-data", "id", id));
        }
        int size = Math.max(9, Math.min(54, ((items.length + 8) / 9) * 9));
        // Vista de solo lectura: es una copia, sacar ítems de aquí los duplicaría.
        var holder = new com.admintools.model.SnapshotView();
        var inventory = Bukkit.createInventory(holder, size,
                net.kyori.adventure.text.Component.text("Copia #" + id));
        holder.inventory(inventory);
        for (int i = 0; i < Math.min(items.length, inventory.getSize()); i++) {
            inventory.setItem(i, items[i]);
        }
        viewer.openInventory(inventory);
        plugin.getMessages().send(viewer, "investigate.snapshot-view", "id", id,
                "date", TimeUtil.formatDate(snapshot.get().createdAt()),
                "reason", com.admintools.util.Text.safe(snapshot.get().reason()));
    }

    private void restore(CommandSender sender, UUID uuid, String display, long id) throws CommandException {
        Optional<Snapshot> snapshot = plugin.getSnapshotService().byId(id);
        // La copia tiene que pertenecer al jugador indicado: si no, se restauraría
        // el inventario de otra persona.
        if (snapshot.isEmpty() || !snapshot.get().uuid().equals(uuid)) {
            throw new CommandException(plugin.getMessages().raw("investigate.snapshot-not-found", "id", id));
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online == null) {
            plugin.getMessages().send(sender, "investigate.snapshot-offline");
            return;
        }
        if (plugin.getSnapshotService().restore(online, id)) {
            plugin.getMessages().send(sender, "investigate.snapshot-restored", "id", id, "player", display);
            plugin.getAuditService().log(sender, "snapshot.restore", display, "Copia #" + id);
        } else {
            throw new CommandException(plugin.getMessages().raw("investigate.snapshot-no-data", "id", id));
        }
    }
}
