package com.admintools.util;

import com.admintools.AdminToolsPlugin;
import com.admintools.command.CommandRegistry;
import com.admintools.gui.panel.PanelCatalog;
import com.admintools.gui.panel.PanelGroup;
import com.admintools.gui.panel.PanelRank;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Letrero de arranque en consola.
 *
 * <p>Todo sale en un solo evento de log: así el prefijo {@code [AdminTools]} aparece una vez y el
 * marco queda alineado a la izquierda, como en los plugins de pago. El bloque tiene 62 columnas
 * de ancho interior (cabe en una consola de 80 sin partirse) y usa colores ANSI de 256 colores,
 * que Paper, Pterodactyl y las consolas modernas interpretan directamente.
 *
 * <p>Con {@code console.color: never} se imprime exactamente el mismo dibujo en texto plano, para
 * que el archivo latest.log quede limpio.
 */
public final class ConsoleBanner {

    /** Ancho interior del marco. Con el prefijo del log cabe en 80 columnas. */
    private static final int WIDTH = 62;

    private static final String ESC = "\u001B[";
    private static final String RESET = "\u001B[0m";
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

    // Paleta: cian para el marco, gris para etiquetas, blanco para datos y
    // verde/rojo/ámbar para los interruptores y el estado.
    private static final String C_BORDER = ESC + "38;5;240m";
    private static final String C_ACCENT = ESC + "38;5;45m";
    private static final String C_LABEL = ESC + "38;5;110m";
    private static final String C_MUTED = ESC + "38;5;247m";
    private static final String C_OK = ESC + "38;5;114m";
    private static final String C_OFF = ESC + "38;5;203m";
    private static final String C_WARN = ESC + "38;5;221m";

    private static final String LOGO_TOP = "   ▄▀█ █▀▄ █▀▄▀█ █ █▄░█ ▀█▀ █▀█ █▀█ █░░ █▀";
    private static final String LOGO_BOTTOM = "   █▀█ █▄▀ █░▀░█ █ █░▀█ ░█░ █▄█ █▄█ █▄▄ ▄█";
    private static final String TAGLINE = "ADMINISTRACIÓN · SANCIONES · CÁRCEL · STAFF";

    private ConsoleBanner() {
    }

    /** Una fila del panel: etiqueta y valor (el valor ya puede traer color). */
    private record Row(String label, String value) {
    }

    /** Imprime el letrero, si está activado en config.yml. */
    public static void print(AdminToolsPlugin plugin, CommandRegistry registry, long startupMillis) {
        if (!plugin.getSettings().consoleBanner()) {
            return;
        }
        boolean color = colorEnabled(plugin.getSettings().consoleColor());
        List<String> lines = render(plugin, registry, startupMillis);
        if (!color) {
            lines.replaceAll(ConsoleBanner::strip);
        }
        plugin.getLogger().info(String.join(System.lineSeparator(), lines));
    }

    /** ¿Se pueden usar secuencias ANSI en esta consola? */
    public static boolean colorEnabled(String mode) {
        String value = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        if (value.equals("never") || value.equals("false") || value.equals("off")) {
            return false;
        }
        if (value.equals("always") || value.equals("true") || value.equals("on")) {
            return true;
        }
        // auto: se respeta lo que diga el servidor y se evita soltar códigos crudos
        // en una consola de Windows sin JLine (se verían como «←[38;5;45m»).
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        String ansi = System.getProperty("terminal.ansi");
        if (ansi != null && !ansi.isBlank()) {
            return Boolean.parseBoolean(ansi);
        }
        String jline = System.getProperty("terminal.jline");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return !(windows && jline != null && jline.equalsIgnoreCase("false"));
    }

    private static List<String> render(AdminToolsPlugin plugin, CommandRegistry registry, long startupMillis) {
        List<String> lines = new ArrayList<>();
        // Un renglón en blanco antes: separa el letrero de las líneas de carga.
        lines.add("");
        lines.add(C_BORDER + "  ╔" + "═".repeat(WIDTH) + "╗" + RESET);
        lines.add(content(""));
        lines.add(content(gradient(LOGO_TOP)));
        lines.add(content(gradient(LOGO_BOTTOM)));
        lines.add(content(""));
        lines.add(content(C_MUTED + center(TAGLINE) + RESET));
        lines.add(C_BORDER + "  ╟" + "─".repeat(WIDTH) + "╢" + RESET);
        for (Row row : rows(plugin, registry, startupMillis)) {
            lines.add(content(C_LABEL + padRight(row.label(), 15) + RESET + row.value()));
        }
        lines.add(C_BORDER + "  ╚" + "═".repeat(WIDTH) + "╝" + RESET);
        return lines;
    }

