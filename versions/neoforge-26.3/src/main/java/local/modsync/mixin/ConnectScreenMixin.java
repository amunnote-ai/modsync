package local.modsync.mixin;

import local.modsync.client.ClientSide;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the mod check before connecting, so missing or outdated mods are fixed before the server sees them. */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void modsync$beforeConnect(Screen parent, Minecraft mc, ServerAddress address, ServerData data, boolean quickPlay,
                                              TransferState transfer, CallbackInfo ci) {
        // A server transfer (/transfer) is a reconnect the player did not start; leave it alone
        if (transfer != null) return;
        if (ClientSide.interceptConnect(parent, address, () -> ConnectScreen.startConnecting(parent, mc, address, data, quickPlay, null))) ci.cancel();
    }
}
