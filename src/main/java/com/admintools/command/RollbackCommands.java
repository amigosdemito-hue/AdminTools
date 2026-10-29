package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.InventoryBackup;
import com.admintools.service.InventoryBackupService;
import com.admintools.service.InventoryBackupService.Part;
import com.admintools.util.Text;
import com.admintools.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Rollback de inventarios: menús de jugadores, de copias y de contenido (con
 * enderchest, armadura y mano secundaria), restauración selectiva y borrado.
 *
 * <p>Desde el juego todo pasa por los menús de {@link com.admintools.gui.RollbackGui};
 * desde consola se mantiene el listado de texto y la restauración con
 * {@code confirm}. El identificador de copia se escribe con {@code #} para no
 * confundirlo con el número de página: {@code /rollback Ana 2} es la página 2 y
 * {@code /rollback Ana #2} es la copia 2.</p>
 */
public final class RollbackCommands extends CommandSupport {

    private final InventoryBackupService service;

    public RollbackCommands(AdminToolsPlugin plugin, CommandRegistry registry) {
        super(plugin);
        this.service = plugin.getInventoryBackups();
        register(registry);
    }

    private void register(CommandRegistry registry) {
        registry.register("rollback")
                .permission("admintools.rollback.view")
                .usage("/rollback [jugador [página|#id [confirm|delete [partes]]]] | save <jugador> | purge <días>")
                .completer((sender, args) -> {
                    if (args.length <= 1) {
                        List<String> names = new ArrayList<>(List.of("save", "purge"));
                        names.addAll(CommandRegistry.players());
                        return names;
                    }
                    String first = args[0].toLowerCase(Locale.ROOT);
                    if (first.equals("save")) {
                        return CommandRegistry.players();
                    }
                    if (first.equals("purge")) {
                        return List.of();
                    }
                    if (args.length == 2) {
                        // Las copias recientes del jugador, listas para escribir #id.
                        Optional<UUID> uuid = plugin.getPlayerService().requireUuid(args[0]);
                        List<String> ids = new ArrayList<>();
                        uuid.ifPresent(value -> {
                            for (InventoryBackup backup : service.list(value, 1, 9)) {
                                ids.add("#" + backup.id());
                            }
                        });
                        return ids;
                    }
                    if (args.length == 3 && args[1].startsWith("#")) {
                        return List.of("confirm", "delete");
                    }
                    return List.of("items", "ender", "position", "stats", "all");
                })
                .noAudit()
                .run((sender, args) -> {
                    if (args.length == 0) {
                        Player viewer = requirePlayer(sender);
                        if (!plugin.getSettings().rollbackEnabled()) {
                            throw new CommandException(plugin.getMessages().raw("rollback.disabled"));
                        }
                        plugin.getRollbackGui().openPlayers(viewer, 1);
                        return;
                    }
                    String first = args[0].toLowerCase(Locale.ROOT);
                    if (first.equals("save")) {
                        save(sender, args);
                        return;
                    }
                    if (first.equals("purge")) {
                        purge(sender, args);
                        return;
                    }
                    if (!plugin.getSettings().rollbackEnabled()) {
                        throw new CommandException(plugin.getMessages().raw("rollback.disabled"));
                    }
                    UUID uuid = target(args[0]);
                    String display = targetName(args[0], uuid);
                    if (args.length < 2 || !args[1].startsWith("#")) {
                        int page = args.length >= 2 ? parsePage(args[1]) : 1;
                        if (sender instanceof Player viewer) {
                            plugin.getRollbackGui().openBackups(viewer, uuid, display, page);
                        } else {
                            list(sender, uuid, display, page);
                        }
                        return;
                    }
                    long id = parseCase(args[1].substring(1));
                    Optional<InventoryBackup> found = service.byId(id);
                    if (found.isEmpty() || !found.get().uuid().equals(uuid)) {
                        throw new CommandException(plugin.getMessages().raw("rollback.not-found",
                                "id", id, "player", display));
                    }
                    InventoryBackup backup = found.get();
                    if (args.length >= 3 && args[2].equalsIgnoreCase("delete")) {
                        requirePermission(sender, "admintools.rollback.restore");
                        if (!plugin.getConfirmations().confirm(sender, "rollback-delete:" + id,
                                "rollback.menu-confirm-delete", "player", display, "id", id)) {
                            return;
                        }
                        service.delete(id);
                        plugin.getMessages().send(sender, "rollback.menu-deleted", "id", id, "player", display);
                        plugin.getAuditService().log(sender, "rollback.delete", display, "#" + id);
                        return;
                    }
                    boolean confirm = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
                    if (!confirm) {
                        if (sender instanceof Player viewer) {
                            plugin.getRollbackGui().openPreview(viewer, backup, display, 1);
                        } else {
                            // La consola no puede ver un menú: resumen de la copia.
                            plugin.getMessages().sendRaw(sender, "rollback.line",
                                    "id", backup.id(),
                                    "date", TimeUtil.formatDate(backup.createdAt()),
                                    "reason", plugin.getMessages().raw("rollback.reason-"
                                            + backup.reason().toLowerCase(Locale.ROOT)),
                                    "items", service.itemCount(backup),
                                    "size", backup.sizeText(),
                                    "world", backup.world() == null ? "-" : backup.world());
                            plugin.getMessages().send(sender, "rollback.preview-hint",
                                    "player", display, "id", backup.id());
                        }
                        return;
                    }
                    requirePermission(sender, "admintools.rollback.restore");
                    Set<Part> parts = parts(args, 3);
                    String partsText = partsText(parts);
                    if (!plugin.getConfirmations().confirm(sender,
                            "rollback:" + uuid + ":" + id + ":" + parts,
                            "rollback.confirm", "player", display, "id", id, "parts", partsText)) {
                        return;
                    }
                    Player online = Bukkit.getPlayer(uuid);
                    if (online == null) {
                        throw new CommandException(plugin.getMessages().raw("rollback.offline",
                                "player", display));
                    }
                    if (!service.restore(online, backup, parts)) {
                        throw new CommandException(plugin.getMessages().raw("rollback.failed", "id", id));
                    }
                    plugin.getMessages().send(sender, "rollback.restored", "id", id,
                            "player", display, "parts", partsText);
                    plugin.getAuditService().log(sender, "rollback.restore", display,
                            "#" + id + " (" + partsText + ")");
                });
    }

    // ------------------------------------------------------------
    //  Listado (consola)
    // ------------------------------------------------------------

    private void list(CommandSender sender, UUID uuid, String display, int page) {
        int total = service.count(uuid);
        if (total == 0) {
            plugin.getMessages().send(sender, "rollback.empty", "player", display);
            return;
        }
        int size = plugin.getSettings().rollbackPageSize();
        int pages = Math.max(1, (total + size - 1) / size);
        int current = Math.min(Math.max(1, page), pages);
        List<InventoryBackup> backups = service.list(uuid, current, size);
        plugin.getMessages().sendRawCount(sender, "rollback.header", total, "player", display, "total", total);
        for (InventoryBackup backup : backups) {
            plugin.getMessages().sendRaw(sender, "rollback.line",
                    "id", backup.id(),
                    "date", TimeUtil.formatDate(backup.createdAt()),
                    "reason", plugin.getMessages().raw("rollback.reason-" + backup.reason().toLowerCase(Locale.ROOT)),
                    "items", service.itemCount(backup),
                    "size", backup.sizeText(),
                    "world", backup.world() == null ? "-" : backup.world());
        }
        plugin.getMessages().sendRaw(sender, "rollback.footer",
                "page", current, "pages", pages, "player", display, "next", Math.min(pages, current + 1));
    }

    // ------------------------------------------------------------
    //  save / purge
    // ------------------------------------------------------------

    private void save(CommandSender sender, String[] args) throws CommandException {
        requirePermission(sender, "admintools.rollback.save");
        if (args.length < 2) {
            throw new CommandException(plugin.getMessages().raw("generic.usage",
                    "usage", "/rollback save <jugador>"));
        }
        Player target = requireOnline(args[1]);
        long id = service.captureSync(target, "MANUAL");
        if (id <= 0L) {
            throw new CommandException(plugin.getMessages().raw("rollback.disabled"));
        }
        plugin.getMessages().send(sender, "rollback.saved", "id", id, "player", target.getName());
        plugin.getAuditService().log(sender, "rollback.save", target.getName(), "#" + id);
    }

    private void purge(CommandSender sender, String[] args) throws CommandException {
        requirePermission(sender, "admintools.rollback.purge");
        if (args.length < 2) {
            throw new CommandException(plugin.getMessages().raw("generic.usage",
                    "usage", "/rollback purge <días>"));
        }
        int days = parsePage(args[1]);
        if (!plugin.getConfirmations().confirm(sender, "rollback-purge", "rollback.purge-confirm",
                "days", days)) {
            return;
        }
        int removed = service.purgeOlderThan(days);
        plugin.getMessages().send(sender, "rollback.purged", "count", removed, "days", days);
        plugin.getAuditService().log(sender, "rollback.purge", "-", removed + " copias de +" + days + " días");
    }

    // ------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------

    private long parseCase(String input) throws CommandException {
        try {
            return Long.parseLong(input);
        } catch (NumberFormatException ex) {
            throw new CommandException(plugin.getMessages().raw("generic.invalid-number", "input", input));
        }
    }

    /** Partes a restaurar: sin argumentos, todas. */
    private Set<Part> parts(String[] args, int from) throws CommandException {
        Set<Part> parts = EnumSet.noneOf(Part.class);
        for (int i = from; i < args.length; i++) {
            for (String token : args[i].split(",")) {
                switch (token.toLowerCase(Locale.ROOT)) {
                    case "all", "todo", "todos" -> parts = EnumSet.allOf(Part.class);
                    case "items", "inv", "inventario" -> parts.add(Part.ITEMS);
                    case "ender", "enderchest", "ec" -> parts.add(Part.ENDER);
                    case "pos", "position", "posicion", "posición" -> parts.add(Part.POSITION);
                    case "stats", "vida", "health" -> parts.add(Part.STATS);
                    case "" -> {
                        // separador de comas
                    }
                    default -> throw new CommandException(plugin.getMessages().raw("rollback.invalid-part",
                            "part", Text.safe(token)));
                }
            }
        }
        return parts.isEmpty() ? EnumSet.allOf(Part.class) : parts;
    }

    private String partsText(Set<Part> parts) {
        List<String> names = new ArrayList<>();
        for (Part part : parts) {
            names.add(plugin.getMessages().raw("rollback.part-" + part.name().toLowerCase(Locale.ROOT)));
        }
        return String.join(", ", names);
    }
}
