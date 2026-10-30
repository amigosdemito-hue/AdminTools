package com.admintools.storage;

import com.admintools.AdminToolsPlugin;
import com.admintools.config.Settings;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Acceso a datos con dos dialectos: SQLite (archivo local, un servidor) y
 * MySQL (varias instancias compartiendo datos).
 *
 * <p>Los drivers los provee Paper en runtime (org.xerial:sqlite-jdbc y
 * com.mysql:mysql-connector-j), por eso no se empaquetan en el jar.</p>
 *
 * <p>Las escrituras del plugin van por {@link #async(Runnable)} para no tocar
 * el hilo principal; las lecturas puntuales se hacen con {@link #query}.</p>
 */
public final class Database {

    public enum Dialect {
        SQLITE, MYSQL
    }

    /** Mapea una fila del ResultSet a un objeto. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private static final int SCHEMA_VERSION = 2;

    private final AdminToolsPlugin plugin;
    private final Settings settings;
    private final Dialect dialect;
    private final String prefix;
    private final int maxConnections;
    private final Deque<Connection> idle = new ArrayDeque<>();
    private final Semaphore permits;
    private final ExecutorService executor;
    private final String url;
    private volatile boolean closed;

    public Database(AdminToolsPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.dialect = settings.mysql() ? Dialect.MYSQL : Dialect.SQLITE;
        this.prefix = dialect == Dialect.MYSQL ? settings.tablePrefix() : "";
        // SQLite en modo WAL admite varias conexiones a la vez: una escribe y el
        // resto lee. Con una sola conexión, una copia de inventario en segundo plano
        // dejaba esperando a la lectura del comando que estaba en el hilo principal.
        // Las escrituras siguen serializándose solas (busy_timeout de 5 s).
        this.maxConnections = dialect == Dialect.SQLITE
                ? Math.min(4, Math.max(2, settings.poolSize()))
                : Math.max(1, settings.poolSize());
        this.permits = new Semaphore(maxConnections, true);
        this.executor = Executors.newFixedThreadPool(Math.max(2, maxConnections),
                runnable -> {
                    Thread thread = new Thread(runnable, "AdminTools-DB");
                    thread.setDaemon(true);
                    return thread;
                });

        if (dialect == Dialect.MYSQL) {
            this.url = buildMysqlUrl();
        } else {
            File folder = plugin.getDataFolder();
            if (!folder.exists() && !folder.mkdirs()) {
                plugin.getLogger().warning("No pude crear la carpeta de datos " + folder.getAbsolutePath());
            }
            this.url = "jdbc:sqlite:" + new File(folder, "data.db").getAbsolutePath();
        }
    }

    // ------------------------------------------------------------
    //  URL de MySQL
    // ------------------------------------------------------------

    /**
     * Arma la URL JDBC tolerando lo que la gente pega de los paneles de hosting:
     * {@code jdbc:mysql://host:3306/}, un host que ya trae el puerto, barras
     * sobrantes o parámetros que empiezan por {@code ?}.
     */
    private String buildMysqlUrl() {
        String host = settings.mysqlHost() == null ? "" : settings.mysqlHost().trim();
        int port = settings.mysqlPort();

        for (String prefix : List.of("jdbc:mysql://", "jdbc:mariadb://", "mysql://", "mariadb://")) {
            if (host.regionMatches(true, 0, prefix, 0, prefix.length())) {
                host = host.substring(prefix.length());
                break;
            }
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        if (host.startsWith("[") && host.contains("]")) {
            // IPv6 literal: [::1]:3306
            int end = host.indexOf(']');
            String rest = host.substring(end + 1);
            if (rest.startsWith(":")) {
                port = parsePort(rest.substring(1), port);
            }
            host = host.substring(0, end + 1);
        } else if (host.indexOf(':') >= 0) {
            int colon = host.lastIndexOf(':');
            port = parsePort(host.substring(colon + 1), port);
            host = host.substring(0, colon);
        }
        if (host.isBlank()) {
            host = "localhost";
        }

        String database = settings.mysqlDatabase() == null ? "" : settings.mysqlDatabase().trim();
        while (database.startsWith("/")) {
            database = database.substring(1);
        }
        while (database.endsWith("/")) {
            database = database.substring(0, database.length() - 1);
        }
        if (database.isBlank()) {
            database = "admintools";
        }

        return "jdbc:mysql://" + host + ":" + port + "/" + database + "?" + buildParameters();
    }

    private static int parsePort(String raw, int fallback) {
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 && parsed < 65536 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * Normaliza los parámetros de la URL y añade lo que el conector necesita para
     * funcionar por defecto: con TLS desactivado, MySQL 8+ usa
     * {@code caching_sha2_password} y sin {@code allowPublicKeyRetrieval=true} la
     * conexión muere con «Public Key Retrieval is not allowed».
     */
    private String buildParameters() {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = settings.mysqlParameters() == null ? "" : settings.mysqlParameters().trim();
        while (raw.startsWith("?") || raw.startsWith("&")) {
            raw = raw.substring(1);
        }
        for (String pair : raw.split("&")) {
            String entry = pair.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            String key = eq < 0 ? entry : entry.substring(0, eq).trim();
            String value = eq < 0 ? "true" : entry.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                params.put(key.toLowerCase(Locale.ROOT), key + "=" + value);
            }
        }
        boolean tlsOff = "false".equalsIgnoreCase(valueOf(params, "usessl"))
                || "disabled".equalsIgnoreCase(valueOf(params, "sslmode"))
                || "disabled".equalsIgnoreCase(valueOf(params, "ssl-mode"));
        if (tlsOff && !params.containsKey("allowpublickeyretrieval")) {
            params.put("allowpublickeyretrieval", "allowPublicKeyRetrieval=true");
        }
        return params.isEmpty() ? "" : String.join("&", params.values());
    }

    private static String valueOf(Map<String, String> params, String key) {
        String entry = params.get(key);
        if (entry == null) {
            return null;
        }
        int eq = entry.indexOf('=');
        return eq < 0 ? "" : entry.substring(eq + 1);
    }

    /** Destino de la conexión, sin contraseña, para los mensajes de consola. */
    public String describeTarget() {
        if (dialect == Dialect.SQLITE) {
            return "SQLite " + new File(plugin.getDataFolder(), "data.db").getAbsolutePath();
        }
        String shown = url.replaceAll("(?i)(password=)[^&]*", "$1***");
        return "MySQL " + shown + " (usuario " + settings.mysqlUser() + ")";
    }

    // ------------------------------------------------------------
    //  Conexiones
    // ------------------------------------------------------------

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url, dialect == Dialect.MYSQL ? settings.mysqlUser() : null,
                dialect == Dialect.MYSQL ? settings.mysqlPassword() : null);
        if (dialect == Dialect.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA foreign_keys=ON");
            }
        }
        return connection;
    }

    /** Toma una conexión (creando otra si hace falta) y la devuelve al terminar. */
    private Connection acquire() throws SQLException {
        if (closed) {
            throw new SQLException("La base de datos está cerrada");
        }
        permits.acquireUninterruptibly();
        synchronized (idle) {
            Connection connection = idle.poll();
            if (connection != null) {
                return connection;
            }
        }
        try {
            return open();
        } catch (SQLException ex) {
            permits.release();
            throw ex;
        }
    }

    private void release(Connection connection) {
        boolean dropped = false;
        try {
            if (connection.isClosed()) {
                dropped = true;
            }
        } catch (SQLException ex) {
            dropped = true;
        }
        if (!dropped) {
            synchronized (idle) {
                if (idle.size() < maxConnections) {
                    idle.push(connection);
                } else {
                    dropped = true;
                }
            }
        }
        if (dropped) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // nada que hacer
            }
        }
        permits.release();
    }

    // ------------------------------------------------------------
    //  Helpers de consulta
    // ------------------------------------------------------------

    private void bind(PreparedStatement statement, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            if (value instanceof byte[] bytes) {
                statement.setBytes(i + 1, bytes);
            } else if (value instanceof Boolean flag) {
                statement.setInt(i + 1, flag ? 1 : 0);
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        Connection connection = null;
        try {
            connection = acquire();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<T> rows = new ArrayList<>();
                    while (resultSet.next()) {
                        rows.add(mapper.map(resultSet));
                    }
                    return rows;
                }
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Error de consulta: " + sql, ex);
        } finally {
            if (connection != null) {
                release(connection);
            }
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public long queryLong(String sql, Object... params) {
        return queryOne(sql, rs -> rs.getLong(1), params).orElse(0L);
    }

    public String queryString(String sql, Object... params) {
        return queryOne(sql, rs -> rs.getString(1), params).orElse(null);
    }

    /** Ejecuta una escritura y devuelve las filas afectadas. */
    public int update(String sql, Object... params) {
        Connection connection = null;
        try {
            connection = acquire();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                return statement.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Error de escritura: " + sql, ex);
        } finally {
            if (connection != null) {
                release(connection);
            }
        }
    }

    /** Inserta y devuelve el id generado (0 si la tabla no usa autoincremento). */
    public long insert(String sql, Object... params) {
        Connection connection = null;
        try {
            connection = acquire();
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    bind(statement, params);
                    statement.executeUpdate();
                }
                long id;
                try (Statement statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery(dialect == Dialect.MYSQL
                             ? "SELECT LAST_INSERT_ID()" : "SELECT last_insert_rowid()")) {
                    id = resultSet.next() ? resultSet.getLong(1) : 0L;
                }
                connection.commit();
                return id;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(previous);
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Error al insertar: " + sql, ex);
        } finally {
            if (connection != null) {
                release(connection);
            }
        }
    }

    /** Ejecuta varias sentencias sueltas (DDL) sin transacción. */
    private void executeAll(List<String> statements) {
        for (String sql : statements) {
            Connection connection = null;
            try {
                connection = acquire();
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            } catch (SQLException ex) {
                plugin.getLogger().warning("Fallo al ejecutar DDL (" + ex.getMessage() + "): " + sql);
            } finally {
                if (connection != null) {
                    release(connection);
                }
            }
        }
    }

    // ------------------------------------------------------------
    //  Tareas asíncronas
    // ------------------------------------------------------------

    /** Encola una escritura en el pool de base de datos. */
    public void async(Runnable task) {
        if (closed) {
            return;
        }
        executor.execute(() -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Tarea de base de datos falló: " + throwable.getMessage());
                if (plugin.getSettings().debug()) {
                    throwable.printStackTrace();
                }
            }
        });
    }

    // ------------------------------------------------------------
    //  Esquema
    // ------------------------------------------------------------

    public String table(String base) {
        return prefix + base;
    }

    public Dialect dialect() {
        return dialect;
    }

    /**
     * SQL de "insertar o actualizar" para el dialecto actual.
     *
     * @param columns    columnas que se insertan
     * @param keyColumns columnas que forman la clave única
     */
    public String upsert(String table, List<String> columns, List<String> keyColumns) {
        String values = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table)
                .append(" (").append(String.join(", ", columns)).append(")")
                .append(" VALUES (").append(values).append(")");
        List<String> sets = new ArrayList<>();
        for (String column : columns) {
            if (!keyColumns.contains(column)) {
                sets.add(mysql() ? column + " = VALUES(" + column + ")" : column + " = excluded." + column);
            }
        }
        if (mysql()) {
            sql.append(" ON DUPLICATE KEY UPDATE ").append(String.join(", ", sets));
        } else {
            sql.append(" ON CONFLICT(").append(String.join(", ", keyColumns))
                    .append(") DO UPDATE SET ").append(String.join(", ", sets));
        }
        return sql.toString();
    }

    public boolean mysql() {
        return dialect == Dialect.MYSQL;
    }

    /** Crea (si faltan) todas las tablas e índices. */
    public void migrate() {
        boolean mysql = dialect == Dialect.MYSQL;
        String pk = mysql ? "INT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String id = mysql ? "CHAR(36)" : "TEXT";
        String ip = mysql ? "VARCHAR(45)" : "TEXT";
        String shortText = mysql ? "VARCHAR(255)" : "TEXT";
        String blob = mysql ? "LONGBLOB" : "BLOB";
        String flag = mysql ? "TINYINT(1)" : "INTEGER";
        String dec = mysql ? "DOUBLE" : "REAL";
        String indexSuffix = mysql ? ", KEY idx_%T% (%C%)" : "";

        List<String> ddl = new ArrayList<>();

        ddl.add("CREATE TABLE IF NOT EXISTS " + table("sanctions") + " ("
                + "id " + pk + ", "
                + "type VARCHAR(16) NOT NULL, "
                + "target_uuid " + id + " NOT NULL, "
                + "target_name " + shortText + " NOT NULL, "
                + "ip " + ip + ", "
                + "reason TEXT, "
                + "staff_uuid " + id + ", "
                + "staff_name " + shortText + ", "
                + "created_at BIGINT NOT NULL, "
                + "expires_at BIGINT, "
                + "active " + flag + " NOT NULL DEFAULT 1, "
                + "silent " + flag + " NOT NULL DEFAULT 0, "
                + "lifted_by " + id + ", "
                + "lifted_name " + shortText + ", "
                + "lifted_at BIGINT, "
                + "lift_reason TEXT, "
                + "ladder " + shortText + ", "
                + "template " + shortText + ", "
                + "origin " + shortText
                + indexSuffix.replace("%T%", "sanctions_target").replace("%C%", "target_uuid")
                + indexSuffix.replace("%T%", "sanctions_active").replace("%C%", "active")
                + ")");

        ddl.add("CREATE TABLE IF NOT EXISTS " + table("notes") + " ("
                + "id " + pk + ", "
                + "target_uuid " + id + " NOT NULL, "
                + "target_name " + shortText + " NOT NULL, "
                + "author_uuid " + id + ", "
                + "author_name " + shortText + ", "
                + "text TEXT, "
                + "created_at BIGINT NOT NULL, "
                + "origin " + shortText
                + indexSuffix.replace("%T%", "notes_target").replace("%C%", "target_uuid")
                + ")");

        ddl.add("CREATE TABLE IF NOT EXISTS " + table("reports") + " ("
                + "id " + pk + ", "
                + "reporter_uuid " + id + " NOT NULL, "
                + "reporter_name " + shortText + " NOT NULL, "
                + "target_uuid " + id + " NOT NULL, "
                + "target_name " + shortText + " NOT NULL, "
                + "reason TEXT, "
                + "created_at BIGINT NOT NULL, "
                + "status VARCHAR(16) NOT NULL, "
                + "claimed_by " + id + ", "
                + "claimed_name " + shortText + ", "
                + "claimed_at BIGINT, "
                + "resolved_by " + id + ", "
                + "resolved_name " + shortText + ", "
                + "resolved_at BIGINT, "
                + "resolution TEXT, "
                + "origin " + shortText
                + indexSuffix.replace("%T%", "reports_status").replace("%C%", "status")
                + indexSuffix.replace("%T%", "reports_target").replace("%C%", "target_uuid")
                + ")");

        ddl.add("CREATE TABLE IF NOT EXISTS " + table("players") + " ("
                + "uuid " + id + " NOT NULL PRIMARY KEY, "
                + "name " + shortText + ", "
                + "first_seen BIGINT NOT NULL, "
                + "last_seen BIGINT NOT NULL, "
                + "last_login BIGINT, "
                + "last_logout BIGINT, "
                + "playtime_seconds BIGINT NOT NULL DEFAULT 0, "
                + "sessions INT NOT NULL DEFAULT 0, "
                + "frozen " + flag + " NOT NULL DEFAULT 0, "
                + "duty " + flag + " NOT NULL DEFAULT 0, "
                + "last_ip " + ip
                + indexSuffix.replace("%T%", "players_name").replace("%C%", "name")
                + ")");

        ddl.add("CREATE TABLE IF NOT EXISTS " + table("player_ips") + " ("
                + "uuid " + id + " NOT NULL, "
                + "ip " + ip + " NOT NULL, "
                + "first_seen BIGINT NOT NULL, "
                + "last_seen BIGINT NOT NULL, "
                + "logins INT NOT NULL DEFAULT 1, "
                + "PRIMARY KEY (uuid, ip)"
                + indexSuffix.replace("%T%", "player_ips_ip").replace("%C%", "ip")
                + ")");

        ddl.add("CREATE TABLE IF NOT EXISTS " + table("chat_log") + " ("
                + "id " + pk + ", "
                + "uuid " + id + ", "
                + "name " + shortText + ", "
                + "message TEXT, "
                + "created_at BIGINT NOT NULL, "
                + "origin " + shortText
                + indexSuffix.replace("%T%", "chat_log_uuid").replace("%C%", "uuid")
                + indexSuffix.replace("%T%", "chat_log_time").replace("%C%", "created_at")
                + ")");

        ddl.add("CREATE TABLE IF NOT EXISTS " + table("audit_log") + " ("
                + "id " + pk + ", "
                + "actor_uuid " + id + ", "
                + "actor_name " + shortText + ", "
                + "action " + shortText + ", "
                + "target " + shortText + ", "
                + "details TEXT, "
                + "created_at BIGINT NOT NULL, "
                + "origin " + shortText
                + indexSuffix.replace("%T%", "audit_actor").replace("%C%", "actor_uuid")
                + indexSuffix.replace("%T%", "audit_time").replace("%C%", "created_at")
                + ")");

        ddl.add("CREATE TABLE IF NOT EXISTS " + table("snapshots") + " ("
                + "id " + pk + ", "
                + "uuid " + id + " NOT NULL, "
                + "name " + shortText + ", "
                + "reason " + shortText + ", "
                + "created_at BIGINT NOT NULL, "
                + "kind " + shortText + ", "
                + "size INT NOT NULL DEFAULT 0, "
                + "data " + blob
                + indexSuffix.replace("%T%", "snapshots_uuid").replace("%C%", "uuid")
                + ")");

        // Cárceles: un punto con radio de fuga. El nombre es único.
        ddl.add("CREATE TABLE IF NOT EXISTS " + table("jails") + " ("
                + "id " + pk + ", "
                + "name VARCHAR(64) NOT NULL UNIQUE, "
                + "world VARCHAR(128) NOT NULL, "
                + "x " + dec + " NOT NULL, "
                + "y " + dec + " NOT NULL, "
                + "z " + dec + " NOT NULL, "
                + "yaw " + dec + " NOT NULL DEFAULT 0, "
                + "pitch " + dec + " NOT NULL DEFAULT 0, "
                + "radius " + dec + " NOT NULL DEFAULT 0, "
                + "created_at BIGINT NOT NULL, "
                + "created_by " + shortText
                + ")");

        // Condenas: remaining_seconds = -1 es cadena perpetua; se descuenta el tiempo
        // jugado (mientras el preso está conectado) y se guarda al salir.
        ddl.add("CREATE TABLE IF NOT EXISTS " + table("jail_sentences") + " ("
                + "id " + pk + ", "
                + "target_uuid " + id + " NOT NULL, "
                + "target_name " + shortText + " NOT NULL, "
                + "jail VARCHAR(64) NOT NULL, "
                + "reason TEXT, "
                + "staff_uuid " + id + ", "
                + "staff_name " + shortText + ", "
                + "created_at BIGINT NOT NULL, "
                + "remaining_seconds BIGINT NOT NULL DEFAULT -1, "
                + "active " + flag + " NOT NULL DEFAULT 1, "
                + "released_at BIGINT, "
                + "released_name " + shortText + ", "
                + "release_reason TEXT, "
                // Posición donde estaba al entrar: es donde vuelve al salir.
                + "orig_world VARCHAR(128), "
                + "orig_x " + dec + ", "
                + "orig_y " + dec + ", "
                + "orig_z " + dec + ", "
                + "orig_yaw " + dec + ", "
                + "orig_pitch " + dec + ", "
                + "origin " + shortText
                + indexSuffix.replace("%T%", "jail_target").replace("%C%", "target_uuid")
                + indexSuffix.replace("%T%", "jail_active").replace("%C%", "active")
                + ")");

        // Copias completas para /rollback: inventario, enderchest, posición y stats.
        ddl.add("CREATE TABLE IF NOT EXISTS " + table("inventory_backups") + " ("
                + "id " + pk + ", "
                + "uuid " + id + " NOT NULL, "
                + "name " + shortText + ", "
                + "reason VARCHAR(32) NOT NULL, "
                + "created_at BIGINT NOT NULL, "
                + "inventory " + blob + ", "
                + "enderchest " + blob + ", "
                + "world VARCHAR(128), "
                + "x " + dec + ", "
                + "y " + dec + ", "
                + "z " + dec + ", "
                + "yaw " + dec + ", "
                + "pitch " + dec + ", "
                + "health " + dec + ", "
                + "food INT, "
                + "xp INT, "
                + "gamemode VARCHAR(16), "
                + "size INT NOT NULL DEFAULT 0"
                + indexSuffix.replace("%T%", "backups_uuid").replace("%C%", "uuid")
                + indexSuffix.replace("%T%", "backups_time").replace("%C%", "created_at")
                + ")");

        // Inventario confiscado al entrar en la cárcel. Se guarda aparte para poder
        // devolverlo exacto al salir (incluidas armadura y mano secundaria).
        ddl.add("CREATE TABLE IF NOT EXISTS " + table("jail_inventory") + " ("
                + "id " + pk + ", "
                + "sentence_id BIGINT NOT NULL, "
                + "uuid " + id + " NOT NULL, "
                + "data " + blob + ", "
                + "created_at BIGINT NOT NULL, "
                + "returned " + flag + " NOT NULL DEFAULT 0"
                + indexSuffix.replace("%T%", "jail_inv_uuid").replace("%C%", "uuid")
                + ")");

        ddl.add("CREATE TABLE IF NOT EXISTS " + table("jail_gamemode") + " ("
                + "uuid " + id + " NOT NULL PRIMARY KEY, "
                + "gamemode VARCHAR(16) NOT NULL, "
                + "sentence_id BIGINT NOT NULL DEFAULT 0, "
                + "created_at BIGINT NOT NULL"
                + ")");

        ddl.add("CREATE TABLE IF NOT EXISTS " + table("meta") + " ("
                + "k VARCHAR(64) NOT NULL PRIMARY KEY, "
                + "v TEXT"
                + ")");

        executeAll(ddl);

        if (!mysql) {
            executeAll(List.of(
                    "CREATE INDEX IF NOT EXISTS idx_sanctions_target ON " + table("sanctions") + " (target_uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_sanctions_active ON " + table("sanctions") + " (active)",
                    "CREATE INDEX IF NOT EXISTS idx_notes_target ON " + table("notes") + " (target_uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_reports_status ON " + table("reports") + " (status)",
                    "CREATE INDEX IF NOT EXISTS idx_players_name ON " + table("players") + " (name)",
                    // Búsquedas por nombre sin distinguir mayúsculas: sin este índice
                    // LOWER(name) recorría la tabla entera en cada comando.
                    "CREATE INDEX IF NOT EXISTS idx_players_name_nocase ON " + table("players")
                            + " (name COLLATE NOCASE)",
                    "CREATE INDEX IF NOT EXISTS idx_player_ips_ip ON " + table("player_ips") + " (ip)",
                    "CREATE INDEX IF NOT EXISTS idx_chat_log_uuid ON " + table("chat_log") + " (uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_chat_log_time ON " + table("chat_log") + " (created_at)",
                    "CREATE INDEX IF NOT EXISTS idx_audit_actor ON " + table("audit_log") + " (actor_uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_snapshots_uuid ON " + table("snapshots") + " (uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_jail_target ON " + table("jail_sentences") + " (target_uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_jail_active ON " + table("jail_sentences") + " (active)",
                    "CREATE INDEX IF NOT EXISTS idx_jail_inv_uuid ON " + table("jail_inventory") + " (uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_backups_uuid ON " + table("inventory_backups") + " (uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_backups_time ON " + table("inventory_backups") + " (created_at)"));
        }

        String storedVersion = queryString("SELECT v FROM " + table("meta") + " WHERE k = ?", "schema_version");
        if (storedVersion == null) {
            update("INSERT INTO " + table("meta") + " (k, v) VALUES (?, ?)", "schema_version",
                    String.valueOf(SCHEMA_VERSION));
        } else {
            long current;
            try {
                current = Long.parseLong(storedVersion.trim());
            } catch (NumberFormatException ex) {
                current = 0L;
            }
            if (current < SCHEMA_VERSION) {
                // Aquí irán las migraciones de versiones futuras (ALTER TABLE, etc.).
                update("UPDATE " + table("meta") + " SET v = ? WHERE k = ?", String.valueOf(SCHEMA_VERSION),
                        "schema_version");
            }
        }
        plugin.getLogger().info("Base de datos lista (" + dialect.name().toLowerCase(Locale.ROOT) + ").");
    }

    /** Prueba de conexión: devuelve null si todo va bien o el motivo completo si falla. */
    public String test() {
        try {
            queryOne("SELECT 1", rs -> rs.getInt(1));
            return null;
        } catch (Exception ex) {
            return diagnose(ex);
        }
    }

    /**
     * Traduce un fallo de JDBC a algo accionable.
     *
     * <p>Antes se devolvía {@code ex.getMessage()}, que para un fallo de conexión
     * era solo «Error de consulta: SELECT 1»: se perdía el motivo real (contraseña,
     * base inexistente, firewall…) y era imposible diagnosticar desde el log.</p>
     */
    private String diagnose(Exception ex) {
        if (settings.debug()) {
            plugin.getLogger().log(Level.WARNING, "Detalle del fallo de conexión a " + describeTarget(), ex);
        }

        SQLException sql = null;
        for (Throwable cursor = ex; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof SQLException found) {
                sql = found;
            }
        }

        String rootMessage = ex.getMessage();
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String deepest = root.getMessage() == null ? "" : root.getMessage().trim();
        if (!deepest.isEmpty() && (rootMessage == null || !rootMessage.contains(deepest))) {
            rootMessage = (rootMessage == null ? "" : rootMessage + " — ") + deepest;
        }

        StringBuilder out = new StringBuilder();
        out.append(rootMessage == null ? ex.getClass().getSimpleName() : rootMessage.replace('\n', ' '));
        if (sql != null) {
            if (sql.getSQLState() != null) {
                out.append(" [SQLState ").append(sql.getSQLState()).append(']');
            }
            if (sql.getErrorCode() != 0) {
                out.append(" [código ").append(sql.getErrorCode()).append(']');
            }
        }

        String hint = hint(sql, rootMessage == null ? "" : rootMessage, root);
        if (hint != null) {
            out.append(System.lineSeparator()).append("  -> ").append(hint);
        }
        out.append(System.lineSeparator()).append("  -> Probado contra: ").append(describeTarget());
        return out.toString();
    }

    /**
     * Pista según el error de MySQL, para no dejar al usuario adivinando.
     *
     * <p>Se apoya en la excepción y en el código siempre que se puede: el conector
     * traduce sus mensajes al idioma del servidor, así que buscar solo texto en
     * inglés falla (p. ej. «Host desconocido» en vez de «Unknown host»).</p>
     */
    private String hint(SQLException sql, String message, Throwable root) {
        String lower = message.toLowerCase(Locale.ROOT);
        int code = sql == null ? 0 : sql.getErrorCode();
        String state = sql == null || sql.getSQLState() == null ? "" : sql.getSQLState();

        if (root instanceof java.net.UnknownHostException
                || lower.contains("unknownhost") || lower.contains("host desconocido")) {
            return "El host no resuelve: revisa que storage.mysql.host sea el que indica el panel "
                    + "(sin http:// y sin puerto, el puerto va en port).";
        }

        if (lower.contains("public key retrieval")) {
            return "Añade allowPublicKeyRetrieval=true a storage.mysql.parameters (con TLS desactivado, MySQL 8+ lo exige).";
        }
        if (code == 1045) {
            return "Acceso denegado: revisa usuario y contraseña, y que ese usuario tenga permisos sobre la base.";
        }
        if (code == 1044) {
            return "El usuario existe pero no tiene permiso sobre esa base de datos: concédelo en el panel del hosting.";
        }
        if (code == 1049) {
            return "Esa base de datos no existe. En hostings suele llevar el prefijo del usuario (ej. usuario_admintools).";
        }
        if (code == 1130) {
            return "El servidor MySQL no permite conexiones desde esta máquina: autoriza el host/IP en el panel.";
        }
        if (code == 1129 || lower.contains("blocked because of many connection errors")) {
            return "Host bloqueado por demasiados intentos fallidos: espera unos minutos y vuelve a probar.";
        }
        if (code == 1698 || lower.contains("unix_socket")) {
            return "Ese usuario usa autenticación por socket del sistema: cámbialo a contraseña (mysql_native_password o caching_sha2_password).";
        }
        if (lower.contains("require_secure_transport") || lower.contains("ssl") && lower.contains("required")) {
            return "El servidor exige TLS: quita useSSL=false de storage.mysql.parameters (o usa sslMode=REQUIRED).";
        }
        if (lower.contains("no suitable driver")) {
            return "Este Paper no cargó el conector de MySQL: usa una versión de Paper que lo incluya o añádelo como librería.";
        }
        if (lower.contains("communications link failure") || lower.contains("connection refused")
                || code == 2002 || code == 2003 || state.equals("08S01")) {
            return "No se pudo abrir el socket: host o puerto incorrectos, firewall, o MySQL no acepta conexiones remotas "
                    + "(en hosting usa el host interno, normalmente localhost, y el puerto del panel).";
        }
        if (code == 3159) {
            return "El servidor exige conexión segura (TLS): ajusta sslMode en storage.mysql.parameters.";
        }
        return null;
    }

    public void close() {
        closed = true;
        executor.shutdown();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        synchronized (idle) {
            while (!idle.isEmpty()) {
                try {
                    idle.pop().close();
                } catch (SQLException ignored) {
                    // nada que hacer
                }
            }
        }
    }
}
