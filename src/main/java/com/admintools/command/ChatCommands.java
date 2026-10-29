package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.ChatMessage;
import com.admintools.util.TimeUtil;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Locale;

/** Moderación del chat: historial, limpieza, modo lento y silencio global. */
public final class ChatCommands extends CommandSupport {

    public ChatCommands(AdminToolsPlugin plugin, CommandRegistry registry) {
        super(plugin);

        registry.register("chatlog")
                .permission("admintools.chat.log")
                .usage("/chatlog <jugador> [cantidad]")
                .minArgs(1)
                .completer((sender, args) -> CommandRegistry.playerNames(args.length == 0 ? "" : args[0]))
                .noAudit()
                .run((sender, args) -> {
                    var uuid = target(args[0]);
                    String display = targetName(args[0], uuid);
                    int limit = args.length > 1 ? (int) Math.min(200, parseLong(args[1])) : 20;
                    List<ChatMessage> messages = plugin.getChatService().history(uuid, limit);
                    if (messages.isEmpty()) {
                        plugin.getMessages().send(sender, "chat.log-empty", "player", display);
                        return;
                    }
                    plugin.getMessages().sendRaw(sender, "chat.log-header", "player", display, "count", messages.size());
                    // Del más viejo al más nuevo, para leerlo como una conversación
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        ChatMessage message = messages.get(i);
                        plugin.getMessages().sendRaw(sender, "chat.log-line",
                                "date", TimeUtil.formatDate(message.createdAt()),
                                "player", message.name(),
                                "message", com.admintools.util.Text.safe(message.message()));
                    }
                });

        registry.register("clearchat")
                .permission("admintools.chat.clear")
                .usage("/clearchat [líneas]")
                .completer((sender, args) -> java.util.List.of("10", "25", "50", "100"))
                .noAudit()                .run((sender, args) -> {
                    int lines = args.length > 0 ? (int) Math.min(400, Math.max(1, parseLong(args[0])))
                            : com.admintools.service.ChatService.DEFAULT_CLEAR_LINES;
                    plugin.getChatService().clearChat(staffName(sender), lines);
                    plugin.getAuditService().log(sender, "chat.clear", "-", lines + " líneas");
                });

        registry.register("slowmode")
                .permission("admintools.chat.slowmode")
                .usage("/slowmode <segundos|off>")
                .completer((sender, args) -> java.util.List.of("off", "5", "10", "30", "60", "300"))
                .minArgs(1)
                .literals("off", "5", "10", "30", "60")
                .noAudit().run((sender, args) -> {
                    String input = args[0].toLowerCase(Locale.ROOT);
                    if (input.equals("off") || input.equals("0")) {
                        plugin.getChatService().slowmode(0);
                        Bukkit.getServer().broadcast(plugin.getMessages().get("chat.slowmode-off"));
                        plugin.getAuditService().log(sender, "chat.slowmode", "-", "off");
                        return;
                    }
                    int seconds = (int) Math.min(600, parseLong(input));
                    plugin.getChatService().slowmode(seconds);
                    Bukkit.getServer().broadcast(plugin.getMessages().get("chat.slowmode-on", "seconds", seconds));
                    plugin.getAuditService().log(sender, "chat.slowmode", "-", String.valueOf(seconds));
                });

        registry.register("muteall")
                .permission("admintools.chat.muteall")
                .usage("/muteall [on|off]")
                .completer((sender, args) -> java.util.List.of("on", "off"))
                .literals("on", "off")
                .noAudit().run((sender, args) -> {
                    boolean enable;
                    if (args.length == 0) {
                        enable = !plugin.getChatService().muteAll();
                    } else {
                        enable = args[0].equalsIgnoreCase("on");
                    }
                    plugin.getChatService().muteAll(enable);
                    Bukkit.getServer().broadcast(plugin.getMessages().get(enable ? "chat.muteall-on" : "chat.muteall-off"));
                    plugin.getAuditService().log(sender, "chat.muteall", "-", enable ? "on" : "off");
                });
    }
}
