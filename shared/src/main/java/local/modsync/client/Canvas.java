package local.modsync.client;

import net.minecraft.network.chat.Component;

/** The drawing calls {@link SyncScreen} needs; each version implements them with its own graphics class. Colours are ARGB. */
public interface Canvas {
    void centered(Component text, int x, int y, int color);

    void centered(String text, int x, int y, int color);

    void text(String text, int x, int y, int color);

    void fill(int x1, int y1, int x2, int y2, int color);

    void scissor(int x1, int y1, int x2, int y2);

    void endScissor();
}
