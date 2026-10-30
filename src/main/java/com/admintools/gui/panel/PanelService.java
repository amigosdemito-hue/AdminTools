package com.admintools.gui.panel;

import com.admintools.AdminToolsPlugin;
import com.admintools.command.CommandRegistry;
import com.admintools.service.ChatInputService;
import com.admintools.util.ClientEdition;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Punto único de entrada de los paneles de rango.
 *
 * <p>Decide qué interfaz usa cada jugador, filtra el catálogo por permisos reales y
 * ejecuta la acción elegida como el propio jugador. Las tres interfaces (inventario,
 * diálogos Java y formularios Bedrock) sólo se encargan de pintar y de recoger los
 * datos; toda la lógica de permisos y ejecución vive aquí, así que las tres se
 * comportan igual.</p>
 */
public final class PanelService {

    /** Interfaz con la que se abre el panel. */
    public enum Ui {
        INVENTORY, DIALOG, BEDROCK
    }

    private final AdminToolsPlugin plugin;
    private final ChatInputService chatInput;
    private final InventoryPanel inventory;
    private final DialogPanel dialogs;

    public PanelService(AdminToolsPlugin plugin, ChatInputService chatInput) {
        this.plugin = plugin;
        this.chatInput = chatInput;
        this.inventory = new InventoryPanel(plugin, this);
        this.dialogs = new DialogPanel(plugin, this);
    }

    public InventoryPanel inventory() {
        return inventory;
    }

    // ------------------------------------------------------------
    //  Apertura
    // ------------------------------------------------------------

    /** Abre el panel que corresponde al rango, o explica por qué no puede. */
    public void open(Player player, PanelRank rank) {
        if (!rank.grants(player)) {
            plugin.getMessages().send(player, "generic.no-permission");
            return;
        }
        List<PanelGroup> groups = visibleGroups(player, rank);
        if (groups.isEmpty()) {
            plugin.getMessages().send(player, "panel.empty");
            return;
        }
        switch (pick(player)) {
            case INVENTORY -> inventory.openRoot(player, rank, groups);
            case BEDROCK -> {
                if (!BedrockPanel.open(plugin, this, player, rank, groups)) {
                    // Floodgate sin formularios disponibles: se usa el diálogo Java,
                    // que Geyser también sabe mostrar.
                    dialogs.openRoot(player, rank, groups);
                }
            }
            case DIALOG -> dialogs.openRoot(player, rank, groups);
        }
    }

    /** Grupos con al menos una acción permitida, ya sin botones que no puede usar. */
    public List<PanelGroup> visibleGroups(Player player, PanelRank rank) {
        List<PanelGroup> result = new ArrayList<>();
        for (PanelGroup group : PanelCatalog.groups(rank)) {
            List<PanelAction> allowed = group.visible(player);
            if (!allowed.isEmpty()) {
                result.add(new PanelGroup(group.id(), group.icon(), allowed));
            }
        }
        return List.copyOf(result);
    }

    /** Interfaz según configuración y edición del cliente. */
    public Ui pick(Player player) {
        String mode = plugin.getSettings().panelUi();
        if (mode.equals("inventory")) {
            return Ui.INVENTORY;
        }
        if (mode.equals("dialog")) {
            return Ui.DIALOG;
        }
        if (plugin.getSettings().panelBedrockForms() && ClientEdition.bedrock(player)
                && BedrockPanel.available()) {
            return Ui.BEDROCK;
        }
        return Ui.DIALOG;
    }

    // ------------------------------------------------------------
    //  Ejecución
    // ------------------------------------------------------------

    /**
     * Ejecuta la acción como el jugador. Se comprueba el permiso otra vez: entre abrir
     * el menú y pulsar el botón puede haber pasado una recarga de permisos.
     */
    public void run(Player player, PanelAction action, Map<PanelInput, String> values) {
        if (!player.isOnline()) {
            return;
        }
        if (!action.allowed(player)) {
            plugin.getMessages().send(player, "generic.no-permission");
            return;
        }
        if (!action.complete(values)) {
            plugin.getMessages().send(player, "panel.error-missing");
            return;
        }
        String command = action.render(values);
        if (command.isBlank()) {
            return;
        }
        // Fuera del clic del menú/diálogo: el comando puede abrir otro inventario o
        // teletransportar, cosas que no se hacen dentro del evento.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                Bukkit.dispatchCommand(player, command);
            }
        });
    }

    /**
     * Recoge por chat los datos que faltan para una acción (modo inventario) y luego
     * la ejecuta. Los campos se piden en el orden declarado; un opcional vacío se
     * salta sin más.
     */
    public void collect(Player player, PanelAction action) {
        if (!action.needsInput()) {
            run(player, action, Map.of());
            return;
        }
        askNext(player, action, new EnumMap<>(PanelInput.class), 0);
    }

    private void askNext(Player player, PanelAction action, Map<PanelInput, String> values, int index) {
        if (!player.isOnline()) {
            return;
        }
        if (index >= action.fields().size()) {
            run(player, action, values);
            return;
        }
        PanelField field = action.fields().get(index);
        PanelInput input = field.input();
        Component question = plugin.getMessages().get("panel.input.ask",
                "command", "/" + action.id(),
                "label", plugin.getMessages().raw(input.labelKey()),
                "example", input.example().isBlank() ? "-" : input.example(),
                "optional", field.required() ? plugin.getMessages().raw("panel.input.required")
                        : plugin.getMessages().raw("panel.input.optional"));
        chatInput.ask(player, question, answer -> {
            if (answer.isBlank() && !field.required()) {
                askNext(player, action, values, index + 1);
                return;
            }
            if (!input.accept(answer)) {
                plugin.getMessages().send(player, "panel.input.invalid",
                        "label", plugin.getMessages().raw(input.labelKey()),
                        "example", input.example().isBlank() ? "-" : input.example());
                askNext(player, action, values, index);
                return;
            }
            values.put(input, answer.trim());
            askNext(player, action, values, index + 1);
        });
    }

    // ------------------------------------------------------------
    //  Comprobación del catálogo
    // ------------------------------------------------------------

    /**
     * Avisa en consola si alguna acción apunta a un comando que ya no existe o cuyo
     * permiso no coincide con el registrado. Evita botones muertos tras un renombrado.
     */
    public void validate(CommandRegistry registry) {
        Set<String> commands = new LinkedHashSet<>();
        for (CommandRegistry.Definition definition : registry.definitions()) {
            commands.add(definition.name().toLowerCase(java.util.Locale.ROOT));
        }
        Set<String> reported = new LinkedHashSet<>();
        int actions = 0;
        for (PanelRank rank : PanelRank.values()) {
            for (PanelGroup group : PanelCatalog.groups(rank)) {
                for (PanelAction action : group.actions()) {
                    actions++;
                    String root = root(action.plainCommand());
                    if (!commands.contains(root) && reported.add(root)) {
                        plugin.getLogger().warning("Panel: la acción '" + action.id()
                                + "' usa el comando /" + root + ", que no está registrado.");
                    }
                }
            }
        }
        plugin.getLogger().info("Paneles: " + actions + " acciones en "
                + PanelRank.values().length + " rangos · interfaz " + plugin.getSettings().panelUi());
    }

    private static String root(String command) {
        int space = command.indexOf(' ');
        return (space < 0 ? command : command.substring(0, space)).toLowerCase(java.util.Locale.ROOT);
    }
}
