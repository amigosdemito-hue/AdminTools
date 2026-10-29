package com.admintools.service;

import com.admintools.AdminToolsPlugin;
import com.admintools.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Copias de seguridad de mundos y configuración, en segundo plano y con rotación. */
public final class BackupService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final AdminToolsPlugin plugin;
    private BukkitTask task;
    private volatile boolean running;

    public BackupService(AdminToolsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!plugin.getSettings().backupsEnabled()) {
            return;
        }
        long interval = plugin.getSettings().backupsIntervalHours() * 72000L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> runAsync(Bukkit.getConsoleSender()), interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public File folder() {
        File folder = new File(plugin.getDataFolder(), plugin.getSettings().backupsFolder());
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("No pude crear la carpeta de copias " + folder.getAbsolutePath());
        }
        return folder;
    }

    public List<File> list() {
        File[] files = folder().listFiles((dir, name) -> name.endsWith(".zip"));
        if (files == null) {
            return List.of();
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparingLong(File::lastModified).reversed());
        return sorted;
    }

    /** Guarda los mundos en disco y comprime todo en un zip (hilo aparte). */
    public void runAsync(CommandSender notify) {
        if (running) {
            plugin.getMessages().send(notify, "ops.backup-busy");
            return;
        }
        running = true;
        plugin.getMessages().send(notify, "ops.backup-started");
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Todo lo que toca la API de Bukkit se hace aquí, en el hilo del servidor:
            // el zip sólo recibe ya las rutas resueltas.
            List<Path> worldPaths = new ArrayList<>();
            boolean withWorlds = plugin.getSettings().backupsWorlds();
            for (World world : Bukkit.getWorlds()) {
                world.save();
                if (withWorlds) {
                    Path path = world.getWorldFolder().toPath();
                    if (Files.exists(path)) {
                        worldPaths.add(path);
                    }
                }
            }
            boolean withConfig = plugin.getSettings().backupsPluginConfig();
            Path dataPath = plugin.getDataFolder().toPath();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                long started = System.currentTimeMillis();
                String fileName = "backup-" + LocalDateTime.now().format(STAMP) + ".zip";
                File target = new File(folder(), fileName);
                try {
                    List<Path> sources = new ArrayList<>(worldPaths);
                    if (withConfig && Files.exists(dataPath)) {
                        sources.add(dataPath);
                    }
                    long size = zip(target, sources);
                    int removed = rotate(plugin.getSettings().backupsKeep());
                    long elapsed = (System.currentTimeMillis() - started) / 1000L;
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.getMessages().send(notify, "ops.backup-done",
                            "file", fileName, "size", Util.bytes(size), "time", elapsed));
                    if (removed > 0) {
                        plugin.getLogger().info("Copias antiguas eliminadas: " + removed);
                    }
                } catch (IOException ex) {
                    // Una copia a medias no sirve: se borra para que no ocupe sitio ni
                    // desplace a las buenas en la rotación.
                    if (target.exists() && !target.delete()) {
                        plugin.getLogger().warning("No pude borrar la copia incompleta " + target.getName());
                    }
                    plugin.getLogger().warning("Backup falló: " + ex.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.getMessages().send(notify, "ops.backup-failed",
                            "error", ex.getMessage()));
                } finally {
                    running = false;
                }
            });
        });
    }

    private long zip(File target, List<Path> sources) throws IOException {
        long written = 0L;
        byte[] buffer = new byte[8192];
        // Las copias anteriores nunca entran en la copia nueva: si no, cada zip
        // crecería con todos los anteriores.
        Path targetPath = target.toPath().toAbsolutePath().normalize();
        Path backupsRoot = folder().toPath().toAbsolutePath().normalize();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
            for (Path source : sources) {
                File sourceFile = source.toFile();
                String base = sourceFile.getParentFile() == null ? "" : "";
                List<Path> files;
                if (Files.isDirectory(source)) {
                    // El stream se cierra: si no, en Windows quedan handles abiertos del mundo.
                    try (var walked = Files.walk(source)) {
                        files = walked.filter(Files::isRegularFile).toList();
                    }
                } else {
                    files = List.of(source);
                }
                for (Path file : files) {
                    Path absolute = file.toAbsolutePath().normalize();
                    // Fuera del zip: las propias copias, las temporales y los logs rotados.
                    if (absolute.equals(targetPath) || absolute.startsWith(backupsRoot)
                            || absolute.toString().endsWith(".zip")) {
                        continue;
                    }
                    String name = file.toString();
                    if (name.endsWith(".lock") || name.endsWith("-journal") || name.endsWith(".log.gz")) {
                        continue;
                    }
                    zip.putNextEntry(new ZipEntry(relativize(source, file, base)));
                    try (InputStream in = new FileInputStream(file.toFile())) {
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            zip.write(buffer, 0, read);
                            written += read;
                        }
                    }
                    zip.closeEntry();
                }
                if (Files.isDirectory(source)) {
                    zip.putNextEntry(new ZipEntry(sourceFile.getName() + "/"));
                    zip.closeEntry();
                }
            }
        }
        return written;
    }

    private String relativize(Path source, Path file, String base) {
        Path root = Files.isDirectory(source) ? source : source.getParent();
        String relative = root == null ? file.getFileName().toString()
                : root.relativize(file).toString().replace('\\', '/');
        String prefix = Files.isDirectory(source) ? source.getFileName().toString() + "/" : "";
        return prefix + relative;
    }

    /** Deja sólo las N copias más nuevas. */
    public int rotate(int keep) {
        List<File> files = list();
        int removed = 0;
        for (int i = keep; i < files.size(); i++) {
            if (files.get(i).delete()) {
                removed++;
            }
        }
        return removed;
    }

    public String sizeText(File file) {
        return Util.bytes(file.length());
    }
}
