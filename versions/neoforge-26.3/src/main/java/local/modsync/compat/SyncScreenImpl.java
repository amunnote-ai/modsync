package local.modsync.compat;

import local.modsync.client.Canvas;
import local.modsync.client.SyncScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/** Rendering hook for 26.3, where screens fill a GuiGraphicsExtractor in extractRenderState instead of render(GuiGraphics). */
public final class SyncScreenImpl extends SyncScreen {
    public SyncScreenImpl(Screen parent, ServerAddress address, Runnable reconnect) {
        super(parent, address, reconnect);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // The background is added by extractRenderStateWithTooltipAndSubtitles; this adds the widgets, then our text
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        drawContents(new Canvas() {
            public void centered(Component text, int x, int y, int color) { g.centeredText(font, text, x, y, color); }
            public void centered(String text, int x, int y, int color) { g.centeredText(font, text, x, y, color); }
            public void text(String text, int x, int y, int color) { g.text(font, text, x, y, color); }
            public void fill(int x1, int y1, int x2, int y2, int color) { g.fill(x1, y1, x2, y2, color); }
            public void scissor(int x1, int y1, int x2, int y2) { g.enableScissor(x1, y1, x2, y2); }
            public void endScissor() { g.disableScissor(); }
        });
    }
}
