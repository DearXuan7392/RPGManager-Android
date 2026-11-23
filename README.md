# RPGManager（Android）

把桌面的 NW.js 启动器（[RPGManager-Desktop](https://github.com/DearXuan7392/RPGManager-Desktop)）复刻到安卓：选一个文件夹 → 自动列出里面的
RPG Maker 游戏 → 点开就能玩。公共逻辑来自 [RPGManager-Core](https://github.com/DearXuan7392/RPGManager-Core) 子模块。

**关键点：游戏由应用内嵌的 Node.js 运行时加载，不是纯 WebView。**

桌面上游戏跑在 `nw.exe` 里，页面里天然有 `require` / `process` / `nw`；纯 WebView 没有这些，
引擎会退化成「网页版」（存档进 localStorage、资源走另一套解密路径、部分反调试逻辑会错判）。
所以这里把 [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile) 的 `libnode.so`
打进 APK，让页面里的 `require('fs')` 真的落到 Node 上。

## 架构

```
┌──────────────────────── Android 应用进程 ────────────────────────┐
│                                                                  │
│  Compose 菜单 (MainActivity)      WebView (GameActivity)          │
│         │  SAF 选目录 + 真实路径          │  http://127.0.0.1:18666 │
│         └────────────┬───────────────────┘                       │
│                      ▼                                           │
│  ┌──────── 内嵌 Node 运行时（libnode 18.20.4）────────┐           │
│  │ assets/nodehost/main.js                          │           │
│  │  ├ server.js   HTTP 服务：/g/<游戏>/** 资源下发   │           │
│  │  │             按需解密 rpgmvp/rpgmvo、Range      │           │
│  │  │             HTML 注入 shim.js + cheat.js       │           │
│  │  ├ core/       RPGManager-Core：解密/MIME/注入规则│           │
│  │  ├ crypto.js   → core 兼容 shim                   │           │
│  │  └ /__rpg/fs   页面 require('fs') 的读写通道       │           │
│  └──────────────────────────────────────────────────┘           │
│                      │ Node fs 直接读写                          │
│                      ▼                                           │
│           /storage/emulated/0/<用户选的文件夹>/<游戏>/            │
└──────────────────────────────────────────────────────────────────┘
```

- **菜单**（Compose）用 SAF 选文件夹（持久化授权），做列表、封面、解密、存档管理。
- **游戏页** 是 WebView，但页面里的 Node 环境由 `page/shim.js` 提供：
  `require('fs')` 是同步 XHR → `/__rpg/fs` → Node 真正读写磁盘。
  于是 `Utils.isNwjs() === true`，引擎走单机分支，**存档直接写进 `<游戏目录>/save/fileN.rpgsave`**，
  和桌面端位置、格式完全一致，可以直接互相拷贝。
- 资源下发仍在服务端做：MV 的加密标记被锁成 false，请求的是明文名，Node 解密后再给浏览器；
  音频做 `.ogg ↔ .m4a` 后缀补偿（手机端的 `audioFileExt` 会被改成实际能播的格式）。

## 公共核心（RPGManager-Core）

仓库根目录的 `core/` 是 [RPGManager-Core](https://github.com/DearXuan7392/RPGManager-Core) 的 git submodule。
`app/build.gradle.kts` 中的 `copySharedCore` 任务会在构建时把 `core/index.js` 与 `core/package.json`
复制到 `build/generated/core-assets/nodehost/core/`，内嵌 Node 的 `server.js` 通过 `require('./core')`
调用解密、MIME、后缀补偿与 HTML 注入规则。原来的 `crypto.js` / `mime.js` 只保留为兼容 shim。

```bash
git clone --recurse-submodules git@github.com:DearXuan7392/RPGManager-Android.git
# 已克隆但没带子模块时
git submodule update --init --recursive
```

### WebView 模式兼容性

设置里的「纯 WebView」模式不启动内嵌 Node，因此无法调用 Core。为了保持这条兜底路径可用，
Kotlin 侧有意保留 `RpgCrypto.kt`、`DecryptTasks.kt`、`CoverLoader.kt`、`SafRepo.scanGames`
以及 `GameServer.kt` 中的同类实现；它们不通过 RPC 依赖 Core。Core 是内嵌 Node 模式与桌面端的单一规则来源。

### 为什么要给 fs 加缓存

每次同步 `fs` 调用在这个架构里都是一次同步 XHR，开销是毫秒级；而 MV 打开一次读档界面
会给每个存档位算两遍 `loadGlobalInfo()`（1 次读 `global.rpgsave` + 20 次 `exists`），
20 个位置接近 900 次调用 —— 直接变成「点继续要等好几秒」。

所以 `page/shim.js` 里做了两层缓存：目录条目（一次 `list` RPC 覆盖整个目录的
`exists/stat/readdir`）和文件内容（重复读同一个存档不再走 HTTP），写操作成功后按路径精确失效，
另有 1 秒 TTL 兜住外部改动。同一屏的 900 次调用因此收敛成 2 次 RPC。
服务端配合做了 ETag/304、静态资源 1 小时 HTTP 缓存、解密结果 LRU，并把大文件读取改成异步，
避免同步读盘阻塞 Node 事件循环（那会连带卡住页面里的同步 fs 调用）。

## 目录

| 路径 | 说明 |
| --- | --- |
| `core/` | git submodule：RPGManager-Core，公共纯逻辑 |
| `app/build.gradle.kts` | `copySharedCore`：把 Core 复制进 assets |
| `app/src/main/assets/nodehost/` | Node 端脚本（服务、Core shim、注入载荷） |
| `app/src/main/assets/nodehost/page/shim.js` | 页面里的 NW.js / Node 模拟层 |
| `app/src/main/assets/nodehost/page/cheat.js` | 作弊面板 + 虚拟按键入口 |
| `app/src/main/java/.../node/NodeRuntime.kt` | 解压脚本、挑端口、起线程、等就绪、下发根目录 |
| `app/src/main/java/.../node/NodeLauncher.kt` | JNI 声明（`node::Start`） |
| `app/src/main/cpp/node-jni.cpp` | JNI 实现 |
| `app/src/main/jniLibs/arm64-v8a/` | `libnode.so`、`libnodejni.so`、`libc++_shared.so` |
| `app/src/main/java/.../core/` | SAF 访问、真实路径换算、解密、封面 |
| `app/src/main/java/.../ui/MenuScreen.kt` | 菜单 UI |
| `app/src/main/java/.../GameActivity.kt` | 游戏页 + 虚拟按键 + 菜单 |
| `design/app-icon.png` | 图标母图（1024×1024），改图标从这里重新切图 |

图标是自适应图标：`mipmap-anydpi/ic_launcher.xml` 引用 `@mipmap/ic_launcher_foreground`（整图铺满 108dp）
与 `@drawable/ic_launcher_background`（深紫径向渐变，前景铺满时基本看不到）。
各密度 `mipmap-*/ic_launcher_foreground.webp` 由母图缩放到 108/162/216/324/432px 生成，
`ic_launcher.webp`、`ic_launcher_round.webp` 是给旧启动器兜底的正方形与圆形版本。

## 设置

主界面右上角 `⚙` 打开，改动即时保存：

| 设置 | 默认 | 说明 |
| --- | --- | --- |
| 运行方式 | 内嵌 Node | `纯 WebView` 走 Kotlin 回环服务、不注入 Node 环境：存档进 IndexedDB、与桌面端不互通，用于只认浏览器环境的游戏兜底 |
| 虚拟按键 | 开 | 方向键 + 右下角弧形键簇（Enter 为确定，Space、Shift、Ctrl、Esc 由下至上环绕；Esc 兼作取消与菜单） |
| 作弊菜单 | 开 | 游戏画面上的悬浮球；关掉后网页仍会加载 `cheat.js`（虚拟按键依赖它），只是不挂面板 |
| 列表封面 | 原图 | 可选 `模糊`、`不显示`（直接把游戏名显示在卡片里） |
| 列表布局 | 双列 | `单列` 时不加载封面，只显示文字 |

运行方式与游戏内开关在下次进入游戏时生效；列表外观立即生效。
两种运行方式的存档互不相通（Node 模式写游戏目录 `save/`，WebView 模式写应用内 IndexedDB）。

## 权限

1. **选文件夹**：系统目录选择器（SAF），授权会持久化。
2. **所有文件访问权限**（`MANAGE_EXTERNAL_STORAGE`）：内嵌 Node 用真实路径读游戏目录，
   Android 11+ 对非媒体文件必须有这个权限。没有它只能看到列表、点不动游戏，
   菜单顶部会显示提示条，点一下跳到系统设置里开。

不支持没有实体路径的目录（网盘类 provider）。

## 构建

```bash
# 0. 初始化 core 子模块（若克隆时未带 --recurse-submodules）
git submodule update --init --recursive

# 1. 准备 native 运行时（只需一次；换 Node 版本时重跑）
#    下载 nodejs-mobile-vXX-android.zip 并解压
NODEMOBILE_DIR=/path/to/nodejs-mobile ./tools/build-node-runtime.sh arm64-v8a

# 2. 构建
./gradlew :app:assembleDebug        # 或 :app:assembleRelease
```

依赖：NDK r27（`NDK_HOME`）、JDK 21+、Android SDK 37。产物在 `app/build/outputs/apk/`。

> 只打包了 `arm64-v8a`。要出 32 位包需要 `NODEMOBILE_DIR` 里有 `armeabi-v7a/libnode.so`，
> 再把 `abiFilters` 加上去。

## 排查

游戏页右上角 `≡` → **查看 Node 运行日志**，里面是 Node 端的启动参数、监听端口、
资源 404、解密失败等信息。也可以直接取：

```bash
adb shell run-as com.dearxuan.rpgmanager cat files/node-runtime.log
```

fs 缓存效果可以在游戏页的控制台里看（`chrome://inspect` 连上后）：

```js
__rpgFsStats()      // { rpcCalls, cacheHits, cachedDirs, cachedFiles }
__rpgFsClearCache() // 手动清空缓存
```
