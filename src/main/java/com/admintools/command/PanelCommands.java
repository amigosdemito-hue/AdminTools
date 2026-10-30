package com.admintools.command;

import com.admintools.AdminToolsPlugin;
import com.admintools.gui.panel.PanelRank;
import com.admintools.gui.panel.PanelService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Los tres accesos a los paneles: {@code /helper}, {@code /moderator} y {@code /admin}.
 *
 * <p>Cada uno comprueba su permiso de rango y abre la interfaz que corresponda al
 * jugador (inventario, diálogo o formulario Bedrock). El contenido ya viene filtrado
 * acción por acción, así que un rango nunca ve botones que no puede usar.</p>
 */
public final class PanelCommands {

    public PanelCommands(AdminToolsPlugin plugin, CommandRegistry registry, PanelService panels) {
        for (PanelRank rank : PanelRank.values()) {
            registry.register(rank.id())
                    .permission(rank.permission())
                    .usage("/" + rank.id())
                    .noAudit()
                    .run((sender, args) -> open(plugin, panels, sender, rank));
        }
    }

    private void open(AdminToolsPlugin plugin, PanelService panels, CommandSender sender,
                      PanelRank rank) throws CommandException {
        if (!(sender instanceof Player player)) {
            throw new CommandException(plugin.getMessages().raw("generic.player-only"));
        }
        panels.open(player, rank);
    }
}
