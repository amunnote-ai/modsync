package local.modsync;

import local.modsync.client.ClientSide;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only NeoForge hooks, kept apart so the dedicated server never loads client classes. */
final class ModSyncClient {
    static void init() {
        ClientSide.init();
        NeoForge.EVENT_BUS.addListener(ModSyncClient::onScreenInit);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen) ClientSide.onTitleScreen();
    }
}
