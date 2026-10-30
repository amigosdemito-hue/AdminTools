package com.admintools;

import com.admintools.command.ChatCommands;
import com.admintools.command.CommandOwnership;
import com.admintools.command.CommandRegistry;
import com.admintools.command.InvestigationCommands;
import com.admintools.command.JailCommands;
import com.admintools.command.OpsCommands;
import com.admintools.command.PanelCommands;
import com.admintools.command.RollbackCommands;
import com.admintools.command.ReportCommands;
import com.admintools.command.RootCommand;
import com.admintools.command.SanctionCommands;
import com.admintools.command.StaffCommands;
import com.admintools.command.UtilityCommands;
import com.admintools.config.Messages;
import com.admintools.config.Settings;
import com.admintools.gui.RollbackGui;
import com.admintools.gui.panel.PanelService;
import com.admintools.integration.IntegrationManager;
import com.admintools.listener.ChatListener;
import com.admintools.listener.ConnectionListener;
import com.admintools.listener.JailListener;
import com.admintools.listener.RollbackListener;
import com.admintools.listener.StaffListener;
import com.admintools.listener.StaffModeListener;
import com.admintools.service.ActionLimiter;
import com.admintools.service.AuditService;
import com.admintools.service.BackupService;
import com.admintools.service.ChatInputService;
import com.admintools.service.ChatService;
import com.admintools.service.ConfirmationService;
import com.admintools.service.FollowService;
import com.admintools.service.InventoryBackupService;
import com.admintools.service.JailService;
import com.admintools.service.MaintenanceService;
import com.admintools.service.NoteService;
import com.admintools.service.PlayerService;
import com.admintools.service.ReportService;
import com.admintools.service.RestartService;
import com.admintools.service.RetentionService;
import com.admintools.service.SanctionService;
import com.admintools.service.SnapshotService;
import com.admintools.service.StaffModeService;
import com.admintools.service.StaffStateService;
import com.admintools.service.WatchdogService;
import com.admintools.storage.Database;
import com.admintools.util.SpectatorOpen;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AdminTools — suite de administración para Paper 26.1+.
 *
 * <p>Punto de entrada: carga configuración, mensajes y base de datos, arranca
 * los servicios (sanciones, staff, reportes, chat, operación), registra
 * comandos y listeners, y programa las tareas periódicas.</p>
 */
public final class AdminToolsPlugin extends JavaPlugin {

    /** Id de bStats (0 = métricas desactivadas). */
    private static final int BSTATS_ID = 0;

    private Settings settings;
    private Messages messages;
    private Database database;

    private AuditService auditService;
    private PlayerService playerService;
    private SanctionService sanctionService;
    private NoteService noteService;
    private ReportService reportService;
    private ChatService chatService;
    private StaffStateService staffState;
    private StaffModeService staffMode;
    private FollowService followService;
    private SnapshotService snapshotService;
    private JailService jailService;
    private InventoryBackupService inventoryBackups;
    private RollbackGui rollbackGui;
    private RollbackListener rollbackListener;
    private ChatInputService chatInput;
    private PanelService panels;
    private WatchdogService watchdogService;
    private MaintenanceService maintenanceService;
    private RestartService restartService;
    private BackupService backupService;
    private RetentionService retentionService;
    private ConfirmationService confirmationService;
    private ActionLimiter actionLimiter;
    private IntegrationManager integrations;

    private String serverName = "servidor";

