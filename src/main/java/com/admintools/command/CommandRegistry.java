package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registro central de comandos: cada comando se declara una vez con su
 * permiso, su uso y su lógica; de aquí salen también el autocompletado,
 * el aviso de "sin permiso", el de uso correcto y la auditoría.
 */
public final class CommandRegistry implements CommandExecutor, TabCompleter {

    /** Lógica de un comando. */
    @FunctionalInterface
    public interface Handler {
        void handle(CommandSender sender, String[] args) throws Exception;
    }

    /** Sugerencias de autocompletado. */
    @FunctionalInterface
    public interface Completer {
        List<String> suggest(CommandSender sender, String[] args);
    }

    /** Definición fluida de un comando. */
    public final class Definition {

        private final String name;
        private final String label;
        private String permission;
        private String usage;
        private int minArgs;
        private Handler handler;
        private Completer completer;
        private List<String> literals = List.of();
        private boolean audit = true;

        private Definition(String name) {
            this.name = name;
            this.label = name;
        }

        public Definition permission(String value) {
            this.permission = value;
            return this;
        }

        public Definition usage(String value) {
            this.usage = value;
            return this;
        }

        public Definition minArgs(int value) {
            this.minArgs = value;
            return this;
        }

        public Definition literals(String... values) {
            this.literals = List.of(values);
            return this;
        }

        public Definition completer(Completer value) {
            this.completer = value;
            return this;
        }

        public Definition noAudit() {
            this.audit = false;
            return this;
        }

        public Definition run(Handler value) {
            this.handler = value;
            return this;
        }

        public String name() {
            return name;
        }

        public String permission() {
            return permission;
        }

        public String usage() {
            return usage == null ? "/" + label : usage;
        }

        public List<String> literals() {
            return literals;
        }

        List<String> suggestions(CommandSender sender, String[] args) {
            String current = args.length == 0 ? "" : args[args.length - 1];
            List<String> result = new ArrayList<>();
            if (completer != null) {
                List<String> fromCompleter = completer.suggest(sender, args);
                if (fromCompleter != null) {
                    result.addAll(fromCompleter);
                }
            } else {
                result.addAll(playerNames(current));
            }
            // Los flags (-s) se suman a las sugerencias, no las reemplazan; el resto de
            // literales solo vale como primer argumento (/reports claim, /snapshot save...).
            for (String literal : literals) {
                if (!literal.startsWith("-") && args.length > 1) {
                    continue;
                }
                if (!result.contains(literal)) {
                    result.add(literal);
                }
            }
            return result;
        }

        boolean shouldAudit() {
            return audit;
        }
    }

    /**
     * Comandos que mueven cosas reales (inventarios, datos, servidor) y no son
     * sanciones. Junto con todo {@code admintools.sanction.*} entran en el límite
     * de acciones por minuto del staff.
     */
    private static final Set<String> LIMITED_COMMANDS = Set.of(
            "clear", "freeze", "restore", "snapshot", "maintenance", "restart",
            "backup", "clearchat", "muteall", "slowmode", "unpunish", "ipban");

