package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.Jail;
import com.admintools.model.JailSentence;
import com.admintools.service.JailImporter;
import com.admintools.service.JailService;
import com.admintools.util.Text;
import com.admintools.util.TimeUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Cárcel: condenar, liberar, gestionar celdas y consultar el tiempo restante. */
public final class JailCommands extends CommandSupport {

    public JailCommands(AdminToolsPlugin plugin, CommandRegistry registry) {
        super(plugin);
        registerJail(registry);
        registerUnjail(registry);
        registerTime(registry);
        registerList(registry);
        registerCells(registry);
        registerImport(registry);
    }

    // ------------------------------------------------------------
    //  /jail <jugador> [duración] [cárcel] [motivo]
    // ------------------------------------------------------------

    private void registerJail(CommandRegistry registry) {
        registry.register("jail")
                .permission("admintools.jail.use")
                .usage("/jail <jugador> [duración] [cárcel] [motivo]")
                .minArgs(1)
                .completer((sender, args) -> {
                    if (args.length <= 1) {
                        return CommandRegistry.players();
                    }
                    if (args.length == 2) {
                        List<String> out = new ArrayList<>(jailNames());
                        out.addAll(CommandRegistry.durations());
                        return out;
                    }
                    return jailNames();
                })
                .noAudit()
                .run((sender, args) -> {
                    String name = args[0];
                    int index = 1;
                    long duration = plugin.getSettings().jailDefaultDuration();
                    if (args.length > index) {
                        Long parsed = TimeUtil.parse(args[index]);
                        if (parsed != null) {
                            duration = parsed;
                            index++;
                        } else if (TimeUtil.looksLikeDuration(args[index])) {
                            throw new CommandException(plugin.getMessages().raw("generic.invalid-duration",
                                    "input", args[index]));
                        }
                    }
                    // Sin celda explícita se elige una al azar: así nadie acaba siempre en
                    // la misma y un preso no puede aprenderse el recorrido de su celda.
                    String jailName = null;
                    if (args.length > index && plugin.getJailService().jail(args[index]).isPresent()) {
                        jailName = args[index];
                        index++;
                    }
                    if (jailName == null) {
                        jailName = pickCell();
                    }
                    if (jailName == null || jailName.isBlank() || plugin.getJailService().jail(jailName).isEmpty()) {
                        throw new CommandException(plugin.getMessages().raw(
                                plugin.getJailService().jails().isEmpty() ? "jail.no-cells" : "jail.unknown",
                                "jail", jailName == null ? "" : jailName,
                                "available", String.join(", ", jailNames())));
                    }
                    Player target = requireOnline(name);
                    if (target.hasPermission("admintools.jail.exempt")) {
                        throw new CommandException(plugin.getMessages().raw("jail.exempt",
                                "player", target.getName()));
                    }
                    Optional<JailSentence> current = plugin.getJailService().active(target.getUniqueId());
                    if (current.isPresent()) {
                        throw new CommandException(plugin.getMessages().raw("jail.already",
                                "player", target.getName(), "id", current.get().id()));
                    }
                    JailService.Result result = plugin.getJailService().jail(target, jailName, duration,
                            reason(args, index), staffUuid(sender), staffName(sender));
                    switch (result) {
                        case OK -> plugin.getMessages().send(sender, "jail.applied-sender",
                                "player", target.getName(), "jail", jailName,
                                "time", duration == TimeUtil.PERMANENT
                                        ? plugin.getMessages().raw("jail.time-permanent")
                                        : TimeUtil.format(duration));
                        case DISABLED -> throw new CommandException(plugin.getMessages().raw("jail.disabled"));
                        case NO_JAIL -> throw new CommandException(plugin.getMessages().raw("jail.unknown",
                                "jail", jailName, "available", String.join(", ", jailNames())));
                        case ALREADY -> throw new CommandException(plugin.getMessages().raw("jail.already",
                                "player", target.getName(), "id", "-"));
                    }
                });
    }

    // ------------------------------------------------------------
    //  /unjail <jugador> [motivo]
    // ------------------------------------------------------------

