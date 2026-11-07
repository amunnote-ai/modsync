package local.modsync.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the server publishes at {@code /modsync/manifest.json}.
 * The client never trusts it blindly: {@link #validate()} must pass before anything is downloaded.
 */
public final class Manifest {
    public static final int FORMAT = 1;
    public static final String MANIFEST_PATH = "/modsync/manifest.json";
    public static final String FILES_PATH = "/modsync/files/";
    public static final long MAX_FILE_SIZE = 512L * 1024 * 1024;
    private static final int MAX_ENTRIES = 2000;
    // Characters Windows forbids in file names, plus both path separators
    private static final String FORBIDDEN_CHARS = "/\\:*?\"<>|";
    private static final Pattern WINDOWS_RESERVED = Pattern.compile("^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(\\..*)?$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int format = FORMAT;
    public List<Entry> files = new ArrayList<>();
    /** Mod ids the client must not have, e.g. cheats or mods known to break this server. */
    public List<String> remove = new ArrayList<>();
    /** Jars in the server's folders that are not offered, with the reason. Informational only. */
    public List<String> skipped = new ArrayList<>();

    public static final class Entry {
        public String name;
        public String sha256;
        public long size;
        public List<String> modIds = new ArrayList<>();

        public Entry() {}

        public Entry(ModJar jar) {
            this.name = jar.fileName();
            this.sha256 = jar.sha256();
            this.size = jar.size();
            this.modIds = new ArrayList<>(jar.modIds());
        }
    }

    /**
     * A plain file name that is safe to create inside the mods folder on any OS. Any language is fine
     * (e.g. "[帕秋莉手册] Patchouli.jar"); what is rejected is anything that could escape the folder or that Windows refuses.
     */
    public static boolean isSafeFileName(String name) {
        if (name == null || name.length() > 200 || !name.toLowerCase(Locale.ROOT).endsWith(".jar") || name.length() <= 4) return false;
        if (name.startsWith(".") || name.startsWith(" ") || name.contains("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F || FORBIDDEN_CHARS.indexOf(c) >= 0) return false;
        }
        return !WINDOWS_RESERVED.matcher(name.toUpperCase(Locale.ROOT)).matches();
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static Manifest fromJson(String json) {
        Manifest m = GSON.fromJson(json, Manifest.class);
        if (m == null) throw new IllegalArgumentException("empty manifest");
        m.validate();
        return m;
    }

    public void validate() {
        if (format != FORMAT) throw new IllegalArgumentException("unsupported manifest format " + format);
        if (files == null) files = new ArrayList<>();
        if (remove == null) remove = new ArrayList<>();
        if (skipped == null) skipped = new ArrayList<>();
        if (files.size() > MAX_ENTRIES) throw new IllegalArgumentException("too many files");
        // Drop bad entries instead of rejecting the whole manifest, so one odd file never blocks the rest of the sync
        List<Entry> valid = new ArrayList<>();
        for (Entry e : files) {
            String problem = e == null ? "null entry"
                    : !isSafeFileName(e.name) ? "unsafe file name"
                    : e.sha256 == null || !SHA256.matcher(e.sha256).matches() ? "bad sha256"
                    : e.size <= 0 || e.size > MAX_FILE_SIZE ? "bad size" : null;
            if (problem != null) {
                skipped.add((e == null ? "?" : e.name) + " (rejected by client: " + problem + ")");
                continue;
            }
            if (e.modIds == null) e.modIds = new ArrayList<>();
            valid.add(e);
        }
        files = valid;
    }
}
