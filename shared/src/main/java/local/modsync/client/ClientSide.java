package local.modsync.client;

import local.modsync.common.Platform;
import local.modsync.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Client logic shared by every Minecraft version. The loader entry point calls {@link #init()} and
 * {@link #onTitleScreen()}; the version's ConnectScreen mixin calls {@link #interceptConnect}.
 */
public final class ClientSide {
    public static ClientConfig config = new ClientConfig();
    private static boolean bypassNextConnect;
    private static boolean pendingLeftover;
    private static boolean titleNoticeShown;

    public static void init() {
        config = ClientConfig.load(Platform.configDir().resolve("modsync-client.properties"));
        // A plan is still here: the updater did not finish (e.g. the launcher killed it). Re-arm it for this session's exit.
        if (PendingUpdate.exists()) {
            pendingLeftover = true;
            try {
                PendingUpdate.launchUpdater();
            } catch (Exception e) {
                Platform.LOGGER.error("[ModSync] Could not restart the updater", e);
            }
        }
    }

    /** Called once the title screen is shown: report what the last sync did. */
    public static void onTitleScreen() {
        if (titleNoticeShown) return;
        titleNoticeShown = true;
        String result = PendingUpdate.consumeLastResult();
        if (result != null) {
            int added = field(result, "added");
            int removed = field(result, "removed");
            int failed = field(result, "failed");
            Compat.toast(Component.translatable("modsync.toast.applied.title"),
                    Component.translatable(failed > 0 ? "modsync.toast.applied.failed" : "modsync.toast.applied", added, removed, failed));
        }
        if (pendingLeftover) {
            Compat.toast(Component.translatable("modsync.toast.pending.title"), Component.translatable("modsync.toast.pending"));
        }
    }

    private static int field(String text, String key) {
        for (String line : text.split("\n")) {
            if (line.startsWith(key + "=")) {
                try {
                    return Integer.parseInt(line.substring(key.length() + 1).strip());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    /**
     * Called from the ConnectScreen mixin. {@code reconnect} repeats the original vanilla call with the original
     * arguments (they differ between Minecraft versions). Returns true if the connection was taken over by the sync screen.
     */
    public static boolean interceptConnect(Screen parent, ServerAddress address, Runnable reconnect) {
        if (bypassNextConnect) {
            bypassNextConnect = false;
            return false;
        }
        if (!config.enabled) return false;
        Compat.setScreen(new local.modsync.compat.SyncScreenImpl(parent, address, reconnect));
        return true;
    }

    /** Continue with the vanilla connection, skipping our check once. */
    public static void connect(Runnable reconnect) {
        bypassNextConnect = true;
        reconnect.run();
    }
}