    /**
     * Comandos de sólo consulta: no gastan el presupuesto de acciones. Leer el
     * historial de veinte jugadores antes de banear a uno no debe dejar al moderador
     * bloqueado, porque nada de esto cambia el estado del servidor.
     */
    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "history", "notes", "audit", "stafflist", "playtime", "seen", "check", "case", "alts");

    private final AdminToolsPlugin plugin;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();

    public CommandRegistry(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Declara un comando y lo engancha al comando de plugin.yml con el mismo nombre. */
    public Definition register(String name) {
        Definition definition = new Definition(name);
        definitions.put(name.toLowerCase(Locale.ROOT), definition);
        PluginCommand command = plugin.getCommand(name);
        if (command == null) {
            plugin.getLogger().severe("El comando /" + name + " no está declarado en plugin.yml");
            return definition;
        }
        command.setExecutor(this);
        command.setTabCompleter(this);
        return definition;
    }

    /** Todas las definiciones registradas (para la ayuda). */
    public List<Definition> definitions() {
        return new ArrayList<>(definitions.values());
    }

    // ------------------------------------------------------------
    //  Ejecución
    // ------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             String @NotNull [] args) {
        Definition definition = definitions.get(command.getName().toLowerCase(Locale.ROOT));
        if (definition == null) {
            return false;
        }
        if (definition.permission != null && !sender.hasPermission(definition.permission)) {
            plugin.getMessages().send(sender, "generic.no-permission");
            return true;
        }
        // Límite anti-abuso: una cuenta de staff comprometida no puede vaciar el
        // servidor de bans en diez segundos. El uso normal queda muy por debajo.
        if (sender instanceof Player player && limited(definition)
                && !plugin.getActionLimiter().allow(player, definition.name)) {
            plugin.getMessages().send(sender, "generic.action-limit",
                    "max", plugin.getSettings().staffActionMaxPerMinute());
            return true;
        }
        // Los flags (-s / --silent) no cuentan como argumentos: "/ban -s" debe mostrar el uso,
        // no reventar al acceder a args[0] después de limpiarlos.
        if (definition.minArgs > 0 && usable(args, definition) < definition.minArgs) {
            plugin.getMessages().send(sender, "generic.usage", "usage", definition.usage());
            return true;
        }
        try {
            definition.handler.handle(sender, args);
        } catch (CommandException ex) {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(ex.getMessage()));
        } catch (Exception ex) {
            // El detalle va al log (y a la consola en debug), nunca al jugador: un
            // mensaje de SQL o una ruta interna no le sirven de nada al staff.
            plugin.getMessages().send(sender, "generic.internal-error", "command", "/" + definition.name);
            plugin.getLogger().warning("Error en /" + definition.name + ": " + ex);
            if (plugin.getSettings().debug()) {
                ex.printStackTrace();
            }
        }
        if (definition.shouldAudit() && isStaff(sender)) {
            plugin.getAuditService().log(sender, definition.name, args.length > 0 ? args[0] : "-",
                    String.join(" ", args));
        }
        return true;
    }

    /** ¿Esta acción cuenta para el límite por minuto? */
    private static boolean limited(Definition definition) {
        String permission = definition.permission;
        if (permission == null || READ_ONLY_COMMANDS.contains(definition.name)) {
            return false;
        }
        return permission.startsWith("admintools.sanction.")
                || LIMITED_COMMANDS.contains(definition.name);
    }

    /** Argumentos que no son el flag de silencio. */
    private int usable(String[] args, Definition definition) {
        if (!definition.literals.contains("-s")) {
            return args.length;
        }
        int count = 0;
        for (String value : args) {
            if (!value.equalsIgnoreCase("-s") && !value.equalsIgnoreCase("--silent")) {
                count++;
            }
        }
        return count;
    }

    private boolean isStaff(CommandSender sender) {
        return !(sender instanceof Player player) || player.hasPermission("admintools.helper")
                || player.hasPermission("admintools.admin");
    }

    // ------------------------------------------------------------
    //  Autocompletado
    // ------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      String @NotNull [] args) {
        Definition definition = definitions.get(command.getName().toLowerCase(Locale.ROOT));
        if (definition == null) {
            return List.of();
        }
        if (definition.permission != null && !sender.hasPermission(definition.permission)) {
            return List.of();
        }
        String current = args.length == 0 ? "" : args[args.length - 1];
        List<String> suggestions = definition.suggestions(sender, args);
        List<String> filtered = new ArrayList<>();
        for (String suggestion : suggestions) {
            if (suggestion.toLowerCase(Locale.ROOT).startsWith(current.toLowerCase(Locale.ROOT))) {
                filtered.add(suggestion);
            }
        }
        return filtered;
    }

    /** Nombres de jugadores online (el registro ya filtra por lo que se está escribiendo). */
    public static List<String> players() {
        return playerNames("");
    }

    /** Duraciones habituales para /tempban, /tempmute y similares. */
    public static List<String> durations() {
        return List.of("15m", "30m", "1h", "6h", "1d", "7d", "30d", "permanent");
    }

    /** Nombres de jugadores online que empiezan por el prefijo indicado. */
    public static List<String> playerNames(String prefix) {
        List<String> names = new ArrayList<>();
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(needle)) {
                names.add(online.getName());
            }
        }
        return names;
    }
}
