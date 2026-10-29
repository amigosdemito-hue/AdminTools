package com.admintools.integration;

import com.admintools.AdminToolsPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Alertas a Discord por webhook, sin dependencias externas.
 * Los envíos salen en un hilo aparte para no bloquear el servidor.
 */
public final class DiscordWebhook {

    /** Un campo del embed. */
    public record Field(String name, String value, boolean inline) {
    }

    private final AdminToolsPlugin plugin;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public DiscordWebhook(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        String url = plugin.getSettings().discordWebhook();
        return plugin.getSettings().discordEnabled() && url != null && !url.isBlank() && url.startsWith("https://");
    }

    /** Envía un embed simple. */
    public void send(String title, String description, int color, List<Field> fields) {
        if (!enabled()) {
            return;
        }
        StringBuilder json = new StringBuilder();
        json.append("{\"username\":\"").append(escape(plugin.getSettings().discordUsername())).append("\"");
        String avatar = plugin.getSettings().discordAvatar();
        if (avatar != null && !avatar.isBlank()) {
            json.append(",\"avatar_url\":\"").append(escape(avatar)).append("\"");
        }
        json.append(",\"embeds\":[{");
        json.append("\"title\":\"").append(escape(title)).append("\"");
        if (description != null && !description.isBlank()) {
            json.append(",\"description\":\"").append(escape(description)).append("\"");
        }
        json.append(",\"color\":").append(color);
        json.append(",\"timestamp\":\"").append(java.time.Instant.now()).append("\"");
        if (fields != null && !fields.isEmpty()) {
            List<String> rendered = new ArrayList<>();
            for (Field field : fields) {
                rendered.add("{\"name\":\"" + escape(field.name()) + "\",\"value\":\"" + escape(field.value())
                        + "\",\"inline\":" + field.inline() + "}");
            }
            json.append(",\"fields\":[").append(String.join(",", rendered)).append("]");
        }
        json.append("}]}");
        post(json.toString());
    }

    private void post(String payload) {
        String url = plugin.getSettings().discordWebhook();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            // Un webhook mal escrito no debe romper una sanción ni un backup.
            plugin.getLogger().warning("El webhook de Discord no es una URL válida: " + url);
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 300) {
                        plugin.getLogger().warning("Discord respondió " + response.statusCode() + ": "
                                + response.body());
                    }
                })
                .exceptionally(error -> {
                    plugin.getLogger().warning("No pude enviar la alerta a Discord: " + error.getMessage());
                    return null;
                });
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
        // Cualquier carácter de control invalida el JSON: Discord respondería 400 y la
        // alerta se perdería sin que nadie se entere del motivo real.
        StringBuilder safe = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            safe.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return safe.toString();
    }
}
