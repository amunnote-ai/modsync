package local.modsync.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** config/modsync-server.properties */
public final class ServerConfig {
    private static final String DEFAULTS = """
            # ModSync 服务端配置 / server config
            # 是否开启同步服务
            enabled=true
            # 0 = 直接用游戏端口传 mod（推荐，不用额外开端口）
            # 填其他数字 = 单独开一个 HTTP 端口（例如服务器前面有 BungeeCord/Velocity 代理时），客户端要配置 url.
            port=0
            # 单独端口时的监听地址，留空表示所有网卡
            bindAddress=
            # 不发给客户端的 jar（文件名通配，逗号分隔），例如 spark-*.jar,ledger*.jar
            exclude=
            # 自动跳过声明了 displayTest=IGNORE_SERVER_VERSION 的纯服务端 mod
            autoExcludeServerOnly=true
            # 额外只给客户端的 mod 放这个目录（相对服务器根目录），例如小地图、JEI 之类服务器不装的
            clientOnlyDir=modsync/client-mods
            # 要求客户端删除的 mod id（逗号分隔），例如 xray,freecam
            removeModIds=
            """;

    public boolean enabled = true;
    public int port = 0;
    public String bindAddress = "";
    public List<String> exclude = List.of();
    public boolean autoExcludeServerOnly = true;
    public String clientOnlyDir = "modsync/client-mods";
    public List<String> removeModIds = List.of();

    public static ServerConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, DEFAULTS, StandardCharsets.UTF_8);
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        ServerConfig c = new ServerConfig();
        c.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").strip());
        c.port = Integer.parseInt(p.getProperty("port", "0").strip());
        c.bindAddress = p.getProperty("bindAddress", "").strip();
        c.exclude = list(p.getProperty("exclude", ""));
        c.autoExcludeServerOnly = Boolean.parseBoolean(p.getProperty("autoExcludeServerOnly", "true").strip());
        c.clientOnlyDir = p.getProperty("clientOnlyDir", "modsync/client-mods").strip();
        c.removeModIds = list(p.getProperty("removeModIds", ""));
        return c;
    }

    private static List<String> list(String s) {
        return Arrays.stream(s.split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
    }
}
