package com.admintools.config;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.LadderSpec;
import com.admintools.model.LadderSpec.LadderStep;
import com.admintools.model.PunishmentType;
import com.admintools.model.TemplateSpec;
import com.admintools.util.TimeUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lectura tipada de config.yml, con valores por defecto seguros si falta algo. */
public final class Settings {

    /** Umbrales de aviso del watchdog. */
    public record Thresholds(double tpsWarn, double tpsCritical, double msptWarn, double msptCritical,
                             double memoryWarn, double memoryCritical, double diskWarn) {
    }

    private final AdminToolsPlugin plugin;

    private FileConfiguration config;
    private String language;
    private String storageType;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUser;
    private String mysqlPassword;
    private String mysqlParameters;
    private String tablePrefix;
    private int poolSize;
    private int chatRetentionDays;
    private int ipRetentionDays;
    private int auditRetentionDays;
    private int snapshotsPerPlayer;
    private int purgeIntervalHours;
    private boolean sanctionsBroadcast;
    private boolean notifyStaffOnJoin;
    private boolean silentByDefault;
    private boolean showIpInBroadcast;
    private int warnDecayDays;
    private boolean protectHigherRanks;
    private boolean staffActionLimitEnabled;
    private int staffActionMaxPerMinute;
    private boolean staffModeGiveTools;
    private boolean staffModeBlockPickup;
    private boolean vanishPickup;
    private boolean vanishSilentContainers;
    private Map<String, String> durationLimits;
    private boolean autoEscalateEnabled;
    private int autoEscalateWindowDays;
    private int autoEscalateThreshold;
    private PunishmentType autoEscalateType;
    private long autoEscalateDuration;
    private String autoEscalateReason;
    private Map<String, TemplateSpec> templates;
    private Map<String, LadderSpec> ladders;
    private boolean chatFormatEnabled;
    private String chatFormat;
    private int chatCooldownSeconds;
    private int chatSlowmodeSeconds;
    private boolean antiCapsEnabled;
    private int antiCapsMinLength;
    private int antiCapsMaxPercent;
    private boolean antiSpamEnabled;
    private int antiSpamWindowSeconds;
    private int antiSpamMaxSimilar;
    private boolean antiAdvertisingEnabled;
    private boolean antiAdvertisingNotifyStaff;
    private int antiAdvertisingPunishAfter;
    private List<String> chatFilters;
    private List<String> blockedCommands;
    private boolean chatLogEnabled;
    private int reportCooldownSeconds;
    private int reportMaxOpen;
    private boolean reportNotifyStaff;
    private boolean reportAnonymous;
    private boolean reportRequireOnline;
    private boolean altsEnabled;
    private boolean altsCgnatExempt;
    private boolean altsBlockMulti;
    private boolean altsNotifyStaff;
    private boolean jailEnabled;
    private String jailDefault;
    private long jailDefaultDuration;
    private boolean jailReturnOnEscape;
    private boolean jailNotifyStaff;
    private boolean jailReleaseToOriginal;
    private boolean jailBlockDamage;
    private List<String> jailAllowedCommands;
    private boolean jailConfiscateInventory;
    private double jailDefaultRadius;
    private boolean jailAdventureMode;
    private boolean rollbackEnabled;
    private boolean rollbackJoin;
    private boolean rollbackQuit;
    private boolean rollbackDeath;
    private boolean rollbackWorldChange;
    private int rollbackIntervalMinutes;
    private int rollbackKeepDeath;
    private int rollbackKeepJoin;
    private int rollbackKeepQuit;
    private int rollbackKeepManual;
    private int rollbackPageSize;
    private boolean watchdogEnabled;
    private int watchdogIntervalSeconds;
    private Thresholds watchdogThresholds;
    private int watchdogAlertCooldownMinutes;
    private boolean watchdogDiscord;
    private boolean restartEnabled;
    private List<String> restartTimes;
    private List<Integer> restartWarnings;
    private String restartCommand;
    private boolean backupsEnabled;
    private int backupsIntervalHours;
    private int backupsKeep;
    private boolean backupsWorlds;
    private boolean backupsPluginConfig;
    private String backupsFolder;
    private boolean discordEnabled;
    private String discordWebhook;
    private String discordUsername;
    private String discordAvatar;
    private Map<String, Boolean> discordEvents;
    private boolean debug;
    private String serverName;
    private boolean hookLuckPerms;
    private boolean hookCoreProtect;
    private boolean hookPlaceholderApi;
    private boolean anticheatAlerts;

