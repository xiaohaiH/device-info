# 设备信息 APK

供老电视通过 U 盘安装的独立诊断工具。无需 ADB、root、联网或任何运行时权限；不会替换默认桌面，也不会修改系统设置。

这是独立的 Android 工程，不依赖 `tvhome-zh` 仓库、隔离工作树或其构建配置。`src/` 为应用源码，`tests/` 为核心逻辑测试，Gradle Wrapper 和构建/验证脚本均位于本项目内。

## 使用

1. 将项目根目录交付的 `device-info.apk` 复制到 U 盘，通过电视文件管理器安装。
2. 打开“设备信息”，拍下顶部的 **Android 版本 / API 等级** 和下方型号、固件、CPU、内存等信息。
3. 方向键在按钮和报告区间移动；报告区可上下滚动。支持触屏操作。
4. “打开 Android 原生设置”只请求标准系统设置组件。“刷新信息”重新读取设备信息与组件状态。

不要因为看到 YunOS 2.1.0 就把它当作 Android 2.1；实际 SDK_INT/API 等级单独显示。本工具保留对 `tvhome-zh` 项目中 `tvhome-zh-debug.apk` 的 API 21 门槛提示，达到门槛不代表一定可安装/运行。

## 原生设置的边界

只接受系统包 `com.android.settings`，按顺序检查：

- `com.android.settings.Settings`
- `com.android.settings.homepage.SettingsHomepageActivity`
- `com.android.settings.Settings$SettingsDashboardActivity`

要求所属包为系统/更新系统应用、组件启用且导出、调用者持有所需权限；别名若指向名单之外的组件也拒绝打开。使用显式 `ComponentName`，不使用通用 `ACTION_SETTINGS` 或厂商 TVSettings 兜底。错误会显示在报告和提示框内，不申请提权、不强制启用组件。

**包名和类名无法证明系统界面未被修改。** 如果厂商改写了标准类的实现，界面仍由固件决定；如果已经删除原生设置，普通 APK 无法恢复。本应用不是自制设置界面，也不打包或安装 AOSP Settings。

## 系统与签名

- 最低：Android 4.1 / API 16。
- `compileSdk 36`、`targetSdk 35`：较新的目标版本不会自动提高最低版本。
- 同一 APK 同时有 **v1 + v2 签名**。API 16–23 使用 v1；较新系统使用 v2，也满足 targetSdk >= 30 应用的现代签名要求。
- 无 AndroidX、第三方运行时依赖或 native `.so`，不限定 ARM/x86/MIPS ABI。
- 非 debuggable 的 release 构建，使用构建机器的默认 debug keystore 签名，仅供本地诊断/侧载。另一台机器的密钥通常不同，重新构建可能不能覆盖安装；不要发布为正式商店版本。
- 不采集设备序列号、MAC、账号或位置。信息只显示在屏幕，不上传、不保存文件。

厂商安装白名单、移除标准 API、复制损坏等问题不由签名方案解决；不能承诺任意 YunOS 固件都可安装。

## Windows 构建

需要 JDK 17+、Android SDK `platforms;android-36` 和 `build-tools;36.0.0`。脚本优先使用 `JAVA_HOME` / `ANDROID_HOME`，否则尝试 Android Studio 的 JBR 和 `%LOCALAPPDATA%\Android\Sdk`。Gradle 8.14.3 / AGP 8.12.0。

在本项目根目录执行：

```powershell
cd C:\Users\seven\AppData\Local\project\device-info
.\test.ps1
.\build.ps1 -Offline  # 依赖已缓存时；首次下载依赖则省略 -Offline
```

输出：`dist\device-info.apk`。根目录 `device-info.apk` 是本次交付副本；后续重新构建时，请以 `dist\device-info.apk` 为准。构建脚本执行测试、release 构建、lint、manifest/权限检查和签名验证，**没有设备安装命令**。

macOS / Linux 可设置 JDK/SDK 后运行 `./gradlew assembleRelease lintRelease`；核心测试亦可用 javac 编译 `DeviceReport.java`、`SettingsNavigator.java` 和 `tests/.../CoreTests.java` 后直接运行。图标 PNG 已纳入源码，通常不需要执行 `tools/generate-artwork.ps1`。

单独检查产物：

```powershell
.\verify-apk.ps1 -Apk .\dist\device-info.apk
```

该脚本对 API 16、17、19、21、23、24、28、30、34、35、36 分别验证签名；**这不是在这些 Android 版本上进行了安装或 UI 测试**。

## 验证范围与真机检查

本机：33 项纯 JVM 策略/报告测试、release 编译、Android lint（无错误）、aapt 包声明检查、apksigner 新旧 API 验证、zipalign 校验。lint 的兼容提示（target 35 非最新 36、旧系统忽略 banner、无 AndroidX 注解、图标/本地化等）不等同于运行失败；未关闭 NewApi 检查。构建工具有 SDK 元数据/Java 8 弃用提示。apksigner 对旧系统检查会提示两项 Gradle `META-INF` 构建元数据不受 v1 保护；代码/资源签名通过，v2 保护整个 APK。

纯 JVM 测试覆盖导航策略收到的状态，不模拟 Android PackageManager、Activity 启动异常或系统启用状态的真实行为；这些适配层分支仍需设备测试。独立只读代码审查未发现关键/重要阻塞项，并补充了成功打开后保留先前失败信息的回归测试。

尚无目标电视或模拟器测试，且没有安装到电脑连接的个人手机。请在电视上检查：

- U 盘安装后能打开，顶部 API 等级可读。
- 遥控器能选中两个按钮，能进入报告区上下滚动，返回键正常退出。
- 设置存在时打开系统提供的标准设置；不存在、禁用或受保护时显示原因，不转到厂商简化设置。
- 在较新 Android 设备上，按钮和内容不被系统状态栏/导航栏遮住。
