package com.admintools.config;

import com.admintools.AdminToolsPlugin;
import com.admintools.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mensajes traducidos desde messages_&lt;idioma&gt;.yml con MiniMessage.
 * Los textos se cachean como componentes ya parseados para no reparsear en cada uso.
 */
public final class Messages {

    private final AdminToolsPlugin plugin;
    private final Map<String, String> raw = new ConcurrentHashMap<>();
    private final Map<String, net.kyori.adventure.text.Component> cache = new ConcurrentHashMap<>();
    private final java.util.Set<String> missing = ConcurrentHashMap.newKeySet();
    private String prefix = "";

    public Messages(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Carga (o recarga) el archivo de idioma indicado. */
    public void load(String language) {
        raw.clear();
        cache.clear();

        String fileName = "messages_" + language + ".yml";
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists() && plugin.getResource(fileName) != null) {
            plugin.saveResource(fileName, false);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        if (file.exists()) {
            try {
                yaml.load(file);
            } catch (Exception ex) {
                plugin.getLogger().warning("No se pudo leer " + fileName + ": " + ex.getMessage());
            }
        }
        // Valores por defecto del jar: si falta una clave en el archivo del usuario, se usa la del jar.
        try (InputStream in = plugin.getResource(fileName)) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration
                        .loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                yaml.setDefaults(defaults);
                yaml.options().copyDefaults(true);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("No se pudieron leer los mensajes por defecto: " + ex.getMessage());
        }

        flatten(yaml, "");
        prefix = yaml.getString("prefix", "");
    }

    private void flatten(YamlConfiguration yaml, String prefix) {
        for (String key : yaml.getKeys(true)) {
            if (key.equals("prefix")) {
                continue;
            }
            Object value = yaml.get(key);
            if (value instanceof String text) {
                raw.put(key, text);
            }
        }
    }

    /** Texto crudo (sin parsear) de una clave. Avisa una sola vez si falta. */
    public String raw(String key) {
        String value = raw.get(key);
        if (value != null) {
            return value;
        }
        if (missing.add(key)) {
            plugin.getLogger().warning("Falta el mensaje '" + key + "' en el archivo de idioma; se usará la clave.");
        }
        return key;
    }

    public String raw(String key, Object... placeholders) {
        return Text.substitute(raw(key), placeholders);
    }

    /**
     * Componente listo para enviar. Sólo se cachean los mensajes sin
     * placeholders: cachear los que llevan nombres de jugador o tiempos haría
     * crecer la caché sin límite durante toda la vida del servidor.
     */
    public net.kyori.adventure.text.Component get(String key, Object... placeholders) {
        if (placeholders.length == 0) {
            return cache.computeIfAbsent(key, ignored -> Text.mm(raw(key)));
        }
        return Text.mm(Text.substitute(raw(key), placeholders));
    }

    public net.kyori.adventure.text.Component prefixed(String key, Object... placeholders) {
        return net.kyori.adventure.text.Component.text()
                .append(Text.mm(prefix))
                .append(get(key, placeholders))
                .build();
    }

    /** Envía un mensaje con prefijo al remitente. */
    public void send(CommandSender sender, String key, Object... placeholders) {
        sender.sendMessage(prefixed(key, placeholders));
    }

    /** Igual que {@link #send} pero sin prefijo (avisos largos, listados). */
    public void sendRaw(CommandSender sender, String key, Object... placeholders) {
        sender.sendMessage(get(key, placeholders));
    }

    /**
     * Clave que corresponde a una cantidad: {@code base-one} cuando es 1 y
     * {@code base} en el resto de los casos.
     *
     * <p>Así "1 advertencia" y "3 advertencias" salen de un solo sitio que cuenta,
     * sin duplicar la lógica de cada comando.</p>
     */
    public String plural(String baseKey, long count) {
        if (count == 1L) {
            String one = baseKey + "-one";
            if (raw.containsKey(one)) {
                return one;
            }
        }
        return baseKey;
    }

    /** Igual que {@link #send} pero eligiendo singular o plural según {@code count}. */
    public void sendCount(CommandSender sender, String baseKey, long count, Object... placeholders) {
        sender.sendMessage(prefixed(plural(baseKey, count), placeholders));
    }

    /** Igual que {@link #sendRaw} pero eligiendo singular o plural según {@code count}. */
    public void sendRawCount(CommandSender sender, String baseKey, long count, Object... placeholders) {
        sender.sendMessage(get(plural(baseKey, count), placeholders));
    }

    public String prefix() {
        return prefix;
    }
}
