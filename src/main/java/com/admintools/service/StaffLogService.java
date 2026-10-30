package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.integration.DiscordWebhook;
import com.admintools.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Registro de uso del staff en un canal privado de Discord.
 *
 * <p>Sólo entran jugadores con rango ({@code admintools.helper}, {@code .moderator} o
 * {@code .admin}) y sólo si el webhook está configurado. Se anotan tanto los comandos
 * como las herramientas que no pasan por un comando (el espectador).</p>
 *
 * <p>Los mensajes se agrupan: Discord limita los envíos por minuto, así que en vez de
 * un mensaje por acción se manda uno cada pocos segundos con varias líneas. La cola
 * tiene tope para que un bucle de comandos no llene la memoria.</p>
 */
public final class StaffLogService {

    /** Líneas por mensaje de Discord. */
    private static final int LINES_PER_MESSAGE = 12;
    /** Tope de la cola: si se desborda, se descartan las entradas más antiguas. */
    private static final int MAX_QUEUE = 500;
    /** Longitud máxima de cada campo, para que una línea no ocupe media pantalla. */
    private static final int MAX_FIELD = 120;
    /** Color del embed (azul del plugin). */
    private static final int COLOR = 0x2B6CFF;

    /**
     * Comandos cuyo contenido no debe acabar en el registro (contraseñas, correo,
     * doble factor). Se anota la acción, nunca los argumentos.
     */
    private static final Set<String> SENSITIVE = Set.of(
            "login", "l", "register", "reg", "unregister", "changepassword", "changepass", "passwd",
            "password", "email", "auth", "2fa", "totp", "setpassword");

    private record Entry(String actor, String action, String target, String details) {
    }

    private final AdminToolsPlugin plugin;
    private final Deque<Entry> queue = new ConcurrentLinkedDeque<>();
    private BukkitTask task;

    public StaffLogService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Programa el envío agrupado. Sin webhook configurado no se programa nada. */
    public void start() {
        stop();
        if (!plugin.getSettings().staffActionLogDiscord()) {
            return;
        }
        if (!usable()) {
            // Antes el registro se apagaba en silencio y parecía roto. El motivo real
            // es que falta la URL: o la propia plaza o el webhook general.
            plugin.getLogger().warning("El registro de staff a Discord está activado pero no hay webhook: "
                    + "rellena staff.action-log.webhook con el enlace del canal privado del staff "
                    + "(también valen announcements.staff-webhook o integrations.discord.webhook-url).");
            return;
        }
        plugin.getLogger().info("Registro de staff a Discord: ON · canal tomado de "
                + plugin.getSettings().staffActionLogTarget());
        // Cada 3 s (60 ticks) se vacía lo acumulado. Se ejecuta en el hilo del
        // servidor porque el envío real ya es asíncrono dentro del webhook.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 60L, 60L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        flush();
    }

    /**
     * ¿Hay con dónde enviar y está activado el registro?
     *
     * <p>No exige {@code discord.enabled}: ese interruptor es para las alertas del
     * plugin. El registro del staff solo necesita su webhook (o el general).</p>
     */
    public boolean usable() {
        if (!plugin.getSettings().staffActionLogDiscord()) {
            return false;
        }
        return DiscordWebhook.validUrl(plugin.getSettings().staffActionLogWebhook());
    }

    // ------------------------------------------------------------
    //  Registro
    // ------------------------------------------------------------

    /** Un comando ejecutado (se llama desde el registro central de comandos). */
    public void command(CommandSender sender, String name, String[] args) {
        if (!(sender instanceof Player player) || !usable()) {
            return;
        }
        if (!isStaff(player)) {
            return;
        }
        String bare = name.toLowerCase(Locale.ROOT);
        if (bare.equals("spectate")) {
            // El propio servicio del espectador anota el inicio y el fin con más detalle
            // (motivo y tiempo usado); anotar además el comando duplicaría la línea.
            return;
        }
        String target = args.length > 0 ? args[0] : "-";
        String details;
        if (SENSITIVE.contains(bare)) {
            // Ni contraseñas ni correos: sólo que se usó.
            target = "-";
            details = "(argumentos ocultos)";
        } else {
            details = args.length > 1 ? join(args, 1) : "";
        }
        enqueue(new Entry(player.getName(), "/" + bare, target, details));
    }

    /** Una herramienta que no pasa por un comando (espectador, toggles del modo staff). */
    public void action(Player player, String action, String details) {
        if (player == null || !usable() || !isStaff(player)) {
            return;
        }
        enqueue(new Entry(player.getName(), action, "-", details));
    }