    private static List<Row> rows(AdminToolsPlugin plugin, CommandRegistry registry, long startupMillis) {
        var settings = plugin.getSettings();
        var jail = plugin.getJailService();
        List<Row> rows = new ArrayList<>();

        rows.add(new Row("Versión", C_ACCENT + plugin.getPluginMeta().getVersion() + RESET
                + C_MUTED + " · " + Bukkit.getName() + " " + Bukkit.getMinecraftVersion() + RESET));
        rows.add(new Row("Servidor", plugin.getServerName()));

        String storage = settings.mysql()
                ? "MySQL · " + settings.mysqlHost() + ":" + settings.mysqlPort() + "/" + settings.mysqlDatabase()
                : "SQLite · data.db";
        rows.add(new Row("Datos", storage));

        rows.add(new Row("Idioma", settings.language() + C_MUTED + " · messages_" + settings.language() + ".yml" + RESET));

        int actions = 0;
        for (PanelRank rank : PanelRank.values()) {
            for (PanelGroup group : PanelCatalog.groups(rank)) {
                actions += group.actions().size();
            }
        }
        rows.add(new Row("Comandos", registry.definitions().size() + C_MUTED + " disponibles" + RESET));
        rows.add(new Row("Paneles", PanelRank.values().length + " rangos · " + actions
                + " acciones · " + C_ACCENT + settings.panelUi() + RESET));

        if (jail.enabled()) {
            rows.add(new Row("Cárcel", count(jail.jails().size(), "celda", "celdas")
                    + C_MUTED + " · " + RESET + count(jail.activeAll().size(), "preso", "presos")));
        } else {
            rows.add(new Row("Cárcel", C_MUTED + "desactivada" + RESET));
        }

        var active = plugin.getIntegrations().activeTexts();
        rows.add(new Row("Integraciones", active.isEmpty() ? C_MUTED + "ninguna" + RESET : String.join(", ", active)));

        rows.add(new Row("Discord", "alertas " + onOff(settings.discordEnabled())
                + C_MUTED + " · " + RESET + "anuncios " + onOff(settings.announcementsEnabled())
                + C_MUTED + " · " + RESET + "staff-log " + onOff(plugin.getStaffLog().usable())));

        String state = plugin.getMaintenanceService().enabled()
                ? C_WARN + "EN MANTENIMIENTO" + RESET
                : C_OK + "operativo" + RESET;
        if (plugin.getChatService().muteAll()) {
            state = state + C_MUTED + " · chat silenciado" + RESET;
        }
        rows.add(new Row("Estado", state));

        rows.add(new Row("Arranque", startupMillis + C_MUTED + " ms" + RESET));
        rows.add(new Row("Ayuda", C_MUTED + "/at help · /helper · /moderator · /admin" + RESET));
        return rows;
    }

    /** «1 celda» / «2 celdas»: el plural a mano evitaba «1 presos». */
    private static String count(int amount, String singular, String plural) {
        return amount + " " + (amount == 1 ? singular : plural);
    }

    private static String onOff(boolean value) {
        return value ? C_OK + "ON" + RESET : C_MUTED + "OFF" + RESET;
    }

    /** Una línea del marco con el contenido ya coloreado y relleno a la derecha. */
    private static String content(String text) {
        int padding = Math.max(0, WIDTH - 2 - visibleWidth(text));
        return C_BORDER + "  ║" + RESET + "  " + text + " ".repeat(padding) + C_BORDER + "║" + RESET;
    }

    private static String center(String text) {
        int left = Math.max(0, (WIDTH - 2 - text.length()) / 2);
        return " ".repeat(left) + text;
    }

    private static String padRight(String text, int width) {
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }

    /** Degradado horizontal cian → verde sobre el logo. */
    private static String gradient(String text) {
        int[] from = {56, 189, 248};
        int[] to = {52, 211, 153};
        int last = Math.max(1, text.length() - 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                out.append(c);
                continue;
            }
            double t = (double) i / last;
            int r = (int) Math.round(from[0] + (to[0] - from[0]) * t);
            int g = (int) Math.round(from[1] + (to[1] - from[1]) * t);
            int b = (int) Math.round(from[2] + (to[2] - from[2]) * t);
            out.append(ESC).append("38;5;").append(cube(r, g, b)).append('m').append(c);
        }
        return out + RESET;
    }

    /** Aproxima un color RGB al cubo de 216 colores de la paleta xterm. */
    private static int cube(int r, int g, int b) {
        int ri = Math.min(5, Math.round(r / 255f * 5));
        int gi = Math.min(5, Math.round(g / 255f * 5));
        int bi = Math.min(5, Math.round(b / 255f * 5));
        return 16 + 36 * ri + 6 * gi + bi;
    }

    /** Quita los códigos ANSI (texto plano y pruebas). */
    public static String strip(String text) {
        return ANSI.matcher(text).replaceAll("");
    }

    /** Ancho visible de una línea, sin contar los códigos ANSI. */
    public static int visibleWidth(String text) {
        return ANSI.matcher(text).replaceAll("").length();
    }
}
