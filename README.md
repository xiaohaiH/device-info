# 设备信息

面向各类 Android 设备的独立诊断工具，支持 Android 4.1 / API 16 及以上。

## 使用

将 `device-info.apk` 传到设备，通过系统安装器安装后，可：

* 查看 Android 版本、API 等级、型号、CPU、内存等信息。
* 打开系统设置或正在运行的服务页面，刷新设备信息。
* 点击首页“退出应用”关闭应用界面，不强制结束进程；进程由 Android 管理。
* 点击“安装 APK”，通过系统选择器选择本机/已挂载 U 盘中的 APK；也可使用内置简易文件浏览器。选好后点击“交给系统安装器”，由系统确认安装。
* 经二次确认，依次尝试 Root、已配置的 Device Owner（Android 7.0+）和系统 `REBOOT` 权限重启；无直接权限时，可使用无障碍菜单或受支持的厂商定时开关机设置辅助，仍需手动操作。
* 单独选择“仅重启本应用”，不会重启设备；自动重新打开可能被系统延迟或阻止。
* 打开“悬浮返回键”，用圆点返回上一层或回到系统桌面（Android 5.1+）。

支持触屏和方向键操作。屏幕方向跟随系统，布局适应窗口大小。首页“安装 APK”与“退出应用”并排；安装页顶部“返回首页”直接回主界面，目录栏右侧“上级目录”只用于文件浏览。

普通安装不会自动取得 Root 或 Device Owner。Root 管理器可能要求授权；无障碍需你在系统设置手动启用，返回后重新确认才操作。Android 5.0+ 可打开电源菜单，旧系统仅展开通知栏；是否有重启项取决于固件。

旧系统浏览文件可能需要读取存储授权；Android 8.0 之前浏览超大目录仍可能耗尽内存，使用兜底浏览器前会提示风险。Android 10+ 直接浏览受分区存储限制，请优先使用系统选择器。Android 8.0+ 若未允许安装未知应用，会提供授权设置入口，返回后仍需再次点击安装。只支持单个 APK（最多 512 MiB），不支持 APKS/XAPK，不使用 Root 或静默安装。

应用离线运行，设备信息仅在屏幕显示，不上传、不保存文件。安装会临时保存所选 APK 的只读副本，不修改原文件；旧系统使用兼容安装方式，详见技术文档。系统页面、USB 访问、安装和重启均受固件及权限限制，入口不可用时会显示原因。

## 构建

推送到 `main` 时，GitHub Actions 会自动打包，也可在 Actions 中选 `main` 手动运行 **Build APK**。验证通过后，从 **Artifacts** 下载 `device-info-apk`，解压得到 `device-info.apk`。拉取请求运行测试和 Lint。主分支打包需要配置仓库 Secrets `APK_SIGNING_KEYSTORE`（Base64 编码的项目签名文件）和 `APK_SIGNING_PASSWORD`（密码），上传前会核对签名指纹。

需要 JDK 17+、Android SDK Platform 36 和 Build Tools 36.0.0。Windows 构建脚本优先使用 `config.local.jsonc` 中的 `jdkPath` 和 `androidSdkPath`，留空时分别读取 `JAVA_HOME` 和 `ANDROID_HOME`。

在 Windows 项目根目录执行：

```powershell
.\build.bat  # 依赖已缓存时可加 -Offline
```

双击 `build.bat` 后，构建结束时按任意键关闭窗口。自动化可直接运行 `.\build.ps1` （可加 `-Offline` ）。这两个入口均运行核心测试、release 构建、lint 和 APK 验证。

Gradle 的 `assembleRelease` 完成后会自动更新 `dist/device-info.apk` 和根目录 `device-info.apk` ，存在时覆盖；直接运行 Gradle 也一样。副本可能在后续 lint 或 APK 验证失败前已更新，应以完整构建的退出状态为准。

常规构建使用项目专用签名。首次本地打包前，将 [config.jsonc](config.jsonc) 复制为 `config.local.jsonc`，填写签名文件路径和原始密码。换电脑时按[技术说明](技术说明.md#签名备份与恢复)恢复。

配置好 `config.local.jsonc` 后，macOS / Linux 设置好 JDK 和 SDK 环境变量后，可执行 `./gradlew assembleRelease lintRelease`；完整验证流程由 Windows 脚本提供。产物同步的 Gradle 回归测试可在 Windows 上运行 `.\tests\build-output.Tests.ps1` （依赖已缓存时可加 `-Offline` ），需设置 `DEVICE_INFO_SIGNING_KEYSTORE`（绝对路径）和 `DEVICE_INFO_SIGNING_PASSWORD`，测试在临时目录执行。单独验证已有 APK：

```powershell
.\verify-apk.ps1 -Apk .\dist\device-info.apk
```

构建与签名检查不等于真机验证，实际界面和系统入口需在目标设备确认。实现逻辑见 [技术文档](技术说明.md)，开发约定见 [AGENTS.md](AGENTS.md)。
