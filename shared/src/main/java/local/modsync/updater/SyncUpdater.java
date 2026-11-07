package local.modsync.updater;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Standalone program (no Minecraft/Forge classes, no nested classes): the client copies this single
 * class into modsync/updater.jar and runs it in a separate JVM. It waits for the game to exit, since
 * Windows keeps loaded jars locked, then applies the plan file.
 *
 * Plan file, one tab-separated command per line:
 *   MODS    &lt;mods dir&gt;
 *   BACKUP  &lt;backup dir&gt;
 *   REMOVE  &lt;file name in mods dir&gt;
 *   ADD     &lt;staged file&gt;  &lt;file name in mods dir&gt;
 */
public final class SyncUpdater {
    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path plan = Paths.get(args[1]).toAbsolutePath();
        Path workDir = plan.getParent();
        try (FileChannel ch = FileChannel.open(workDir.resolve("updater.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = ch.tryLock();
            if (lock == null) {
                log("Another updater is already running, exiting");
                return;
            }
            Optional<ProcessHandle> game = ProcessHandle.of(pid);
            if (game.isPresent()) {
                log("Waiting for game process " + pid + " to exit...");
                game.get().onExit().join();
            }
            Thread.sleep(1500); // give the OS a moment to release file handles
            apply(plan, workDir, 40);
            lock.release();
        }
    }

    /**
     * Applies the plan; whatever cannot be moved after {@code attempts} tries (500 ms apart) stays in the plan file.
     * Also called inside the running game with one attempt: files the game holds open on Windows fail and are left for the external run.
     */
    public static boolean apply(Path plan, Path workDir, int attempts) throws IOException, InterruptedException {
        if (!Files.exists(plan)) {
            log("No plan file, nothing to do");
            return true;
        }
        Path mods = null;
        Path backup = null;
        List<String[]> removes = new ArrayList<>();
        List<String[]> adds = new ArrayList<>();
        for (String line : Files.readAllLines(plan, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            switch (p[0]) {
                case "MODS" -> mods = Paths.get(p[1]);
                case "BACKUP" -> backup = Paths.get(p[1]);
                case "REMOVE" -> removes.add(p);
                case "ADD" -> adds.add(p);
                default -> { }
            }
        }
        if (mods == null || backup == null) {
            log("Plan file is missing MODS/BACKUP, aborting");
            return false;
        }

        // Phase 1: move every old jar out (removals, plus anything sitting where a new jar goes).
        // All or nothing: if one is locked, put the moved ones back so the folder never holds both
        // the old and the new version of a mod (that would crash the next launch).
        List<String> toMove = new ArrayList<>();
        for (String[] r : removes) toMove.add(r[1]);
        for (String[] a : adds) {
            if (Files.exists(Paths.get(a[1])) && Files.exists(mods.resolve(a[2])) && !toMove.contains(a[2])) toMove.add(a[2]);
        }
        List<Path[]> moved = new ArrayList<>();
        boolean blocked = false;
        for (String name : toMove) {
            Path src = mods.resolve(name);
            if (!Files.exists(src)) continue;
            Files.createDirectories(backup);
            Path dst = uniqueTarget(backup, name);
            if (moveWithRetry(src, dst, attempts)) {
                moved.add(new Path[]{src, dst});
            } else {
                blocked = true;
                break;
            }
        }
        if (blocked) {
            for (int i = moved.size() - 1; i >= 0; i--) {
                try {
                    Files.move(moved.get(i)[1], moved.get(i)[0]);
                } catch (IOException e) {
                    log("Could not restore " + moved.get(i)[0] + ": " + e);
                }
            }
            log("Some mods are still in use, nothing changed; will retry after the game exits");
            return false;
        }
        for (Path[] m : moved) log("Moved to backup: " + m[0].getFileName());

        // Phase 2: install the new jars (the mods folder itself is never locked, so these only fail on real IO errors)
        List<String> failed = new ArrayList<>();
        int added = 0;
        for (String[] a : adds) {
            Path staged = Paths.get(a[1]);
            Path target = mods.resolve(a[2]);
            if (!Files.exists(staged)) {
                if (!Files.exists(target)) log("Staged file missing, skipped: " + a[1]);
                continue;
            }
            if (moveWithRetry(staged, target, Math.max(attempts, 5))) {
                added++;
                log("Installed: " + a[2]);
            } else {
                failed.add(String.join("\t", a));
            }
        }

        if (failed.isEmpty()) {
            Files.delete(plan);
        } else {
            List<String> rest = new ArrayList<>();
            rest.add("MODS\t" + mods);
            rest.add("BACKUP\t" + backup);
            rest.addAll(failed);
            Files.write(plan, rest, StandardCharsets.UTF_8);
        }
        String result = "added=" + added + "\nremoved=" + moved.size() + "\nfailed=" + failed.size() + "\n";
        Files.writeString(workDir.resolve("last-result.txt"), result, StandardCharsets.UTF_8);
        log("Done: " + result.replace('\n', ' '));
        return true;
    }

    private static boolean moveWithRetry(Path from, Path to, int attempts) throws InterruptedException {
        for (int i = 0; i < attempts; i++) {
            try {
                Files.move(from, to);
                return true;
            } catch (IOException e) {
                if (i == attempts - 1) {
                    log("Failed to move " + from + " -> " + to + ": " + e);
                    return false;
                }
                Thread.sleep(500);
            }
        }
        return false;
    }

    private static Path uniqueTarget(Path dir, String name) {
        Path p = dir.resolve(name);
        for (int i = 1; Files.exists(p); i++) p = dir.resolve(name + "." + i);
        return p;
    }

    private static void log(String msg) {
        System.out.println("[" + LocalDateTime.now() + "] " + msg);
    }
}
