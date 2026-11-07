# ModSync（多版本）

玩家点击加入服务器时，先和服务端的 mod 列表比对：下载缺少的 mod，移走版本不对或被禁止的 mod，退出游戏后自动替换。

## 安装

按游戏版本选 jar（都在 `dist/` 文件夹）：

| 游戏版本 | 加载器 | 文件 |
|---|---|---|
| 1.20.1 | Forge | `modsync-forge-1.20.1-1.1.0.jar` |
| 1.21.1 | Forge | `modsync-forge-1.21.1-1.1.0.jar` |
| 1.21.4 | NeoForge | `modsync-neoforge-1.21.4-1.1.0.jar` |
| 1.21.8 | NeoForge | `modsync-neoforge-1.21.8-1.1.0.jar` |
| 1.21.11 | NeoForge | `modsync-neoforge-1.21.11-1.1.0.jar` |
| 26.3 | NeoForge | `modsync-neoforge-26.3-1.1.0.jar` |

每个 jar 只能用在它对应的那个版本上。

- **服务端和客户端都要放对应版本的 jar**。客户端第一次需要手动装（"引导"），之后 ModSync 自己也会跟着服务端自动更新。

## 服务端

首次启动会生成 `config/modsync-server.properties`：

| 配置 | 默认 | 说明 |
|---|---|---|
| `port` | 0 | 0 = 直接用游戏端口传 mod，**不用额外开端口**；填其他数字则单独开 HTTP 端口（服务器前有 BungeeCord/Velocity 代理时用） |
| `exclude` | 空 | 不发给客户端的 jar，文件名通配，逗号分隔 |
| `autoExcludeServerOnly` | true | 自动跳过声明为纯服务端的 mod |
| `clientOnlyDir` | `modsync/client-mods` | 只给客户端的 mod（服务器不加载）放这里 |
| `removeModIds` | 空 | 强制客户端移除的 mod id，如 `xray,freecam` |

可以用浏览器打开 `http://服务器地址:游戏端口/modsync/manifest.json` 检查是否正常（就是玩家进服用的那个地址和端口）。

## 客户端

默认不用配置：玩家连哪个地址进游戏，就从同一个地址和端口同步。
只有服务端单独开了端口时，才需要在 `config/modsync-client.properties` 里添加
`url.服务器地址=http://服务器地址:端口`。

## 同步规则

- **缺少的 → 下载**：服务端有、本地没有（按 SHA-256 判断）的 jar
- **版本不对的 → 删除并重新下载**：与服务端 mod 同 id 但文件不同的 jar，换成服务端的版本
- **服务器禁止的 → 删除**：`removeModIds` 里的 mod
- **多出来的 → 不管**：服务端没有的 mod（客户端 mod、它们的前置、光影 mod 等）一律保留
- 光影包、资源包不会动：只处理 `mods` 文件夹里的 jar
- 移除的 mod 会移到 `mods_backup/<时间>/`，不会直接删除
- 每次检查都会写 `modsync/sync-report.txt`（客户端游戏目录），列出两边的 mod 和每一项决定，排查问题时看这个
- 每次都会列出改动让玩家确认；连不上同步服务（服务器没装 ModSync）时直接正常进服

## 工作原理

Forge / NeoForge 只在启动时加载 mod，所以替换后要重启游戏：
下载到 `modsync/staging` → 写入 `modsync/pending-update.txt` → **先在游戏内直接替换**（一般都能成功）→
如果有 jar 被占用，就什么都不改，交给独立小程序 `modsync/updater.jar` 在游戏退出后替换 → 玩家重启后右上角提示结果。
替换是"全有或全无"的：不会出现新旧两个版本同时在 mods 文件夹里的情况。日志在 `modsync/updater.log`。

## 构建

需要 JDK 17、21、25，默认放在 `E:\java17`、`E:\java21`、`E:\java25`（路径在各版本的 `gradle.properties` 里改）。

```
powershell -ExecutionPolicy Bypass -File build-all.ps1
```

全部版本构建完后 jar 会放进 `dist\`。只构建某个版本：`build-all.ps1 -Only neoforge-1.21.4`；需要走代理时加 `-Proxy 127.0.0.1:端口`。

## 代码结构

- `shared\`：所有版本共用的部分（比对、下载、校验、更新程序、HTTP 服务、同步界面）
- `loader\forge`、`loader\neoforge`：两种加载器的入口和事件注册
- `versions\<加载器>-<版本>\`：每个版本各不相同的那一小部分（进服拦截的 Mixin、提示框写法）和构建配置

加新版本：复制最接近的 `versions\` 文件夹，改 `gradle.properties` 里的版本号；编译报错的地方改 `compat\Compat.java` 和 `mixin\ConnectScreenMixin.java`，最后加进 `build-all.ps1`。