    /** ¿Tiene rango de staff? Se usa también al interceptar comandos ajenos. */
    public boolean isStaff(Player player) {
        return player.hasPermission("admintools.helper")
                || player.hasPermission("admintools.moderator")
                || player.hasPermission("admintools.admin");
    }

    /**
     * Un comando que NO despacha el plugin (otro plugin o del propio servidor:
     * {@code /give}, {@code /gamemode}, {@code /essentials:...}) ejecutado por staff.
     *
     * <p>Se anota en la auditoría de la base (para {@code /audit}) siempre, y en el
     * canal de Discord cuando hay webhook. Los argumentos de los comandos sensibles
     * (contraseñas, correos, doble factor) nunca se guardan.</p>
     */
    public void foreignCommand(Player player, String label, String[] args) {
        if (player == null || !isStaff(player)) {
            return;
        }
        String bare = label.toLowerCase(Locale.ROOT);
        Fields fields = fields(bare, args);
        plugin.getAuditService().log(player, bare, fields.target(), fields.details());
        if (!usable()) {
            return;
        }
        enqueue(new Entry(player.getName(), "/" + bare, fields.target(), fields.details()));
    }

    /** Campos de un comando ya saneados: qué se guarda como objetivo y como detalle. */
    record Fields(String target, String details) {
    }

    /**
     * Elige los campos de un comando ajeno. Los comandos de credenciales no guardan
     * argumentos: una contraseña tecleada en {@code /login} no puede acabar en la base
     * ni en Discord. Los campos admiten texto largo ({@code /tell}), así que se cortan.
     */
    static Fields fields(String label, String[] args) {
        if (SENSITIVE.contains(label)) {
            return new Fields("-", "(argumentos ocultos)");
        }
        String target = args.length > 0 ? truncate(args[0]) : "-";
        String details = args.length > 1 ? truncate(join(args, 1)) : "";
        return new Fields(target, details);
    }

    /** Los argumentos de un comando ajeno pueden ser kilométricos (un /tell largo). */
    private static String truncate(String value) {
        return value.length() > 200 ? value.substring(0, 199) + "…" : value;
    }

    private void enqueue(Entry entry) {
        queue.addLast(entry);
        while (queue.size() > MAX_QUEUE) {
            queue.pollFirst();
        }
    }

    // ------------------------------------------------------------
    //  Envío
    // ------------------------------------------------------------

    /** Manda lo acumulado en un solo mensaje (hasta {@link #LINES_PER_MESSAGE} líneas). */
    private void flush() {
        if (queue.isEmpty() || !usable()) {
            return;
        }
        List<String> lines = new ArrayList<>(LINES_PER_MESSAGE);
        Entry entry = queue.pollFirst();
        while (entry != null && lines.size() < LINES_PER_MESSAGE) {
            lines.add(format(entry));
            entry = queue.pollFirst();
        }
        // Si sobró alguna, vuelve a la cola para el siguiente envío (3 s después).
        if (entry != null) {
            queue.addFirst(entry);
        }
        // El título cuenta sólo lo que va en este mensaje; el resto sale en el siguiente.
        // Discord no entiende MiniMessage: se manda ya en texto plano.
        String title = Text.plain(plugin.getMessages().get("staff.log-title", "count", lines.size()));
        DiscordWebhook webhook = plugin.getIntegrations().discord();
        webhook.sendTo(plugin.getSettings().staffActionLogWebhook(),
                plugin.getSettings().discordUsername(), plugin.getSettings().discordAvatar(),
                DiscordWebhook.Embed.of(title, String.join("\n", lines), COLOR, List.of()));
    }

    private String format(Entry entry) {
        StringBuilder line = new StringBuilder();
        line.append("**").append(clean(entry.actor(), 24)).append("** · ")
                .append(clean(entry.action(), 40));
        if (entry.target() != null && !entry.target().isBlank() && !entry.target().equals("-")) {
            line.append(" · ").append(clean(entry.target(), 40));
        }
        if (entry.details() != null && !entry.details().isBlank()) {
            line.append(" · ").append(clean(entry.details(), MAX_FIELD));
        }
        return line.toString();
    }

    /**
     * Deja el texto en una sola línea y sin marcado que pueda romper el embed o
     * mencionar a medio servidor: un motivo con {@code @everyone} no debe pingear.
     */
    private static String clean(String value, int max) {
        if (value == null) {
            return "-";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return "-";
        }
        // Se escapa con barra invertida, no se borra: los guiones bajos son legales
        // en los nombres de jugador y quitarlos falsearía el registro.
        text = text.replace("`", "'")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("~", "\\~")
                .replace("|", "\\|")
                .replace("@", "@\u200B");
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }

    private static String join(String[] args, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }
}
