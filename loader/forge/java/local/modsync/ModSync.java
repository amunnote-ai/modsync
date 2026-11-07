package local.modsync;

import local.modsync.common.Platform;
import local.modsync.server.ServerSync;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/** Forge entry point (Minecraft 1.20.1 - 1.21.1). */
@Mod(ModSync.MODID)
public final class ModSync {
    public static final String MODID = "modsync";

    public ModSync() {
        Platform.init(FMLPaths.GAMEDIR.get(), FMLPaths.MODSDIR.get(), FMLPaths.CONFIGDIR.get());
        if (FMLEnvironment.dist.isClient()) {
            ModSyncClient.init();
        } else {
            ServerSync.init();
            MinecraftForge.EVENT_BUS.addListener(ModSync::onServerStarted);
            MinecraftForge.EVENT_BUS.addListener(ModSync::onServerStopping);
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ServerSync.onStarted(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        ServerSync.onStopping();
    }
}
