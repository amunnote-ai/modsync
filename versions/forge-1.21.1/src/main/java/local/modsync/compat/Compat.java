package local.modsync.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minecraft 1.21.1 versions of the calls whose API differs between versions. */
public final class Compat {
    public static void setScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    public static void toast(Component title, Component body) {
        SystemToast.add(Minecraft.getInstance().getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, body);
    }

    /** Screen#render already draws the background since 1.20.2. */
    public static void renderBackground(Screen screen, GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }
}
