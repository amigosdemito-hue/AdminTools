package com.admintools.integration;

import com.admintools.AdminToolsPlugin;
import com.admintools.model.Report;
import com.admintools.model.Sanction;
import com.admintools.util.Heads;
import com.admintools.util.TimeUtil;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Integraciones opcionales: Discord, LuckPerms, CoreProtect, anticheats y PlaceholderAPI. */
public final class IntegrationManager {

    private final AdminToolsPlugin plugin;
    private final DiscordWebhook discord;
    private final CoreProtectHook coreProtect;
    private final LuckPermsHook luckPerms;
    private final AnticheatHook anticheat;
    private final EconomyHook economy;
    private final boolean placeholderApi;
    private Placeholders placeholders;

    public IntegrationManager(AdminToolsPlugin plugin) {
        this.plugin = plugin;
        this.discord = new DiscordWebhook(plugin);
        this.coreProtect = new CoreProtectHook(plugin);
        this.luckPerms = new LuckPermsHook(plugin);
        this.anticheat = new AnticheatHook(plugin);
        this.economy = new EconomyHook(plugin);
        this.placeholderApi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    /** Registra la expansión de PlaceholderAPI y deja constancia en consola. */
    public void setup() {
        if (placeholderApi && plugin.getSettings().hookPlaceholderApi()) {
            try {
                placeholders = new Placeholders(plugin);
                placeholders.register();
                plugin.getLogger().info("PlaceholderAPI detectado: placeholders %admintools_*% registrados.");
            } catch (Exception ex) {
                plugin.getLogger().warning("No pude registrar los placeholders: " + ex.getMessage());
            }
        }
        if (coreProtect.installed()) {
            plugin.getLogger().info("CoreProtect detectado (v" + coreProtect.version() + "): /at inspect y /at rollback activos.");
        }
        if (anticheat.any()) {
            plugin.getLogger().info("Anticheat detectado: " + anticheat.installedText());
        }
    }

    public void shutdown() {
        if (placeholders != null) {
            placeholders.unregister();
            placeholders = null;
        }
        luckPerms.forgetAll();
    }

    // ------------------------------------------------------------
    //  Accesores
    // ------------------------------------------------------------

    public DiscordWebhook discord() {
        return discord;
    }

    public CoreProtectHook coreProtect() {
        return coreProtect;
    }

    public LuckPermsHook luckPerms() {
        return luckPerms;
    }

    public AnticheatHook anticheat() {
        return anticheat;
    }

    public EconomyHook economy() {
        return economy;
    }

    public boolean placeholderApi() {
        return placeholderApi;
    }

    /** Resuelve un placeholder de otro plugin (o null si no se puede). */
    public String placeholder(String identifier, Player player) {
        if (!placeholderApi) {
            return null;
        }
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%" + identifier + "%");
        } catch (Exception ex) {
            return null;
        }
    }

    public String rankOf(Player player) {
        return luckPerms.rankOf(player);
    }

    public String prefixOf(Player player) {
        return luckPerms.prefixOf(player);
    }

    // ------------------------------------------------------------
    //  Alertas a Discord
    // ------------------------------------------------------------

    private static final int COLOR_BAN = 0xE74C3C;
    private static final int COLOR_MUTE = 0xE67E22;
    private static final int COLOR_KICK = 0xF1C40F;
    private static final int COLOR_WARN = 0xF39C12;
    private static final int COLOR_REPORT = 0x3498DB;
    private static final int COLOR_WATCHDOG = 0x9B59B6;

    public void discordSanction(Sanction sanction) {
        if (!discord.enabled()) {
            return;
        }
        String event = switch (sanction.type()) {
            case BAN, IP_BAN -> "ban";
            case MUTE, IP_MUTE, SHADOW_MUTE -> "mute";
            case KICK -> "kick";
            case WARN -> "warn";
        };
        if (!plugin.getSettings().discordEvent(event)) {
            return;
        }
        int color = switch (sanction.type()) {
            case BAN, IP_BAN -> COLOR_BAN;
            case MUTE, IP_MUTE, SHADOW_MUTE -> COLOR_MUTE;
            case KICK -> COLOR_KICK;
            case WARN -> COLOR_WARN;
        };
        String title = plugin.getMessages().raw("discord." + event + "-title") + " · " + sanction.type().display();
        List<DiscordWebhook.Field> fields = new ArrayList<>();
        fields.add(new DiscordWebhook.Field(plugin.getMessages().raw("discord.field-player"), sanction.targetName(), true));
        fields.add(new DiscordWebhook.Field(plugin.getMessages().raw("discord.field-staff"), sanction.staffName(), true));
        fields.add(new DiscordWebhook.Field(plugin.getMessages().raw("discord.field-duration"),
                sanction.permanent() ? plugin.getMessages().raw("generic.permanent")
                        : TimeUtil.format(sanction.expiresAt() - System.currentTimeMillis()), true));
        fields.add(new DiscordWebhook.Field(plugin.getMessages().raw("discord.field-reason"), sanction.reason(), false));
        fields.add(new DiscordWebhook.Field(plugin.getMessages().raw("discord.field-id"), "#" + sanction.id(), true));
        discord.send(title, null, color, fields);
    }

