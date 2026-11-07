package local.modsync.compat;

import local.modsync.client.Canvas;
import local.modsync.client.SyncScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/** Rendering hook for the versions that draw with GuiGraphics (1.20.1 - 1.21.11). */
public final class SyncScreenImpl extends SyncScreen {
    public SyncScreenImpl(Screen parent, ServerAddress address, Runnable reconnect) {
        super(parent, address, reconnect);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Background (by hand only where Screen#render does not draw it), then widgets, then our text on top
        Compat.renderBackground(this, g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        drawContents(new Canvas() {
            public void centered(Component text, int x, int y, int color) { g.drawCenteredString(font, text, x, y, color); }
            public void centered(String text, int x, int y, int color) { g.drawCenteredString(font, text, x, y, color); }
            public void text(String text, int x, int y, int color) { g.drawString(font, text, x, y, color); }
            public void fill(int x1, int y1, int x2, int y2, int color) { g.fill(x1, y1, x2, y2, color); }
            public void scissor(int x1, int y1, int x2, int y2) { g.enableScissor(x1, y1, x2, y2); }
            public void endScissor() { g.disableScissor(); }
        });
    }
}
