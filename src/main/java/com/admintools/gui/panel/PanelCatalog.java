package com.admintools.gui.panel;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.admintools.gui.panel.PanelField.optional;
import static com.admintools.gui.panel.PanelField.required;
import static com.admintools.gui.panel.PanelInput.DURATION;
import static com.admintools.gui.panel.PanelInput.MODE;
import static com.admintools.gui.panel.PanelInput.NAME;
import static com.admintools.gui.panel.PanelInput.PLAYER;
import static com.admintools.gui.panel.PanelInput.REASON;
import static com.admintools.gui.panel.PanelInput.TEMPLATE;
import static com.admintools.gui.panel.PanelInput.VALUE;

/**
 * Catálogo de los tres paneles.
 *
 * <p>Se declara una vez por nivel y se acumula: el panel de moderador es el del
 * helper más lo suyo, y el de admin los dos anteriores más lo suyo. Cada acción lleva
 * el permiso de su comando, así que un rango ve exactamente lo que puede usar; si
 * mañana cambia un permiso, el botón aparece o desaparece solo.</p>
 *
 * <p>Cada entrada debe apuntar a un comando realmente registrado. {@link PanelService}
 * lo comprueba al arrancar y avisa en consola de cualquier acción huérfana, así que un
 * renombrado no deja botones muertos en silencio.</p>
 */
public final class PanelCatalog {

    private static final Map<PanelRank, List<PanelGroup>> CACHE = new EnumMap<>(PanelRank.class);

    private PanelCatalog() {
    }

    /** Grupos visibles para un rango, ya acumulados y sin filtrar por permisos sueltos. */
    public static List<PanelGroup> groups(PanelRank rank) {
        return CACHE.computeIfAbsent(rank, PanelCatalog::build);
    }

