package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.Jail;
import com.admintools.model.JailSentence;
import com.admintools.storage.Database;
import com.admintools.util.Text;
import com.admintools.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cárcel con varias celdas: condenas con tiempo jugado, protección contra fugas
 * y liberación automática.
 *
 * <p>Está inspirada en xJail pero con lo que le falta: varias cárceles con radio,
 * historial de condenas con quién y por qué, devolución a la posición original,
 * persistencia del tiempo restante entre reinicios y avisos al staff. Todo se
 * guarda en la base de datos del plugin, así que funciona igual en SQLite y MySQL.</p>
 */
public final class JailService {

    /** Resultado de intentar encarcelar. */
    public enum Result {
        OK, DISABLED, NO_JAIL, ALREADY
    }

    /** Resultado de borrar una cárcel. */
    public enum DeleteResult {
        OK, DISABLED, NOT_FOUND, IN_USE
    }

    private final AdminToolsPlugin plugin;
    private final Database db;
    private final String jailsTable;
    private final String sentencesTable;
    private final String inventoryTable;
    private final String gamemodeTable;

    private final Map<String, Jail> byName = new ConcurrentHashMap<>();
    private final Map<UUID, JailSentence> active = new ConcurrentHashMap<>();
    private final Map<UUID, Long> escapeNotices = new ConcurrentHashMap<>();
    /** Jugadores con un inventario guardado sin devolver (para no consultar en cada entrada). */
    private final java.util.Set<UUID> pendingInventories = ConcurrentHashMap.newKeySet();
    /** Modo de juego original de los presos: se les devuelve al salir. */
    private final Map<UUID, GameMode> savedGameModes = new ConcurrentHashMap<>();
    /** Celdas ya avisadas por apuntar a un mundo inexistente (evita llenar el log cada segundo). */
    private final java.util.Set<String> warnedMissingWorld = ConcurrentHashMap.newKeySet();

