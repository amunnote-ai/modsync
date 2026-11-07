package local.modsync.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minecraft 1.20.1 versions of the calls whose API differs between versions. */
public final class Compat {
    public static void setScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    public static void toast(Component title, Component body) {
        SystemToast.add(Minecraft.getInstance().getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION, title, body);
    }

    /** 1.20.1 screens draw their own background; later versions do it in Screen#render. */
    public static void renderBackground(Screen screen, GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        screen.renderBackground(g);
    }
}
