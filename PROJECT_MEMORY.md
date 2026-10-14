# DiPlay 项目记忆

无线 CarPlay 移植到 Android 4.2.2 车机（MY16ADA，800×480，API 17）的项目级约束与经验教训。包名 `local.airuize.diplaylegacy`。后续开发与调试以此为准，避免重复踩坑。

## 硬约束（Hard Constraints）

- 移植目标设备为 Android 4.2.2（API 17），需将 minSdkVersion 设为 17，ABI 收敛为 armeabi-v7a
- 必须移除 androidx.car.app 依赖，该库不兼容 API 17
- 必须开启 multidex 和 core library desugaring 以支持 Kotlin 与第三方依赖在 API 17 上运行
- NDK r28 不支持 platform 17，需设置 APP_PLATFORM=android-21 并添加抑制 minSdk 版本错误的配置
- 操作过程中禁止重启主机
- 目标设备 MY16ADA 已 root。`pm disable` 对 OEM 系统服务跨重启持久生效，禁用会导致车机重启后无法启动，禁止使用。唯一的例外是热点自动化：反编译证实 OEM 无软 AP 入口，app 内通过 `su -c` 执行瞬时脚本（hostapd/dnsmasq/ifconfig/setprop ctl.stop mdnsd）是唯一可行方案，脚本均为非持久操作
- 禁止通过看门狗机制处理音频、视频相关问题，应通过 /command modes 等状态、会话管理机制实现，参考苹果官方 ACCESSORY 代码实现

## 经验教训（Lessons Learned）

### API 17 兼容

- Compose 最低支持 API 21，无法在 Android 4.2.2 上运行，移植版本需完全移除 Compose 依赖，仅保留核心运行时逻辑
- LocalMfiAuthenticationClient 中的 noBackupFilesDir 为 API 21+ 方法，在 API 17 上会触发 NoSuchMethodError，需降级使用 filesDir 作为离线 MFi 资产目录
- AudioRecord.Builder 为 API 23+ 类，在 API 17 上会触发 VerifyError，需改用 legacy 构造函数创建 AudioRecord 实例
- 所有 ReflectiveOperationException 相关引用在 API 19 上会导致 VerifyError 崩溃，需在 shared 模块中完全移除
- IPv6 未指定地址 `::` 在 API 17 上 bind 会抛 `SocketException: Can't bind to a link-local address without a scope id: /::`，NtpClock/keepalive/event/screen/audio 的 wildcard bind 需改绑 IPv4 `0.0.0.0`
- `java.net.Socket`/`ServerSocket`/`DatagramSocket` 在 API 17 的 Java 6 运行时**不实现** `Closeable`，`safeClose(Closeable?)` 接收这些类型会抛 `ClassCastException`；已将 `safeClose` 参数改为 `Any?` 并用 when 分支分别 close
- MediaCodec 的 `getInputBuffer(int)`/`getOutputBuffer(int)`/`releaseOutputBuffer(int,long)` 是 API 21+ 方法，API 17 崩 `NoSuchMethodError`；需降级用 `getInputBuffers()[i]`/`getOutputBuffers()[i]` 数组 + `releaseOutputBuffer(int,boolean)`，已在 `AndroidMediaSink.kt` 加 `decoderInputBuffer`/`decoderOutputBuffer`/`releaseOutputCompatible` 兼容函数
- `java.lang.Process.waitFor(long, TimeUnit)` 是 API 26+ 方法，API 17 崩 `NoSuchMethodError`；runSu 需轮询 `exitValue()` + `IllegalThreadStateException` + sleep 实现超时
- `MediaCodec.CodecException` 是 API 21+ 类，在 Android 4.2.2（API 17）上会触发 `NoClassDefFoundError`，在 `VideoDecoder.onJobFailure` 中需改用类名字符串判断而非 `is` 关键字，避免进程崩溃导致黑屏

### 无线链路 / 热点

