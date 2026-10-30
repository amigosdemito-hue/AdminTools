package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado en memoria de las herramientas de staff: vanish, espías, chat de staff,
 * invulnerabilidad y la última posición para /back.
 */
public final class StaffStateService {

    private final AdminToolsPlugin plugin;
    private final Set<UUID> vanished = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<UUID> socialSpy = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<UUID> commandSpy = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<UUID> staffChat = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<UUID> god = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, Location> back = new ConcurrentHashMap<>();
    /** Para avisar una sola vez de un fallo al pintar el brillo. */
    private final java.util.concurrent.atomic.AtomicBoolean glowErrorLogged =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** Color del brillo ya resuelto por rango (la config no cambia en caliente). */
    private final Map<String, NamedTextColor> glowColors = new ConcurrentHashMap<>();
    /** A quién le hemos puesto nosotros la visibilidad por defecto en false. */
    private final Set<UUID> hiddenByDefault = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** vista abierta -> permiso que hace falta para modificarla (invsee/enderchest) */
    private final Map<UUID, String> inventoryPermission = new ConcurrentHashMap<>();
    /** Dónde se guarda el vanish para que sobreviva a reconexiones y reinicios. */
    private final File vanishFile;

    public StaffStateService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.vanishFile = new File(plugin.getDataFolder(), "vanish.yml");
        loadVanish();
    }

    /** Lee los jugadores que quedaron en vanish la última vez que el servidor los vio. */
    private void loadVanish() {
        if (!vanishFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(vanishFile);
        for (String raw : yaml.getStringList("vanished")) {
            try {
                vanished.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // Entrada corrupta: se ignora en vez de romper el arranque.
            }
        }
    }

    /** Escribe el estado actual. Se llama sólo cuando cambia, no en cada entrada. */
    private void saveVanish() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        for (UUID id : vanished) {
            ids.add(id.toString());
        }
        yaml.set("vanished", ids);
        try {
            yaml.save(vanishFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar vanish.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------
    //  Vanish
    // ------------------------------------------------------------

    public boolean vanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    /** Alterna el vanish y ajusta la visibilidad para todos los conectados. */
    public boolean toggleVanish(Player player) {
        UUID uuid = player.getUniqueId();
        if (vanished.remove(uuid)) {
            saveVanish();
            resyncVanish(player);
            clearGlow(player);
            fakeJoin(player);
            plugin.getMessages().send(player, "staff.vanish-off");
            return false;
        }
        vanished.add(uuid);
        saveVanish();
        // Los que entren después tampoco deben verlo, sin depender del orden de los eventos.
        player.setVisibleByDefault(false);
        hiddenByDefault.add(uuid);
        resyncVanish(player);
        reapplyGlow(player);
        fakeQuit(player);
        plugin.getMessages().send(player, "staff.vanish-on");
        return true;
    }

    /**
     * ¿Este observador ve a los que están en vanish?
     *
     * <p>Por defecto el vanish es simétrico: sólo el staff invisible ve a los invisibles,
     * así dos miembros del staff desaparecidos siguen pudiendo coordinarse entre ellos
     * mientras un moderador que no está en vanish no se entera. Con
     * {@code staff.vanish-see-requires-vanish: false} basta con el permiso
     * {@code admintools.staff.vanish.see}, que es el comportamiento antiguo.</p>
     */
    public boolean seesVanished(Player viewer) {
        return seesThroughVanish(vanished(viewer.getUniqueId()),
                viewer.hasPermission("admintools.staff.vanish.see"),
                plugin.getSettings().vanishSeeRequiresVanish());
    }

    /**
     * Regla de visibilidad, sin Bukkit: quien está en vanish ve a los invisibles, y quien no
     * lo está sólo los ve si la configuración mantiene el permiso como excepción.
     */
    static boolean seesThroughVanish(boolean viewerVanished, boolean viewerHasSeePermission,
                                     boolean seeRequiresVanish) {
        if (viewerVanished) {
            return true;
        }
        return !seeRequiresVanish && viewerHasSeePermission;
    }

    /**
     * Recalcula quién ve a quién cuando cambia un jugador.
     *
     * <p>Antes se recorría la matriz completa en cada entrada o cambio de vanish
     * (n² ocultar/mostrar: con 200 conectados, unas 40 000 llamadas por conexión). La
     * regla depende de los dos lados del par, así que basta con reconciliar los pares
     * en los que participa quien cambia: como observador (empieza o deja de ver a los
     * invisibles) y como objetivo (los demás empiezan o dejan de verlo).</p>
     */
    private void resyncVanish(Player changed) {
        UUID id = changed.getUniqueId();
        boolean hidden = vanished(id);
        // Los que entren a partir de ahora tampoco lo verán. Sólo se toca la
        // visibilidad por defecto de quien hemos ocultado nosotros: ponerla en true al
        // pasar por aquí desharía la de otro plugin que oculte a alguien por su cuenta.
        if (hidden) {
            changed.setVisibleByDefault(false);
            hiddenByDefault.add(id);
        } else if (hiddenByDefault.remove(id)) {
            changed.setVisibleByDefault(true);
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(changed)) {
                continue;
            }
            applyPair(other, changed);
            applyPair(changed, other);
        }
        paintGlowSafely();
    }

    /** Ajusta un par: a un invisible sólo lo ven los demás invisibles. */
    private void applyPair(Player viewer, Player target) {
        if (viewer.equals(target)) {
            return;
        }
        if (vanished(target.getUniqueId()) && !seesVanished(viewer)) {
            viewer.hidePlayer(plugin, target);
        } else {
            viewer.showPlayer(plugin, target);
        }
    }

    /**
     * Reaplica el vanish cuando el jugador entra (o lo activa para otros al entrar alguien nuevo).
     *
     * <p>La visibilidad se reconcilia en los dos sentidos: si consta en vanish se vuelve a
     * ocultar, y si no consta se vuelve a mostrar. Los clientes guardan por UUID a quién
     * tienen oculto y ese registro no se borra al reconectar, así que sin esta segunda mitad
     * un staff podría quedar invisible sin estar en vanish: ni los demás lo verían ni el
     * plugin abriría los contenedores en silencio.</p>
     */
    public void onJoin(Player joiner) {
        resyncVanish(joiner);
        reapplyGlow(joiner);
        if (vanished(joiner.getUniqueId())) {
            plugin.getMessages().send(joiner, "staff.vanish-restored");
        }
    }

    /** Mensaje falso de desconexión para el vanish (sólo lo ven los que no lo tienen). */
    public void fakeQuit(Player player) {
        Component message = plugin.getMessages().get("staff.vanish-fake-quit", "player", player.getName());
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player) && !seesVanished(online)) {
                online.sendMessage(message);
            }
        }
    }

    /** Mensaje falso de conexión para el vanish. */
    public void fakeJoin(Player player) {
        Component message = plugin.getMessages().get("staff.vanish-fake-join", "player", player.getName());
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player) && !seesVanished(online)) {
                online.sendMessage(message);
            }
        }
    }
    // ------------------------------------------------------------
    //  Brillo por rango
    // ------------------------------------------------------------

    /** Rangos con color propio, de mayor a menor (el primero que coincida manda). */
    private static final List<String> GLOW_RANKS = List.of("admin", "moderator", "helper");

    /**
     * Rango del jugador según sus permisos: el mismo orden que usan los paneles
     * (admin &gt; moderador &gt; helper).
     */
    static String rankKey(boolean admin, boolean moderator) {
        if (admin) {
            return "admin";
        }
        return moderator ? "moderator" : "helper";
    }

    private static String rankKey(Player player) {
        return rankKey(player.hasPermission("admintools.admin"),
                player.hasPermission("admintools.moderator"));
    }

    private static String teamName(String rank) {
        return "at_vanish_" + rank;
    }

    /**
     * Vuelve a aplicar el brillo de un jugador en vanish.
     *
     * <p>Hace falta reaplicarlo al entrar, al reaparecer tras morir o si otro plugin
     * limpia los efectos: el efecto no sobrevive a ninguna de las tres cosas.</p>
     */
    public void reapplyGlow(Player player) {
        if (!vanished(player.getUniqueId()) || !plugin.getSettings().vanishGlow()) {
            return;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,
                PotionEffect.INFINITE_DURATION, 0, false, false, false));
        paintGlowSafely();
    }

    /** Quita el brillo y su entrada de los marcadores de todos. */
    private void clearGlow(Player player) {
        player.removePotionEffect(PotionEffectType.GLOWING);
        String name = player.getName();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            removeEntry(viewer.getScoreboard(), name);
        }
    }

    private void removeEntry(Scoreboard board, String name) {
        for (String rank : GLOW_RANKS) {
            Team team = board.getTeam(teamName(rank));
            if (team != null && team.hasEntry(name)) {
                team.removeEntry(name);
            }
        }
    }

    /**
     * Envuelve el pintado para que un marcador ajeno no pueda tumbar el vanish.
     *
     * <p>El brillo es cosmético; la invisibilidad no. Si pintar falla, se avisa una vez y se
     * sigue: antes una excepción aquí interrumpía el toggle a medias, con el staff ya oculto
     * pero sin mensaje de confirmación.</p>
     */
    private void paintGlowSafely() {
        try {
            paintGlow();
        } catch (RuntimeException ex) {
            if (glowErrorLogged.compareAndSet(false, true)) {
                plugin.getLogger().warning("No pude pintar el brillo del vanish: " + ex.getMessage());
            }
        }
    }

    /**
     * Reparte el brillo y su color.
     *
     * <p>El color se pone en un equipo del marcador porque el efecto GLOWING siempre
     * brilla en blanco. Se escribe en el marcador de <b>cada observador</b> en vez de en el
     * principal: muchos servidores dan a cada jugador un marcador propio (TAB, HUDs) y en
     * ese caso el principal no pinta nada, y de paso el color sólo lo ve quien debe ver a
     * los invisibles.</p>
     */
    private void paintGlow() {
        boolean enabled = plugin.getSettings().vanishGlow();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            // Limpieza: entradas que ya no valen (salió del vanish, cambió de rango,
            // se desconectó o este observador ya no debe verlas).
            for (String rank : GLOW_RANKS) {
                Team team = board.getTeam(teamName(rank));
                if (team == null) {
                    continue;
                }
                for (String entry : new ArrayList<>(team.getEntries())) {
                    Player owner = Bukkit.getPlayerExact(entry);
                    boolean keep = enabled
                            && owner != null
                            && vanished(owner.getUniqueId())
                            && seesVanished(viewer)
                            && rankKey(owner).equals(rank);
                    if (!keep) {
                        team.removeEntry(entry);
                    }
                }
            }
            if (!enabled || !seesVanished(viewer)) {
                continue;
            }
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (!vanished(target.getUniqueId())) {
                    continue;
                }
                Team team = ensureTeam(board, rankKey(target));
                if (!team.hasEntry(target.getName())) {
                    team.addEntry(target.getName());
                }
            }
        }
    }

    private Team ensureTeam(Scoreboard board, String rank) {
        Team team = board.getTeam(teamName(rank));
        if (team == null) {
            team = board.registerNewTeam(teamName(rank));
        }
        // No se compara con team.color(): lanza IllegalStateException mientras el equipo
        // todavía no tiene color (el caso de uno recién creado), y eso rompía /vanish.
        team.color(glowColor(rank));
        return team;
    }

    /**
     * Color configurado para el rango; si el valor no es un color válido, usa el de serie.
     * Se resuelve una sola vez por rango para no repetir el aviso en cada refresco.
     */
    NamedTextColor glowColor(String rank) {
        return glowColors.computeIfAbsent(rank, this::resolveGlowColor);
    }

    private NamedTextColor resolveGlowColor(String rank) {
        String raw = plugin.getSettings().vanishGlowColor(rank);
        if (raw != null) {
            NamedTextColor named = NamedTextColor.NAMES.value(raw.toLowerCase(Locale.ROOT).trim());
            if (named != null) {
                return named;
            }
            plugin.getLogger().warning("Color de vanish desconocido ('" + raw
                    + "'): se usa el de serie para el rango " + rank + ".");
        }
        return switch (rank) {
            case "admin" -> NamedTextColor.RED;
            case "moderator" -> NamedTextColor.YELLOW;
            default -> NamedTextColor.GREEN;
        };
    }

    /** Aviso a todo el staff conectado + consola. */
    public void sendToStaff(Component message) {
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("admintools.staff.staffchat") || online.hasPermission("admintools.staff.vanish")) {
                online.sendMessage(message);
            }
        }
    }

    // ------------------------------------------------------------
    //  Espías y chat de staff
    // ------------------------------------------------------------

    public boolean toggleSocialSpy(Player player) {
        UUID uuid = player.getUniqueId();
        if (socialSpy.remove(uuid)) {
            plugin.getMessages().send(player, "staff.spy-off");
            return false;
        }
        socialSpy.add(uuid);
        plugin.getMessages().send(player, "staff.spy-on");
        return true;
    }

    public boolean socialSpy(UUID uuid) {
        return socialSpy.contains(uuid);
    }

    public boolean toggleCommandSpy(Player player) {
        UUID uuid = player.getUniqueId();
        if (commandSpy.remove(uuid)) {
            plugin.getMessages().send(player, "staff.cspy-off");
            return false;
        }
        commandSpy.add(uuid);
        plugin.getMessages().send(player, "staff.cspy-on");
        return true;
    }

    public boolean commandSpy(UUID uuid) {
        return commandSpy.contains(uuid);
    }

    /** Alterna el canal de staff permanente (los mensajes normales van al staff). */
    public boolean toggleStaffChat(Player player) {
        UUID uuid = player.getUniqueId();
        if (staffChat.remove(uuid)) {
            return false;
        }
        staffChat.add(uuid);
        return true;
    }

    public boolean staffChat(UUID uuid) {
        return staffChat.contains(uuid);
    }

    // ------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------

    public boolean toggleGod(Player player) {
        UUID uuid = player.getUniqueId();
        if (god.remove(uuid)) {
            return false;
        }
        god.add(uuid);
        return true;
    }

    public boolean god(UUID uuid) {
        return god.contains(uuid);
    }

    public void rememberBack(Player player) {
        back.put(player.getUniqueId(), player.getLocation().clone());
    }

    // ------------------------------------------------------------
    //  Inventarios abiertos por el staff
    // ------------------------------------------------------------

    /** Recuerda qué permiso corresponde a la vista que acaba de abrir el staff. */
    public void trackInventory(UUID viewer, String permission) {
        inventoryPermission.put(viewer, permission);
    }

    /** Permiso de la vista abierta (o null si no la abrió el plugin). */
    public String inventoryPermission(UUID viewer) {
        return inventoryPermission.get(viewer);
    }

    public void forgetInventory(UUID viewer) {
        inventoryPermission.remove(viewer);
    }

    public Optional<Location> back(UUID uuid) {
        return Optional.ofNullable(back.get(uuid));
    }

    /**
     * Estado de sesión que se olvida al salir: espías, /back y las vistas abiertas.
     *
     * <p>El <b>vanish sí se conserva</b>, también en disco ({@code vanish.yml}), así que
     * sobrevive a la reconexión, a una recarga del plugin y a un reinicio del servidor. El
     * bloqueo de visibilidad entre clientes no se resetea al reconectar (cada jugador
     * guarda por UUID a quién le ha ocultado), así que si se borrara el estado quedaría el
     * peor de los mundos: el staff seguiría invisible para los demás mientras el plugin
     * cree que está visible, con los contenedores abriéndose con animación y sonido. Se
     * apaga con {@code /vanish}.</p>
     */
    public void forget(UUID uuid) {
        hiddenByDefault.remove(uuid);
        back.remove(uuid);
        inventoryPermission.remove(uuid);
        socialSpy.remove(uuid);
        commandSpy.remove(uuid);
        staffChat.remove(uuid);
        god.remove(uuid);
    }

    /**
     * Limpia lo que deja el brillo al salir del servidor.
     *
     * <p>El nombre no se puede resolver una vez desconectado: si estaba en vanish, su
     * entrada quedaría para siempre en los equipos de los demás y un futuro jugador con
     * ese mismo nombre heredaría el color de otro. Se llama con el jugador todavía en
     * memoria ({@link org.bukkit.event.player.PlayerQuitEvent}).</p>
     */
    public void onQuit(Player player) {
        clearGlow(player);
    }
}
