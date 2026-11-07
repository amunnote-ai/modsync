package local.modsync.mixin;

import local.modsync.client.ClientSide;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the mod check before the Forge handshake, which would otherwise just disconnect on a mod mismatch. */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void modsync$beforeConnect(Screen parent, Minecraft mc, ServerAddress address, ServerData data, boolean quickPlay, CallbackInfo ci) {
        if (ClientSide.interceptConnect(parent, address, () -> ConnectScreen.startConnecting(parent, mc, address, data, quickPlay))) ci.cancel();
    }
}
