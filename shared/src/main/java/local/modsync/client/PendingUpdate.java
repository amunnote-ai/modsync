package local.modsync.client;

import local.modsync.common.Platform;
import local.modsync.updater.SyncUpdater;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Writes the plan file and launches {@link SyncUpdater} in its own JVM so it can run after the game exits. */
public final class PendingUpdate {
    public static Path workDir() {
        return Platform.gameDir().resolve("modsync");
    }

    public static Path stagingDir() {
        return workDir().resolve("staging");
    }

    private static Path planFile() {
        return workDir().resolve("pending-update.txt");
    }

    public static boolean exists() {
        return Files.exists(planFile());
    }

    /**
     * Called once the downloads in the plan are all in the staging folder. Tries to apply the plan right away
     * (Java opens jars with delete sharing on Windows, so this usually works even while the game runs); if some
     * jar is locked nothing is changed and the external updater applies it after the game exits.
     * Returns true if the mods folder is already updated.
     */
    public static boolean schedule(SyncPlan plan) throws IOException {
        Path mods = Platform.modsDir().toAbsolutePath();
        Path backup = Platform.gameDir().resolve("mods_backup")
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))).toAbsolutePath();
        List<String> lines = new ArrayList<>();
        lines.add("MODS\t" + mods);
        lines.add("BACKUP\t" + backup);
        for (var r : plan.removals()) lines.add("REMOVE\t" + r.jar().fileName());
        for (var d : plan.downloads()) {
            lines.add("ADD\t" + stagingDir().resolve(d.entry().sha256 + ".jar").toAbsolutePath() + "\t" + d.targetName());
        }
        Files.createDirectories(workDir());
        Files.write(planFile(), lines, StandardCharsets.UTF_8);
        try {
            SyncUpdater.apply(planFile(), workDir(), 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Platform.LOGGER.warn("[ModSync] Applying the update in-game failed, leaving it for the updater", e);
        }
        if (!exists()) {
            Platform.LOGGER.info("[ModSync] Mods folder updated in-game");
            return true;
        }
        launchUpdater();
        return false;
    }

    /** Starts the updater; it waits for this game process to exit before touching the mods folder. */
    public static void launchUpdater() throws IOException {
        Path jar = writeUpdaterJar();
        Path log = workDir().resolve("updater.log");
        List<String> cmd = List.of(javaExecutable(), "-Xmx64m", "-cp", jar.toString(),
                SyncUpdater.class.getName(), Long.toString(ProcessHandle.current().pid()), planFile().toAbsolutePath().toString());
        new ProcessBuilder(cmd)
                .directory(workDir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        Platform.LOGGER.info("[ModSync] Updater started, changes will be applied after the game exits");
    }

    /** Packs the single SyncUpdater class into its own jar so the updater never depends on the (possibly replaced) mod jar. */
    private static Path writeUpdaterJar() throws IOException {
        Path jar = workDir().resolve("updater.jar");
        String entry = SyncUpdater.class.getName().replace('.', '/') + ".class";
        java.util.jar.Manifest mf = new java.util.jar.Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, SyncUpdater.class.getName());
        try (InputStream in = SyncUpdater.class.getResourceAsStream("SyncUpdater.class");
             OutputStream os = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(os, mf)) {
            if (in == null) throw new IOException("SyncUpdater.class not found");
            out.putNextEntry(new JarEntry(entry));
            in.transferTo(out);
            out.closeEntry();
        }
        return jar;
    }

    private static String javaExecutable() {
        String cmd = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        // Prefer javaw on Windows so no console window flashes up
        if (cmd.toLowerCase().endsWith("java.exe")) {
            Path javaw = Path.of(cmd).resolveSibling("javaw.exe");
            if (Files.exists(javaw)) return javaw.toString();
        }
        return cmd;
    }

    /** Result written by the updater after a successful run, or null. Deleted once read. */
    public static String consumeLastResult() {
        Path p = workDir().resolve("last-result.txt");
        try {
            if (!Files.exists(p)) return null;
            String s = Files.readString(p, StandardCharsets.UTF_8);
            Files.delete(p);
            return s;
        } catch (IOException e) {
            return null;
        }
    }
}
