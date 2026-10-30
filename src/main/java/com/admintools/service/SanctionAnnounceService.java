package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.integration.DiscordWebhook;
import com.admintools.model.JailSentence;
import com.admintools.model.PunishmentType;
import com.admintools.model.Sanction;
import com.admintools.util.Heads;
import com.admintools.util.Text;
import com.admintools.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Anuncio de sanciones en Discord, en dos destinos separados.
 *
 * <p>Es independiente del <b>registro de staff</b> ({@link StaffLogService}): aquel
 * es una auditoría interna de lo que hace cada rango, éste es la cara pública de la
 * moderación. Por eso tiene sus propios webhooks y su propio formato.</p>
 *
 * <ul>
 *   <li><b>Canal público</b>: embed firmado por el jugador sancionado (nombre y cabeza
 *       de su skin), con el tipo, la duración, el motivo y quién la emitió. Si es
 *       temporal, un campo con la hora exacta y una cuenta atrás viva
 *       ({@code <t:...:R>}), que Discord actualiza solo, sin que el servidor envíe nada.</li>
 *   <li><b>Canal de staff</b>: la misma información, pero firmada por el miembro del
 *       staff que emitió la sanción (su nombre y su cabeza), para saber de un vistazo
 *       quién actuó.</li>
 * </ul>
 *
 * <p>Cuando una sanción temporal llega a cero se manda otro embed anunciando el fin, en
 * los dos canales. No hay temporizadores propios: se engancha a los caminos que ya
 * expiran sanciones y condenas (idempotentes), así que tras un reinicio no se repiten
 * avisos ni se pierde ninguno.</p>
 */
public final class SanctionAnnounceService {

    private static final int COLOR_BAN = 0xE74C3C;
    private static final int COLOR_MUTE = 0xE67E22;
    private static final int COLOR_JAIL = 0x8E44AD;
    private static final int COLOR_FREEZE = 0x3498DB;
    private static final int COLOR_END = 0x2ECC71;

    private static final int MAX_VALUE = 220;

    private final AdminToolsPlugin plugin;

    public SanctionAnnounceService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------
    //  Estado
    // ------------------------------------------------------------

    /** ¿Hay canal configurado y anuncios activados? */
    public boolean usable() {
        return plugin.getSettings().announcementsEnabled() && (publicUrl() != null || staffUrl() != null);
    }

    /** Texto corto para la línea de arranque. */
    public String status() {
        if (!plugin.getSettings().announcementsEnabled()) {
            return "OFF";
        }
        List<String> channels = new ArrayList<>();
        if (publicUrl() != null) {
            channels.add("público");
        }
        if (staffUrl() != null) {
            channels.add("staff");
        }
        return channels.isEmpty() ? "sin webhook" : String.join(" + ", channels);
    }

    private String publicUrl() {
        String url = plugin.getSettings().announcementsPublicWebhook();
        return DiscordWebhook.validUrl(url) ? url : null;
    }

    private String staffUrl() {
        String url = plugin.getSettings().announcementsStaffWebhook();
        return DiscordWebhook.validUrl(url) ? url : null;
    }

    // ------------------------------------------------------------
    //  Sanciones de la base (ban, mute)
    // ------------------------------------------------------------

    /** Anuncia una sanción recién aplicada. */
    public void sanction(Sanction sanction) {
        if (!usable() || sanction == null || sanction.silent()) {
            return;
        }
        String kind = kindOf(sanction.type());
        if (kind == null || !plugin.getSettings().announcementsType(kind)) {
            return;
        }
        List<DiscordWebhook.Field> fields = new ArrayList<>();
        fields.add(field("announce.field-duration", durationText(sanction), true));
        if (!sanction.permanent()) {
            fields.add(field("announce.field-ends", countdown(sanction.expiresAt()), false));
        }
        fields.add(field("announce.field-reason", sanction.reason(), false));
        fields.add(field("announce.field-staff", sanction.staffName(), true));
        fields.add(field("announce.field-id", "#" + sanction.id(), true));

        send(kind, colorOf(kind), typeText(kind, sanction.permanent()),
                sanction.targetName(), headUrl(sanction.targetUuid(), sanction.targetName()),
                sanction.staffName(), headUrl(sanction.staffUuid(), sanction.staffName()), fields);
    }

