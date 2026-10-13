# Android 4.2.2 无线 CarPlay 移植计划

## Context

DiPlay 的 `legacy` 模块（`local.airuize.diplaylegacy`）已跑通有线 WIRED CarPlay（`CarPlayTransport.WIRED` 硬编码接线）。现在要新增**无线无线 CarPlay**，目标设备仍是 Android 4.2.2（API 17）/ armeabi-v7a 车机（MY16ADA，NVIDIA Tegra）。

关键调研结论：存在一个**已在该类车机跑通无线 CarPlay 的参考 APK**（`demo/carplay1.apk`），反编译确认它就是 DiPlay 项目的 API 17 移植版（包 `local.airuize.receiver`），其无线拓扑是**车机开热点**（非 iPhone 开热点）。参考实现在 `/tmp/carplay1_decompiled/sources/local/airuize/receiver/`。

无线 CarPlay 端到端流程（参考 APK）：车机开热点 → 蓝牙 RFCOMM 连 iPhone（UUID `00000000-deca-fade-deca-deafdecacafe`）→ iAP2 识别 + MFi 认证 → 把 Wi-Fi 配置(SSID/passphrase) 发给 iPhone → iPhone 加入热点 → Wi-Fi 上 iAP2 + AirPlay 会话。

## 架构决策

**跟随参考 APK**，在 legacy 模块新增无线入口，绕过 `orchestration.CarPlayController`（其 `runWireless` 依赖 `WsDiscovery`/`CarPlayVpnService`/`LocalOnlyHotspot` 等 API 21/26 组件）。参考 APK 的两个关键替换：
- 用 **JmDNS**（`javax.jmdns`，shared 已传递依赖）替代 `NsdManager`（厂商兼容性问题）。
- 用 **裸 `ServerSocket`**（`LegacyListenerBinder`）替代 `CarPlayVpnService`（无需 VPN 隧道）。

## 复用 shared 的类（已核对存在且签名基本一致）

- `transport/BluetoothRfcommDuplexStream.kt`（`constructor(socket: BluetoothSocket, onDiagnostic)`）
- `iap2/session/Iap2Session.kt`：`openWireless(stream)`、`openTunnel(stream)`
- `transport/Iap2WirelessControlClient.kt` + `Iap2WirelessControlTerminal` + `Iap2WirelessCarPlayEndpoint` + `Iap2WirelessIdentification` + `Iap2WirelessSecurity`
- `airplay/`：`AirPlayConfig`、`AirPlayIdentity`、`AirPlaySession`、`AirPlaySessionListener`、`PairingStore`、`CarPlayMediaEngine`、`AirPlayInfoPlist`
- `mfi/`：`LocalMfiAuthenticationClient.load(directory)` + `Iap2MfiAuthenticationClient`
- `media/`：`AndroidMediaSink`、`CarPlayTouchMapper`
- `transport/BluetoothLinkCheck`、`BluetoothRadioCheck`

## 必须适配的签名分歧（shared 已演进，勿照抄参考）

- `Iap2MfiAuthenticationClient`：参考 4 参 → 当前 `(MfiAuthenticator, maximumCertificateLength=默认)` 2 参。
- `AirPlayIdentity.loadIdentity(context)` 已删 → legacy 自持久化（提取 `LegacyCarPlayHost` 现有 `loadIdentity/deviceId` 逻辑）。
- `AirPlayConfig`：位置参 → 命名参数 data class（`port=7000` 有默认值）。
- `Iap2LocationRequest` 类已删 → 改为实现 shared 的 `Iap2LocationProvider` 接口。

## 新写 legacy 类（Kotlin，放在 `legacy/src/main/java/local/airuize/diplaylegacy/`）

对标参考 APK 的对应类：

1. **`LegacyNetwork.kt`**（← `LegacyNetwork.java`）——三种网络模式，返回 `Endpoint(ssid, password, channel, iface, ipv4)`：
   - `direct`：`getSystemService("wifip2p")` → `WifiP2pManager.initialize(ctx, mainLooper, null)` → `createGroup(listener)` → `requestGroupInfo` 取 `networkName`/`passphrase`；遍历 `NetworkInterface` 找 `p2p*` 接口的 `Inet4Address`。
   - `app`：反射 `WifiManager.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType).invoke(...)` 开 SoftAP；`SSID="DiPlay-Legacy"`，`preSharedKey`=随机 16 位，`allowedKeyManagement.set(1)`（WPA_PSK）；找 `wlan*`/`ap*` 接口。
   - `router`：读 `wifi.connectionInfo.ssid` + `receiver-ui`/`routerPassword`（8-63 位校验）。
   - `close()`：`removeGroup` + 反射还原 `setWifiApEnabled(null,false)` / `setWifiApConfiguration(oldConfig)`。
   - `state()` 用反射 `WifiManager.getWifiApState()`（13=已启用）。
