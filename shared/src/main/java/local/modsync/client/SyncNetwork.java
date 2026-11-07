package local.modsync.client;

import local.modsync.common.Manifest;
import local.modsync.common.ModJar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/** HTTP calls to the server's sync service. Redirects are refused so files only come from the address the player joined. */
public final class SyncNetwork {
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;

    /** Thrown when the sync service cannot be reached at all (server probably has no ModSync). */
    public static final class UnavailableException extends IOException {
        public UnavailableException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    public static Manifest fetchManifest(String baseUrl, int timeoutMs) throws IOException {
        HttpURLConnection c;
        int status;
        try {
            c = open(baseUrl + Manifest.MANIFEST_PATH, timeoutMs);
            c.setReadTimeout(timeoutMs);
            // A game port without ModSync answers with a Minecraft disconnect or just closes: not HTTP, so "unavailable"
            status = c.getResponseCode();
        } catch (IOException e) {
            throw new UnavailableException(e.toString(), e);
        }
        try {
            if (status != 200) throw new UnavailableException("HTTP " + status, null);
            try (InputStream in = c.getInputStream()) {
                byte[] body = in.readNBytes(MAX_MANIFEST_BYTES + 1);
                if (body.length > MAX_MANIFEST_BYTES) throw new IOException("manifest too large");
                return Manifest.fromJson(new String(body, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new IOException("invalid manifest: " + e.getMessage(), e);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Downloads one jar to {@code dest}, verifying size and SHA-256. The file only appears at {@code dest} if it is valid. */
    public static void download(String baseUrl, Manifest.Entry entry, Path dest, int timeoutMs,
                                AtomicBoolean cancelled, LongConsumer progress) throws IOException {
        String url = baseUrl + Manifest.FILES_PATH + URLEncoder.encode(entry.name, StandardCharsets.UTF_8).replace("+", "%20");
        HttpURLConnection c = open(url, timeoutMs);
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + entry.name);
            MessageDigest md = ModJar.newDigest();
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.get()) throw new IOException("cancelled");
                    total += n;
                    if (total > entry.size) throw new IOException("file larger than announced: " + entry.name);
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                    progress.accept(total);
                }
            }
            if (total != entry.size) throw new IOException("size mismatch for " + entry.name);
            String hash = HexFormat.of().formatHex(md.digest());
            if (!hash.equals(entry.sha256)) throw new IOException("SHA-256 mismatch for " + entry.name);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            c.disconnect();
            Files.deleteIfExists(tmp);
        }
    }

    private static HttpURLConnection open(String url, int timeoutMs) throws IOException {
        URI uri = URI.create(url);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) throw new IOException("unsupported URL: " + url);
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(Math.max(timeoutMs, 15000));
        c.setRequestProperty("User-Agent", "ModSync");
        return c;
    }
}