    /** Anuncia que una sanción temporal ya no está en vigor. */
    public void sanctionEnded(Sanction sanction) {
        if (!usable() || sanction == null || sanction.silent() || !plugin.getSettings().announcementsEnd()) {
            return;
        }
        String kind = kindOf(sanction.type());
        if (kind == null || !plugin.getSettings().announcementsType(kind)) {
            return;
        }
        List<DiscordWebhook.Field> fields = new ArrayList<>();
        fields.add(field("announce.field-type", typeText(kind, sanction.permanent()), true));
        fields.add(field("announce.field-reason", sanction.reason(), false));
        fields.add(field("announce.field-staff", sanction.staffName(), true));
        fields.add(field("announce.field-ended", timestamp(System.currentTimeMillis()), true));
        fields.add(field("announce.field-id", "#" + sanction.id(), true));

        sendEnd(typeText(kind, sanction.permanent()), sanction.targetName(),
                headUrl(sanction.targetUuid(), sanction.targetName()),
                sanction.staffName(), headUrl(sanction.staffUuid(), sanction.staffName()), fields);
    }

    // ------------------------------------------------------------
    //  Cárcel
    // ------------------------------------------------------------

    /** Anuncia un encarcelamiento. */
    public void jail(JailSentence sentence) {
        if (!usable() || sentence == null || !plugin.getSettings().announcementsType("jail")) {
            return;
        }
        boolean permanent = sentence.permanent();
        List<DiscordWebhook.Field> fields = new ArrayList<>();
        fields.add(field("announce.field-duration",
                permanent ? plain("generic.permanent") : TimeUtil.format(jailMillis(sentence)), true));
        if (!permanent) {
            fields.add(field("announce.field-ends", countdown(jailExpires(sentence)), false));
        }
        fields.add(field("announce.field-jail", sentence.jail(), true));
        fields.add(field("announce.field-reason", sentence.reason(), false));
        fields.add(field("announce.field-staff", sentence.staffName(), true));
        fields.add(field("announce.field-id", "#" + sentence.id(), true));

        send("jail", COLOR_JAIL, typeText("jail", permanent),
                sentence.targetName(), headUrl(sentence.targetUuid(), sentence.targetName()),
                sentence.staffName(), headUrl(sentence.staffUuid(), sentence.staffName()), fields);
    }

    /** Anuncia el fin de una condena (cumplida o levantada). */
    public void jailEnded(JailSentence sentence) {
        if (!usable() || sentence == null || !plugin.getSettings().announcementsType("jail")
                || !plugin.getSettings().announcementsEnd()) {
            return;
        }
        boolean permanent = sentence.permanent();
        List<DiscordWebhook.Field> fields = new ArrayList<>();
        fields.add(field("announce.field-type", typeText("jail", permanent), true));
        fields.add(field("announce.field-jail", sentence.jail(), true));
        fields.add(field("announce.field-reason", sentence.reason(), false));
        fields.add(field("announce.field-staff", sentence.staffName(), true));
        fields.add(field("announce.field-ended", timestamp(System.currentTimeMillis()), true));
        fields.add(field("announce.field-id", "#" + sentence.id(), true));

        String title = typeText("jail", permanent);
        sendEnd(title, sentence.targetName(), headUrl(sentence.targetUuid(), sentence.targetName()),
                sentence.staffName(), headUrl(sentence.staffUuid(), sentence.staffName()), fields);
    }

    // ------------------------------------------------------------
    //  Congelamiento
    // ------------------------------------------------------------