2. **`LegacyListenerBinder.kt`**（← `LegacyListenerBinder.java`）——`ServerSocket().apply { reuseAddress=true }.bind(InetSocketAddress(address, port))`，`BindException` 时轮询重试（默认 5s），作为 AirPlay 裸 TCP 监听。
3. **`LegacyWirelessController.kt`**（← `LegacyWirelessController.java`，核心编排）——按 §Context 流程串起：acquire WakeLock/WifiLock → `LegacyNetwork.prepare()` → `AirPlayIdentity` 持久化 → `PairingStore` → 拷贝 `offline-mfi` → `LocalMfiAuthenticationClient.load` → 构建 `AirPlayConfig`/`CarPlayMediaEngine` → `LegacyListenerBinder.bind` 监听 → MulticastLock → JmDNS 广播 `_airplay._tcp` + 发现 `_carplay-ctrl._tcp` → 蓝牙 RFCOMM connect → `BluetoothRfcommDuplexStream` → `Iap2Session.openWireless` → `Iap2WirelessControlClient.run`（识别/MFi/Wi-Fi配置/CarPlayStartSession）→ 等待 Wi-Fi 会话 → tunnel handler + `Iap2Session.openTunnel` 运行时控制。
4. **`WirelessHandoff.kt`**（← `WirelessHandoff.java`）——握手看门狗状态机。
5. **`LegacyVehicleLocationProvider.kt`**——实现 shared `Iap2LocationProvider`（按需引用 `LegacyGpsProbe`/`LegacyLocationEncoder` 的等价逻辑）。
6. **`LegacyWirelessHost.kt`**——无线入口，直接实例化 `LegacyWirelessController`，不再经过 `CarPlayController`。

## 入口与 manifest 改动

- `LegacyCarPlayActivity`（或新增分派）按 `SharedPreferences("receiver-ui")/networkMode` 分派：`saved`（=有线）→ 现有 `LegacyCarPlayHost.start()`；`direct`/`app`/`router` → `LegacyWirelessHost.start()`。MVP 阶段先硬编码 `networkMode=direct` 跑通。
- `legacy/src/main/AndroidManifest.xml` 补权限（参考 `shared/src/main/AndroidManifest.xml` L9-21）：`BLUETOOTH`、`BLUETOOTH_ADMIN`、`CHANGE_WIFI_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、`MODIFY_AUDIO_SETTINGS`（API 31+ 的 `BLUETOOTH_CONNECT`/`NEARBY_WIFI_DEVICES` 可一并声明，API 17 忽略）。

## 分阶段实施

1. **阶段一（MVP）**：`LegacyNetwork`（仅 `direct` P2P）+ `LegacyListenerBinder` + `LegacyWirelessController` 最小闭环 + JmDNS + 蓝牙 bootstrap，目标「iPhone 加入热点 + 出画面」。复用 `AndroidMediaSink`。
2. **阶段二**：加 `app`（SoftAP 反射）与 `router` 两种模式，完成 `LegacyNetwork` 三模式 `close()`/还原。
3. **阶段三**：补齐 `WirelessHandoff` 看门狗、`LegacyVehicleLocationProvider`，完善错误诊断与重连。

## 验证

- 构建：`./gradlew :legacy:assembleDebug`（minSdk 17 / armeabi-v7a），`adb install -r` 后 `am start`。
- `adb logcat` 观察点（对标参考 APK 日志）：`Network mode=direct interface=p2p* ready=true` → 蓝牙 `iAP2 已建立` → `Wi-Fi配置发送=1 CarPlay启动发送=1` → `Wi-Fi iAP2 通道已建立` → iPhone 加入热点 → SurfaceView 出画面/声音。
- 全程**禁止重启车机**；通过 WiFi ADB `192.168.31.159:5555` 操作。