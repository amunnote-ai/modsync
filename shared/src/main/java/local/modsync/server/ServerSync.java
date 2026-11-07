package local.modsync.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import local.modsync.common.Platform;
import local.modsync.common.Manifest;
import local.modsync.common.ModJar;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated server only: publishes the mod list and jars over HTTP. The loader entry point calls {@link #init()},
 * then {@link #onStarted} / {@link #onStopping} from its server lifecycle events.
 */
public final class ServerSync {
    private static ServerConfig config;
    private static HttpServer http;
    private static ExecutorService executor;
    private static volatile byte[] manifestJson;
    private static volatile Map<String, Path> servedFiles = Map.of();

    public static void init() {
        try {
            config = ServerConfig.load(Platform.configDir().resolve("modsync-server.properties"));
        } catch (Exception e) {
            Platform.LOGGER.error("[ModSync] Bad server config, sync disabled", e);
            config = null;
            return;
        }
        if (!config.enabled) Platform.LOGGER.info("[ModSync] Disabled in config");
    }

    /** True when mods are served over the game port (see GamePortHttpHandler). */
    public static boolean sharesGamePort() {
        return config != null && config.enabled && config.port == 0;
    }

    /** The manifest as JSON, or null until the server has finished starting. */
    public static byte[] manifestJson() {
        return manifestJson;
    }

    public static Path servedFile(String name) {
        return servedFiles.get(name);
    }

    public static void onStarted(MinecraftServer server) {
        if (config == null || !config.enabled || !server.isDedicatedServer()) return;
        try {
            buildManifest(config);
            if (config.port > 0) {
                startHttp(config);
            } else {
                Platform.LOGGER.info("[ModSync] Serving mods on the game port: http://<server>:{}{}", server.getPort(), Manifest.MANIFEST_PATH);
            }
        } catch (Exception e) {
            Platform.LOGGER.error("[ModSync] Failed to start sync service", e);
        }
    }

    public static void onStopping() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static void buildManifest(ServerConfig cfg) throws IOException {
        List<PathMatcher> excludes = cfg.exclude.stream()
                .map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g.toLowerCase(Locale.ROOT)))
                .toList();
        Manifest manifest = new Manifest();
        Map<String, Path> files = new HashMap<>();

        for (ModJar jar : ModJar.scan(Platform.modsDir())) {
            Path lower = Path.of(jar.fileName().toLowerCase(Locale.ROOT));
            if (excludes.stream().anyMatch(m -> m.matches(lower))) {
                skip(manifest, jar, "excluded by config");
                continue;
            }
            if (cfg.autoExcludeServerOnly && jar.serverOnly()) {
                skip(manifest, jar, "server-only mod");
                continue;
            }
            add(manifest, files, jar);
        }

        Path clientDir = Platform.gameDir().resolve(cfg.clientOnlyDir);
        Files.createDirectories(clientDir);
        for (ModJar jar : ModJar.scan(clientDir)) add(manifest, files, jar);

        manifest.remove.addAll(cfg.removeModIds);
        manifestJson = manifest.toJson().getBytes(StandardCharsets.UTF_8);
        servedFiles = Map.copyOf(files);
        Platform.LOGGER.info("[ModSync] Manifest: {} files, {} forbidden mod ids", manifest.files.size(), manifest.remove.size());
    }

    private static void add(Manifest manifest, Map<String, Path> files, ModJar jar) {
        if (!Manifest.isSafeFileName(jar.fileName())) {
            skip(manifest, jar, "unsupported file name");
            return;
        }
        if (jar.size() > Manifest.MAX_FILE_SIZE) {
            skip(manifest, jar, "larger than 512MB");
            return;
        }
        if (files.putIfAbsent(jar.fileName(), jar.path()) != null) {
            skip(manifest, jar, "duplicate file name");
            return;
        }
        manifest.files.add(new Manifest.Entry(jar));
    }

    /** Logged on the server and listed in the manifest so it shows up in the player's sync report. */
    private static void skip(Manifest manifest, ModJar jar, String reason) {
        Platform.LOGGER.warn("[ModSync] Not sent to clients ({}): {}", reason, jar.fileName());
        manifest.skipped.add(jar.fileName() + " (" + reason + ")");
    }

    private static void startHttp(ServerConfig cfg) throws IOException {
        InetSocketAddress addr = cfg.bindAddress.isEmpty()
                ? new InetSocketAddress(cfg.port)
                : new InetSocketAddress(cfg.bindAddress, cfg.port);
        http = HttpServer.create(addr, 32);
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ModSync-HTTP");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(executor);
        http.createContext("/", ServerSync::handle);
        http.start();
        Platform.LOGGER.info("[ModSync] Serving mods on http://{}:{}{}", cfg.bindAddress.isEmpty() ? "0.0.0.0" : cfg.bindAddress, cfg.port, Manifest.MANIFEST_PATH);
    }

    private static void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String path = ex.getRequestURI().getRawPath();
            if (path.equals(Manifest.MANIFEST_PATH)) {
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                send(ex, manifestJson);
            } else if (path.startsWith(Manifest.FILES_PATH)) {
                String name = URLDecoder.decode(path.substring(Manifest.FILES_PATH.length()), StandardCharsets.UTF_8);
                Path file = servedFiles.get(name); // only names from the manifest are reachable
                if (file == null) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                ex.getResponseHeaders().set("Content-Type", "application/java-archive");
                long size = Files.size(file);
                if (method.equals("HEAD")) {
                    ex.getResponseHeaders().set("Content-Length", Long.toString(size));
                    ex.sendResponseHeaders(200, -1);
                    return;
                }
                ex.sendResponseHeaders(200, size);
                try (InputStream in = Files.newInputStream(file); OutputStream out = ex.getResponseBody()) {
                    in.transferTo(out);
                }
            } else {
                ex.sendResponseHeaders(404, -1);
            }
        }
    }

    private static void send(HttpExchange ex, byte[] body) throws IOException {
        if (ex.getRequestMethod().equals("HEAD")) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
