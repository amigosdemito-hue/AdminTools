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

    /**
     * Un embed completo.
     *
     * <p>{@code thumbnailUrl} pinta la imagen a la derecha (la cabeza de la skin) y el
     * bloque de autor permite firmar el mensaje con un nombre y un icono distintos del
     * resto del embed: es lo que distingue el anuncio público (firmado por el jugador)
     * de la copia del staff (firmada por quien sancionó).</p>
     */
    public record Embed(String title, String description, int color, String thumbnailUrl,
                        String authorName, String authorIconUrl, String footer, List<Field> fields) {

        public static Embed of(String title, String description, int color, List<Field> fields) {
            return new Embed(title, description, color, null, null, null, null, fields);
        }
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
        return plugin.getSettings().discordEnabled() && validUrl(url);
    }

    /**
     * ¿Es una URL de webhook usable? (cualquier destino, no sólo el configurado).
     *
     * <p>Sólo se acepta {@code https://} para destinos remotos. La excepción es el
     * bucle local: ahí se permite {@code http://} para poder probar el formato del
     * embed con un receptor propio antes de apuntar a Discord.</p>
     */
    public static boolean validUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        if (url.startsWith("https://")) {
            return true;
        }
        return url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost");
    }

    /** Envía un embed simple. */
    public void send(String title, String description, int color, List<Field> fields) {
        if (!enabled()) {
            return;
        }
        sendTo(plugin.getSettings().discordWebhook(), plugin.getSettings().discordUsername(),
                plugin.getSettings().discordAvatar(), Embed.of(title, description, color, fields));
    }

    /** Envía un embed a un webhook concreto, con su propio nombre y avatar. */
    public void sendTo(String webhookUrl, String username, String avatar, Embed embed) {
        if (!validUrl(webhookUrl) || embed == null) {
            return;
        }
        post(webhookUrl, payload(username, avatar, embed));
    }

    private String payload(String username, String avatar, Embed embed) {
        StringBuilder json = new StringBuilder();
        json.append("{\"username\":\"").append(escape(username)).append("\"");
        if (avatar != null && !avatar.isBlank()) {
            json.append(",\"avatar_url\":\"").append(escape(avatar)).append("\"");
        }
        // Nunca se permite que un motivo con @everyone mención a medio Discord.
        json.append(",\"allowed_mentions\":{\"parse\":[\"users\"]}");
        json.append(",\"embeds\":[{\"title\":\"").append(escape(embed.title())).append("\"");
        if (embed.description() != null && !embed.description().isBlank()) {
            json.append(",\"description\":\"").append(escape(embed.description())).append("\"");
        }
        json.append(",\"color\":").append(embed.color());
        json.append(",\"timestamp\":\"").append(java.time.Instant.now()).append("\"");
        if (embed.authorName() != null && !embed.authorName().isBlank()) {
            json.append(",\"author\":{\"name\":\"").append(escape(embed.authorName())).append("\"");
            if (embed.authorIconUrl() != null && !embed.authorIconUrl().isBlank()) {
                json.append(",\"icon_url\":\"").append(escape(embed.authorIconUrl())).append("\"");
            }
            json.append("}");
        }
        if (embed.thumbnailUrl() != null && !embed.thumbnailUrl().isBlank()) {
            json.append(",\"thumbnail\":{\"url\":\"").append(escape(embed.thumbnailUrl())).append("\"}");
        }
        if (embed.footer() != null && !embed.footer().isBlank()) {
            json.append(",\"footer\":{\"text\":\"").append(escape(embed.footer())).append("\"}");
        }
        if (embed.fields() != null && !embed.fields().isEmpty()) {
            List<String> rendered = new ArrayList<>();
            for (Field field : embed.fields()) {
                rendered.add("{\"name\":\"" + escape(field.name()) + "\",\"value\":\"" + escape(field.value())
                        + "\",\"inline\":" + field.inline() + "}");
            }
            json.append(",\"fields\":[").append(String.join(",", rendered)).append("]");
        }
        json.append("}]}");
        return json.toString();
    }

    private void post(String url, String payload) {
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