    /** Anuncia un congelamiento (o su fin). */
    public void freeze(Player target, boolean frozen, String staffName, String reason, CommandSender staff) {
        if (!usable() || !plugin.getSettings().announcementsType("freeze")) {
            return;
        }
        // El congelamiento no dura: al descongelar sólo se avisa si los fines están activados.
        if (!frozen && !plugin.getSettings().announcementsEnd()) {
            return;
        }
        UUID staffUuid = staff instanceof Player player ? player.getUniqueId() : null;
        List<DiscordWebhook.Field> fields = new ArrayList<>();
        if (frozen) {
            fields.add(field("announce.field-reason", reason == null || reason.isBlank() ? "-" : reason, false));
            fields.add(field("announce.field-staff", staffName, true));
            send("freeze", COLOR_FREEZE, typeText("freeze", false),
                    target.getName(), headUrl(target.getUniqueId(), target.getName()),
                    staffName, headUrl(staffUuid, staffName), fields);
            return;
        }
        fields.add(field("announce.field-type", typeText("freeze", false), true));
        fields.add(field("announce.field-staff", staffName, true));
        fields.add(field("announce.field-ended", timestamp(System.currentTimeMillis()), true));
        sendEnd(typeText("freeze", false), target.getName(), headUrl(target.getUniqueId(), target.getName()),
                staffName, headUrl(staffUuid, staffName), fields);
    }

    // ------------------------------------------------------------
    //  Envío
    // ------------------------------------------------------------

    /**
     * Arma los dos destinos. El público va firmado por el jugador; la copia del staff,
     * por quien emitió la sanción (su nombre y su cabeza de skin).
     */
    private void send(String kind, int color, String title, String playerName, String playerHead,
                      String staffName, String staffHead, List<DiscordWebhook.Field> fields) {
        String user = plugin.getSettings().announcementsUsername();
        DiscordWebhook webhook = plugin.getIntegrations().discord();

        String url = publicUrl();
        if (url != null) {
            webhook.sendTo(url, user, null, new DiscordWebhook.Embed(title, null, color, playerHead,
                    playerName, playerHead, footer(), fields));
        }
        url = staffUrl();
        if (url != null) {
            webhook.sendTo(url, user, null, new DiscordWebhook.Embed(title, null, color, staffHead,
                    staffName, staffHead, staffFooter(), fields));
        }
    }

    /** Embed de fin de sanción: mismo reparto de firmas, color verde. */
    private void sendEnd(String title, String playerName, String playerHead,
                         String staffName, String staffHead, List<DiscordWebhook.Field> fields) {
        String user = plugin.getSettings().announcementsUsername();
        DiscordWebhook webhook = plugin.getIntegrations().discord();
        String headline = plain("announce.end-title");
        String description = Text.substitute(plain("announce.end-description"), "player", playerName, "type", title);

        String url = publicUrl();
        if (url != null) {
            webhook.sendTo(url, user, null, new DiscordWebhook.Embed(headline, description, COLOR_END, playerHead,
                    playerName, playerHead, footer(), fields));
        }
        url = staffUrl();
        if (url != null) {
            webhook.sendTo(url, user, null, new DiscordWebhook.Embed(headline, description, COLOR_END, staffHead,
                    staffName, staffHead, staffFooter(), fields));
        }
    }

    // ------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------

    /** Tipo de anuncio que corresponde a una sanción (o {@code null} si no se anuncia). */
    private static String kindOf(PunishmentType type) {
        return switch (type) {
            case BAN, IP_BAN -> "ban";
            case MUTE, IP_MUTE -> "mute";
            // El silencio fantasma tiene que pasar desapercibido: anunciarlo lo delataría.
            // Una expulsión o una advertencia tampoco son una restricción pública.
            default -> null;
        };
    }

    private static int colorOf(String kind) {
        return switch (kind) {
            case "ban" -> COLOR_BAN;
            case "mute" -> COLOR_MUTE;
            case "jail" -> COLOR_JAIL;
            default -> COLOR_FREEZE;
        };
    }

