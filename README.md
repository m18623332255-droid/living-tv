# 客厅桌面 · Living TV

基于 CleanYourTV 的 Android TV 桌面和点播播放器。中文界面支持遥控器方向键、玻璃控制中心、原厂设置入口、大时钟、天气、静态和动态壁纸。

播放器分为直播与点播，支持海报墙、分类与地区筛选、选集、续播、收藏和局域网扫码搜索。可在「客厅影院 → 设置」导入 M3U、媒体直链、苹果 CMS 或 TVBox JSON。TVBox 配置仅读取支持的 JSON 数据类型。

公开源码版的片源列表为空，壁纸图库有两个内置背景。可从本地文件或通过局域网扫码导入自己的壁纸。默认桌面由 Android 系统及电视固件决定。

## Android 构建

需要 JDK 17、Android SDK Platform 35 和 Platform Tools。最低支持 Android 8.0（API 26）。Gradle Wrapper 会下载 Gradle 8.11.1，首次构建还需要访问 Google Maven 和 Maven Central。

```sh
cd launcher
./gradlew :app:assembleDebug :app:testDebugUnitTest :features:testDebugUnitTest :app:lintDebug
```

构建产物位于 `launcher/app/build/outputs/apk/debug/app-debug.apk`。调试包使用构建机的调试签名。已安装同包名的发行版时，覆盖安装需要对应的原签名。可在模拟器或测试电视上验收调试包。

发布构建可用自己的密钥。在环境中设置 `LIVINGTV_KEYSTORE`、`LIVINGTV_STORE_PASSWORD`，并按需设置 `LIVINGTV_KEY_ALIAS`、`LIVINGTV_KEY_PASSWORD`，然后在 `launcher/` 执行 `./gradlew :app:assembleRelease`。未配置时输出未签名的发行包。

## Mac 工具

在 Apple Silicon Mac 上安装 Xcode Command Line Tools、Python 3 和 Android SDK Platform Tools，从仓库根目录运行：

```sh
./scripts/build-mac.sh
./scripts/build-remote.sh
python3 -m unittest discover -s tests -p 'test_manager.py'
```

生成的「客厅管家」和「客厅遥控器」位于 `delivery/`。遥控器默认操作唯一连接的模拟器。ADB 安装在其他位置时，可在启动遥控器的进程环境里指定 `LIVINGTV_ADB`（ADB 可执行文件路径）和 `LIVINGTV_ADB_SERIAL`（目标序列号）。

模拟器需要 Mac 局域网中继来接收扫码输入或壁纸。通过 `adb devices` 找到模拟器序列号后运行 `python3 mac/wallpaper_bridge.py "$(command -v adb)" <模拟器序列号>`。实机和手机在同一局域网内可直接扫码。管家的组件规则格式见 [品牌规则](docs/规则格式.md)，公开版规则库为空。

## 播放与壁纸测试

安装 FFmpeg，从仓库根目录运行 `./scripts/generate-test-media.sh`，再启动 `python3 tests/fixture_server.py`。在另一个终端执行 `cd launcher && ./gradlew :app:connectedDebugAndroidTest`。测试使用模拟器网络地址，操作前请使用测试专用模拟器。

## 网络功能

自动天气通过 IPwhois 识别网络出口城市，并向 Open-Meteo 获取预报。也可手动选择城市或关闭天气。播放与跨源搜索会连接你配置的片源。扫码输入和上传壁纸使用临时局域网链接。

## 上游与许可

本项目是 [CleanYourTV](https://github.com/Gathin23/cleanyourtv) 的独立派生版，基于[上游提交 04aec4b](https://github.com/Gathin23/cleanyourtv/commit/04aec4b7c763ed642d3994d25e5b89dd57014a6d)。源码采用 [MIT](LICENSE)，随应用保留上游版权声明、字体和第三方资源的许可文件。