    private void registerUnjail(CommandRegistry registry) {
        registry.register("unjail")
                .permission("admintools.jail.use")
                .usage("/unjail <jugador> [motivo]")
                .minArgs(1)
                .completer((sender, args) -> jailedNames())
                .noAudit()
                .run((sender, args) -> {
                    Optional<JailSentence> found = find(args[0]);
                    if (found.isEmpty()) {
                        throw new CommandException(plugin.getMessages().raw("jail.not-jailed",
                                "player", args[0]));
                    }
                    JailSentence sentence = found.get();
                    plugin.getJailService().release(sentence.targetUuid(), staffName(sender), reason(args, 1));
                    plugin.getMessages().send(sender, "jail.release-done", "player", sentence.targetName());
                });
    }

    // ------------------------------------------------------------
    //  /jailtime [jugador]
    // ------------------------------------------------------------

    private void registerTime(CommandRegistry registry) {
        // Sin permiso a propósito: un preso tiene que poder ver lo que le queda.
        // Consultar el tiempo de otro sí exige admintools.jail.view.
        registry.register("jailtime")
                .usage("/jailtime [jugador]")
                .completer((sender, args) -> jailedNames())
                .noAudit()
                .run((sender, args) -> {
                    if (args.length == 0) {
                        Player player = requirePlayer(sender);
                        Optional<JailSentence> active = plugin.getJailService().active(player.getUniqueId());
                        if (active.isEmpty()) {
                            plugin.getMessages().send(player, "jail.time-none");
                            return;
                        }
                        plugin.getMessages().send(player, "jail.time-self",
                                "time", plugin.getJailService().timeText(active.get()));
                        return;
                    }
                    requirePermission(sender, "admintools.jail.view");
                    Optional<JailSentence> found = find(args[0]);
                    if (found.isEmpty()) {
                        plugin.getMessages().send(sender, "jail.not-jailed", "player", args[0]);
                        return;
                    }
                    plugin.getMessages().send(sender, "jail.time-other",
                            "player", found.get().targetName(),
                            "time", plugin.getJailService().timeText(found.get()));
                });
    }

    // ------------------------------------------------------------
    //  /jaillist
    // ------------------------------------------------------------

    private void registerList(CommandRegistry registry) {
        registry.register("jaillist")
                .permission("admintools.jail.view")
                .usage("/jaillist")
                .noAudit()
                .run((sender, args) -> {
                    List<JailSentence> prisoners = plugin.getJailService().activeAll();
                    if (prisoners.isEmpty()) {
                        plugin.getMessages().send(sender, "jail.prisoners-empty");
                        return;
                    }
                    plugin.getMessages().sendRaw(sender, "jail.prisoners-header", "count", prisoners.size());
                    for (JailSentence sentence : prisoners) {
                        plugin.getMessages().sendRaw(sender, "jail.prisoners-line",
                                "player", sentence.targetName(),
                                "jail", sentence.jail(),
                                "time", plugin.getJailService().timeText(sentence));
                    }
                });
    }

    // ------------------------------------------------------------
    //  /setjail, /deljail, /jails, /jailtp
    // ------------------------------------------------------------