    /** Nombre del tipo tal como lo lee la comunidad (permanente / temporal). */
    private String typeText(String kind, boolean permanent) {
        // El congelamiento no tiene duración: una sola etiqueta para todos los casos.
        if ("freeze".equals(kind)) {
            return plain("announce.type-freeze");
        }
        return plain("announce.type-" + kind + (permanent ? "-permanent" : "-temporal"));
    }

    /** Duración pedida, no lo que queda (mismo criterio que el anuncio del chat). */
    private String durationText(Sanction sanction) {
        if (sanction.permanent()) {
            return plain("generic.permanent");
        }
        long requested = sanction.expiresAt() - sanction.createdAt();
        return TimeUtil.format(requested > 0L ? requested : sanction.expiresAt() - System.currentTimeMillis());
    }

    private static long jailMillis(JailSentence sentence) {
        return Math.max(0L, sentence.remainingSeconds() * 1000L);
    }

    /** Momento exacto en que termina una condena. */
    private static long jailExpires(JailSentence sentence) {
        return sentence.createdAt() + sentence.remainingSeconds() * 1000L;
    }

    /**
     * Cuenta atrás viva de Discord.
     *
     * <p>{@code <t:...:f>} es la fecha completa y {@code <t:...:R>} el "en 2 horas", que
     * el cliente recalcula cada segundo. El servidor no tiene que enviar nada más.</p>
     */
    private static String countdown(long epochMillis) {
        long epoch = Math.max(0L, epochMillis / 1000L);
        return "<t:" + epoch + ":f> · <t:" + epoch + ":R>";
    }

    private static String timestamp(long epochMillis) {
        return "<t:" + (epochMillis / 1000L) + ":f>";
    }

    /**
     * Cabeza de la skin del jugador.
     *
     * <p>Se resuelve con el UUID (impermeable a cambios de nombre) o, si no se conoce
     * (consola, sanción por nombre antes de entrar), con el nombre. Un jugador de
     * Bedrock con Floodgate también sale: su UUID es el de Floodgate y el servicio de
     * cabezas lo resuelve por nombre.</p>
     */
    private String headUrl(UUID uuid, String name) {
        return Heads.url(plugin.getSettings().announcementsHeadUrl(), uuid, name);
    }

    private DiscordWebhook.Field field(String key, String value, boolean inline) {
        return new DiscordWebhook.Field(plain(key), clean(value), inline);
    }

    private String footer() {
        return clean(plugin.getSettings().serverName());
    }

    private String staffFooter() {
        return clean(plain("announce.staff-footer")) + " · " + footer();
    }

    /** Texto de mensaje ya plano: Discord no entiende MiniMessage. */
    private String plain(String key) {
        return Text.plain(plugin.getMessages().get(key));
    }

    /**
     * Deja el valor en una línea y sin marcado que rompa el embed o mencione a medio
     * servidor: un motivo con {@code @everyone} no debe pingear a nadie.
     */
    private static String clean(String value) {
        if (value == null) {
            return "-";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return "-";
        }
        // Los ángulos se dejan intactos: son la sintaxis de la cuenta atrás
        // (<t:...:R>) y en Discord no tienen ningún efecto por sí solos.
        text = text.replace("`", "'")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("~", "\\~")
                .replace("|", "\\|")
                .replace("@", "@\u200B");
        return text.length() > MAX_VALUE ? text.substring(0, MAX_VALUE - 1) + "…" : text;
    }

    /** Reprograma nada: los avisos de fin cuelgan de los caminos de expiración existentes. */
    public void reload() {
        // Sin temporizadores propios no hay nada que reiniciar; el método existe para que
        // /at reload deje constancia de la configuración nueva en el log.
        plugin.getLogger().info("Anuncios de sanciones: " + status());
    }
}
