# DiPlay 移植到 Android 4.2.2（API 17）最小可用方案

## Context（背景与目标）

当前 DiPlay 工程是面向 Android 7.0+（各模块 minSdk 25/28/30）的现代工程：

- `shared`：CarPlay 协议核心（`com.shilapi.xcertplay.{transport,iap2,airplay,mfi,media,network}` 等 261 个 kt 文件）+ 纯 Java Opus（`org.concentus`）+ 原生 speexdsp + AIDL。基本只依赖 Android 框架与 bouncycastle/jmdns。
- `common`：全部 Compose UI + AndroidX + media3 + WorkManager（`DiPlayActivity` 6174 行、`CarPlayHostActivity` 6332 行）。
- `mobile`/`automotive`：仅 manifest 的壳。

目标是产出能在 **Android 4.2.2（API 17）/ armeabi-v7a** 上安装并启动的最小可用 APK（USB 有线 CarPlay + 视频 + 基础音频），后续再迭代联调。

参考 APK `demo/carplay1.apk`（minSdk 17 / targetSdk 18，包名 `local.airuize.diplaylegacy`）已被验证跑在同类型 4.2.2 车机上。其反编译源码在 `/tmp/diplay_jadx/sources`，其中 `local/airuize/receiver/LegacyUsbConfiguration.java`、`LegacyUsbDescriptorsKt.java` 含有该车机的 iPhone USB VID/PID 与 CH341 MFi 协处理器配置，作为设备配置来源。

## 核心约束

**Jetpack Compose 运行时最低要求 API 21**，Android 4.2.2（API 17）无法运行 Compose。因此"直接降 minSdk"的最终形态必然是：**保留 `shared` 运行时核心，丢弃 Compose 的 `common` 层，另建一个用原生 View（SurfaceView）的最小模块**。这是本次计划的根本前提。

## 总体思路（分为 4 个阶段，前 3 个阶段即可交付"可安装 APK"）

### 阶段 A：Gradle 底座改造（产出可编译的 minSdk17 工程）
1. `gradle/libs.versions.toml`：新增 `coreLibraryDesugaring = "2.1.5"` 对应 `com.android.tools:desugar_jdk_libs`（参考 APK 里 `$$ExternalSyntheticBackport0` 证明其用了 desugaring，bouncycastle `bcprov-jdk18on` 与 Kotlin 的 `java.util.function` 依赖它才能在 API 17 上运行）。
2. `settings.gradle.kts`：注册新模块 `legacy`，`project(":legacy")`（不再 `include` 时沿用默认路径）。

### 阶段 B：`shared` 降到 API 17
文件：`shared/build.gradle`（Groovy）。
1. `defaultConfig.minSdk` 25 → 17，`targetSdk` 37 → 18（匹配 4.2.2）。
2. `ndk.abiFilters` 收敛为 `'armeabi-v7a'`；`externalNativeBuild.ndkBuild.arguments` 的 `APP_PLATFORM=android-25` → `android-17`。
3. 移除 `implementation libs.androidx.app`（`androidx.car.app:app:1.4.0`，要求 minSdk 23）。它只被 `shared/src/main/java/com/shilapi/xcertplay/shared/` 下的 `MyCarAppService.kt` / `MyCarAppSession.kt` / `MyCarAppScreen.kt` 使用（Car App Library 投影层，车机镜像不需要）。**将这三个文件连同该 `shared` 子包一起删除或移出主源集**。
4. 打开 `compileOptions` 的 `isCoreLibraryDesugaringEnabled = true`，并 `dependencies { coreLibraryDesugaring(...) }`。
5. 无线/telecom 等 API26+ 的文件（`network/LocalOnlyHotspotManager.kt`、`network/WifiP2pGroupManager.kt`、`network/ManualHotspotInterfaces.kt`、`telecom/TelecomPhoneAudioBackend.kt`、`telecom/VoipCallWatcher.kt` 等）已用 `androidx.annotation.RequiresApi` 守卫。做法：补一个 `androidx.annotation:annotation` 依赖（`implementation`，minSdk 1，纯编译注解），或替换 `import androidx.annotation.RequiresApi` 为 `android.annotation.RequiresApi`。最小改动优先后者；如文件分散较多则前者更快。
6. 原生 `speex_echo`（`media/SpeexEchoCanceller.kt`）：保留代码，但为缺库做优雅降级（`System.loadLibrary` 失败时静默禁用回声消除，不崩溃）——需确认其 `init` 侧已捕获 `UnsatisfiedLinkError`；未捕获则补 try/catch。