    private void registerCells(CommandRegistry registry) {
        registry.register("setjail")
                .permission("admintools.jail.manage")
                .usage("/setjail <nombre> [radio]")
                .minArgs(1)
                .run((sender, args) -> {
                    Player player = requirePlayer(sender);
                    // Por defecto 2 bloques: área cuadrada de 5x5 alrededor del punto.
                    double radius = plugin.getSettings().jailDefaultRadius();
                    if (args.length > 1) {
                        try {
                            radius = Math.max(0.0, Double.parseDouble(args[1].replace(',', '.')));
                        } catch (NumberFormatException ex) {
                            throw new CommandException(plugin.getMessages().raw("generic.invalid-number",
                                    "input", args[1]));
                        }
                    }
                    String name = args[0].toLowerCase(Locale.ROOT);
                    Location location = player.getLocation();
                    if (!plugin.getJailService().createJail(name, location, radius, staffName(sender))) {
                        throw new CommandException(plugin.getMessages().raw("jail.exists", "jail", name));
                    }
                    plugin.getMessages().send(player, "jail.created", "jail", name,
                            "radius", radius <= 0 ? "-" : String.valueOf(Math.round(radius)),
                            "coords", Math.round(location.getX()) + ", " + Math.round(location.getY())
                                    + ", " + Math.round(location.getZ()));
                });

        registry.register("deljail")
                .permission("admintools.jail.manage")
                .usage("/deljail <nombre>")
                .minArgs(1)
                .completer((sender, args) -> jailNames())
                .run((sender, args) -> {
                    String name = args[0].toLowerCase(Locale.ROOT);
                    JailService.DeleteResult result = plugin.getJailService().deleteJail(name);
                    switch (result) {
                        case OK -> plugin.getMessages().send(sender, "jail.deleted", "jail", name);
                        case DISABLED -> throw new CommandException(plugin.getMessages().raw("jail.disabled"));
                        case NOT_FOUND -> throw new CommandException(plugin.getMessages().raw("jail.unknown",
                                "jail", name, "available", String.join(", ", jailNames())));
                        case IN_USE -> throw new CommandException(plugin.getMessages().raw("jail.in-use",
                                "jail", name, "count", plugin.getJailService().jailedCount(name)));
                    }
                });

        registry.register("jails")
                .permission("admintools.jail.view")
                .usage("/jails")
                .noAudit()
                .run((sender, args) -> {
                    List<Jail> jails = plugin.getJailService().jails();
                    if (jails.isEmpty()) {
                        plugin.getMessages().send(sender, "jail.list-empty");
                        return;
                    }
                    plugin.getMessages().sendRaw(sender, "jail.list-header", "count", jails.size());
                    for (Jail jail : jails) {
                        plugin.getMessages().sendRaw(sender, "jail.list-line",
                                "jail", jail.name(),
                                "world", jail.world(),
                                "coords", jail.coords(),
                                "radius", jail.radius() <= 0 ? "-" : String.valueOf(Math.round(jail.radius())),
                                "jailed", plugin.getJailService().jailedCount(jail.name()));
                    }
                });

        registry.register("jailtp")
                .permission("admintools.jail.use")
                .usage("/jailtp <jugador>")
                .minArgs(1)
                .completer((sender, args) -> jailedNames())
                .run((sender, args) -> {
                    Player player = requirePlayer(sender);
                    Optional<JailSentence> found = find(args[0]);
                    if (found.isEmpty()) {
                        throw new CommandException(plugin.getMessages().raw("jail.not-jailed",
                                "player", args[0]));
                    }
                    Optional<Jail> cell = plugin.getJailService().jail(found.get().jail());
                    Location location = cell.map(Jail::location).orElse(null);
                    if (location == null) {
                        throw new CommandException(plugin.getMessages().raw("jail.unknown",
                                "jail", found.get().jail(),
                                "available", String.join(", ", jailNames())));
                    }
                    player.teleport(location);
                    plugin.getMessages().send(player, "jail.visit",
                            "player", found.get().targetName(), "jail", cell.get().name());
                });
    }

    // ------------------------------------------------------------
    //  /jailimport [ruta|xjail] [--dry]
    // ------------------------------------------------------------