    public void discordLift(Sanction sanction, String staff) {
        if (!discord.enabled()) {
            return;
        }
        List<DiscordWebhook.Field> fields = List.of(
                new DiscordWebhook.Field(plugin.getMessages().raw("discord.field-player"), sanction.targetName(), true),
                new DiscordWebhook.Field(plugin.getMessages().raw("discord.field-staff"), staff, true),
                new DiscordWebhook.Field(plugin.getMessages().raw("discord.field-id"), "#" + sanction.id(), true));
        discord.send("Sanción levantada · " + sanction.type().display(), null, 0x2ECC71, fields);
    }

    /**
     * Reporte al canal privado del staff.
     *
     * <p>Va firmado por quien reporta (con la cabeza de su skin) y muestra la cabeza del
     * reportado como miniatura, para que el staff reconozca a los dos de un vistazo. Con
     * {@code reports.anonymous} el reportero queda oculto, igual que en la cola.</p>
     *
     * <p>No depende de {@code discord.enabled}: el canal de reportes puede estar
     * configurado por su cuenta aunque las alertas generales estén apagadas.</p>
     */
    public void discordReport(Report report) {
        if (!plugin.getSettings().discordEvent("report")) {
            return;
        }
        String url = plugin.getSettings().reportDiscordWebhook();
        if (!DiscordWebhook.validUrl(url)) {
            return;
        }
        boolean anonymous = plugin.getSettings().reportAnonymous();
        String headTemplate = plugin.getSettings().announcementsHeadUrl();
        String reporter = anonymous ? plain("announce.report-anonymous") : report.reporterName();
        String reporterHead = anonymous ? null : Heads.url(headTemplate, report.reporterUuid(), report.reporterName());

        List<DiscordWebhook.Field> fields = new ArrayList<>();
        fields.add(new DiscordWebhook.Field(plain("announce.field-reported"), report.targetName(), true));
        fields.add(new DiscordWebhook.Field(plain("announce.field-report-id"), "#" + report.id(), true));
        fields.add(new DiscordWebhook.Field(plain("announce.field-reason"), report.reason(), false));
        fields.add(new DiscordWebhook.Field(plain("announce.field-date"),
                "<t:" + (report.createdAt() / 1000L) + ":f>", true));
        fields.add(new DiscordWebhook.Field(plain("announce.field-server"),
                report.origin() == null ? "-" : report.origin(), true));

        DiscordWebhook.Embed embed = new DiscordWebhook.Embed(plain("announce.report-title"), null, COLOR_REPORT,
                Heads.url(headTemplate, report.targetUuid(), report.targetName()),
                reporter, reporterHead,
                plain("announce.report-footer") + " · " + plugin.getSettings().serverName(), fields);
        discord.sendTo(url, plugin.getSettings().discordUsername(), plugin.getSettings().discordAvatar(), embed);
    }

    /** Texto de un mensaje ya en plano (Discord no entiende MiniMessage). */
    private String plain(String key) {
        return PlainTextComponentSerializer.plainText().serialize(plugin.getMessages().get(key));
    }

    public void discordWatchdog(String metric, String value, String state, String hint) {
        if (!discord.enabled() || !plugin.getSettings().discordEvent("watchdog")) {
            return;
        }
        List<DiscordWebhook.Field> fields = List.of(
                new DiscordWebhook.Field("Métrica", metric, true),
                new DiscordWebhook.Field("Valor", value, true),
                new DiscordWebhook.Field("Estado", PlainTextComponentSerializer.plainText().serialize(
                        plugin.getMessages().get("ops.watchdog-alert", "metric", metric, "value", value, "state", state,
                                "hint", hint)), false));
        discord.send(plugin.getMessages().raw("discord.watchdog-title") + " · " + metric, hint, COLOR_WATCHDOG, fields);
    }

    /** Set de integraciones activas, para /at status. */
    public Set<String> activeTexts() {
        Set<String> active = new LinkedHashSet<>();
        if (discord.enabled()) {
            active.add("Discord (webhook)");
        }
        if (coreProtect.installed()) {
            active.add("CoreProtect v" + coreProtect.version());
        }
        if (luckPerms.available()) {
            active.add("LuckPerms");
        }
        if (placeholderApi) {
            active.add("PlaceholderAPI");
        }
        if (anticheat.any()) {
            active.add("Anticheat: " + anticheat.installedText());
        }
        if (economy.available()) {
            active.add("Vault (economía)");
        }
        return active;
    }
}