### 阶段 C：新建 `legacy` 模块（最小可启动壳）
1. `legacy/build.gradle.kts`：
   - 插件 `com.android.application`（不含 Compose/Kotlin 可先不启用，若用 Kotlin 则加 kotlin 插件）。
   - `namespace = "local.airuize.diplaylegacy"`，`applicationId` 同名或保留自有包名。
   - `minSdk = 17`、`targetSdk = 18`、`compileSdk = release(37)`，`ndk.abiFilters = ['armeabi-v7a']`。
   - 依赖仅 `implementation(project(":shared"))`。
2. `legacy/src/main/AndroidManifest.xml`：launcher `Activity` + `android.hardware.usb.action.USB_DEVICE_ATTACHED` intent-filter + `res/xml/usb_device_filter.xml`（VID/PID 来自阶段 A 提取的设备配置）。
3. 最小 Activity（原生 View）：`LegacyCarPlayActivity`（`extends android.app.Activity`）：
   - 用 `SurfaceView` 作为 CarPlay 视频输出面。
   - `onCreate` 里请求 USB 权限、注册 attach 广播接收器，展示极简状态文字（连接中/就绪）。
   - 首版**先不接完整 `CarPlayController`**，只保证 Activity 能启动、SurfaceView 正常创建并拿到 `Surface`，作为"可安装、可启动"验收点。
4. 资源：极简 `res/values/strings.xml`、theme（`android:Theme.NoTitleBar.Fullscreen` 等 API 17 可用主题）、应用图标（复用参考 APK 的 `ic_carplay.png` 或任意 png）。

### 阶段 D（后续迭代，不在本次"可安装"验收内）
1. 接入 `CarPlayController` 完整接线：参考 `common` 里 `DiPlaySessionService.kt`（67 行，前景服务）+ `DiPlayBootstrap.kt`（离线 MFI 认证资产装载 `LocalMfiAuthenticationClient`）+ `CarPlayHostActivity` 中 `CarPlayController`/`AndroidMediaSink` 的构造与 surface 绑定逻辑，抽取为非 Compose 的最小实现放入 `legacy`。
2. 用参考 APK 反编译出的 `LegacyUsbConfiguration`/`LegacyUsbDescriptorsKt` 填充 `CarPlayRuntimeConfig`（iPhone `iphoneDevices`、`ch341Devices`、`mfiTarget`、`identification: Iap2IdentificationConfig`）。
3. MFi 认证资产：将 `identity.pk8` / `certificate.p7b` 放入 `legacy/src/main/assets/offline-mfi/`（与 `DiPlayBootstrap` 读取路径一致）。

## 关键复用点（实现时优先复用，勿重造）
- 视频解码+渲染、音频播放/Opus 上行：`shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`（实现 `airplay/AirPlaySession.kt` 中的 `AirPlayMediaHandler`，`onScreen`/`onAudio`/`onBufferedAudio`）。
- USB→iAP2→AirPlay 编排：`shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`（integration seam）。
- 视频 Surface 交接：`shared/src/main/java/com/shilapi/xcertplay/media/VideoSurfaceHandoff.kt`、`common/.../CarPlayVideoSurface.kt`。
- 触摸映射：`media/CarPlayTouchMapper.kt`（`MotionEvent` → CarPlay HID）。
- 离线 MFI 认证：`shared` 的 `mfi/LocalMfiAuthenticationClient` + `common/DiPlayBootstrap.kt`。

## 验证
1. 编译：`./gradlew :legacy:assembleDebug`（阶段 A–C 完成）产出 APK。
2. 确认产物：`aapt2 dump badging legacy/build/outputs/apk/debug/*.apk` 中 `minSdkVersion:'17'` 且 `native-code:` 仅含 `armeabi-v7a`。
3. 安装启动冒烟：安装到 Android 4.2.2 设备或 API 17 AVD，确认 Activity 能启动、SurfaceView 正常创建、无 `NoClassDefFoundError`（尤其避免 Compose/AndroidX 类泄漏进 dex）。可用 `d8`/dexdump 或 `unzip -l` 确认 dex 里不含 `androidx.compose` / `androidx.car.app`。
4. 回归（保持原工程不被破坏）：`./gradlew :shared:testDebugUnitTest` 验证核心测试仍通过。

## 备注 / 风险
- bouncycastle `bcprov-jdk18on` 依赖 desugaring 才兼容 API 17；若遇运行期异常，备选降级为 `bcprov-jdk15on`。
- `CarPlayController` 构造依赖较多（蓝牙、无线、hud `BydNavigationOutputs` 等）。阶段 C 先不接它，规避此处耦合；阶段 D 再逐步摘出有线最小路径。
- AGP 9.3 / Kotlin 2.2.10 下 targetSdk 18 若触发工具的 lint 门槛，可保留 targetSdk 23–25 而仅改 minSdk（安装兼容性由 minSdk 决定）。