    @Override
    public void onEnable() {
        long started = System.currentTimeMillis();
        saveDefaultConfig();
        warnLegacyFolder();

        settings = new Settings(this);
        messages = new Messages(this);
        messages.load(settings.language());
        serverName = settings.serverName();

        database = new Database(this, settings);
        String error = database.test();
        if (error != null) {
            getLogger().severe("No pude conectar a la base de datos: " + error);
            getLogger().severe("El plugin se desactiva para no trabajar sin datos.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        database.migrate();

        // Servicios
        auditService = new AuditService(this);
        playerService = new PlayerService(this);
        sanctionService = new SanctionService(this);
        noteService = new NoteService(this);
        reportService = new ReportService(this);
        chatService = new ChatService(this);
        staffState = new StaffStateService(this);
        snapshotService = new SnapshotService(this);
        inventoryBackups = new InventoryBackupService(this);
        jailService = new JailService(this);
        // Se carga antes de aceptar jugadores: si alguien entra con condena activa
        // tiene que aparecer en la celda desde el primer segundo.
        jailService.load();
        staffMode = new StaffModeService(this);
        followService = new FollowService(this);
        watchdogService = new WatchdogService(this);
        maintenanceService = new MaintenanceService(this);
        restartService = new RestartService(this);
        backupService = new BackupService(this);
        retentionService = new RetentionService(this);
        confirmationService = new ConfirmationService(this);
        actionLimiter = new ActionLimiter(this);
        chatInput = new ChatInputService(this);
        panels = new PanelService(this, chatInput);

        // Integraciones opcionales
        integrations = new IntegrationManager(this);
        integrations.setup();

        // Comandos
        rollbackGui = new RollbackGui(this);
        CommandRegistry registry = new CommandRegistry(this);
        new RootCommand(this, registry);
        new SanctionCommands(this, registry);
        new StaffCommands(this, registry);
        new UtilityCommands(this, registry);
        new ReportCommands(this, registry);
        new ChatCommands(this, registry);
        new InvestigationCommands(this, registry);
        new JailCommands(this, registry);
        new RollbackCommands(this, registry);
        new OpsCommands(this, registry);
        new PanelCommands(this, registry, panels);
        // Un botón que apunte a un comando renombrado se canta en consola.
        panels.validate(registry);

        // Algunos nombres (p. ej. /restart) ya los tenía el servidor: se recuperan
        // y, si hace falta, se redirigen hacia los manejadores del plugin.
        CommandOwnership ownership = new CommandOwnership(this, registry);
        ownership.verify();

        // Listeners
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(ownership, this);
        pluginManager.registerEvents(new ConnectionListener(this), this);
        // Después de ConnectionListener: al salir, el modo staff ya devolvió el inventario.
        rollbackListener = new RollbackListener(this);
        pluginManager.registerEvents(rollbackListener, this);
        pluginManager.registerEvents(rollbackGui, this);
        pluginManager.registerEvents(new JailListener(this), this);
        pluginManager.registerEvents(new ChatListener(this), this);
        pluginManager.registerEvents(new StaffListener(this), this);
        pluginManager.registerEvents(new StaffModeListener(this), this);
        pluginManager.registerEvents(chatInput, this);
        pluginManager.registerEvents(panels.inventory(), this);

        // Tareas periódicas
        sanctionService.refreshCache();
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, sanctionService::refreshCache, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, sanctionService::expireDue, 600L, 600L);
        followService.start();
        Bukkit.getScheduler().runTaskTimer(this, jailService::tick, 20L, 20L);
        rollbackListener.start();
        watchdogService.start();
        restartService.start();
        backupService.start();
        retentionService.start();

        if (BSTATS_ID > 0) {
            new Metrics(this, BSTATS_ID);
        }

        getLogger().info("AdminTools habilitado en " + (System.currentTimeMillis() - started) + " ms · "
                + (settings.mysql() ? "MySQL" : "SQLite") + " · idioma " + settings.language() + " · "
                + (maintenanceService.enabled() ? "EN MANTENIMIENTO" : "operativo"));
        // Línea de soporte: deja por escrito qué modo de vanish quedó activo.
        getLogger().info("Vanish: contenedores silenciosos="
                + (settings.vanishSilentContainers() ? "ON" : "OFF")
                + " · recogida de ítems y XP=" + (settings.vanishPickup() ? "ON" : "OFF")
                + " · acciones silenciosas=" + (settings.vanishSilentActions() ? "ON" : "OFF")
                + " · brillo por rango=" + (settings.vanishGlow() ? "ON" : "OFF")
                + " · verse entre invisibles="
                + (settings.vanishSeeRequiresVanish() ? "SÓLO EN VANISH" : "CON PERMISO")
                + " · apertura sin animación=" + (SpectatorOpen.available() ? "OK" : "NO DISPONIBLE"));
    }

    /**
     * Aviso de actualización: hasta el renombrado la carpeta era plugins/ServerAdmin.
     * Si sigue ahí, el staff probablemente espera conservar sus datos.
     */
    private void warnLegacyFolder() {
        java.io.File legacy = new java.io.File(getDataFolder().getParentFile(), "ServerAdmin");
        java.io.File current = new java.io.File(getDataFolder(), "data.db");
        if (legacy.isDirectory() && !current.exists()) {
            getLogger().warning("Encontré datos de la versión anterior en plugins/ServerAdmin.");
            getLogger().warning("Para conservarlos, detén el servidor y copia data.db (y backups/) a plugins/AdminTools/.");
            getLogger().warning("Si no los necesitas, ignora este aviso: el plugin empieza de cero.");
        }
    }

    @Override
    public void onDisable() {
        if (watchdogService != null) {
            watchdogService.stop();
        }
        if (restartService != null) {
            restartService.stop();
        }
        if (backupService != null) {
            backupService.stop();
        }
        if (retentionService != null) {
            retentionService.stop();
        }
        if (integrations != null) {
            integrations.shutdown();
        }
        if (staffMode != null) {
            staffMode.disableAll();
        }
        if (followService != null) {
            followService.stop();
        }
        if (rollbackListener != null) {
            rollbackListener.stop();
        }
        if (jailService != null) {
            jailService.shutdown();
        }
        Bukkit.getScheduler().cancelTasks(this);
        if (database != null) {
            database.close();
        }
    }

    /** Recarga configuración, mensajes y todo lo que depende de ellos. */
    public void reloadAll() {
        reloadConfig();
        settings.load();
        messages.load(settings.language());
        serverName = settings.serverName();
        chatService.compileFilters();
        chatService.slowmode(settings.chatSlowmodeSeconds());
        watchdogService.start();
        restartService.start();
        backupService.start();
        retentionService.start();
        sanctionService.refreshCache();
        reportService.clearCache();
        rollbackListener.start();
        integrations.luckPerms().forgetAll();
        integrations.anticheat().refresh();
    }

    // ------------------------------------------------------------
    //  Accesores de servicios
    // ------------------------------------------------------------

    public Settings getSettings() {
        return settings;
    }

    public Messages getMessages() {
        return messages;
    }

    public Database getDatabase() {
        return database;
    }

    public AuditService getAuditService() {
        return auditService;
    }

    public PlayerService getPlayerService() {
        return playerService;
    }

    public SanctionService getSanctionService() {
        return sanctionService;
    }

    public NoteService getNoteService() {
        return noteService;
    }

    public ReportService getReportService() {
        return reportService;
    }

    public ChatService getChatService() {
        return chatService;
    }

    public StaffStateService getStaffState() {
        return staffState;
    }

    public StaffModeService getStaffMode() {
        return staffMode;
    }

    public FollowService getFollowService() {
        return followService;
    }

    public SnapshotService getSnapshotService() {
        return snapshotService;
    }

    public JailService getJailService() {
        return jailService;
    }

    /** Copias completas para /rollback (no confundir con las de /at backup). */
    public RollbackGui getRollbackGui() {
        return rollbackGui;
    }

    public InventoryBackupService getInventoryBackups() {
        return inventoryBackups;
    }


    public WatchdogService getWatchdogService() {
        return watchdogService;
    }

    public MaintenanceService getMaintenanceService() {
        return maintenanceService;
    }

    public RestartService getRestartService() {
        return restartService;
    }

    public BackupService getBackupService() {
        return backupService;
    }

    public RetentionService getRetentionService() {
        return retentionService;
    }

    public ConfirmationService getConfirmations() {
        return confirmationService;
    }

    public ActionLimiter getActionLimiter() {
        return actionLimiter;
    }

    public IntegrationManager getIntegrations() {
        return integrations;
    }

    /** Nombre de esta instancia (config `server-name` o el del servidor). */
    public String getServerName() {
        return serverName == null || serverName.isBlank() ? getServer().getName() : serverName;
    }
}
