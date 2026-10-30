package com.admintools.gui.panel;

import com.admintools.AdminToolsPlugin;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Panel en forma de diálogo nativo de Minecraft (Java 1.21.6+).
 *
 * <p>Mismos datos y mismos permisos que el inventario, pero con la interfaz que el
 * cliente dibuja encima del juego. Los botones se construyen con
 * {@link DialogAction#customClick} y se ejecutan en el servidor, así que el cliente
 * nunca decide qué comando se lanza. Cada diálogo se genera al abrirlo: no se registra
 * nada permanente en el registro de diálogos del servidor.</p>
 */
public final class DialogPanel {

    /** Un solo uso y diez minutos: un diálogo viejo no puede dispararse dos veces. */
    private static final ClickCallback.Options OPTIONS = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(10))
            .build();

    private final AdminToolsPlugin plugin;
    private final PanelService service;

    public DialogPanel(AdminToolsPlugin plugin, PanelService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private Component message(String key, Object... placeholders) {
        return plugin.getMessages().get(key, placeholders);
    }

    private String raw(String key) {
        return plugin.getMessages().raw(key);
    }

    // ------------------------------------------------------------
    //  Raíz: un botón por grupo
    // ------------------------------------------------------------

    public void openRoot(Player player, PanelRank rank, List<PanelGroup> groups) {
        if (!player.isOnline()) {
            return;
        }
        List<ActionButton> buttons = new ArrayList<>(groups.size());
        for (PanelGroup group : groups) {
            buttons.add(ActionButton.builder(message(group.labelKey()))
                    .tooltip(message(group.hintKey()))
                    .width(200)
                    .action(DialogAction.customClick((response, audience) ->
                            // El callback puede llegar fuera del hilo del servidor.
                            Bukkit.getScheduler().runTask(plugin, () ->
                                    openGroup(player, rank, group)), OPTIONS))
                    .build());
        }
        DialogBase base = DialogBase.builder(message(rank.titleKey()))
                .body(List.of(DialogBody.plainMessage(
                        message(rank.descriptionKey()), 300)))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        show(player, rank, base, DialogType.multiAction(buttons, exitButton(), 2));
    }

    // ------------------------------------------------------------
    //  Grupo: un botón por acción
    // ------------------------------------------------------------

    public void openGroup(Player player, PanelRank rank, PanelGroup group) {
        if (!player.isOnline()) {
            return;
        }
        List<PanelAction> actions = group.visible(player);
        if (actions.isEmpty()) {
            plugin.getMessages().send(player, "panel.empty");
            return;
        }
        List<ActionButton> buttons = new ArrayList<>(actions.size());
        for (PanelAction action : actions) {
            buttons.add(ActionButton.builder(message(action.labelKey()))
                    .tooltip(message("panel.action-hint",
                            "command", "/" + action.plainCommand()))
                    .width(200)
                    .action(DialogAction.customClick((response, audience) ->
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (action.needsInput()) {
                                    openInput(player, action);
                                } else {
                                    service.run(player, action, Map.of());
                                }
                            }), OPTIONS))
                    .build());
        }
        DialogBase base = DialogBase.builder(message(group.labelKey()))
                .body(List.of(DialogBody.plainMessage(message(group.hintKey()), 300)))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        // El botón de salir vuelve a la raíz en lugar de cerrar el panel entero.
        ActionButton back = ActionButton.builder(message("panel.back"))
                .tooltip(message("panel.back-hint"))
                .action(DialogAction.customClick((response, audience) ->
                        Bukkit.getScheduler().runTask(plugin, () ->
                                openRoot(player, rank, service.visibleGroups(player, rank))), OPTIONS))
                .build();
        show(player, rank, base, DialogType.multiAction(buttons, back, 2));
    }

    // ------------------------------------------------------------
    //  Datos de una acción
    // ------------------------------------------------------------

    private void openInput(Player player, PanelAction action) {
        if (!player.isOnline()) {
            return;
        }
        List<DialogInput> inputs = new ArrayList<>(action.fields().size());
        for (PanelField field : action.fields()) {
            PanelInput input = field.input();
            Component label = message(input.labelKey());
            if (input.hasOptions()) {
                List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                for (String option : input.options()) {
                    entries.add(SingleOptionDialogInput.OptionEntry.create(
                            option, Component.text(option), option.equalsIgnoreCase(input.initial())));
                }
                inputs.add(DialogInput.singleOption(input.key(), label, entries).build());
            } else {
                inputs.add(DialogInput.text(input.key(), label)
                        .initial(input.initial())
                        .maxLength(input.maxLength())
                        .width(220)
                        .build());
            }
        }

        ActionButton run = ActionButton.builder(message("panel.run"))
                .tooltip(message("panel.run-hint"))
                .width(200)
                .action(DialogAction.customClick((response, audience) ->
                        Bukkit.getScheduler().runTask(plugin, () ->
                                submit(player, action, response)), OPTIONS))
                .build();

        DialogBase base = DialogBase.builder(message("panel.input-title",
                        "action", raw(action.labelKey())))
                .body(List.of(DialogBody.plainMessage(message("panel.action-hint",
                        "command", "/" + action.plainCommand()), 300)))
                .inputs(inputs)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        show(player, null, base, DialogType.multiAction(List.of(run), exitButton(), 1));
    }

    /** Lee los campos del diálogo, valida y lanza la acción. */
    private void submit(Player player, PanelAction action, DialogResponseView response) {
        Map<PanelInput, String> values = new EnumMap<>(PanelInput.class);
        for (PanelField field : action.fields()) {
            String value = response.getText(field.input().key());
            if (value == null) {
                value = "";
            }
            value = value.trim();
            // Un desplegable sin selección explícita usa su valor inicial, que es lo
            // que el jugador está viendo marcado.
            if (value.isEmpty() && field.input().hasOptions()) {
                value = field.input().initial();
            }
            if (value.isEmpty() && !field.required()) {
                continue;
            }
            if (!field.input().accept(value)) {
                plugin.getMessages().send(player, "panel.input.invalid",
                        "label", raw(field.input().labelKey()),
                        "example", field.input().example().isBlank() ? "-" : field.input().example());
                return;
            }
            values.put(field.input(), value);
        }
        service.run(player, action, values);
    }

    // ------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------

    private ActionButton exitButton() {
        return ActionButton.builder(message("panel.close"))
                .tooltip(message("panel.close-hint"))
                .action(DialogAction.staticAction(ClickEvent.callback(this::close, OPTIONS)))
                .build();
    }

    private void close(Audience audience) {
        if (audience instanceof Player player) {
            player.closeDialog();
        }
    }

    /**
     * Muestra el diálogo. Si el cliente no lo soporta (versión anterior a 1.21.6),
     * se cae al inventario en vez de dejar al staff sin panel.
     */
    private void show(Player player, PanelRank rank, DialogBase base, DialogType type) {
        try {
            player.showDialog(Dialog.create(factory -> factory.empty().base(base).type(type)));
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("No pude mostrar el diálogo del panel a "
                    + player.getName() + ": " + ex.getMessage());
            plugin.getMessages().send(player, "panel.dialog-unsupported");
            // En el diálogo de datos no hay rango a mano: el aviso ya explica qué
            // pasó y el staff puede repetir la acción desde el menú de inventario.
            if (rank != null) {
                service.inventory().openRoot(player, rank, service.visibleGroups(player, rank));
            }
        }
    }
}
