package com.admintools.gui.panel;

import com.admintools.AdminToolsPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Panel en forma de formulario nativo de Bedrock, servido por Floodgate.
 *
 * <p>Los textos de los formularios son texto plano: Bedrock no interpreta MiniMessage,
 * así que cada mensaje se convierte antes de enviarlo. Si Floodgate no está instalado,
 * o su API no responde, {@link #open} devuelve {@code false} y el servicio usa el
 * diálogo Java (que Geyser también dibuja) en lugar de dejar al jugador sin panel.</p>
 *
 * <p>Esta clase es la única que toca las clases de Floodgate y Cumulus; sólo se carga
 * cuando {@link #available()} ya confirmó que están presentes.</p>
 */
final class BedrockPanel {

    private BedrockPanel() {
    }

    /** ¿Hay Floodgate activo y con las clases de formularios disponibles? */
    static boolean available() {
        var floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate == null || !floodgate.isEnabled()) {
            return false;
        }
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Class.forName("org.geysermc.cumulus.form.SimpleForm");
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }

    /** Abre el panel raíz. {@code false} si no se pudo enviar el formulario. */
    static boolean open(AdminToolsPlugin plugin, PanelService service, Player player,
                        PanelRank rank, List<PanelGroup> groups) {
        if (!player.isOnline()) {
            return false;
        }
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            SimpleForm.Builder builder = SimpleForm.builder()
                    .title(plain(plugin, rank.titleKey()))
                    .content(plain(plugin, rank.descriptionKey()));
            for (PanelGroup group : groups) {
                builder.button(plain(plugin, group.labelKey()));
            }
            SimpleForm form = builder
                    .validResultHandler(response -> {
                        int index = response.clickedButtonId();
                        if (index < 0 || index >= groups.size()) {
                            return;
                        }
                        Player p = player;
                        PanelGroup group = groups.get(index);
                        Bukkit.getScheduler().runTask(plugin, () ->
                                openGroup(plugin, service, p, rank, group));
                    })
                    .build();
            return api.sendForm(player.getUniqueId(), form);
        } catch (Throwable failure) {
            plugin.getLogger().warning("No pude enviar el formulario Bedrock a "
                    + player.getName() + ": " + failure);
            return false;
        }
    }

    private static void openGroup(AdminToolsPlugin plugin, PanelService service, Player player,
                                  PanelRank rank, PanelGroup group) {
        if (!player.isOnline()) {
            return;
        }
        List<PanelAction> actions = group.visible(player);
        if (actions.isEmpty()) {
            plugin.getMessages().send(player, "panel.empty");
            return;
        }
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            SimpleForm.Builder builder = SimpleForm.builder()
                    .title(plain(plugin, group.labelKey()))
                    .content(plain(plugin, group.hintKey()));
            for (PanelAction action : actions) {
                builder.button(plain(plugin, action.labelKey()));
            }
            builder.button(plain(plugin, "panel.back"));
            SimpleForm form = builder
                    .validResultHandler(response -> {
                        int index = response.clickedButtonId();
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (index == actions.size()) {
                                open(plugin, service, player, rank, service.visibleGroups(player, rank));
                                return;
                            }
                            if (index < 0 || index >= actions.size()) {
                                return;
                            }
                            PanelAction action = actions.get(index);
                            if (action.needsInput()) {
                                openInput(plugin, service, player, action);
                            } else {
                                service.run(player, action, Map.of());
                            }
                        });
                    })
                    .build();
            api.sendForm(player.getUniqueId(), form);
        } catch (Throwable failure) {
            plugin.getLogger().warning("No pude abrir el grupo Bedrock '"
                    + group.id() + "': " + failure);
            service.inventory().openGroup(player, rank, group, 1);
        }
    }

    /** Formulario con los campos de la acción (texto o desplegable). */
    private static void openInput(AdminToolsPlugin plugin, PanelService service, Player player,
                                  PanelAction action) {
        if (!player.isOnline()) {
            return;
        }
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            // Sin componentes de etiqueta: en Cumulus cada componente ocupa un índice
            // en la respuesta, y un label desplazaría todos los campos de texto.
            CustomForm.Builder form = CustomForm.builder()
                    .title(plain(plugin, action.labelKey()) + " · "
                            + plain(plugin, "panel.action-hint",
                            "command", "/" + action.plainCommand()));
            for (PanelField field : action.fields()) {
                PanelInput input = field.input();
                String label = plain(plugin, input.labelKey());
                if (input.hasOptions()) {
                    form.dropdown(label, input.options(), Math.max(0, input.options().indexOf(input.initial())));
                } else {
                    form.input(label, input.example(), input.initial());
                }
            }
            form.validResultHandler(response -> {
                Map<PanelInput, String> values = new EnumMap<>(PanelInput.class);
                int index = 0;
                for (PanelField field : action.fields()) {
                    PanelInput input = field.input();
                    String value = input.hasOptions()
                            ? input.options().get(Math.max(0, Math.min(response.asDropdown(index),
                                    input.options().size() - 1)))
                            : response.asInput(index);
                    index++;
                    if (value != null) {
                        value = value.trim();
                    }
                    if (value == null || value.isEmpty()) {
                        if (field.required()) {
                            plugin.getMessages().send(player, "panel.error-missing");
                            return;
                        }
                        continue;
                    }
                    if (!input.accept(value)) {
                        plugin.getMessages().send(player, "panel.input.invalid",
                                "label", plain(plugin, input.labelKey()),
                                "example", input.example().isBlank() ? "-" : input.example());
                        return;
                    }
                    values.put(input, value);
                }
                service.run(player, action, values);
            });
            api.sendForm(player.getUniqueId(), form.build());
        } catch (Throwable failure) {
            plugin.getLogger().warning("No pude pedir datos en Bedrock para '"
                    + action.id() + "': " + failure);
            service.collect(player, action);
        }
    }

    /** Texto plano para el formulario: Bedrock no entiende MiniMessage. */
    private static String plain(AdminToolsPlugin plugin, String key, Object... placeholders) {
        return PlainTextComponentSerializer.plainText()
                .serialize(plugin.getMessages().get(key, placeholders));
    }
}