    public JailService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        this.jailsTable = db.table("jails");
        this.sentencesTable = db.table("jail_sentences");
        this.inventoryTable = db.table("jail_inventory");
        this.gamemodeTable = db.table("jail_gamemode");
    }

    /** Copia del inventario de un preso, a la espera de devolverse. */
    private record SavedInventory(long id, byte[] data) {
    }

    // ------------------------------------------------------------
    //  Carga y consulta
    // ------------------------------------------------------------

    /** Lee cárceles y condenas activas. Se llama al arrancar, antes de aceptar jugadores. */
    public void load() {
        List<Jail> jails = db.query("SELECT * FROM " + jailsTable + " ORDER BY name", this::mapJail);
        byName.clear();
        for (Jail jail : jails) {
            byName.put(jail.name().toLowerCase(Locale.ROOT), jail);
        }
        List<JailSentence> sentences = db.query(
                "SELECT * FROM " + sentencesTable + " WHERE active = 1", this::mapSentence);
        active.clear();
        for (JailSentence sentence : sentences) {
            active.put(sentence.targetUuid(), sentence);
        }
        pendingInventories.clear();
        for (String raw : db.query("SELECT DISTINCT uuid FROM " + inventoryTable + " WHERE returned = 0",
                rs -> rs.getString("uuid"))) {
            try {
                pendingInventories.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // Fila antigua con UUID ilegible: se ignora.
            }
        }
        savedGameModes.clear();
        for (Object[] row : db.query("SELECT uuid, gamemode FROM " + gamemodeTable,
                rs -> new Object[]{rs.getString("uuid"), rs.getString("gamemode")})) {
            try {
                savedGameModes.put(UUID.fromString((String) row[0]),
                        GameMode.valueOf(((String) row[1]).toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Fila antigua con UUID o modo ilegible: se descarta.
            }
        }
        plugin.getLogger().info("Cárcel: " + byName.size() + " celda(s) y " + active.size()
                + " preso(s), " + pendingInventories.size() + " inventario(s) pendientes.");
    }

    public boolean enabled() {
        return plugin.getSettings().jailEnabled();
    }

    public List<Jail> jails() {
        List<Jail> list = new ArrayList<>(byName.values());
        list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return Collections.unmodifiableList(list);
    }

    public Optional<Jail> jail(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public Optional<JailSentence> active(UUID uuid) {
        return uuid == null ? Optional.empty() : Optional.ofNullable(active.get(uuid));
    }

    public List<JailSentence> activeAll() {
        List<JailSentence> list = new ArrayList<>(active.values());
        list.sort((a, b) -> Long.compare(a.createdAt(), b.createdAt()));
        return list;
    }

    /** Cuántos presos hay en una cárcel concreta. */
    public long jailedCount(String jailName) {
        return active.values().stream().filter(s -> s.jail().equalsIgnoreCase(jailName)).count();
    }

    // ------------------------------------------------------------
    //  Crear y borrar cárceles
    // ------------------------------------------------------------

    public boolean createJail(String name, Location location, double radius, String createdBy) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (byName.containsKey(key)) {
            return false;
        }
        long id = db.insert("INSERT INTO " + jailsTable
                        + " (name, world, x, y, z, yaw, pitch, radius, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                name, location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                (double) location.getYaw(), (double) location.getPitch(), Math.max(0.0, radius),
                System.currentTimeMillis(), createdBy);
        Jail jail = new Jail(id, name, location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch(), Math.max(0.0, radius),
                System.currentTimeMillis(), createdBy);
        byName.put(key, jail);
        return true;
    }

    /** Borra una cárcel vacía. Devuelve IN_USE si todavía tiene presos. */
    public DeleteResult deleteJail(String name) {
        if (!enabled()) {
            return DeleteResult.DISABLED;
        }
        Optional<Jail> found = jail(name);
        if (found.isEmpty()) {
            return DeleteResult.NOT_FOUND;
        }
        if (jailedCount(found.get().name()) > 0) {
            return DeleteResult.IN_USE;
        }
        db.update("DELETE FROM " + jailsTable + " WHERE id = ?", found.get().id());
        byName.remove(found.get().name().toLowerCase(Locale.ROOT));
        return DeleteResult.OK;
    }

    // ------------------------------------------------------------
    //  Encarcelar
    // ------------------------------------------------------------

    public Result jail(Player target, String jailName, long durationMillis, String reason,
                       UUID staffUuid, String staffName) {
        if (!enabled()) {
            return Result.DISABLED;
        }
        Optional<Jail> found = jail(jailName);
        if (found.isEmpty()) {
            return Result.NO_JAIL;
        }
        if (active.containsKey(target.getUniqueId())) {
            return Result.ALREADY;
        }
        Jail jail = found.get();
        long remaining = durationMillis == TimeUtil.PERMANENT ? JailSentence.PERMANENT
                : Math.max(1L, durationMillis / 1000L);
        Location origin = target.getLocation();
        long now = System.currentTimeMillis();
        long id = db.insert("INSERT INTO " + sentencesTable
                        + " (target_uuid, target_name, jail, reason, staff_uuid, staff_name, created_at,"
                        + " remaining_seconds, active, orig_world, orig_x, orig_y, orig_z, orig_yaw, orig_pitch, origin)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)",
                target.getUniqueId().toString(), target.getName(), jail.name(), reason,
                staffUuid == null ? null : staffUuid.toString(), staffName, now, remaining,
                origin.getWorld() == null ? null : origin.getWorld().getName(),
                origin.getX(), origin.getY(), origin.getZ(), (double) origin.getYaw(), (double) origin.getPitch(),
                plugin.getServerName());

        JailSentence sentence = new JailSentence(id, target.getUniqueId(), target.getName(), jail.name(),
                reason, staffUuid, staffName, now, remaining,
                origin.getWorld() == null ? null : origin.getWorld().getName(),
                origin.getX(), origin.getY(), origin.getZ(), origin.getYaw(), origin.getPitch());
        active.put(target.getUniqueId(), sentence);

        // Primero se le quita el inventario (y se guarda) y después se le mueve:
        // si el guardado falla, conserva sus objetos.
        confiscate(target, sentence);
        applyAdventure(target, sentence);
        teleportTo(target, jail);
        target.sendMessage(plugin.getMessages().prefixed("jail.target",
                "jail", jail.name(), "reason", Text.safe(sentence.reason()), "time", timeText(sentence)));

        plugin.getAuditService().log(staffUuid == null ? null : staffUuid.toString(), staffName,
                "jail.apply", target.getName(), jail.name() + " · " + reason);
        plugin.getSanctionAnnounce().jail(sentence);
        if (plugin.getSettings().jailNotifyStaff()) {
            plugin.getStaffState().sendToStaff(plugin.getMessages().get("jail.applied",
                    "player", target.getName(), "jail", jail.name(), "staff", staffName,
                    "time", timeText(sentence), "reason", Text.safe(sentence.reason())));
        }
        return Result.OK;
    }

    // ------------------------------------------------------------
    //  Liberar
    // ------------------------------------------------------------

    public boolean release(UUID uuid, String releasedBy, String reason) {
        JailSentence sentence = active.remove(uuid);
        if (sentence == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        db.async(() -> db.update("UPDATE " + sentencesTable
                        + " SET active = 0, released_at = ?, released_name = ?, release_reason = ? WHERE id = ?",
                now, releasedBy, reason, sentence.id()));

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            Location back = releaseLocation(sentence);
            if (back != null) {
                player.teleport(back);
            }
            player.sendMessage(plugin.getMessages().prefixed("jail.released-target",
                    "reason", Text.safe(reason == null || reason.isBlank() ? "-" : reason)));
            // Se le devuelve lo que tenía al entrar. Si estaba desconectado, la copia
            // queda pendiente y se le entrega la próxima vez que entre.
            try {
                returnPending(player);
            } catch (RuntimeException ex) {
                // Un fallo al devolver el inventario no puede dejar al jugador sin su
                // modo de juego: antes ese error se llevaba por delante la restauración.
                plugin.getLogger().warning("No pude devolver el inventario a " + player.getName()
                        + " al salir de la cárcel: " + ex.getMessage());
            }
            // El modo se devuelve en el tick siguiente, con el teletransporte ya
            // aplicado: en Bedrock (Geyser) cambiar de mundo y de modo en el mismo tick
            // deja al cliente con las habilidades del modo anterior.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    restoreGamemode(player);
                }
            });
        }
        if (plugin.getSettings().jailNotifyStaff()) {
            plugin.getStaffState().sendToStaff(plugin.getMessages().get("jail.released",
                    "player", sentence.targetName(), "reason", Text.safe(reason == null ? "-" : reason)));
        }
        plugin.getAuditService().log((String) null, releasedBy, "jail.release", sentence.targetName(),
                sentence.jail() + " · " + reason);
        // Se anuncia una sola vez: esta rama sólo se alcanza si la condena seguía activa.
        plugin.getSanctionAnnounce().jailEnded(sentence);
        return true;
    }

    /**
     * Dónde reaparece al salir: su posición original o el spawn del mundo principal.
     *
     * <p>Si no se conoce la posición (condenas importadas de xJail) nunca se le deja
     * dentro de la celda: eso lo volvería a encerrar al instante.</p>
     */
    public Location releaseLocation(JailSentence sentence) {
        if (plugin.getSettings().jailReleaseToOriginal()) {
            Location origin = sentence.origin();
            if (origin != null) {
                return origin;
            }
        }
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.getFirst().getSpawnLocation();
    }

    /**
     * Inserta una condena ya existente (importación). El jugador puede estar offline:
     * se aplicará la próxima vez que entre.
     */
    public boolean importSentence(UUID uuid, String name, String jailName, long remainingSeconds,
                                  String reason, String importedBy) {
        if (uuid == null || jail(jailName).isEmpty() || active.containsKey(uuid)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long remaining = remainingSeconds <= 0L ? JailSentence.PERMANENT : remainingSeconds;
        long id = db.insert("INSERT INTO " + sentencesTable
                        + " (target_uuid, target_name, jail, reason, staff_name, created_at,"
                        + " remaining_seconds, active, origin) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)",
                uuid.toString(), name, jailName, reason, importedBy, now, remaining, plugin.getServerName());
        active.put(uuid, new JailSentence(id, uuid, name, jailName, reason, null, importedBy, now,
                remaining, null, 0.0, 0.0, 0.0, 0.0f, 0.0f));
        return true;
    }

    // ------------------------------------------------------------
    //  Tic y eventos
    // ------------------------------------------------------------

    /**
     * Se ejecuta cada segundo: descuenta la condena a quien está conectado,
     * devuelve a la celda a quien se escapa y libera a quien cumple el tiempo.
     */
    public void tick() {
        if (active.isEmpty()) {
            return;
        }
        for (JailSentence sentence : List.copyOf(active.values())) {
            Player player = Bukkit.getPlayer(sentence.targetUuid());
            if (player == null) {
                continue;
            }
            if (!sentence.permanent()) {
                // El tiempo corre sólo mientras el preso está dentro del servidor.
                sentence.remainingSeconds(sentence.remainingSeconds() - 1L);
                if (sentence.remainingSeconds() <= 0L) {
                    release(sentence.targetUuid(), "Sistema", "Condena cumplida");
                    continue;
                }
                if (sentence.remainingSeconds() % 30L == 0L) {
                    persist(sentence);
                }
            }
            checkEscape(player, sentence);
        }
    }

    /** Si se sale de la celda, vuelve dentro y el staff se entera. */
    private void checkEscape(Player player, JailSentence sentence) {
        if (!plugin.getSettings().jailReturnOnEscape()) {
            return;
        }
        Optional<Jail> found = jail(sentence.jail());
        if (found.isEmpty()) {
            return;
        }
        Jail cell = found.get();
        if (cell.inside(player.getLocation())) {
            return;
        }
        if (!teleportTo(player, cell)) {
            // La celda apunta a un mundo que no existe: no hay a dónde devolverlo.
            return;
        }
        // El aviso va limitado: si el jugador vuelve a salirse cada segundo, ni él
        // ni el staff deben recibir un mensaje por tick.
        long now = System.currentTimeMillis();
        Long last = escapeNotices.get(player.getUniqueId());
        if (last == null || now - last > 5_000L) {
            escapeNotices.put(player.getUniqueId(), now);
            player.sendMessage(plugin.getMessages().prefixed("jail.cannot-leave"));
            if (plugin.getSettings().jailNotifyStaff()) {
                plugin.getStaffState().sendToStaff(plugin.getMessages().get("jail.escaped",
                        "player", player.getName(), "jail", cell.name()));
            }
        }
    }

    /** Al entrar: si tenía condena activa, vuelve a la celda. */
    public void onJoin(Player player) {
        JailSentence sentence = active.get(player.getUniqueId());
        if (sentence == null) {
            // Condena cumplida mientras estaba desconectado: se le devuelve su modo
            // de juego en cuanto entra (antes se quedaría en aventura).
            if (savedGameModes.containsKey(player.getUniqueId())) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        restoreGamemode(player);
                    }
                });
            }
            if (plugin.getSettings().jailConfiscateInventory()
                    && pendingInventories.contains(player.getUniqueId())) {
                // Salió de la cárcel mientras estaba desconectado: se le devuelve su
                // inventario en cuanto está dentro del mundo.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        returnPending(player);
                    }
                });
            }
            return;
        }
        Optional<Jail> cell = jail(sentence.jail());
        if (cell.isEmpty()) {
            return;
        }
        if (cell.get().location() == null) {
            // La celda apunta a un mundo que ya no existe: sin aviso, el preso se
            // quedaría con la condena, el inventario retenido y los comandos
            // bloqueados sin que nadie se entere de por qué.
            player.sendMessage(plugin.getMessages().prefixed("jail.world-missing-target",
                    "jail", cell.get().name()));
            plugin.getLogger().warning("El preso " + player.getName() + " está en la cárcel '"
                    + cell.get().name() + "', cuyo mundo '" + cell.get().world() + "' no existe.");
            if (plugin.getSettings().jailNotifyStaff()
                    && warnedMissingWorld.add(cell.get().name().toLowerCase(Locale.ROOT))) {
                plugin.getStaffState().sendToStaff(plugin.getMessages().get("jail.world-missing",
                        "jail", cell.get().name(), "world", cell.get().world(),
                        "player", player.getName()));
            }
            return;
        }
        // Se hace al tick siguiente: en PlayerJoin el jugador aún no está en el mundo.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && teleportTo(player, cell.get())) {
                applyAdventure(player, sentence);
                player.sendMessage(plugin.getMessages().prefixed("jail.on-join",
                        "jail", cell.get().name(), "time", timeText(sentence)));
            }
        });
        if (plugin.getSettings().jailNotifyStaff()) {
            plugin.getStaffState().sendToStaff(plugin.getMessages().get("jail.join-notice",
                    "player", player.getName(), "jail", cell.get().name(), "time", timeText(sentence)));
        }
    }

    /** Al salir: se guarda el tiempo exacto que le queda. */
    public void onQuit(Player player) {
        escapeNotices.remove(player.getUniqueId());
        JailSentence sentence = active.get(player.getUniqueId());
        if (sentence != null) {
            persist(sentence);
        }
    }

    public void shutdown() {
        for (JailSentence sentence : active.values()) {
            persist(sentence);
        }
        escapeNotices.clear();
    }

    // ------------------------------------------------------------
    //  Inventario del preso
    // ------------------------------------------------------------

    /**
     * Guarda el inventario del preso y se lo quita.
     *
     * <p>Se guarda primero y sólo se limpia si el guardado funcionó: ante un fallo
     * de la base el jugador conserva sus objetos en vez de perderlos.</p>
     */
    private void confiscate(Player target, JailSentence sentence) {
        if (!plugin.getSettings().jailConfiscateInventory()) {
            return;
        }
        // Si quedó una copia sin devolver de un encarcelamiento anterior, se le
        // entrega ahora: así no se acumulan dos inventarios del mismo jugador.
        returnPending(target);
        try {
            byte[] data = ItemStack.serializeItemsAsBytes(target.getInventory().getContents());
            db.insert("INSERT INTO " + inventoryTable
                            + " (sentence_id, uuid, data, created_at, returned) VALUES (?, ?, ?, ?, 0)",
                    sentence.id(), target.getUniqueId().toString(), data, System.currentTimeMillis());
            pendingInventories.add(target.getUniqueId());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("No pude guardar el inventario de " + target.getName()
                    + " al encarcelarlo; conserva sus objetos: " + ex.getMessage());
            return;
        }
        target.getInventory().clear();
        target.getInventory().setArmorContents(null);
        target.getInventory().setItemInOffHand(null);
        target.updateInventory();
        target.sendMessage(plugin.getMessages().prefixed("jail.inventory-taken"));
    }

    /**
     * Pone al preso en modo aventura y guarda el modo que tenía.
     *
     * <p>En aventura el propio cliente se niega a romper y a colocar bloques: no hay
     * predicción local que deshacer, que es lo que en Bedrock dejaba bloques
     * fantasma y al jugador trabado dentro del hueco.</p>
     */
    private void applyAdventure(Player player, JailSentence sentence) {
        if (!plugin.getSettings().jailAdventureMode()) {
            return;
        }
        // Con bypass no se le toca nada: es el staff que cayó preso por error.
        if (player.hasPermission("admintools.jail.bypass")) {
            return;
        }
        UUID uuid = player.getUniqueId();
        GameMode current = player.getGameMode();
        if (!savedGameModes.containsKey(uuid)) {
            GameMode original = originalMode(current, Bukkit.getDefaultGameMode());
            savedGameModes.put(uuid, original);
            db.async(() -> {
                db.update("DELETE FROM " + gamemodeTable + " WHERE uuid = ?", uuid.toString());
                db.update("INSERT INTO " + gamemodeTable
                                + " (uuid, gamemode, sentence_id, created_at) VALUES (?, ?, ?, ?)",
                        uuid.toString(), original.name(), sentence.id(), System.currentTimeMillis());
            });
        }
        if (current != GameMode.ADVENTURE) {
            player.setGameMode(GameMode.ADVENTURE);
        }
    }

    /**
     * Modo que se guarda como "el de antes de la cárcel".
     *
     * <p>Nunca se guarda {@link GameMode#ADVENTURE}. Si el jugador ya estaba en aventura
     * puede ser porque un encarcelamiento anterior lo dejó ahí (fila perdida, condena
     * importada, recarga a mitad): guardar aventura como original lo dejaría sin poder
     * romper bloques para siempre y cada nuevo encarcelamiento lo perpetuaría.</p>
     */
    static GameMode originalMode(GameMode current, GameMode serverDefault) {
        return current == GameMode.ADVENTURE ? serverDefault : current;
    }

    /**
     * Modo al que hay que devolver al preso al salir.
     *
     * <p>Con un modo guardado se respeta tal cual. Sin él (condena importada de xJail,
     * fila que no llegó a escribirse, recarga justo al encarcelar) el jugador está en
     * aventura porque lo puso el plugin, así que vuelve al modo por defecto del
     * servidor en vez de quedarse atrapado sin poder romper bloques.</p>
     */
    static GameMode restoreTarget(GameMode saved, GameMode current, GameMode serverDefault) {
        if (saved != null) {
            return saved;
        }
        return current == GameMode.ADVENTURE ? serverDefault : current;
    }

    /** Devuelve el modo de juego que tenía antes de entrar en la cárcel. */
    private void restoreGamemode(Player player) {
        GameMode saved = savedGameModes.remove(player.getUniqueId());
        db.async(() -> db.update("DELETE FROM " + gamemodeTable + " WHERE uuid = ?",
                player.getUniqueId().toString()));
        GameMode current = player.getGameMode();
        GameMode target = restoreTarget(saved, current, Bukkit.getDefaultGameMode());
        if (target != null && target != current) {
            player.setGameMode(target);
            if (plugin.getSettings().debug()) {
                plugin.getLogger().info("Cárcel: " + player.getName() + " vuelve a "
                        + target.name() + (saved == null ? " (no había modo guardado)" : ""));
            }
        }
    }

    /**
     * Devuelve la copia pendiente más reciente del jugador, si la hay.
     *
     * <p>Para no duplicar nada se vacía lo que tenga encima (se suelta al suelo,
     * no se borra) y después se escribe la copia completa, incluidas armadura y
     * mano secundaria.</p>
     */
    public boolean returnPending(Player player) {
        Optional<SavedInventory> saved = db.queryOne("SELECT id, data FROM " + inventoryTable
                        + " WHERE uuid = ? AND returned = 0 ORDER BY id DESC",
                rs -> new SavedInventory(rs.getLong("id"), rs.getBytes("data")),
                player.getUniqueId().toString());
        if (saved.isEmpty() || saved.get().data() == null) {
            return false;
        }
        ItemStack[] contents;
        try {
            contents = ItemStack.deserializeItemsFromBytes(saved.get().data());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("No pude leer el inventario guardado de " + player.getName()
                    + ": " + ex.getMessage());
            return false;
        }
        // Se marca la copia como devuelta ANTES de entregarla, y sólo si seguía pendiente.
        // Al revés (entregar y luego marcar), un fallo de la base al marcar dejaba la
        // copia pendiente y la siguiente entrada la entregaba otra vez: ítems duplicados.
        // El "AND returned = 0" hace que sólo una ejecución pueda reclamarla.
        int claimed;
        try {
            claimed = db.update("UPDATE " + inventoryTable
                    + " SET returned = 1 WHERE id = ? AND returned = 0", saved.get().id());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("No pude marcar el inventario de " + player.getName()
                    + " como devuelto; queda pendiente para el próximo intento: " + ex.getMessage());
            return false;
        }
        if (claimed == 0) {
            // Otra ejecución (o el tick de entrada) se lo llevó antes.
            pendingInventories.remove(player.getUniqueId());
            return false;
        }
        // Copias viejas del mismo jugador: se descartan para que no se entreguen luego.
        db.async(() -> db.update("UPDATE " + inventoryTable
                + " SET returned = 1 WHERE uuid = ? AND returned = 0", player.getUniqueId().toString()));

        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            }
        }
        // 41 = 36 de inventario + 4 de armadura + mano secundaria.
        ItemStack[] full = contents.length >= 41 ? contents : java.util.Arrays.copyOf(contents, 41);
        player.getInventory().setContents(full);
        player.updateInventory();
        pendingInventories.remove(player.getUniqueId());
        player.sendMessage(plugin.getMessages().prefixed("jail.inventory-returned"));
        return true;
    }

    private void persist(JailSentence sentence) {
        long remaining = sentence.remainingSeconds();
        db.async(() -> db.update("UPDATE " + sentencesTable + " SET remaining_seconds = ? WHERE id = ?",
                remaining, sentence.id()));
    }

    /** @return {@code false} si la celda apunta a un mundo que ya no existe. */
    private boolean teleportTo(Player player, Jail cell) {
        Location location = cell.location();
        if (location == null) {
            if (warnedMissingWorld.add(cell.name().toLowerCase(Locale.ROOT))) {
                plugin.getLogger().warning("La cárcel '" + cell.name() + "' apunta a un mundo inexistente ("
                        + cell.world() + ").");
            }
            return false;
        }
        player.teleport(location);
        player.setFallDistance(0.0f);
        return true;
    }

    // ------------------------------------------------------------
    //  Texto y mapeo
    // ------------------------------------------------------------

    public String timeText(JailSentence sentence) {
        return sentence.permanent() ? plugin.getMessages().raw("jail.time-permanent")
                : TimeUtil.format(sentence.remainingSeconds() * 1000L);
    }

    private Jail mapJail(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Jail(rs.getLong("id"), rs.getString("name"), rs.getString("world"),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                (float) rs.getDouble("yaw"), (float) rs.getDouble("pitch"), rs.getDouble("radius"),
                rs.getLong("created_at"), rs.getString("created_by"));
    }

    private JailSentence mapSentence(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JailSentence(rs.getLong("id"),
                UUID.fromString(rs.getString("target_uuid")), rs.getString("target_name"),
                rs.getString("jail"), rs.getString("reason"),
                rs.getString("staff_uuid") == null ? null : UUID.fromString(rs.getString("staff_uuid")),
                rs.getString("staff_name"), rs.getLong("created_at"), rs.getLong("remaining_seconds"),
                rs.getString("orig_world"), rs.getDouble("orig_x"), rs.getDouble("orig_y"),
                rs.getDouble("orig_z"), (float) rs.getDouble("orig_yaw"), (float) rs.getDouble("orig_pitch"));
    }
}
