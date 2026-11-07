package local.modsync.client;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** config/modsync-client.properties */
public final class ClientConfig {
    private static final String DEFAULTS = """
            # ModSync 客户端配置 / client config
            # 进服前是否检查 mod
            enabled=true
            # 同步端口：0 = 和游戏端口相同（默认，适合大多数服务器）
            port=0
            # 连接同步服务的超时（毫秒）。连不上时视为该服务器没装 ModSync，直接进服
            connectTimeoutMs=3000
            # 连不上同步服务时是否弹窗询问（false = 直接进服）
            promptWhenUnavailable=false
            # 为某个服务器单独指定同步地址（键为 url.<地址> 或 url.<地址>_<端口>），例如：
            # url.play.example.com=http://play.example.com:8080
            # url.127.0.0.1_25565=http://127.0.0.1:25566
            """;

    public boolean enabled = true;
    public int port = 0;
    public int connectTimeoutMs = 3000;
    public boolean promptWhenUnavailable = false;
    private Properties props = new Properties();

    public static ClientConfig load(Path file) {
        ClientConfig c = new ClientConfig();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULTS, StandardCharsets.UTF_8);
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                c.props.load(r);
            }
            c.enabled = Boolean.parseBoolean(c.props.getProperty("enabled", "true").strip());
            c.port = Integer.parseInt(c.props.getProperty("port", "0").strip());
            c.connectTimeoutMs = Integer.parseInt(c.props.getProperty("connectTimeoutMs", "3000").strip());
            c.promptWhenUnavailable = Boolean.parseBoolean(c.props.getProperty("promptWhenUnavailable", "false").strip());
        } catch (IOException | NumberFormatException e) {
            local.modsync.common.Platform.LOGGER.error("[ModSync] Bad client config, using defaults", e);
        }
        return c;
    }

    /** Base URL configured for this server (as typed in the server list), or null. No trailing slash. */
    public String overrideUrl(String host, int gamePort) {
        String override = props.getProperty("url." + host + "_" + gamePort);
        if (override == null) override = props.getProperty("url." + host);
        if (override == null || override.isBlank()) return null;
        override = override.strip();
        return override.endsWith("/") ? override.substring(0, override.length() - 1) : override;
    }

    /** Default base URL for a resolved server address: the game port unless {@code port} is set. */
    public String defaultUrl(String ip, int gamePort) {
        String h = ip.contains(":") && !ip.startsWith("[") ? "[" + ip + "]" : ip; // IPv6 literal
        return "http://" + h + ":" + (port > 0 ? port : gamePort);
    }
}