    private static List<PanelGroup> build(PanelRank rank) {
        LinkedHashMap<String, GroupBuilder> groups = new LinkedHashMap<>();
        base(groups);
        if (rank.covers(PanelRank.MODERATOR)) {
            moderator(groups);
        }
        if (rank.covers(PanelRank.ADMIN)) {
            admin(groups);
        }
        List<PanelGroup> result = new ArrayList<>(groups.size());
        for (GroupBuilder builder : groups.values()) {
            result.add(builder.build());
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------
    //  Helper: lo que ve cualquier rango
    // ------------------------------------------------------------

    private static void base(LinkedHashMap<String, GroupBuilder> groups) {
        GroupBuilder sanctions = group(groups, "sanctions", Material.IRON_SWORD);
        add(sanctions, "warn", Material.WRITABLE_BOOK, "admintools.sanction.warn",
                "warn {player} {reason}", required(PLAYER), optional(REASON));
        add(sanctions, "mute", Material.BELL, "admintools.sanction.mute",
                "mute {player} {reason}", required(PLAYER), optional(REASON));
        add(sanctions, "tempmute", Material.CLOCK, "admintools.sanction.mute",
                "tempmute {player} {duration} {reason}",
                required(PLAYER), required(DURATION), optional(REASON));
        add(sanctions, "unmute", Material.LEVER, "admintools.sanction.unmute",
                "unmute {player} {reason}", required(PLAYER), optional(REASON));
        add(sanctions, "kick", Material.LEATHER_BOOTS, "admintools.sanction.kick",
                "kick {player} {reason}", required(PLAYER), optional(REASON));
        add(sanctions, "history", Material.BOOK, "admintools.sanction.history",
                "history {player}", required(PLAYER));
        add(sanctions, "notes", Material.PAPER, "admintools.sanction.notes",
                "notes {player}", required(PLAYER));

        GroupBuilder staff = group(groups, "staff", Material.COMPASS);
        add(staff, "vanish", Material.ENDER_EYE, "admintools.staff.vanish", "vanish");
        add(staff, "freeze", Material.PACKED_ICE, "admintools.staff.freeze",
                "freeze {player}", required(PLAYER));
        add(staff, "staffchat", Material.WRITTEN_BOOK, "admintools.staff.staffchat", "staffchat");
        add(staff, "stafflist", Material.PLAYER_HEAD, "admintools.staff.list", "stafflist");
        add(staff, "seen", Material.MAP, "admintools.staff.seen",
                "seen {player}", required(PLAYER));
        add(staff, "tp", Material.ENDER_PEARL, "admintools.staff.teleport",
                "tp {player}", required(PLAYER));
        add(staff, "follow", Material.LEAD, "admintools.staff.follow",
                "follow {player}", required(PLAYER));
        // El helper ya tiene admintools.staff.teleport: si puede teclear /back o
        // /tphere, el panel debe ofrecérselos también.
        add(staff, "tphere", Material.ARROW, "admintools.staff.teleport",
                "tphere {player}", required(PLAYER));
        add(staff, "back", Material.FEATHER, "admintools.staff.teleport", "back");
        add(staff, "playtime", Material.CLOCK, "admintools.staff.seen",
                "playtime {player}", optional(PLAYER));

        GroupBuilder jail = group(groups, "jail", Material.IRON_BARS);
        add(jail, "jail", Material.IRON_DOOR, "admintools.jail.use",
                "jail {player} {duration} {reason}",
                required(PLAYER), required(DURATION), optional(REASON));
        add(jail, "unjail", Material.OAK_DOOR, "admintools.jail.use",
                "unjail {player} {reason}", required(PLAYER), optional(REASON));
        add(jail, "jailtp", Material.ENDER_PEARL, "admintools.jail.use",
                "jailtp {player}", required(PLAYER));
        add(jail, "jails", Material.CHEST, "admintools.jail.view", "jails");
        add(jail, "jaillist", Material.BOOKSHELF, "admintools.jail.view", "jaillist");
        add(jail, "jailtime", Material.CLOCK, "admintools.jail.use",
                "jailtime {player}", required(PLAYER));

        GroupBuilder investigation = group(groups, "investigation", Material.SPYGLASS);
        add(investigation, "check", Material.PLAYER_HEAD, "admintools.investigate.check",
                "check {player}", required(PLAYER));

        GroupBuilder chat = group(groups, "chat", Material.WRITABLE_BOOK);
        add(chat, "reports", Material.BELL, "admintools.report.staff", "reports");
        add(chat, "chatlog", Material.NAME_TAG, "admintools.chat.log",
                "chatlog {player}", required(PLAYER));
    }

    // ------------------------------------------------------------
    //  Moderador
    // ------------------------------------------------------------

    private static void moderator(LinkedHashMap<String, GroupBuilder> groups) {
        GroupBuilder sanctions = group(groups, "sanctions", Material.IRON_SWORD);
        add(sanctions, "ban", Material.IRON_AXE, "admintools.sanction.ban",
                "ban {player} {reason}", required(PLAYER), optional(REASON));
        add(sanctions, "tempban", Material.DIAMOND_AXE, "admintools.sanction.ban",
                "tempban {player} {duration} {reason}",
                required(PLAYER), required(DURATION), optional(REASON));
        add(sanctions, "unban", Material.IRON_DOOR, "admintools.sanction.unban",
                "unban {player} {reason}", required(PLAYER), optional(REASON));
        add(sanctions, "shadowmute", Material.INK_SAC, "admintools.sanction.shadowmute",
                "shadowmute {player} {duration} {reason}",
                required(PLAYER), required(DURATION), optional(REASON));
        add(sanctions, "punish", Material.WRITTEN_BOOK, "admintools.sanction.templates",
                "punish {template} {player} {reason}",
                required(TEMPLATE), required(PLAYER), optional(REASON));
        add(sanctions, "ladder", Material.LADDER, "admintools.sanction.ladder",
                "ladder {template} {player} {reason}",
                required(TEMPLATE), required(PLAYER), optional(REASON));

        GroupBuilder staff = group(groups, "staff", Material.COMPASS);
        add(staff, "spy", Material.ENDER_CHEST, "admintools.staff.spy", "spy");
        add(staff, "cspy", Material.REDSTONE_TORCH, "admintools.staff.spy.command", "cspy");
        add(staff, "duty", Material.GOLDEN_SHOVEL, "admintools.staff.duty", "duty");
        add(staff, "staffmode", Material.CRAFTING_TABLE, "admintools.staff.mode", "staffmode");
        add(staff, "spectate", Material.SPYGLASS, "admintools.staff.mode", "spectate");
        add(staff, "invsee", Material.CHEST, "admintools.staff.invsee",
                "invsee {player}", required(PLAYER));
        add(staff, "enderchest", Material.ENDER_CHEST, "admintools.staff.enderchest",
                "enderchest {player}", required(PLAYER));
        add(staff, "heal", Material.GOLDEN_APPLE, "admintools.staff.utility",
                "heal {player}", optional(PLAYER));
        add(staff, "feed", Material.COOKED_BEEF, "admintools.staff.utility",
                "feed {player}", optional(PLAYER));
        add(staff, "fly", Material.ELYTRA, "admintools.staff.utility",
                "fly {player}", optional(PLAYER));
        add(staff, "god", Material.TOTEM_OF_UNDYING, "admintools.staff.utility",
                "god {player}", optional(PLAYER));
        add(staff, "clear", Material.BUCKET, "admintools.staff.utility",
                "clear {player}", optional(PLAYER));
        add(staff, "gamemode", Material.GRASS_BLOCK, "admintools.staff.utility",
                "gamemode {mode} {player}", required(MODE), optional(PLAYER));
        GroupBuilder jail = group(groups, "jail", Material.IRON_BARS);
        add(jail, "setjail", Material.BRICKS, "admintools.jail.manage",
                "setjail {name}", required(NAME));
        add(jail, "deljail", Material.BARRIER, "admintools.jail.manage",
                "deljail {name}", required(NAME));

        GroupBuilder investigation = group(groups, "investigation", Material.SPYGLASS);
        add(investigation, "case", Material.BOOK, "admintools.investigate.case",
                "case {player}", required(PLAYER));
        add(investigation, "alts", Material.NAME_TAG, "admintools.investigate.alts",
                "alts {player}", required(PLAYER));
        add(investigation, "snapshot", Material.SHULKER_BOX, "admintools.investigate.snapshot",
                "snapshot {player}", required(PLAYER));

        GroupBuilder chat = group(groups, "chat", Material.WRITABLE_BOOK);
        add(chat, "clearchat", Material.SPONGE, "admintools.chat.clear", "clearchat");
        add(chat, "slowmode", Material.CLOCK, "admintools.chat.slowmode",
                "slowmode {value}", required(VALUE));

        GroupBuilder rollback = group(groups, "rollback", Material.CHEST);
        add(rollback, "rollback", Material.CHEST, "admintools.rollback.view", "rollback");
        add(rollback, "rollback-save", Material.WRITABLE_BOOK, "admintools.rollback.save",
                "rollback save {player}", required(PLAYER));
    }

    // ------------------------------------------------------------
    //  Admin
    // ------------------------------------------------------------

    private static void admin(LinkedHashMap<String, GroupBuilder> groups) {
        GroupBuilder sanctions = group(groups, "sanctions", Material.IRON_SWORD);
        add(sanctions, "ipban", Material.TNT, "admintools.sanction.ipban",
                "ipban {player} {reason}", required(PLAYER), optional(REASON));
        add(sanctions, "ipmute", Material.BARRIER, "admintools.sanction.ipmute",
                "ipmute {player} {duration} {reason}",
                required(PLAYER), required(DURATION), optional(REASON));
        add(sanctions, "unpunish", Material.MILK_BUCKET, "admintools.sanction.rollback",
                "unpunish {player} {reason}", required(PLAYER), optional(REASON));

        GroupBuilder investigation = group(groups, "investigation", Material.SPYGLASS);
        add(investigation, "restore", Material.ENDER_CHEST, "admintools.investigate.restore",
                "restore {player} {value}", required(PLAYER), required(VALUE));

        GroupBuilder jail = group(groups, "jail", Material.IRON_BARS);
        add(jail, "jailimport", Material.HOPPER, "admintools.jail.import", "jailimport xjail");

        GroupBuilder chat = group(groups, "chat", Material.WRITABLE_BOOK);
        add(chat, "muteall", Material.BELL, "admintools.chat.muteall", "muteall");

        GroupBuilder rollback = group(groups, "rollback", Material.CHEST);
        add(rollback, "rollback-purge", Material.LAVA_BUCKET, "admintools.rollback.purge",
                "rollback purge {value}", required(VALUE));

        GroupBuilder server = group(groups, "server", Material.COMMAND_BLOCK);
        add(server, "maintenance", Material.REDSTONE_LAMP, "admintools.ops.maintenance",
                "maintenance");
        add(server, "restart", Material.CLOCK, "admintools.ops.restart", "restart");
        add(server, "backup-list", Material.CHEST, "admintools.ops.backup", "backup list");
        add(server, "backup-now", Material.CHEST_MINECART, "admintools.ops.backup", "backup now");
        add(server, "audit", Material.BOOK, "admintools.ops.audit", "audit");
        add(server, "watchdog", Material.COMPARATOR, "admintools.ops.watchdog", "watchdog");
        add(server, "reload", Material.REDSTONE, "admintools.ops.reload", "admintools reload");
    }

    // ------------------------------------------------------------
    //  Construcción
    // ------------------------------------------------------------

    private static GroupBuilder group(LinkedHashMap<String, GroupBuilder> groups, String id,
                                      Material icon) {
        return groups.computeIfAbsent(id, ignored -> new GroupBuilder(id, icon));
    }

    private static void add(GroupBuilder group, String id, Material icon, String permission,
                            String command, PanelField... fields) {
        group.actions.add(PanelAction.of(id, icon, permission, command, fields));
    }

    /** Acumulador intermedio: los grupos se van ampliando entre niveles. */
    private static final class GroupBuilder {

        private final String id;
        private final Material icon;
        private final List<PanelAction> actions = new ArrayList<>();

        private GroupBuilder(String id, Material icon) {
            this.id = id;
            this.icon = icon;
        }

        private PanelGroup build() {
            return new PanelGroup(id, icon, actions);
        }
    }
}