- 目标设备 MY16ADA 不支持 Wi-Fi Direct，无法使用 direct 模式实现无线 CarPlay
- router 模式下 iPhone 未连接 7100：0x4301 已把车机 IP 发给 iPhone，但纯网络可达性问题（iPhone 未与车机同处 owork_5G_zhibo / 192.168.31.x，或路由器 AP 隔离）。AirPlay server 实际监听在 IPv6 双栈 ::ffff:192.168.31.159:7100（IPv4 可达）。倾向改用 app（热点）模式，车机自建 AP 让 iPhone 直连
- 车机自带的 `mdnsd` 守护进程（uid 1020）会占用 5353 端口，导致 JmDNS 绑定失败；可通过 `setprop ctl.stop mdnsd` 瞬时释放端口，该操作非持久，重启即恢复
- 车机 wl18xx 芯片硬件可正常开热点，app（热点）模式脱网的真因是 miwifi 定制固件的 `setWifiApEnabled` 不完整（只写 softap.conf，未启动 hostapd、未配 IP），而非芯片异常；手动三段即可正常广播热点：hostapd（`/data/misc/wifi/hostapd.conf`，DiPlay-Legacy/diplay1234/channel 6）→ wlan0 配静态 `192.168.43.1/24` → dnsmasq（`--pid-file=/data/local/tmp/dnsmasq.pid` 指向可写目录，因 /var 只读）
- B 方向反编译结论（wifi-decomp 产物）：车机**不存在 OEM 软 AP 入口**——`WifiApService.apk` 是 `IWifiApService` 客户端 WiFi 管理 AIDL（Ap=Application Service，仅 setWifiEnabled/connect/设备列表，无热点方法）；`services.odex` 无 hostapd/startSoftAp 代码（仅 NetworkManagementService 有 "softap" netd 字符串），OEM 已阉割 WifiStateMachine 的 AP 路径，`setWifiApEnabled` 因此不启动 hostapd；WifiApp/honda-framework 均无网络共享能力。自动化只能走 root 三段式
- `LegacyNetwork` 新增 `auto` 模式（`LegacyWirelessHost` 已切换）：`su -c` 执行单行三段式脚本（杀残留 → `setprop ctl.stop mdnsd` → `ifconfig wlan0 down` → `hostapd -B /data/misc/wifi/hostapd.conf` → 配 192.168.43.1/24 → dnsmasq），fast path 检测 wlan 接口已有 192.168.43.x 时直接复用不重启热点，close() 时仅当热点由本实例拉起才 teardown；SSID/密码从 hostapd.conf 读取，默认 DiPlay-Legacy/diplay1234
- app 内 `su -c` 内联脚本时，执行脚本的 sh 进程 cmdline 含整段脚本文本（含 "hostapd"），`/proc` 扫描 `case *hostapd*` 会**误杀自己**（rc=143 SIGTERM，宿主 adb 试验同症状）；修复：循环内先 `[ ${p#/proc/} -eq $$ ] && continue` 自排除。文件版脚本（`sh /data/local/tmp/x.sh`）cmdline 不含脚本正文，无此问题
- `InetAddress.toString()` 带 `/` 前缀（`/192.168.43.1`），前缀匹配必须用 `hostAddress`；fast path 曾因 `startsWith("192.168.43.")` 恒 false 而每次重启热点
- 车机 toolbox shell 极简：仅 grep，无 awk/xargs/killall/pidof/head/tail/sed/cut/basename/find/tr/timeout；脚本只能纯 sh（dnsmasq 用 pid 文件 kill，hostapd 用 /proc 扫描 + `${p#/proc/}` 提取 PID）
- 僵尸热点 fast path 缺陷（已修复）：`ifconfig down` 不清除内核地址 + `scanEndpoint` 不看 `isUp` → app 退出（进程死、192.168.43.1 残留）后下次冷启动误判热点仍在（`already up`），iPhone 拿不到 DHCP。修复：fast path 增加 `isDnsmasqAlive()`（读 pid 文件 + /proc cmdline，纯 Java 无 su），`SU_HOTSPOT_DOWN` 尾部追加 `ifconfig wlan0 0.0.0.0` 清地址。实测三态：僵尸态走重路径 rc=0、真热点运行中仍 fast path 复用、新 DOWN 后进程与地址双清
- su 安全审计结论（全部已提交代码）：全仓库唯一 su 通道是 `LegacyNetwork.runSu`（4 个脚本：id 只读/cat 只读/UP/DOWN），无 pm disable、mount、dd、写 /system、persist.\*、rm、reboot，任何修改重启 100% 恢复，物理上无法变砖。已加固唯一中危点：dnsmasq 击杀改为 `SU_KILL_DNSMASQ` 四重校验（pid 文件非空 → 非 `$$` 自身 → `/proc/<pid>/cmdline` 读得且含 "dnsmasq" → 才 kill），陈旧 pid 与 PID 复用误杀面归零；设备实测：pid 指向 system_server(946) 时拒绝击杀、system_server 存活、新热点拉起 rc=0。且 pid 文件为 root 属主，shell 用户写不进（污染假设本身不存在）