    private void registerImport(CommandRegistry registry) {
        registry.register("jailimport")
                .permission("admintools.jail.import")
                .usage("/jailimport [ruta|xjail] [--dry]")
                .run((sender, args) -> {
                    boolean dry = false;
                    String input = null;
                    for (String arg : args) {
                        if (arg.equalsIgnoreCase("--dry")) {
                            dry = true;
                        } else if (input == null) {
                            input = arg;
                        }
                    }
                    JailImporter importer = new JailImporter(plugin);
                    File source;
                    if (input == null || input.equalsIgnoreCase("xjail")) {
                        source = importer.locate();
                        if (source == null) {
                            throw new CommandException(plugin.getMessages().raw("jail.import-none",
                                    "path", "plugins/xJail"));
                        }
                    } else {
                        File pluginsDir = plugin.getDataFolder().getParentFile().getCanonicalFile();
                        source = new File(input);
                        if (!source.isAbsolute()) {
                            source = new File(pluginsDir, input);
                        }
                        source = source.getCanonicalFile();
                        // Sólo dentro de plugins/: "/jailimport" no debe poder leer
                        // cualquier archivo del disco del servidor.
                        if (!source.getPath().startsWith(pluginsDir.getPath() + File.separator)) {
                            throw new CommandException(plugin.getMessages().raw("jail.import-outside",
                                    "path", source.getPath()));
                        }
                        if (!source.exists()) {
                            throw new CommandException(plugin.getMessages().raw("jail.import-none",
                                    "path", source.getPath()));
                        }
                    }
                    JailImporter.Report report = importer.importFrom(source, dry);
                    plugin.getMessages().sendRaw(sender, "jail.import-header");
                    plugin.getMessages().sendRaw(sender, "jail.import-file", "file", report.source());
                    plugin.getMessages().sendRaw(sender, "jail.import-jails", "count", report.jails());
                    plugin.getMessages().sendRaw(sender, "jail.import-prisoners", "count", report.prisoners());
                    if (report.skipped() > 0) {
                        plugin.getMessages().sendRaw(sender, "jail.import-skipped", "count", report.skipped());
                    }
                    int shown = 0;
                    for (String note : report.notes()) {
                        if (shown++ >= 10) {
                            plugin.getMessages().sendRaw(sender, "jail.import-more",
                                    "count", report.notes().size() - 10);
                            break;
                        }
                        plugin.getMessages().sendRaw(sender, "jail.import-note", "note", Text.safe(note));
                    }
                    if (dry) {
                        plugin.getMessages().sendRaw(sender, "jail.import-dry");
                    }
                    plugin.getAuditService().log(sender, "jail.import", source.getName(),
                            report.jails() + " celdas, " + report.prisoners() + " presos"
                                    + (dry ? " (simulación)" : ""));
                });
    }

    // ------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------

    /** Busca una condena por UUID (si está online) o por nombre entre los presos. */
    private Optional<JailSentence> find(String name) {
        Player online = org.bukkit.Bukkit.getPlayerExact(name);
        if (online != null) {
            Optional<JailSentence> byUuid = plugin.getJailService().active(online.getUniqueId());
            if (byUuid.isPresent()) {
                return byUuid;
            }
        }
        return plugin.getJailService().activeAll().stream()
                .filter(sentence -> sentence.targetName().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * Celda para una condena que no indica ninguna.
     *
     * <p>Al azar entre las que tienen mundo válido, porque una celda de un mundo borrado
     * no puede recibir al preso. Quedan fuera las celdas de mundos inexistentes; si no
     * queda ninguna se devuelve {@code jail.default} y el error lo da la comprobación de
     * siempre, con el mensaje que ya existe.</p>
     */
    private String pickCell() {
        if (!plugin.getSettings().jailRandomCell()) {
            return plugin.getSettings().jailDefault();
        }
        List<String> usable = new ArrayList<>();
        for (Jail jail : plugin.getJailService().jails()) {
            if (jail.location() != null) {
                usable.add(jail.name());
            }
        }
        if (usable.isEmpty()) {
            return plugin.getSettings().jailDefault();
        }
        return pickRandom(usable);
    }

    /**
     * Una de las celdas de la lista, uniformemente.
     *
     * <p>Recibe una lista no vacía; el respaldo para la lista vacía lo hace
     * {@link #pickCell()}, que es quien conoce la config.</p>
     */
    static String pickRandom(List<String> cells) {
        return cells.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(cells.size()));
    }

    private List<String> jailNames() {
        List<String> names = new ArrayList<>();
        for (Jail jail : plugin.getJailService().jails()) {
            names.add(jail.name());
        }
        return names;
    }

    private List<String> jailedNames() {
        List<String> names = new ArrayList<>();
        for (JailSentence sentence : plugin.getJailService().activeAll()) {
            names.add(sentence.targetName());
        }
        return names;
    }
}
