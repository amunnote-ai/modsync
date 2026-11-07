package local.modsync.client;

import local.modsync.common.Manifest;
import local.modsync.common.ModJar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Difference between the server manifest and the local mods folder:
 * missing server jars are downloaded; a local jar is removed only when the server has the same mod in another
 * version (it is replaced by the server's) or its mod id is on the server's remove list. Anything else the player
 * has (client mods, their libraries, shader mods) is left alone.
 */
public record SyncPlan(List<Download> downloads, List<Removal> removals) {
    public record Download(Manifest.Entry entry, String targetName) {}

    public enum Reason { WRONG_VERSION, FORBIDDEN }

    public record Removal(ModJar jar, Reason reason) {}

    public boolean isEmpty() {
        return downloads.isEmpty() && removals.isEmpty();
    }

    public static SyncPlan compute(Manifest manifest, List<ModJar> local) {
        Set<String> localHashes = new HashSet<>();
        Set<String> localNames = new HashSet<>();
        for (ModJar j : local) {
            localHashes.add(j.sha256());
            localNames.add(j.fileName().toLowerCase(Locale.ROOT));
        }

        Set<String> serverHashes = new HashSet<>();
        Set<String> serverIds = new HashSet<>();
        for (Manifest.Entry e : manifest.files) {
            serverHashes.add(e.sha256);
            serverIds.addAll(e.modIds);
        }
        Set<String> forbidden = new HashSet<>(manifest.remove);

        List<Removal> removals = new ArrayList<>();
        Set<String> removedNames = new HashSet<>();
        for (ModJar j : local) {
            if (serverHashes.contains(j.sha256())) continue;
            Reason reason = null;
            if (j.modIds().stream().anyMatch(forbidden::contains)) reason = Reason.FORBIDDEN;
            else if (j.modIds().stream().anyMatch(serverIds::contains)) reason = Reason.WRONG_VERSION;
            if (reason != null) {
                removals.add(new Removal(j, reason));
                removedNames.add(j.fileName().toLowerCase(Locale.ROOT));
            }
        }

        List<Download> downloads = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Manifest.Entry e : manifest.files) {
            if (localHashes.contains(e.sha256) || !seen.add(e.sha256)) continue;
            String target = e.name;
            // Never overwrite a jar we are not removing (Windows file names are case-insensitive)
            String lower = target.toLowerCase(Locale.ROOT);
            if (localNames.contains(lower) && !removedNames.contains(lower)) {
                target = "modsync-" + e.sha256.substring(0, 8) + "-" + e.name;
            }
            downloads.add(new Download(e, target));
        }
        return new SyncPlan(List.copyOf(downloads), List.copyOf(removals));
    }
}