    public Settings(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        load();
    }

    /** Relee config.yml (desde disco) y recalcula todos los valores. */
    public void load() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration fromDisk = new YamlConfiguration();
        if (file.exists()) {
            try {
                fromDisk.load(file);
            } catch (Exception ex) {
                plugin.getLogger().warning("No se pudo leer config.yml: " + ex.getMessage());
            }
        }
        YamlConfiguration defaults = new YamlConfiguration();
        try (var in = plugin.getResource("config.yml")) {
            if (in != null) {
                defaults.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("No se pudo leer la config por defecto: " + ex.getMessage());
        }
        fromDisk.setDefaults(defaults);
        fromDisk.options().copyDefaults(true);
        this.config = fromDisk;

        language = str("language", "es").toLowerCase(Locale.ROOT);
        if (!language.equals("es") && !language.equals("en")) {
            plugin.getLogger().warning("Idioma '" + language + "' no soportado, uso 'es'.");
            language = "es";
        }

        storageType = str("storage.type", "sqlite").toLowerCase(Locale.ROOT);
        mysqlHost = str("storage.mysql.host", "localhost");
        mysqlPort = num("storage.mysql.port", 3306);
        mysqlDatabase = str("storage.mysql.database", "admintools");
        mysqlUser = str("storage.mysql.user", "admintools");
        mysqlPassword = str("storage.mysql.password", "");
        mysqlParameters = str("storage.mysql.parameters", "useSSL=false");
        tablePrefix = str("storage.mysql.table-prefix", "sa_");
        poolSize = Math.max(1, num("storage.pool-size", 1));
        chatRetentionDays = num("storage.retention.chat-days", 30);
        ipRetentionDays = num("storage.retention.ip-days", 60);
        auditRetentionDays = num("storage.retention.audit-days", 180);
        snapshotsPerPlayer = Math.max(1, num("storage.retention.snapshots-per-player", 5));
        purgeIntervalHours = Math.max(1, num("storage.retention.purge-interval-hours", 6));

        sanctionsBroadcast = bool("sanctions.broadcast", true);
        notifyStaffOnJoin = bool("sanctions.notify-staff-on-join", true);
        silentByDefault = bool("sanctions.silent-by-default", false);
        showIpInBroadcast = bool("sanctions.show-ip-in-broadcast", false);
        warnDecayDays = Math.max(0, num("sanctions.warn-decay-days", 0));
        protectHigherRanks = bool("sanctions.protect-higher-ranks", true);
        staffActionLimitEnabled = bool("staff.action-limit.enabled", true);
        staffActionMaxPerMinute = Math.max(0, num("staff.action-limit.max-per-minute", 40));
        staffModeGiveTools = bool("staff.mode.give-tools", true);
        staffModeBlockPickup = bool("staff.mode.block-pickup", true);
        vanishPickup = bool("staff.vanish-pickup", false);
        vanishSilentContainers = bool("staff.vanish-silent-containers", true);
        durationLimits = readDurationLimits();
        autoEscalateEnabled = bool("sanctions.auto-escalate.enabled", true);
        autoEscalateWindowDays = num("sanctions.auto-escalate.window-days", 30);
        autoEscalateThreshold = num("sanctions.auto-escalate.threshold", 3);
        autoEscalateType = punishmentType(str("sanctions.auto-escalate.action.type", "MUTE"), PunishmentType.MUTE);
        autoEscalateDuration = duration(str("sanctions.auto-escalate.action.duration", "1d"), TimeUtil.PERMANENT);
        autoEscalateReason = str("sanctions.auto-escalate.action.reason", "Accumulated warnings");
        templates = readTemplates();
        ladders = readLadders();

        chatFormatEnabled = bool("chat.format.enabled", false);
        chatFormat = str("chat.format.format", "<white>%player%<gray>: <reset>%message%");
        chatCooldownSeconds = Math.max(0, num("chat.cooldown-seconds", 3));
        chatSlowmodeSeconds = Math.max(0, num("chat.slowmode-seconds", 0));
        antiCapsEnabled = bool("chat.anti-caps.enabled", true);
        antiCapsMinLength = num("chat.anti-caps.min-length", 12);
        antiCapsMaxPercent = num("chat.anti-caps.max-percent", 70);
        antiSpamEnabled = bool("chat.anti-spam.enabled", true);
        antiSpamWindowSeconds = num("chat.anti-spam.window-seconds", 10);
        antiSpamMaxSimilar = num("chat.anti-spam.max-similar", 3);
        antiAdvertisingEnabled = bool("chat.anti-advertising.enabled", true);
        antiAdvertisingNotifyStaff = bool("chat.anti-advertising.notify-staff", true);
        antiAdvertisingPunishAfter = num("chat.anti-advertising.punish-after", 2);
        chatFilters = list("chat.filters");
        blockedCommands = list("chat.blocked-commands");
        chatLogEnabled = bool("chat.log.enabled", true);

        reportCooldownSeconds = Math.max(0, num("reports.cooldown-seconds", 120));
        reportMaxOpen = Math.max(1, num("reports.max-open-per-player", 3));
        reportNotifyStaff = bool("reports.notify-staff", true);
        reportAnonymous = bool("reports.anonymous", false);
        reportRequireOnline = bool("reports.require-online-target", true);

        altsEnabled = bool("alts.enabled", true);
        altsCgnatExempt = bool("alts.cgnat-exempt", false);
        altsBlockMulti = bool("alts.block-multi-account", false);
        altsNotifyStaff = bool("alts.notify-staff-on-new-alt", true);

        jailEnabled = bool("jail.enabled", true);
        jailDefault = str("jail.default", "spawn");
        jailDefaultDuration = duration(str("jail.default-duration", "permanent"), TimeUtil.PERMANENT);
        jailReturnOnEscape = bool("jail.return-on-escape", true);
        jailNotifyStaff = bool("jail.notify-staff", true);
        jailReleaseToOriginal = bool("jail.release-to-original", true);
        jailBlockDamage = bool("jail.block-damage", true);
        jailConfiscateInventory = bool("jail.confiscate-inventory", true);
        // Radio por defecto de /setjail: 2 bloques = área cuadrada de 5x5.
        jailDefaultRadius = Math.max(0.0, dbl("jail.default-radius", 2.0));
        jailAdventureMode = bool("jail.adventure-mode", true);
        jailAllowedCommands = list("jail.allowed-commands");
        if (jailAllowedCommands.isEmpty()) {
            // config.yml antiguo (sin esta sección): se dejan los comandos de acceso.
            // Bloquearlos dejaría al preso sin poder autenticarse.
            jailAllowedCommands = List.of("login", "register", "l", "reg", "auth", "changepassword");
        }

        rollbackEnabled = bool("rollback.enabled", true);
        rollbackJoin = bool("rollback.events.join", true);
        rollbackQuit = bool("rollback.events.quit", true);
        rollbackDeath = bool("rollback.events.death", true);
        rollbackWorldChange = bool("rollback.events.world-change", true);
        rollbackIntervalMinutes = Math.max(0, num("rollback.interval-minutes", 0));
        rollbackKeepDeath = Math.max(1, num("rollback.keep.death", 30));
        rollbackKeepJoin = Math.max(1, num("rollback.keep.join", 10));
        rollbackKeepQuit = Math.max(1, num("rollback.keep.quit", 10));
        rollbackKeepManual = Math.max(1, num("rollback.keep.manual", 20));
        rollbackPageSize = Math.max(5, num("rollback.page-size", 8));

        watchdogEnabled = bool("watchdog.enabled", true);
        watchdogIntervalSeconds = Math.max(5, num("watchdog.interval-seconds", 15));
        watchdogThresholds = new Thresholds(
                dbl("watchdog.thresholds.tps-warn", 18.0),
                dbl("watchdog.thresholds.tps-critical", 15.0),
                dbl("watchdog.thresholds.mspt-warn", 45.0),
                dbl("watchdog.thresholds.mspt-critical", 60.0),
                dbl("watchdog.thresholds.memory-warn-percent", 85.0),
                dbl("watchdog.thresholds.memory-critical-percent", 92.0),
                dbl("watchdog.thresholds.disk-warn-percent", 90.0));
        watchdogAlertCooldownMinutes = Math.max(1, num("watchdog.alert-cooldown-minutes", 10));
        watchdogDiscord = bool("watchdog.discord", true);

        restartEnabled = bool("restart.enabled", false);
        restartTimes = list("restart.times");
        restartWarnings = intList("restart.warnings-seconds");
        if (restartWarnings.isEmpty()) {
            restartWarnings = List.of(600, 300, 60, 30, 10);
        }
        restartCommand = str("restart.command", "");

        backupsEnabled = bool("backups.enabled", false);
        backupsIntervalHours = Math.max(1, num("backups.interval-hours", 6));
        backupsKeep = Math.max(1, num("backups.keep", 5));
        backupsWorlds = bool("backups.worlds", true);
        backupsPluginConfig = bool("backups.plugins-config", true);
        backupsFolder = str("backups.folder", "backups");

        discordEnabled = bool("discord.enabled", false);
        discordWebhook = str("discord.webhook-url", "");
        discordUsername = str("discord.username", "AdminTools");
        discordAvatar = str("discord.avatar-url", "");
        Map<String, Boolean> events = new LinkedHashMap<>();
        for (String event : List.of("ban", "mute", "kick", "warn", "report", "watchdog")) {
            events.put(event, bool("discord.events." + event, true));
        }
        discordEvents = Map.copyOf(events);

        debug = bool("debug", false);
        serverName = str("server-name", "");
        if (serverName.isBlank()) {
            serverName = org.bukkit.Bukkit.getServer().getName();
        }
        hookLuckPerms = bool("integrations.luckperms", true);
        hookCoreProtect = bool("integrations.coreprotect", true);
        hookPlaceholderApi = bool("integrations.placeholderapi", true);
        anticheatAlerts = bool("integrations.anticheat-alerts", true);
    }

