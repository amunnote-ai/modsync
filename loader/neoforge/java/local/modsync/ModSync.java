package local.modsync;

import local.modsync.common.Platform;
import local.modsync.server.ServerSync;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** NeoForge entry point (Minecraft 1.21.4 and later). */
@Mod(ModSync.MODID)
public final class ModSync {
    public static final String MODID = "modsync";

    public ModSync(IEventBus modBus, Dist dist) {
        Platform.init(FMLPaths.GAMEDIR.get(), FMLPaths.MODSDIR.get(), FMLPaths.CONFIGDIR.get());
        if (dist.isClient()) {
            ModSyncClient.init();
        } else {
            ServerSync.init();
            NeoForge.EVENT_BUS.addListener(ModSync::onServerStarted);
            NeoForge.EVENT_BUS.addListener(ModSync::onServerStopping);
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ServerSync.onStarted(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        ServerSync.onStopping();
    }
}
