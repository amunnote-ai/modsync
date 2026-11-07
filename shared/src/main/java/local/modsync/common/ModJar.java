package local.modsync.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


/**
 * A jar in a mods folder: its hash plus the mod ids declared in its META-INF/(neoforge.)mods.toml.
 * {@code serverOnly}: displayTest IGNORE_SERVER_VERSION, i.e. clients do not need it.
 */
public record ModJar(Path path, String fileName, String sha256, long size, List<String> modIds, boolean serverOnly) {
    private static final Pattern SECTION = Pattern.compile("^\\[\\[?\\s*([^\\]]+?)\\s*\\]");
    private static final Pattern MOD_ID =Pattern.compile("^modId\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern DISPLAY_TEST = Pattern.compile("^displayTest\\s*=\\s*[\"']([^\"']+)[\"']");

    /** Scans the *.jar files directly inside {@code dir} (not subfolders). Unreadable jars are skipped. */
    public static List<ModJar> scan(Path dir) throws IOException {
        List<ModJar> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    result.add(read(p));
                } catch (IOException e) {
                    Platform.LOGGER.warn("[ModSync] Skipping unreadable jar {}: {}", p.getFileName(), e.toString());
                }
            }
        }
        return result;
    }

    public static ModJar read(Path p) throws IOException {
        List<String> ids = new ArrayList<>();
        boolean serverOnly = false;
        try (ZipFile zip = new ZipFile(p.toFile())) {
            // NeoForge 1.20.5+ uses neoforge.mods.toml; Forge and older NeoForge use mods.toml
            ZipEntry entry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) entry = zip.getEntry("META-INF/mods.toml");
            if (entry != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String section = "";
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.replace(String.valueOf((char) 0xFEFF), "").strip();
                        Matcher header = SECTION.matcher(line);
                        if (header.find()) {
                            section = header.group(1).strip(); // "[[mods]] # comment" -> "mods"
                            continue;
                        }
                        if (!section.equals("mods")) continue;
                        Matcher m = MOD_ID.matcher(line);
                        if (m.find()) ids.add(m.group(1));
                        Matcher d = DISPLAY_TEST.matcher(line);
                        if (d.find() && d.group(1).equals("IGNORE_SERVER_VERSION")) serverOnly = true;
                    }
                }
            }
        }
        return new ModJar(p, p.getFileName().toString(), sha256(p), Files.size(p), List.copyOf(ids), serverOnly);
    }

    public static String sha256(Path p) throws IOException {
        MessageDigest md = newDigest();
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
