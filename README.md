# RPGManager（Android）

在安卓上运行 RPG Maker 游戏。默认使用内嵌 Node.js 运行时，设置中可切换纯 WebView 模式。公共逻辑来自 [RPGManager-Core](https://github.com/DearXuan7392/RPGManager-Core) 子模块；Kotlin 侧保留 WebView 模式所需实现。

## 功能

- SAF 选择目录并自动扫描游戏
- 内嵌 Node 模式，存档位置与桌面端一致
- 虚拟按键、作弊菜单、封面预览、资源解密、存档备份
- 纯 WebView 模式

## 获取

```bash
git clone --recurse-submodules git@github.com:DearXuan7392/RPGManager-Android.git
```

已克隆但未带子模块时：

```bash
git submodule update --init --recursive
```

## 构建

```bash
NODEMOBILE_DIR=/path/to/nodejs-mobile ./tools/build-node-runtime.sh arm64-v8a
./gradlew :app:assembleDebug
```

依赖：NDK r27（`NDK_HOME`）、JDK 21+、Android SDK 37。产物在 `app/build/outputs/apk/`。目前只打包 `arm64-v8a`。

## 权限

1. 选择文件夹：使用系统目录选择器，授权会持久化。
2. 所有文件访问权限：内嵌 Node 需要真实路径读写游戏目录。未授权时无法启动游戏。不支持无实体路径的目录。

## 设置

| 设置 | 默认 | 说明 |
| --- | --- | --- |
| 运行方式 | 内嵌 Node | 纯 WebView 使用 Kotlin 本地服务，存档与应用内数据互通，不与桌面端互通 |
| 虚拟按键 | 开 | 方向键与功能键 |
| 作弊菜单 | 开 | 游戏画面上的悬浮面板 |
| 列表封面 | 原图 | 可选模糊或不显示 |
| 列表布局 | 双列 | 可选单列 |

内嵌 Node 模式与纯 WebView 模式的存档互不相通。
