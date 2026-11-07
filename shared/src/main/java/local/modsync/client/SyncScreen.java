package local.modsync.client;

import local.modsync.common.Platform;
import local.modsync.common.Manifest;
import local.modsync.common.ModJar;
import local.modsync.compat.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shown instead of the connect screen: checks the server's mod list, lets the player review changes, downloads them.
 * Each version subclasses it as {@code compat.SyncScreenImpl} to hook up rendering.
 */
public abstract class SyncScreen extends Screen {
    private enum State { CHECKING, UNAVAILABLE, REVIEW, DOWNLOADING, DONE, ERROR }

    private record Line(String text, int color) {}

    private record CachedJar(long size, long modified, ModJar jar) {}

    private static final Map<Path, CachedJar> HASH_CACHE = new ConcurrentHashMap<>();
    private static final int LINE_HEIGHT = 11;
    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ModSync-Worker");
        t.setDaemon(true);
        return t;
    });

    private final Screen parent;
    private final ServerAddress address;
    private final Runnable reconnect;
    private volatile String baseUrl;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private State state = State.CHECKING;
    private SyncPlan plan;
    private String error = "";
    private final List<Line> lines = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();
    private volatile boolean appliedNow;
    private int scroll;

    private volatile int downloadIndex;
    private volatile long downloadBytes;

    protected SyncScreen(Screen parent, ServerAddress address, Runnable reconnect) {
        super(Component.translatable("modsync.title"));
        this.parent = parent;
        this.address = address;
        this.reconnect = reconnect;
        startCheck();
    }

    // ---- background work ----

    private void startCheck() {
        setState(State.CHECKING);
        CompletableFuture.supplyAsync(() -> {
            try {
                report.setLength(0);
                report("ModSync report " + java.time.LocalDateTime.now());
                report("Server: " + address.getHost() + ":" + address.getPort());
                baseUrl = resolveBaseUrl();
                report("Sync URL: " + baseUrl);
                Manifest manifest = SyncNetwork.fetchManifest(baseUrl, ClientSide.config.connectTimeoutMs);
                report("\nServer mods (" + manifest.files.size() + "), forbidden=" + manifest.remove);
                for (Manifest.Entry e : manifest.files) report("  " + e.name + "  " + e.sha256.substring(0, 12) + "  " + e.size + "  " + e.modIds);
                if (!manifest.skipped.isEmpty()) {
                    report("\nServer jars NOT offered to clients (" + manifest.skipped.size() + "):");
                    for (String s : manifest.skipped) report("  " + s);
                }
                List<ModJar> local = scanLocalMods();
                report("\nLocal mods (" + local.size() + ") in " + Platform.modsDir());
                for (ModJar j : local) report("  " + j.fileName() + "  " + j.sha256().substring(0, 12) + "  " + j.size() + "  " + j.modIds());
                SyncPlan result = SyncPlan.compute(manifest, local);
                report("\nPlan:");
                for (SyncPlan.Download d : result.downloads()) report("  DOWNLOAD " + d.entry().name + " -> " + d.targetName());
                for (SyncPlan.Removal r : result.removals()) report("  REMOVE   " + r.jar().fileName() + "  (" + r.reason() + ")");
                if (result.isEmpty()) report("  nothing to do");
                writeReport();
                return result;
            } catch (IOException e) {
                report("\nCheck failed: " + e);
                writeReport();
                throw new RuntimeException(e);
            }
        }, WORKER).whenComplete((result, ex) -> minecraft().execute(() -> {
            if (cancelled.get()) return;
            if (ex != null) {
                Throwable cause = ex.getCause() != null ? ex.getCause().getCause() : null;
                if (cause instanceof SyncNetwork.UnavailableException) {
                    Platform.LOGGER.info("[ModSync] No sync service at {} ({}), joining normally", baseUrl, cause.getMessage());
                    if (ClientSide.config.promptWhenUnavailable) {
                        setState(State.UNAVAILABLE);
                    } else {
                        join();
                    }
                } else {
                    fail(ex);
                }
            } else if (result.isEmpty()) {
                join();
            } else {
                plan = result;
                buildReviewLines();
                setState(State.REVIEW);
            }
        }));
    }

    private void startDownload() {
        setState(State.DOWNLOADING);
        SyncPlan p = plan;
        CompletableFuture.runAsync(() -> {
            try {
                report("\nDownloads:");
                Files.createDirectories(PendingUpdate.stagingDir());
                for (int i = 0; i < p.downloads().size(); i++) {
                    downloadIndex = i;
                    Manifest.Entry e = p.downloads().get(i).entry();
                    Path dest = PendingUpdate.stagingDir().resolve(e.sha256 + ".jar");
                    if (Files.exists(dest) && ModJar.sha256(dest).equals(e.sha256)) { // left over from an earlier attempt
                        report("  OK (already staged) " + e.name);
                        continue;
                    }
                    for (int attempt = 1; ; attempt++) {
                        downloadBytes = 0;
                        try {
                            SyncNetwork.download(baseUrl, e, dest, ClientSide.config.connectTimeoutMs, cancelled, b -> downloadBytes = b);
                            report("  OK " + e.name + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                            break;
                        } catch (IOException ex) {
                            report("  FAILED " + e.name + " attempt " + attempt + ": " + ex);
                            if (cancelled.get() || attempt >= 3) throw ex;
                            Thread.sleep(1000L * attempt);
                        }
                    }
                }
                if (cancelled.get()) return;
                appliedNow = PendingUpdate.schedule(p);
                report(appliedNow
                        ? "\nMods folder updated in-game: " + p.downloads().size() + " installed, " + p.removals().size() + " moved to mods_backup"
                        : "\nSome mods are in use; update will be applied by modsync/updater.jar after the game exits");
                writeReport();
            } catch (IOException | InterruptedException e) {
                report("\nSync failed: " + e);
                writeReport();
                throw new RuntimeException(e);
            }
        }, WORKER).whenComplete((v, ex) -> minecraft().execute(() -> {
            if (cancelled.get()) return;
            if (ex != null) fail(ex);
            else setState(State.DONE);
        }));
    }

    /** Same address the game connects to, including SRV records. Runs off the render thread since DNS can block. */
    private String resolveBaseUrl() throws IOException {
        String override = ClientSide.config.overrideUrl(address.getHost(), address.getPort());
        if (override != null) return override;
        ResolvedServerAddress resolved = ServerNameResolver.DEFAULT.resolveAddress(address)
                .orElseThrow(() -> new SyncNetwork.UnavailableException("cannot resolve " + address.getHost(), null));
        return ClientSide.config.defaultUrl(resolved.getHostIp(), resolved.getPort());
    }

    private static List<ModJar> scanLocalMods() throws IOException {
        List<ModJar> result = new ArrayList<>();
        Path dir = Platform.modsDir();
        try (var stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    long size = Files.size(p);
                    long modified = Files.getLastModifiedTime(p).toMillis();
                    CachedJar cached = HASH_CACHE.get(p);
                    if (cached == null || cached.size() != size || cached.modified() != modified) {
                        cached = new CachedJar(size, modified, ModJar.read(p));
                        HASH_CACHE.put(p, cached);
                    }
                    result.add(cached.jar());
                } catch (IOException e) {
                    Platform.LOGGER.warn("[ModSync] Skipping unreadable jar {}: {}", p.getFileName(), e.toString());
                }
            }
        }
        return result;
    }

    private void fail(Throwable ex) {
        Throwable t = ex;
        while ((t.getCause() != null) && (t instanceof RuntimeException || t instanceof java.util.concurrent.CompletionException)) t = t.getCause();
        Platform.LOGGER.error("[ModSync] Sync failed", t);
        error = t.getMessage() != null ? t.getMessage() : t.toString();
        setState(State.ERROR);
    }

    private void join() {
        ClientSide.connect(reconnect);
    }

    private net.minecraft.client.Minecraft minecraft() {
        return net.minecraft.client.Minecraft.getInstance();
    }

    // ---- UI ----

    private void setState(State s) {
        state = s;
        scroll = 0;
        if (minecraft != null) rebuildWidgets();
    }

    private void report(String line) {
        synchronized (report) {
            report.append(line).append('\n');
        }
    }

    /** modsync/sync-report.txt: what was compared and decided, for troubleshooting. */
    private void writeReport() {
        try {
            Files.createDirectories(PendingUpdate.workDir());
            String text;
            synchronized (report) {
                text = report.toString();
            }
            Files.writeString(PendingUpdate.workDir().resolve("sync-report.txt"), text, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            Platform.LOGGER.warn("[ModSync] Could not write sync report", e);
        }
    }

    private void buildReviewLines() {
        lines.clear();
        for (SyncPlan.Download d : plan.downloads()) {
            lines.add(new Line("+ " + d.targetName() + "  (" + formatSize(d.entry().size) + ")", 0xFF55FF55));
        }
        for (SyncPlan.Removal r : plan.removals()) {
            String reason = Component.translatable("modsync.reason." + r.reason().name().toLowerCase(java.util.Locale.ROOT)).getString();
            lines.add(new Line("- " + r.jar().fileName() + "  [" + reason + "]", 0xFFFF5555));
        }
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = height - 30;
        switch (state) {
            case CHECKING -> addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                    .bounds(cx - 50, y, 100, 20).build());
            case UNAVAILABLE -> {
                addRenderableWidget(Button.builder(Component.translatable("modsync.button.join_anyway"), b -> join())
                        .bounds(cx - 102, y, 100, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                        .bounds(cx + 2, y, 100, 20).build());
            }
            case REVIEW -> {
                addRenderableWidget(Button.builder(Component.translatable("modsync.button.sync"), b -> startDownload())
                        .bounds(cx - 155, y, 110, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("modsync.button.join_anyway"), b -> join())
                        .bounds(cx - 40, y, 90, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                        .bounds(cx + 55, y, 100, 20).build());
            }
            case DOWNLOADING -> addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                    .bounds(cx - 50, y, 100, 20).build());
            case DONE -> addRenderableWidget(Button.builder(Component.translatable("modsync.button.quit"), b -> minecraft().stop())
                    .bounds(cx - 75, y, 150, 20).build());
            case ERROR -> {
                addRenderableWidget(Button.builder(Component.translatable("modsync.button.retry"), b -> startCheck())
                        .bounds(cx - 155, y, 100, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("modsync.button.join_anyway"), b -> join())
                        .bounds(cx - 50, y, 100, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                        .bounds(cx + 55, y, 100, 20).build());
            }
        }
    }

    /**
     * Everything this screen draws besides the background and buttons. The version's SyncScreenImpl calls it from
     * its render method (whose name and graphics class differ between Minecraft versions), after the vanilla rendering.
     */
    protected void drawContents(Canvas g) {
        int cx = width / 2;
        g.centered(title, cx, 12, 0xFFFFFFFF);
        g.centered(Component.literal(address.getHost() + ":" + address.getPort()).withStyle(ChatFormatting.GRAY), cx, 24, 0xFFAAAAAA);

        switch (state) {
            case CHECKING -> g.centered(Component.translatable("modsync.checking"), cx, height / 2 - 10, 0xFFFFFFFF);
            case UNAVAILABLE -> g.centered(Component.translatable("modsync.unavailable"), cx, height / 2 - 10, 0xFFFFFFFF);
            case REVIEW -> renderReview(g, cx);
            case DOWNLOADING -> renderDownload(g, cx);
            case DONE -> {
                g.centered(Component.translatable("modsync.done.1"), cx, height / 2 - 20, 0xFF55FF55);
                g.centered(Component.translatable(appliedNow ? "modsync.done.applied" : "modsync.done.2"), cx, height / 2 - 6, 0xFFFFFFFF);
                g.centered(Component.translatable("modsync.done.3"), cx, height / 2 + 8, 0xFFAAAAAA);
            }
            case ERROR -> {
                g.centered(Component.translatable("modsync.error"), cx, height / 2 - 20, 0xFFFF5555);
                g.centered(font.plainSubstrByWidth(error, width - 40), cx, height / 2 - 6, 0xFFFFFFFF);
                g.centered(Component.translatable("modsync.error.report"), cx, height / 2 + 8, 0xFFAAAAAA);
            }
        }
    }

    private void renderReview(Canvas g, int cx) {
        g.centered(Component.translatable("modsync.review.summary", plan.downloads().size(), plan.removals().size()), cx, 38, 0xFFFFFFFF);
        g.centered(Component.translatable("modsync.review.warning"), cx, 50, 0xFFFFFF55);

        int top = listTop(), bottom = listBottom();
        int left = Math.max(10, cx - 170), right = Math.min(width - 10, cx + 170);
        g.fill(left - 4, top - 2, right + 4, bottom + 2, 0x80000000);
        g.scissor(left - 4, top - 2, right + 4, bottom + 2);
        for (int i = 0; i < lines.size(); i++) {
            int y = top + (i - scroll) * LINE_HEIGHT;
            if (y < top - LINE_HEIGHT || y > bottom) continue;
            Line l = lines.get(i);
            g.text(font.plainSubstrByWidth(l.text(), right - left), left, y, l.color());
        }
        g.endScissor();
        if (maxScroll() > 0) {
            g.centered(Component.translatable("modsync.review.scroll"), cx, bottom + 6, 0xFF888888);
        }
    }

    private void renderDownload(Canvas g, int cx) {
        int total = plan.downloads().size();
        if (total == 0) {
            g.centered(Component.translatable("modsync.checking"), cx, height / 2 - 10, 0xFFFFFFFF);
            return;
        }
        int i = Math.min(downloadIndex, total - 1);
        Manifest.Entry e = plan.downloads().get(i).entry();
        g.centered(Component.translatable("modsync.downloading", i + 1, total), cx, height / 2 - 24, 0xFFFFFFFF);
        g.centered(font.plainSubstrByWidth(e.name, width - 40), cx, height / 2 - 10, 0xFFAAAAAA);
        int barW = Math.min(300, width - 40), x0 = cx - barW / 2, y0 = height / 2 + 4;
        double frac = e.size > 0 ? Math.min(1.0, (double) downloadBytes / e.size) : 0;
        g.fill(x0, y0, x0 + barW, y0 + 8, 0xFF333333);
        g.fill(x0, y0, x0 + (int) (barW * frac), y0 + 8, 0xFF55FF55);
        g.centered(formatSize(downloadBytes) + " / " + formatSize(e.size), cx, y0 + 14, 0xFFAAAAAA);
    }

    private int listTop() {
        return 66;
    }

    private int listBottom() {
        return height - 50;
    }

    private int maxScroll() {
        int visible = Math.max(1, (listBottom() - listTop()) / LINE_HEIGHT);
        return Math.max(0, lines.size() - visible);
    }

    // The mouse wheel callback changed signature in 1.20.2; both are declared without @Override so each version uses its own
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return scrollList(delta);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        return scrollList(deltaY);
    }

    private boolean scrollList(double delta) {
        if (state != State.REVIEW || delta == 0) return false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(delta) * 3));
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return state != State.DONE;
    }

    @Override
    public void onClose() {
        cancelled.set(true);
        Compat.setScreen(parent);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
