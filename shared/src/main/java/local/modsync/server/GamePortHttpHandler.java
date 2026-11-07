package local.modsync.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultFileRegion;
import local.modsync.common.Manifest;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * First handler on every game-port connection. A Minecraft client starts with a VarInt packet length
 * followed by packet id 0x00, so a connection that starts with "GET " can only be HTTP. Those are
 * answered here; anything else is handed to the vanilla pipeline untouched and this handler removes itself.
 */
public final class GamePortHttpHandler extends ChannelInboundHandlerAdapter {
    private static final byte[] GET = "GET ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEADER_END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_REQUEST = 8192;

    private ByteBuf buffer;
    private boolean http;
    private boolean answered;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf in)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (answered) {
            in.release();
            return;
        }
        if (buffer == null) buffer = ctx.alloc().buffer();
        buffer.writeBytes(in);
        in.release();

        if (!http) {
            int n = Math.min(buffer.readableBytes(), GET.length);
            for (int i = 0; i < n; i++) {
                if (buffer.getByte(buffer.readerIndex() + i) != GET[i]) {
                    passThrough(ctx);
                    return;
                }
            }
            if (n < GET.length) return; // wait for more bytes
            http = true;
            // The vanilla read timeout would cut off long downloads
            if (ctx.pipeline().get("timeout") != null) ctx.pipeline().remove("timeout");
        }

        int end = indexOf(buffer, HEADER_END);
        if (end < 0) {
            if (buffer.readableBytes() > MAX_REQUEST) ctx.close();
            return;
        }
        String head = buffer.toString(buffer.readerIndex(), end - buffer.readerIndex(), StandardCharsets.US_ASCII);
        buffer.release();
        buffer = null;
        answered = true;
        respond(ctx, head);
    }

    private void passThrough(ChannelHandlerContext ctx) {
        ByteBuf b = buffer;
        buffer = null;
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(b);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (buffer != null) {
            buffer.release();
            buffer = null;
        }
    }

    private static void respond(ChannelHandlerContext ctx, String head) {
        String[] requestLine = head.split("\r\n", 2)[0].split(" ");
        String path = requestLine.length >= 2 ? requestLine[1] : "";
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        byte[] manifest = ServerSync.manifestJson();
        if (manifest == null) {
            sendStatus(ctx, "503 Service Unavailable");
        } else if (path.equals(Manifest.MANIFEST_PATH)) {
            ByteBuf out = header(ctx, "200 OK", "application/json; charset=utf-8", manifest.length);
            out.writeBytes(manifest);
            ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
        } else if (path.startsWith(Manifest.FILES_PATH)) {
            String name = URLDecoder.decode(path.substring(Manifest.FILES_PATH.length()), StandardCharsets.UTF_8);
            Path file = ServerSync.servedFile(name); // only names from the manifest are reachable
            long size;
            try {
                size = file == null ? -1 : Files.size(file);
            } catch (IOException e) {
                size = -1;
            }
            if (size < 0) {
                sendStatus(ctx, "404 Not Found");
                return;
            }
            ctx.write(header(ctx, "200 OK", "application/java-archive", size));
            ctx.writeAndFlush(new DefaultFileRegion(file.toFile(), 0, size)).addListener(ChannelFutureListener.CLOSE);
        } else {
            sendStatus(ctx, "404 Not Found");
        }
    }

    private static ByteBuf header(ChannelHandlerContext ctx, String status, String type, long length) {
        String h = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n";
        return Unpooled.copiedBuffer(h, StandardCharsets.US_ASCII);
    }

    private static void sendStatus(ChannelHandlerContext ctx, String status) {
        ctx.writeAndFlush(header(ctx, status, "text/plain", 0)).addListener(ChannelFutureListener.CLOSE);
    }

    private static int indexOf(ByteBuf buf, byte[] needle) {
        int last = buf.writerIndex() - needle.length;
        outer:
        for (int i = buf.readerIndex(); i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (buf.getByte(i + j) != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
