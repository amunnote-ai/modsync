package local.modsync.mixin;

import io.netty.channel.Channel;
import local.modsync.server.GamePortHttpHandler;
import local.modsync.server.ServerSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The game-port channel initializer (anonymous class in ServerConnectionListener#startTcpServerListener). */
@Mixin(targets = "net.minecraft.server.network.ServerConnectionListener$1")
public abstract class ServerConnectionListenerMixin {
    @Inject(method = "initChannel", at = @At("TAIL"), remap = false)
    private void modsync$addHttpHandler(Channel channel, CallbackInfo ci) {
        if (ServerSync.sharesGamePort()) channel.pipeline().addFirst("modsync_http", new GamePortHttpHandler());
    }
}
