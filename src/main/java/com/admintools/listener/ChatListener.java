package com.admintools.listener;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.PunishmentType;
import com.admintools.model.Sanction;
import com.admintools.service.ChatService;
import com.admintools.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Revisa cada mensaje del chat: silencios, filtros, formato y registro. */
public final class ChatListener implements Listener {

    /**
     * Comandos que también son chat. Sin esta lista, un silenciado seguía hablando
     * por {@code /msg}, {@code /me} o {@code /say} y el mute no servía de nada.
     */
    private static final Set<String> CHAT_COMMANDS = Set.of(
            "me", "msg", "tell", "w", "whisper", "reply", "r", "say", "teammsg", "tm",
            "broadcast", "bc", "mail");

    private final AdminToolsPlugin plugin;
    private final Map<UUID, Long> lastMuteNotice = new ConcurrentHashMap<>();

    public ChatListener(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String plain = plugin.getChatService().plain(event.message());

        // 1) Silencios: mute normal bloquea, mute fantasma sólo lo ve él.
        Optional<Sanction> mute = plugin.getSanctionService().activeMute(player.getUniqueId(), player.getName());
        if (mute.isPresent() && !player.hasPermission("admintools.chat.filter.bypass")) {
            Sanction sanction = mute.get();
            event.setCancelled(true);
            if (sanction.type() == PunishmentType.SHADOW_MUTE) {
                player.sendMessage(format(player, event.message()));
                plugin.getAuditService().log(player.getUniqueId().toString(), player.getName(), "chat.shadow", "-",
                        Text.truncate(plain, 120));
            } else {
                Long last = lastMuteNotice.get(player.getUniqueId());
                long now = System.currentTimeMillis();
                if (last == null || now - last > 5_000L) {
                    lastMuteNotice.put(player.getUniqueId(), now);
                    plugin.getChatService().sendMuteNotice(player, sanction);
                }
            }
            return;
        }

        // 1b) Silencio por IP: cubre también las cuentas nuevas de esa conexión.
        String address = player.getAddress() == null ? null
                : player.getAddress().getAddress().getHostAddress();
        Optional<Sanction> ipMute = plugin.getSanctionService().activeIpMute(address);
        if (ipMute.isPresent() && !player.hasPermission("admintools.chat.filter.bypass")) {
            event.setCancelled(true);
            Long last = lastMuteNotice.get(player.getUniqueId());
            long now = System.currentTimeMillis();
            if (last == null || now - last > 5_000L) {
                lastMuteNotice.put(player.getUniqueId(), now);
                plugin.getChatService().sendMuteNotice(player, ipMute.get());
            }
            return;
        }

        // 2) Modo canal de staff
        if (plugin.getStaffState().staffChat(player.getUniqueId())) {
            event.setCancelled(true);
            Component component = plugin.getMessages().get("staff.staffchat-format", "player", player.getName(),
                    "message", Text.safe(plain));
            plugin.getStaffState().sendToStaff(component);
            return;
        }

        // 3) Filtros y antispam
        ChatService.Verdict verdict = plugin.getChatService().review(player, plain);
        if (verdict.blocked()) {
            event.setCancelled(true);
            if (verdict.messageKey() != null) {
                plugin.getMessages().send(player, verdict.messageKey());
            }
            if (verdict.notifyStaff()) {
                boolean advertising = verdict.punishAdvertising() || "chat.advert-blocked".equals(verdict.messageKey());
                boolean punish = verdict.punishAdvertising();
                boolean notify = !advertising || plugin.getSettings().antiAdvertisingNotifyStaff();
                // Este evento corre fuera del hilo principal: avisar al staff y sancionar
                // toca la API de Bukkit (mensajes, expulsiones), así que va al hilo principal.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (notify) {
                        plugin.getChatService().notifyStaff(player, plain,
                                advertising ? "chat.advert-staff" : "chat.filter-staff");
                    }
                    // El staff no entra en el escalado automático por publicidad:
                    // un helper con permisos no debe recibir un mute del propio plugin.
                    if (punish && plugin.getSettings().ladders().containsKey("publicidad")
                            && !player.hasPermission("admintools.helper")) {
                        UUID uuid = player.getUniqueId();
                        long window = 90L * 86_400_000L;
                        int previous = plugin.getSanctionService().countAllWithin(uuid,
                                System.currentTimeMillis() - window);
                        var step = plugin.getSettings().ladders().get("publicidad").stepFor(previous);
                        plugin.getSanctionService().apply(new com.admintools.service.SanctionService.Request(
                                step.type(), uuid, player.getName(), plugin.getSanctionService().ipOf(uuid),
                                step.reason(), step.duration(), null, "Servidor", false, "publicidad", null));
                    }
                });
            }
            return;
        }

        // 4) Formato propio del chat (opcional)
        if (plugin.getSettings().chatFormatEnabled()) {
            event.renderer((source, sourceDisplayName, message, viewer) -> format(source, message));
        }

        // 5) Registro para /chatlog y /case, y referencia firmada para /clearchat
        plugin.getChatService().log(player, plain);
        plugin.getChatService().rememberSigned(event.signedMessage());
    }

    /**
     * Silencio aplicado a los comandos que escriben en el chat. Va en prioridad
     * LOW para que otro plugin no pueda colarse antes con el mensaje privado.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("admintools.chat.filter.bypass")) {
            return;
        }
        String label = event.getMessage().split(" ")[0].toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        if (label.contains(":")) {
            label = label.substring(label.indexOf(':') + 1);
        }
        if (!CHAT_COMMANDS.contains(label)) {
            return;
        }
        Optional<Sanction> mute = plugin.getSanctionService()
                .activeMute(player.getUniqueId(), player.getName());
        if (mute.isEmpty()) {
            String address = player.getAddress() == null ? null
                    : player.getAddress().getAddress().getHostAddress();
            mute = plugin.getSanctionService().activeIpMute(address);
        }
        if (mute.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Sanction sanction = mute.get();
        if (sanction.type() == PunishmentType.SHADOW_MUTE) {
            // El silencio fantasma no avisa a nadie: sólo queda el registro interno.
            plugin.getAuditService().log(player.getUniqueId().toString(), player.getName(), "chat.shadow", "-",
                    "/" + label + " " + Text.truncate(event.getMessage(), 120));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastMuteNotice.get(player.getUniqueId());
        if (last == null || now - last > 5_000L) {
            lastMuteNotice.put(player.getUniqueId(), now);
            plugin.getChatService().sendMuteNotice(player, sanction);
        }
    }

    /** Aplica el formato configurado, con prefijo de LuckPerms/PlaceholderAPI. */
    private Component format(Player source, Component message) {
        String format = plugin.getSettings().chatFormat()
                .replace("%message%", "<message>")
                .replace("%prefix%", "<prefix>")
                .replace("%player%", "<player>")
                .replace("%world%", "<world>")
                .replace("%rank%", "<rank>");
        Component prefix = legacy(plugin.getIntegrations().prefixOf(source));
        try {
            return MiniMessage.miniMessage().deserialize(format,
                    Placeholder.component("message", message),
                    Placeholder.component("prefix", prefix),
                    Placeholder.unparsed("player", source.getName()),
                    Placeholder.unparsed("world", source.getWorld().getName()),
                    Placeholder.unparsed("rank", plugin.getIntegrations().rankOf(source)));
        } catch (Exception ex) {
            return Component.text(source.getName() + ": ").append(message);
        }
    }

    /** Convierte un prefijo con códigos clásicos (&amp; o §) a componente. */
    private Component legacy(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        if (input.indexOf('\u00a7') >= 0) {
            return LegacyComponentSerializer.legacySection().deserialize(input);
        }
        if (input.indexOf('&') >= 0) {
            return LegacyComponentSerializer.legacyAmpersand().deserialize(input);
        }
        return Text.mm(input);
    }
}
