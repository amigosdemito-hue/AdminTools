package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.storage.Database;

/** Modo mantenimiento: sólo entran los jugadores autorizados. */
public final class MaintenanceService {

    private static final String KEY = "maintenance";

    private final AdminToolsPlugin plugin;
    private final Database db;
    private volatile boolean enabled;

    public MaintenanceService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        load();
    }

    private void load() {
        String stored = db.queryString("SELECT v FROM " + db.table("meta") + " WHERE k = ?", KEY);
        enabled = "1".equals(stored);
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
        db.async(() -> db.update(db.upsert(db.table("meta"), java.util.List.of("k", "v"), java.util.List.of("k")),
                KEY, value ? "1" : "0"));
    }
}