### 蓝牙 / iAP2 / 控制流程

- MY16ADA 的 OEM HFP 是运行在 system_server 中的 `BluetoothHeadsetHfService`（HeadsetHf 配置文件），而非标准 `BluetoothHeadset` 配置文件；此前基于 `BluetoothHeadset.getProfileProxy` 的非 root 断开方案仅作用于未连接的标准 Headset 配置文件，无法触及真正占用 RFCOMM 的 HeadsetHf 服务，因此失效
- 无线 CarPlay iAP2 控制流程：0x5702/0x5703（Wi-Fi 配置）→ 0x4e0e/0x4e0d → 0x4300（iPhone 宣告 CarPlay 可用）→ 0x4301（accessory 回复 IP:7100）。0x5703 不含 IP，仅 0x4301 携带车机 IP，故 iPhone 需先收到 0x4300/0x4301 才拿到 IP
- 执行 `setprop ctl.stop/start bluetoothd` 后新 bluetoothd 会出现一次 SIGSEGV 崩溃，但 init 会自动重启，5 秒后 RFCOMM 连接仍可正常建立，不影响最终结果；该操作是瞬时非持久的，仅重启蓝牙守护进程，不会导致车机无法启动
- `pm disable` 命令对 MY16ADA 的 OEM 系统服务是跨重启持久生效的，禁用 avapservice、hft、carplayapservice 等服务后若重启车机，会导致系统无法正常启动，恢复需通过 ADB 执行 `pm enable` 重新启用对应服务
- 无线 CarPlay 端到端已调通：iPhone 通过热点 DiPlay-Legacy 直连车机 192.168.43.1:7100，AirPlay pair/auth 完整，H264 视频到达、解密、解码并渲染（`Video: first frame rendered`，约 9.4fps）；`LegacyNetwork` 新增 `reuse` 模式复用已手动启动的热点、跳过失效的 setWifiApEnabled
- 无线 CarPlay 全自动链路已验证：app 冷启动自动拉起热点（`Hotspot up rc=0` → `Network mode=auto interface=wlan0 ready=true`），重启走 fast path（`already up`，进程 PID 不变）；iPhone 直连后 Wi-Fi iAP2 隧道建立、MFI 认证、0x5703/0x4301、AirPlay 屏幕流 110 建立并渲染首帧（800x480）
- 会话死亡后需自动重建无线链路：`LegacyWirelessController.onSessionEnded` 回调触发后，通过 `LegacyWirelessHost.restartWireless()` 执行整条无线链路冷启动重建，替代看门狗方案

### 音频 / 视频

- 播放/暂停操作导致 iPhone 主动断开 CarPlay 会话的根因是车机侧声明了 CarPlay 音频输出，与原生 A2DP 冲突；修复方案为在 LegacyWirelessController 中设置 `mainBufferedAudio = false`、`disableAudioOutput = true`，使车机不再向 iPhone 声明支持 CarPlay 音频，避免会话被主动终止

### 设备操作陷阱

- 设备操作陷阱：MY16ADA 自带 `adb` 二进制，`adb shell "…; adb logcat"` 会在设备上连本机 adbd 无限流式输出并把命令链卡死；`su -c` 拉起 hostapd/dnsmasq 后这些进程继承 adb shell 会话 stdout，会话不返回 EOF 而挂起，需 StopCommand 后另开会话查状态（进程不受影响）
