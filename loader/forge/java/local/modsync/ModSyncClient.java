package local.modsync;

import local.modsync.client.ClientSide;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;

/** Client-only Forge hooks, kept apart so the dedicated server never loads client classes. */
final class ModSyncClient {
    static void init() {
        ClientSide.init();
        MinecraftForge.EVENT_BUS.addListener(ModSyncClient::onScreenInit);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen) ClientSide.onTitleScreen();
    }
}