    // ------------------------------------------------------------
    //  Lectura de estructuras complejas
    // ------------------------------------------------------------

    private Map<String, String> readDurationLimits() {
        Map<String, String> raw = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("sanctions.duration-limits");
        if (section != null) {
            flatten(section, "", raw);
        }
        Map<String, String> limits = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String node = entry.getKey();
            // Compatibilidad: las configs anteriores al renombrado usaban "serveradmin.*".
            if (node.startsWith("serveradmin.")) {
                String migrated = "admintools." + node.substring("serveradmin.".length());
                plugin.getLogger().warning("Config: el nodo de límite '" + node + "' ahora se llama '"
                        + migrated + "'. Actualízalo en sanctions.duration-limits.");
                node = migrated;
            }
            limits.put(node, entry.getValue());
        }
        if (limits.isEmpty()) {
            limits.put("admintools.limit.admin", "permanent");
        }
        if (bool("debug", false)) {
            plugin.getLogger().info("Límites de duración por rango: " + limits);
        }
        return limits;
    }

    /**
     * Los nombres de permiso llevan puntos y YamlConfiguration los interpreta como
     * rutas anidadas (admintools.limit.mod → admintools → limit → mod). Aquí se
     * vuelven a unir para recuperar el nodo real.
     */
    private void flatten(ConfigurationSection section, String prefix, Map<String, String> out) {
        for (String key : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                flatten(child, prefix + key + ".", out);
            } else {
                out.put(prefix + key, String.valueOf(section.get(key)));
            }
        }
    }

    private Map<String, TemplateSpec> readTemplates() {
        Map<String, TemplateSpec> result = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("sanctions.templates");
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            String base = "sanctions.templates." + key + ".";
            PunishmentType type = punishmentType(str(base + "type", "BAN"), PunishmentType.BAN);
            long duration = duration(str(base + "duration", "permanent"), TimeUtil.PERMANENT);
            String reason = str(base + "reason", key);
            result.put(key.toLowerCase(Locale.ROOT), new TemplateSpec(key, type, duration, reason));
        }
        return result;
    }

    private Map<String, LadderSpec> readLadders() {
        Map<String, LadderSpec> result = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("sanctions.ladders");
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            String base = "sanctions.ladders." + key + ".";
            int windowDays = num(base + "window-days", 30);
            List<Map<?, ?>> rawSteps = config.getMapList(base + "steps");
            List<LadderStep> steps = new ArrayList<>();
            for (Map<?, ?> rawStep : rawSteps) {
                Object typeValue = rawStep.get("type");
                Object durationValue = rawStep.get("duration");
                Object reasonValue = rawStep.get("reason");
                PunishmentType type = punishmentType(typeValue == null ? "WARN" : String.valueOf(typeValue),
                        PunishmentType.WARN);
                long duration = durationValue == null ? TimeUtil.PERMANENT
                        : duration(String.valueOf(durationValue), TimeUtil.PERMANENT);
                String reason = reasonValue == null ? key : String.valueOf(reasonValue);
                steps.add(new LadderStep(type, duration, reason));
            }
            if (!steps.isEmpty()) {
                result.put(key.toLowerCase(Locale.ROOT), new LadderSpec(key, windowDays, steps));
            }
        }
        return result;
    }

    private PunishmentType punishmentType(String value, PunishmentType fallback) {
        try {
            return PunishmentType.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Tipo de sanción desconocido '" + value + "', uso " + fallback);
            return fallback;
        }
    }

    private long duration(String value, long fallback) {
        Long parsed = TimeUtil.parse(value);
        if (parsed == null) {
            plugin.getLogger().warning("Duración inválida '" + value + "' en config, uso el valor por defecto.");
            return fallback;
        }
        return parsed;
    }

    // ------------------------------------------------------------
    //  Accesores
    // ------------------------------------------------------------

    private String str(String path, String def) {
        String value = config.getString(path);
        return value == null ? def : value;
    }

    private int num(String path, int def) {
        return config.isSet(path) ? config.getInt(path, def) : def;
    }

    private double dbl(String path, double def) {
        return config.isSet(path) ? config.getDouble(path, def) : def;
    }

    private boolean bool(String path, boolean def) {
        return config.isSet(path) ? config.getBoolean(path, def) : def;
    }

    private List<String> list(String path) {
        List<String> values = config.getStringList(path);
        return values.isEmpty() ? List.of() : List.copyOf(values);
    }

    private List<Integer> intList(String path) {
        return List.copyOf(config.getIntegerList(path));
    }

    public String language() {
        return language;
    }

    public String storageType() {
        return storageType;
    }

    public boolean mysql() {
        return storageType.equals("mysql");
    }

    public String mysqlHost() {
        return mysqlHost;
    }

    public int mysqlPort() {
        return mysqlPort;
    }

    public String mysqlDatabase() {
        return mysqlDatabase;
    }

    public String mysqlUser() {
        return mysqlUser;
    }

    public String mysqlPassword() {
        return mysqlPassword;
    }

    public String mysqlParameters() {
        return mysqlParameters;
    }

    public String tablePrefix() {
        return tablePrefix;
    }

    public int poolSize() {
        return poolSize;
    }

    public int chatRetentionDays() {
        return chatRetentionDays;
    }

    public int ipRetentionDays() {
        return ipRetentionDays;
    }

    public int auditRetentionDays() {
        return auditRetentionDays;
    }

    public int snapshotsPerPlayer() {
        return snapshotsPerPlayer;
    }

    public int purgeIntervalHours() {
        return purgeIntervalHours;
    }

    public boolean sanctionsBroadcast() {
        return sanctionsBroadcast;
    }

    public boolean notifyStaffOnJoin() {
        return notifyStaffOnJoin;
    }

    public boolean silentByDefault() {
        return silentByDefault;
    }

    public boolean showIpInBroadcast() {
        return showIpInBroadcast;
    }

    /** ¿Un jugador en vanish puede recoger ítems del suelo? */
    public boolean vanishPickup() {
        return vanishPickup;
    }

    /** ¿Los cofres se abren sin animación visible para los demás cuando hay vanish? */
    public boolean vanishSilentContainers() {
        return vanishSilentContainers;
    }

    public Map<String, String> durationLimits() {
        return durationLimits;
    }

    /**
     * Días tras los que una advertencia deja de contar para el escalado automático.
     * 0 = las advertencias no caducan (comportamiento clásico).
     */
    public int warnDecayDays() {
        return warnDecayDays;
    }

    /** Impide sancionar a alguien del mismo rango o superior. */
    public boolean protectHigherRanks() {
        return protectHigherRanks;
    }

    // ------------------------------------------------------------
    //  Cárcel
    // ------------------------------------------------------------

    public boolean jailEnabled() {
        return jailEnabled;
    }

    public String jailDefault() {
        return jailDefault;
    }

    /** Duración por defecto de una condena (TimeUtil.PERMANENT si no se configura). */
    public long jailDefaultDuration() {
        return jailDefaultDuration;
    }

    public boolean jailReturnOnEscape() {
        return jailReturnOnEscape;
    }

    public boolean jailNotifyStaff() {
        return jailNotifyStaff;
    }

    public boolean jailReleaseToOriginal() {
        return jailReleaseToOriginal;
    }

    public boolean jailBlockDamage() {
        return jailBlockDamage;
    }

    /** Al encarcelar se le quita el inventario y se le devuelve al salir. */
    public boolean jailConfiscateInventory() {
        return jailConfiscateInventory;
    }

    /** Comandos que un preso puede seguir usando (/report, /jailtime...). */
    public List<String> jailAllowedCommands() {
        return jailAllowedCommands;
    }

    /** Radio por defecto de una celda nueva (2 bloques = 5x5). */
    public double jailDefaultRadius() {
        return jailDefaultRadius;
    }

    /**
     * Al entrar en la cárcel se le pone en modo aventura: el propio cliente deja de
     * romper y colocar bloques, así que no se producen bloques fantasma (Bedrock).
     */
    public boolean jailAdventureMode() {
        return jailAdventureMode;
    }

    // ------------------------------------------------------------
    //  Rollback de inventarios
    // ------------------------------------------------------------

    public boolean rollbackEnabled() {
        return rollbackEnabled;
    }

    public boolean rollbackJoin() {
        return rollbackJoin;
    }

    public boolean rollbackQuit() {
        return rollbackQuit;
    }

    public boolean rollbackDeath() {
        return rollbackDeath;
    }

    public boolean rollbackWorldChange() {
        return rollbackWorldChange;
    }

    /** Minutos entre copias automáticas (0 = desactivado). */
    public int rollbackIntervalMinutes() {
        return rollbackIntervalMinutes;
    }

    public int rollbackKeepDeath() {
        return rollbackKeepDeath;
    }

    public int rollbackKeepJoin() {
        return rollbackKeepJoin;
    }

    public int rollbackKeepQuit() {
        return rollbackKeepQuit;
    }

    public int rollbackKeepManual() {
        return rollbackKeepManual;
    }

    public int rollbackPageSize() {
        return rollbackPageSize;
    }

    public boolean staffActionLimitEnabled() {
        return staffActionLimitEnabled;
    }

    /** Acciones sensibles permitidas por minuto y por staff (0 = sin límite). */
    public int staffActionMaxPerMinute() {
        return staffActionMaxPerMinute;
    }

    public boolean staffModeGiveTools() {
        return staffModeGiveTools;
    }

    public boolean staffModeBlockPickup() {
        return staffModeBlockPickup;
    }

    public boolean autoEscalateEnabled() {
        return autoEscalateEnabled;
    }

    public int autoEscalateWindowDays() {
        return autoEscalateWindowDays;
    }

    public int autoEscalateThreshold() {
        return autoEscalateThreshold;
    }

    public PunishmentType autoEscalateType() {
        return autoEscalateType;
    }

    public long autoEscalateDuration() {
        return autoEscalateDuration;
    }

    public String autoEscalateReason() {
        return autoEscalateReason;
    }

    public Map<String, TemplateSpec> templates() {
        return templates;
    }

    public Map<String, LadderSpec> ladders() {
        return ladders;
    }

    public boolean chatFormatEnabled() {
        return chatFormatEnabled;
    }

    public String chatFormat() {
        return chatFormat;
    }

    public int chatCooldownSeconds() {
        return chatCooldownSeconds;
    }

    public int chatSlowmodeSeconds() {
        return chatSlowmodeSeconds;
    }

    public boolean antiCapsEnabled() {
        return antiCapsEnabled;
    }

    public int antiCapsMinLength() {
        return antiCapsMinLength;
    }

    public int antiCapsMaxPercent() {
        return antiCapsMaxPercent;
    }

    public boolean antiSpamEnabled() {
        return antiSpamEnabled;
    }

    public int antiSpamWindowSeconds() {
        return antiSpamWindowSeconds;
    }

    public int antiSpamMaxSimilar() {
        return antiSpamMaxSimilar;
    }

    public boolean antiAdvertisingEnabled() {
        return antiAdvertisingEnabled;
    }

    public boolean antiAdvertisingNotifyStaff() {
        return antiAdvertisingNotifyStaff;
    }

    public int antiAdvertisingPunishAfter() {
        return antiAdvertisingPunishAfter;
    }

    public List<String> chatFilters() {
        return chatFilters;
    }

    public List<String> blockedCommands() {
        return blockedCommands;
    }

    public boolean chatLogEnabled() {
        return chatLogEnabled;
    }

    public int reportCooldownSeconds() {
        return reportCooldownSeconds;
    }

    public int reportMaxOpen() {
        return reportMaxOpen;
    }

    public boolean reportNotifyStaff() {
        return reportNotifyStaff;
    }

    public boolean reportAnonymous() {
        return reportAnonymous;
    }

    public boolean reportRequireOnline() {
        return reportRequireOnline;
    }

    public boolean altsEnabled() {
        return altsEnabled;
    }

    public boolean altsCgnatExempt() {
        return altsCgnatExempt;
    }

    public boolean altsBlockMulti() {
        return altsBlockMulti;
    }

    public boolean altsNotifyStaff() {
        return altsNotifyStaff;
    }

    public boolean watchdogEnabled() {
        return watchdogEnabled;
    }

    public int watchdogIntervalSeconds() {
        return watchdogIntervalSeconds;
    }

    public Thresholds watchdogThresholds() {
        return watchdogThresholds;
    }

    public int watchdogAlertCooldownMinutes() {
        return watchdogAlertCooldownMinutes;
    }

    public boolean watchdogDiscord() {
        return watchdogDiscord;
    }

    public boolean restartEnabled() {
        return restartEnabled;
    }

    public List<String> restartTimes() {
        return restartTimes;
    }

    public List<Integer> restartWarnings() {
        return restartWarnings;
    }

    public String restartCommand() {
        return restartCommand;
    }

    public boolean backupsEnabled() {
        return backupsEnabled;
    }

    public int backupsIntervalHours() {
        return backupsIntervalHours;
    }

    public int backupsKeep() {
        return backupsKeep;
    }

    public boolean backupsWorlds() {
        return backupsWorlds;
    }

    public boolean backupsPluginConfig() {
        return backupsPluginConfig;
    }

    public String backupsFolder() {
        return backupsFolder;
    }

    public boolean discordEnabled() {
        return discordEnabled;
    }

    public String discordWebhook() {
        return discordWebhook;
    }

    public String discordUsername() {
        return discordUsername;
    }

    public String discordAvatar() {
        return discordAvatar;
    }

    public boolean discordEvent(String key) {
        return discordEvents.getOrDefault(key, false);
    }

    public boolean debug() {
        return debug;
    }

    /** Nombre de esta instancia (útil cuando varios servidores comparten base de datos). */
    public String serverName() {
        return serverName;
    }

    public boolean hookLuckPerms() {
        return hookLuckPerms;
    }

    public boolean hookCoreProtect() {
        return hookCoreProtect;
    }

    public boolean hookPlaceholderApi() {
        return hookPlaceholderApi;
    }

    public boolean anticheatAlerts() {
        return anticheatAlerts;
    }
}
