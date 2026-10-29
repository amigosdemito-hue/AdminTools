package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.Jail;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Importa cárceles y presos desde xJail.
 *
 * <p>xJail no documenta su esquema, así que en vez de adivinar nombres fijos se
 * descubren las tablas: se busca una tabla con columnas de posición (x/y/z) y
 * nombre para las celdas, y otra con jugador + cárcel para los presos. También
 * lee los YAML típicos. Todo se puede probar antes con {@code --dry}.</p>
 */
public final class JailImporter {

    /** Resultado de una importación, listo para contar al staff. */
    public record Report(String source, int jails, int prisoners, int skipped, List<String> notes) {
    }

    private final AdminToolsPlugin plugin;
    /**
     * Celdas que existen pero que en una simulación no se han guardado: sin esto
     * {@code --dry} marcaría a todos los presos como "cárcel no disponible".
     */
    private final java.util.Set<String> simulatedJails = new java.util.HashSet<>();

    public JailImporter(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /** ¿La celda existe de verdad o la crearía esta misma importación? */
    private boolean hasJail(String name) {
        if (name == null) {
            return false;
        }
        return plugin.getJailService().jail(name).isPresent()
                || simulatedJails.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Carpeta de xJail dentro de plugins/. */
    public File locate() {
        File plugins = plugin.getDataFolder().getParentFile();
        File folder = new File(plugins, "xJail");
        return folder.isDirectory() ? folder : null;
    }

    /** Ejecuta la importación desde una carpeta o archivo concreto. */
    public Report importFrom(File source, boolean dryRun) {
        List<String> notes = new ArrayList<>();
        int jails = 0;
        int prisoners = 0;
        int skipped = 0;

        List<File> files = collect(source);
        if (files.isEmpty()) {
            notes.add("No hay archivos .db, .sqlite ni .yml en " + source.getAbsolutePath());
            return new Report(source.getAbsolutePath(), 0, 0, 0, notes);
        }

        for (File file : files) {
            String lower = file.getName().toLowerCase(Locale.ROOT);
            try {
                if (lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3")) {
                    int[] counts = importSqlite(file, dryRun, notes);
                    jails += counts[0];
                    prisoners += counts[1];
                    skipped += counts[2];
                } else if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
                    int[] counts = importYaml(file, dryRun, notes);
                    jails += counts[0];
                    prisoners += counts[1];
                    skipped += counts[2];
                }
            } catch (Exception ex) {
                notes.add(file.getName() + ": " + ex.getMessage());
            }
        }
        return new Report(source.getAbsolutePath(), jails, prisoners, skipped, notes);
    }

    // ------------------------------------------------------------
    //  SQLite con descubrimiento de tablas
    // ------------------------------------------------------------

    private int[] importSqlite(File file, boolean dryRun, List<String> notes) throws Exception {
        int jails = 0;
        int prisoners = 0;
        int skipped = 0;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath())) {
            List<String> tables = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                List<String> columns = columns(connection, table);
                if (looksLikeJailTable(columns)) {
                    int imported = importJailRows(connection, table, columns, dryRun, notes);
                    jails += imported;
                    continue;
                }
                if (looksLikePrisonerTable(columns)) {
                    int[] counts = importPrisonerRows(connection, table, columns, dryRun, notes);
                    prisoners += counts[0];
                    skipped += counts[1];
                }
            }
        }
        return new int[]{jails, prisoners, skipped};
    }

    private List<String> columns(Connection connection, String table) throws Exception {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + quote(table) + " LIMIT 1")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(meta.getColumnName(i));
            }
        }
        return columns;
    }

    private boolean looksLikeJailTable(List<String> columns) {
        return find(columns, "x", "pos_x", "jail_x") != null
                && find(columns, "y", "pos_y", "jail_y") != null
                && find(columns, "z", "pos_z", "jail_z") != null
                && find(columns, "world", "mundo", "worldname") != null;
    }

    private boolean looksLikePrisonerTable(List<String> columns) {
        return find(columns, "jail", "jail_name", "jailname", "cell", "celda") != null
                && find(columns, "uuid", "player_uuid", "player", "name", "username", "player_name") != null;
    }

    private int importJailRows(Connection connection, String table, List<String> columns,
                               boolean dryRun, List<String> notes) throws Exception {
        int imported = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + quote(table))) {
            while (rs.next()) {
                String name = text(rs, find(columns, "name", "jail", "jail_name", "jailname", "cell", "celda"));
                String world = text(rs, find(columns, "world", "mundo", "worldname"));
                Double x = number(rs, find(columns, "x", "pos_x", "jail_x"));
                Double y = number(rs, find(columns, "y", "pos_y", "jail_y"));
                Double z = number(rs, find(columns, "z", "pos_z", "jail_z"));
                Double radius = number(rs, find(columns, "radius", "radio", "range"));
                if (name == null || name.isBlank() || world == null || x == null || y == null || z == null) {
                    notes.add("Fila de " + table + " sin nombre/mundo/posición; omitida.");
                    continue;
                }
                World bukkit = Bukkit.getWorld(world);
                if (bukkit == null) {
                    notes.add("Cárcel '" + name + "': el mundo '" + world + "' no existe aquí.");
                    continue;
                }
                if (plugin.getJailService().jail(name).isPresent()) {
                    notes.add("Cárcel '" + name + "' ya existía; se conserva la de AdminTools.");
                    continue;
                }
                if (dryRun) {
                    simulatedJails.add(name.toLowerCase(Locale.ROOT));
                } else {
                    plugin.getJailService().createJail(name,
                            new Location(bukkit, x, y, z), radius == null ? 0.0 : radius, "xJail");
                }
                imported++;
            }
        }
        return imported;
    }

    private int[] importPrisonerRows(Connection connection, String table, List<String> columns,
                                     boolean dryRun, List<String> notes) throws Exception {
        int imported = 0;
        int skipped = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + quote(table))) {
            while (rs.next()) {
                String who = text(rs, find(columns, "uuid", "player_uuid", "player", "name", "username",
                        "player_name"));
                String jailName = text(rs, find(columns, "jail", "jail_name", "jailname", "cell", "celda"));
                if (who == null || jailName == null) {
                    skipped++;
                    continue;
                }
                if (!hasJail(jailName)) {
                    notes.add("Preso '" + who + "': la cárcel '" + jailName + "' no se importó.");
                    skipped++;
                    continue;
                }
                Optional<Jail> cell = plugin.getJailService().jail(jailName);
                if (cell.isEmpty()) {
                    // Simulación sin celdas reales: se cuenta el preso igualmente.
                    if (dryRun) {
                        imported++;
                    } else {
                        skipped++;
                    }
                    continue;
                }
                long remaining = remainingSeconds(rs, columns);
                String reason = text(rs, find(columns, "reason", "motivo", "cause"));
                if (dryRun) {
                    imported++;
                    continue;
                }
                UUID uuid = resolve(who);
                String name = displayName(who, uuid);
                if (plugin.getJailService().importSentence(uuid, name, cell.get().name(), remaining,
                        reason == null ? "Importado de xJail" : reason, "xJail")) {
                    imported++;
                } else {
                    skipped++;
                }
            }
        }
        return new int[]{imported, skipped};
    }

    // ------------------------------------------------------------
    //  YAML
    // ------------------------------------------------------------

    private int[] importYaml(File file, boolean dryRun, List<String> notes) {
        int jails = 0;
        int prisoners = 0;
        int skipped = 0;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        for (String key : List.of("jails", "jail", "cells", "celdas")) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            for (String name : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(name);
                if (entry == null) {
                    continue;
                }
                String world = entry.getString("world", entry.getString("mundo"));
                World bukkit = world == null ? null : Bukkit.getWorld(world);
                if (bukkit == null) {
                    notes.add("Cárcel '" + name + "': mundo no encontrado ('" + world + "').");
                    continue;
                }
                if (plugin.getJailService().jail(name).isPresent()) {
                    continue;
                }
                if (dryRun) {
                    simulatedJails.add(name.toLowerCase(Locale.ROOT));
                } else {
                    plugin.getJailService().createJail(name, new Location(bukkit,
                                    entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z")),
                            entry.getDouble("radius", 0.0), "xJail");
                }
                jails++;
            }
        }

        for (String key : List.of("jailed", "prisoners", "presos", "jailed-players", "players")) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            for (String who : section.getKeys(false)) {
                String jailName;
                long remaining = -1L;
                String reason = "Importado de xJail";
                if (section.isConfigurationSection(who)) {
                    ConfigurationSection entry = section.getConfigurationSection(who);
                    jailName = entry.getString("jail", entry.getString("celda"));
                    remaining = entry.getLong("remaining", entry.getLong("seconds",
                            entry.getLong("time", -1L)));
                    reason = entry.getString("reason", reason);
                } else {
                    jailName = section.getString(who);
                }
                if (!hasJail(jailName)) {
                    notes.add("Preso '" + who + "': cárcel '" + jailName + "' no disponible.");
                    skipped++;
                    continue;
                }
                if (dryRun) {
                    prisoners++;
                    continue;
                }
                UUID uuid = resolve(who);
                if (plugin.getJailService().importSentence(uuid, displayName(who, uuid),
                        plugin.getJailService().jail(jailName).get().name(), remaining, reason, "xJail")) {
                    prisoners++;
                } else {
                    skipped++;
                }
            }
        }
        return new int[]{jails, prisoners, skipped};
    }

    // ------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------

    private List<File> collect(File source) {
        List<File> files = new ArrayList<>();
        if (source.isFile()) {
            files.add(source);
            return files;
        }
        try (Stream<Path> walk = Files.walk(source.toPath(), 3)) {
            walk.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(file -> {
                        String name = file.getName().toLowerCase(Locale.ROOT);
                        return name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".sqlite3")
                                || name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .forEach(files::add);
        } catch (Exception ex) {
            files.clear();
        }
        // Primero la base de datos, luego la configuración: así las celdas existen al leer presos.
        files.sort(java.util.Comparator.comparingInt(file -> isYaml(file) ? 1 : 0));
        return files;
    }

    /** Tiempo restante en segundos; -1 si la condena es indefinida. */
    private long remainingSeconds(ResultSet rs, List<String> columns) {
        String column = find(columns, "remaining", "seconds", "time", "until", "expires", "expires_at",
                "end_time", "jail_time");
        if (column == null) {
            return -1L;
        }
        try {
            long value = rs.getLong(column);
            if (value <= 0L) {
                return -1L;
            }
            // Puede venir en segundos restantes, epoch en segundos o epoch en milisegundos.
            long now = System.currentTimeMillis();
            if (value > 100_000_000_000L) {
                return Math.max(1L, (value - now) / 1000L);
            }
            if (value > 1_000_000_000L) {
                return Math.max(1L, value - now / 1000L);
            }
            return value;
        } catch (Exception ex) {
            return -1L;
        }
    }

    private UUID resolve(String raw) {
        if (raw == null) {
            return new UUID(0L, 0L);
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            // Es un nombre: se usa su ficha o el UUID derivado del nombre.
        }
        return plugin.getPlayerService().requireUuid(raw)
                .orElseGet(() -> SanctionService.offlineUuid(raw));
    }

    /**
     * Nombre visible: si el origen guardaba un UUID se resuelve a un nombre y, si
     * ese jugador nunca entró aquí, se muestra el UUID corto en vez de un "?".
     */
    private String displayName(String raw, UUID uuid) {
        String fallback = raw == null ? null : raw.trim();
        if (fallback != null && fallback.length() > 16) {
            fallback = fallback.substring(0, 8) + "…";
        }
        return plugin.getPlayerService().nameOrDefault(uuid, fallback);
    }

    private static boolean isYaml(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static String find(List<String> columns, String... candidates) {
        for (String column : columns) {
            String lower = column.toLowerCase(Locale.ROOT);
            for (String candidate : candidates) {
                if (lower.equals(candidate)) {
                    return column;
                }
            }
        }
        for (String column : columns) {
            String lower = column.toLowerCase(Locale.ROOT);
            for (String candidate : candidates) {
                if (lower.contains(candidate)) {
                    return column;
                }
            }
        }
        return null;
    }

    private static String text(ResultSet rs, String column) {
        if (column == null) {
            return null;
        }
        try {
            String value = rs.getString(column);
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ex) {
            return null;
        }
    }

    private static Double number(ResultSet rs, String column) {
        if (column == null) {
            return null;
        }
        try {
            double value = rs.getDouble(column);
            return rs.wasNull() ? null : value;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String quote(String table) {
        return "\"" + table.replace("\"", "\"\"") + "\"";
    }
}
