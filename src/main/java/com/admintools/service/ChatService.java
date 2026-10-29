package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.ChatMessage;
import com.admintools.storage.Database;
import com.admintools.util.Text;
import com.admintools.util.Util;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Moderación del chat: filtros, antispam, mayúsculas, publicidad, modo lento,
 * silencio global y el registro que alimenta /chatlog.
 */
public final class ChatService {

    /** Resultado de revisar un mensaje. */
    public record Verdict(boolean blocked, String messageKey, boolean notifyStaff, boolean punishAdvertising) {

        public static Verdict allow() {
            return new Verdict(false, null, false, false);
        }

        public static Verdict block(String key) {
            return new Verdict(true, key, false, false);
        }
    }

    private record Said(String text, long at) {
    }

    /** Cuántos mensajes firmados se recuerdan para poder borrarlos en /clearchat. */
    private static final int SIGNED_HISTORY = 200;

    private final AdminToolsPlugin plugin;
    private final Database db;
    private final String table;
    /** Últimos mensajes firmados de jugadores, en orden de envío. */
    private final Deque<SignedMessage> signedHistory = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Said>> recent = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> advertising = new ConcurrentHashMap<>();
    private final List<Pattern> filters = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean muteAll;
    private volatile int slowmodeSeconds;

    public ChatService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        this.table = db.table("chat_log");
        this.slowmodeSeconds = plugin.getSettings().chatSlowmodeSeconds();
        compileFilters();
    }

    /** Recompila los filtros regex de la config. */
    public void compileFilters() {
        filters.clear();
        for (String raw : plugin.getSettings().chatFilters()) {
            try {
                filters.add(Pattern.compile(raw));
            } catch (Exception ex) {
                plugin.getLogger().warning("Filtro de chat inválido '" + raw + "': " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------
    //  Estado global
    // ------------------------------------------------------------

    public boolean muteAll() {
        return muteAll;
    }

    public void muteAll(boolean value) {
        this.muteAll = value;
    }

    public int slowmode() {
        return slowmodeSeconds;
    }

    public void slowmode(int seconds) {
        this.slowmodeSeconds = Math.max(0, seconds);
    }

    // ------------------------------------------------------------
    //  Revisión de mensajes
    // ------------------------------------------------------------

    /** Revisa el mensaje contra todos los filtros. El orden importa: lo grave primero. */
    public Verdict review(Player player, String message) {
        if (muteAll && !player.hasPermission("admintools.chat.muteall")) {
            return Verdict.block("chat.muteall-blocked");
        }

        long now = System.currentTimeMillis();

        // 1) Cooldown por jugador
        int cooldown = plugin.getSettings().chatCooldownSeconds();
        if (cooldown > 0 && !player.hasPermission("admintools.chat.cooldown.bypass")) {
            Long last = lastMessage.get(player.getUniqueId());
            if (last != null) {
                long remaining = cooldown * 1000L - (now - last);
                if (remaining > 0) {
                    player.sendMessage(plugin.getMessages().prefixed("chat.cooldown",
                            "time", com.admintools.util.TimeUtil.format(remaining)));
                    return new Verdict(true, null, false, false);
                }
            }
        }

        // 2) Modo lento global
        if (slowmodeSeconds > 0 && !player.hasPermission("admintools.chat.slowmode")) {
            Long last = lastMessage.get(player.getUniqueId());
            if (last != null) {
                long remaining = slowmodeSeconds * 1000L - (now - last);
                if (remaining > 0) {
                    player.sendMessage(plugin.getMessages().prefixed("chat.slowmode-wait",
                            "time", com.admintools.util.TimeUtil.format(remaining)));
                    return new Verdict(true, null, false, false);
                }
            }
        }

        // 3) Repetición
        if (plugin.getSettings().antiSpamEnabled() && !player.hasPermission("admintools.chat.filter.bypass")) {
            Deque<Said> history = recent.computeIfAbsent(player.getUniqueId(), key -> new ArrayDeque<>());
            String normalized = Text.normalize(message);
            int repeats = 0;
            synchronized (history) {
                long window = plugin.getSettings().antiSpamWindowSeconds() * 1000L;
                history.removeIf(said -> now - said.at() > window);
                for (Said said : history) {
                    if (Text.normalize(said.text()).equals(normalized)) {
                        repeats++;
                    }
                }
                history.addLast(new Said(message, now));
            }
            if (repeats >= plugin.getSettings().antiSpamMaxSimilar()) {
                return Verdict.block("chat.spam-blocked");
            }
        }

        // 4) Filtros de palabras
        if (!player.hasPermission("admintools.chat.filter.bypass")) {
            String probe = Util.leet(Util.compact(message));
            for (Pattern pattern : filters) {
                if (pattern.matcher(probe).find() || pattern.matcher(message).find()) {
                    return new Verdict(true, "chat.filter-blocked", true, false);
                }
            }
        }

        // 5) Publicidad
        if (plugin.getSettings().antiAdvertisingEnabled() && !player.hasPermission("admintools.chat.filter.bypass")
                && looksLikeAdvertising(message)) {
            int hits = advertising.merge(player.getUniqueId(), 1, Integer::sum);
            boolean punish = plugin.getSettings().antiAdvertisingPunishAfter() > 0
                    && hits >= plugin.getSettings().antiAdvertisingPunishAfter();
            return new Verdict(true, "chat.advert-blocked", true, punish);
        }

        // 6) Mayúsculas
        if (plugin.getSettings().antiCapsEnabled() && message.length() >= plugin.getSettings().antiCapsMinLength()
                && Util.uppercasePercent(message) >= plugin.getSettings().antiCapsMaxPercent()) {
            return Verdict.block("chat.caps-blocked");
        }

        lastMessage.put(player.getUniqueId(), now);
        return Verdict.allow();
    }

    /** Detecta dominios, IPs y invitaciones a otros servidores, incluso camuflados. */
    public boolean looksLikeAdvertising(String message) {
        String compact = Util.compact(Util.leet(message));
        if (compact.contains("http") || compact.contains("www.") || compact.contains("discord.gg")
                || compact.contains("discord.com/invite") || compact.contains(".net") || compact.contains(".com")
                || compact.contains(".org") || compact.contains(".gg") || compact.contains(".io")) {
            return true;
        }
        if (compact.matches("(?s).*\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}.*")) {
            return true;
        }
        return compact.contains("join") && compact.contains("server") && compact.contains(":");
    }

    /** Deja constancia del mensaje (para /chatlog y /case). */
    public void log(Player player, String message) {
        if (!plugin.getSettings().chatLogEnabled()) {
            return;
        }
        // Los datos del jugador se leen aquí, en el hilo del servidor: fuera de él
        // la API de Bukkit no está garantizada.
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        long now = System.currentTimeMillis();
        String server = plugin.getServerName();
        db.async(() -> db.update("INSERT INTO " + table
                        + " (uuid, name, message, created_at, origin) VALUES (?, ?, ?, ?, ?)",
                uuid, name, message, now, server));
    }

    public List<ChatMessage> history(UUID uuid, int limit) {
        return db.query("SELECT * FROM " + table + " WHERE uuid = ? ORDER BY id DESC LIMIT ?", rs -> new ChatMessage(
                        rs.getLong("id"), UUID.fromString(rs.getString("uuid")), rs.getString("name"),
                        rs.getString("message"), rs.getLong("created_at"), rs.getString("origin")),
                uuid.toString(), limit);
    }

    public int purge(int days) {
        long before = System.currentTimeMillis() - days * 86_400_000L;
        return db.update("DELETE FROM " + table + " WHERE created_at < ?", before);
    }

    /** Avisa al staff de un mensaje filtrado. */
    public void notifyStaff(Player player, String message, String key) {
        Component component = plugin.getMessages().get(key, "player", player.getName(),
                "message", Text.safe(Text.truncate(message, 120)));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("admintools.chat.log")) {
                staff.sendMessage(component);
            }
        }
        Bukkit.getConsoleSender().sendMessage(component);
    }

    /** Líneas en blanco que manda /clearchat cuando no se indican. */
    public static final int DEFAULT_CLEAR_LINES = 80;

    /**
     * Guarda el mensaje firmado para poder borrarlo después con /clearchat.
     * Sólo se recuerdan los que el cliente permite borrar.
     */
    public void rememberSigned(SignedMessage message) {
        if (message == null || !message.canDelete()) {
            return;
        }
        signedHistory.addLast(message);
        while (signedHistory.size() > SIGNED_HISTORY) {
            signedHistory.pollFirst();
        }
    }

    /**
     * Borra del cliente los mensajes firmados recordados (1.19.3+).
     * Es un borrado real: la línea desaparece del chat, no se tapa con espacios.
     */
    public int deleteSignedMessages() {
        int count = 0;
        SignedMessage message = signedHistory.pollFirst();
        while (message != null) {
            try {
                Bukkit.getServer().deleteMessage(message);
                count++;
            } catch (RuntimeException ignored) {
                // Cliente o versión sin soporte de borrado: queda la limpieza visual.
            }
            message = signedHistory.pollFirst();
        }
        return count;
    }

    /** Limpia el chat: borra los mensajes firmados y rellena con líneas en blanco lo antiguo. */
    public void clearChat(String staffName, int lines) {
        int deleted = deleteSignedMessages();
        int count = Math.max(1, Math.min(400, lines));
        String blank = " ".repeat(2);
        for (Player online : Bukkit.getOnlinePlayers()) {
            for (int i = 0; i < count; i++) {
                online.sendMessage(Component.text(blank));
            }
        }
        Bukkit.getServer().broadcast(plugin.getMessages().get("chat.cleared",
                "staff", staffName, "lines", count, "deleted", deleted));
    }

    /** Texto plano de un componente (para guardar el chat tal cual se escribió). */
    public String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** ¿El jugador está silenciado (normal o fantasma)? */
    public java.util.Optional<com.admintools.model.Sanction> mute(Player player) {
        return plugin.getSanctionService().activeMute(player.getUniqueId(), player.getName());
    }

    /** Mensaje corto del estado del silencio para el jugador. */
    public void sendMuteNotice(Player player, com.admintools.model.Sanction sanction) {
        player.sendMessage(plugin.getMessages().prefixed("chat.muted",
                "duration", sanction.permanent() ? plugin.getMessages().raw("generic.permanent")
                        : com.admintools.util.TimeUtil.format(sanction.expiresAt() - System.currentTimeMillis()),
                "reason", com.admintools.util.Text.safe(sanction.reason())));
    }
}
