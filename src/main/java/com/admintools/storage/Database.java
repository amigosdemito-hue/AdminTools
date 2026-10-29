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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
        this.maxConnections = dialect == Dialect.SQLITE ? 1 : Math.max(1, settings.poolSize());
        this.permits = new Semaphore(maxConnections, true);
        this.executor = Executors.newFixedThreadPool(Math.max(2, maxConnections),
                runnable -> {
                    Thread thread = new Thread(runnable, "AdminTools-DB");
                    thread.setDaemon(true);
                    return thread;
                });

        if (dialect == Dialect.MYSQL) {
            this.url = "jdbc:mysql://" + settings.mysqlHost() + ":" + settings.mysqlPort() + "/"
                    + settings.mysqlDatabase() + "?" + settings.mysqlParameters();
        } else {
            File folder = plugin.getDataFolder();
            if (!folder.exists() && !folder.mkdirs()) {
                plugin.getLogger().warning("No pude crear la carpeta de datos " + folder.getAbsolutePath());
            }
            this.url = "jdbc:sqlite:" + new File(folder, "data.db").getAbsolutePath();
        }
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

    /** Prueba de conexión: devuelve null si todo va bien o el error si falla. */
    public String test() {
        try {
            queryOne("SELECT 1", rs -> rs.getInt(1));
            return null;
        } catch (Exception ex) {
            return ex.getMessage();
        }
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
