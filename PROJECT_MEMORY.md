# DiPlay 项目记忆

无线 CarPlay 面向多款 Android 4.2.2 / API 17 车机；MY16ADA（800×480）是当前现场设备，不是通用代码的型号白名单。以下经验按发生时间保留，旧版脚本中的接口名、型号/哈希门控和事务号属于历史实现；当前设计以末尾记录和 [兼容性说明](docs/LEGACY_ANDROID42_COMPATIBILITY.md) 为准。legacy 当前包名为 `com.shilapi.mgxcertplay`。

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

### 2026-10-26 自动入网现场校正（仍未解决）

- API 17 / 2.4 GHz 现场自动入网仍为 zero stations，不能沿用早先“全自动链路已验证”的表述。既往手动入网后约 90ms 出现首个 AirPlay TCP，仅证明该次后半链路开始工作，未证明 iPhone 自动关联成功。
- 已配置 Apple IE `dd0800a0400000020022` 和 Interworking IE `6b03040a01`；legacy 0x4301 参数 4 从公钥 hex 改为与 Bonjour `pi` 相同的 `pairingId`。这些改动尚未解决自动入网。
- helper 瞬时启用 `wlan0` IPv6，恢复交给 OEM Wi-Fi restart；Network 最多等待 2 秒收集同接口完成 DAD 的 IPv6LL，保留 scope 作 IPv6 / IPv4 监听。0x4301 有 IPv6LL 时仅发 IPv6，无则兼容 IPv4。蓝牙控制最多尝试 3 轮（含首轮），保留热点及监听器，恢复不主动断开 HFP。
- IE 单独、PI 加重试、IPv6 双栈、wire 仅 IPv6、用户 Ultra→普通（`themeAssets=true→false`）、HFP 及完整蓝牙重连均未观察到 station。不能归因 Ultra 或认定根因已解决；后续已实施标准运输参数模式，但不能将组24字段 3 / 5 称为 Ultra 标志，LIVI 宏也会补入它们。
- 前一轮 `/tmp/diplay-autojoin-ci-final.log` 的 `--continue` 结果：要求的 shared / home 单测、lint 和各构建通过，legacy assemble 成功；common 1462 条中现有 `CarPlayBonjourDualStackTest` 第 116 行读取 null 失败 1 条，该轮全量 CI 未通过。Robolectric socket FD shadow 仅为未证实的排查假设；本条不是新一轮 CI 的最终结论。
- 本轮未重启车机、未执行 `pm disable`，保留用户已有修改；不将 MFi 凭证、密码和设备唯一标识写入记录。完整证据边界见 [Legacy 自动入网验证](docs/LEGACY_WIRELESS_AUTOJOIN_VALIDATION.md)。

### 后续现场校正：标准识别与现代连接消息

- 06:23:31.870，首次标准识别版本在用户手动入网后完整出首帧，IPv6 ESTABLISHED、Wi-Fi iAP2 MFi accepted；正常 feedback 持续到 06:25:59。该次已验证完整投屏及超过 60 秒的会话保持，仍不能证明自动入网或持久 Bluetooth vehicle 映射修复。
- 06:26:18 正常退出，06:26:24 `Wifi restart rc=0`、06:26:27 `Media reset rc=0`，随后无热点进程或 ARP 条目；06:27:02 重开后三轮认证、0x5703 / 0x4301 成功但 zero stations，06:30:21 exhausted。车机侧清理成功没有解决自动重连。
- 标准模式 `includeExtendedTransportParameters=false` 已实施；组17为 0/1/2/3，组24为 0/1/2/4。现场 assets unset，用户当前无 Ultra 模式选项；不能称任一扩展字段为 Ultra 标志。
- `includeLegacyConnectionMessages=false` 首轮仅声明现代 0x4300 / 0x4301；任一现代消息被拒时，重试整对移除并恢复旧四消息，默认调用保持原行为。依据是 R9 §4.2 的现代消息替代，不是 LIVI 对齐。06:36:43 现代-only Identification / MFi accepted，只发 0x4301、未发 0x5703，仍 zero stations。
- 0x4301 DeviceIdentifier 的 Bluetooth MAC A/B 失败，已回退 WLAN MAC。首次手动会话虽 `wifiUUID` 非空、`eligible=Y`，后续 iPhone 日志 36029 仍有 `NoNetworkSSID`、carkit 日志 35970 仍有相同 Bluetooth vehicle 的凭据关联警告，不能写成持久映射已修复。
- 40 条针对性测试通过、0 失败；最新 `/tmp/diplay-modern-full-ci.log`：shared 1595 条、0 失败、1 skipped，home 单测任务、要求的 lint 与构建、legacy 构建均通过；common 1462 条中仍有上述 Bonjour 第 116 行失败 1 条，全量 CI 未通过。主线程已请用户仅忽略 HONDA 条目，操作和结果均待确认。仅记录证据边界，不提前写成功；本次文档更新不含凭据或唯一设备标识。


### 原厂 WIAP 实际隔离与手机凭据关联（仍未解决）

- 实际设备 AV 的 `disableWiap` / `enableWiap` 为内存开关，仅暂停原厂 WIAP，HFP/A2DP 保留；小助手手动诊断 n7/n8 已验证可用，尚未接自动生命周期。n7 即使 false 也可能已清标志，任何尝试后均须确认 n8=true 恢复。
- 07:39 受控轮真正执行 n7=true，HFP保持connected，DiPlay无EBUSY且手机仅有其WirelessIAP；Identification/MFi/4301及手机保存凭据均成功，80秒仍zeroARP、无首帧。07:40:22 n8=true已恢复。因此原厂抢占不是唯一解释。
- 手机仍拒绝发送in-car通知，原文 `WiFi credentials were not received while Bluetooth connected`；线上group17 MAC与底层蓝牙对端完全一致，group17/24所需结构存在。CarKit内部映射字段被iOS隐去，不能凭此断定最终根因。`NoNetworkSSID`指手机当前已连网络为空，不是4301缺SSID。
- 临时SDP/EIR probe实测使caff/ec88出现在EIR/SDP，但没有accept/入站仲裁，不可当成完整实现交付。下一轮首次在原厂暂停、通告保持时同时清除两车辆/旧BT再配对，结果待定。不要再将单独删HONDA后原厂重建当成已排除缓存关联。


### 首次受控自动入网与原厂冲突复现

- 07:50 在原厂 WIAP 暂停、通告存在、同时清除两车辆及旧蓝牙后新配对的条件下，手机缓存仅1辆；`wifid AUTO-JOIN trigger=carplay`，07:50:32 首帧、Wi-Fi iAP2 认证成功并稳定超过150秒。用户未手动入网，首次真正证明自动加入。
- n8 先超时后于07:52:42明确true；07:53:10原厂无线iAP又创建HONDA，使车辆1→2；07:54:30再次冷启动复现凭据关联警告及不入网。不能只保留首次成功而忽略这个失败。
- 两端底层BTMAC相同，但实际MFi证书serial不同，原厂无group24。仅统一名字/serial不能被当作已证明修复。用户已明确要求长期停用原厂无线，保留有线；正在实现helper持久偏好+官方运行时disableWiap重应用，禁止pm disable/车机重启。
- `disableWiap`正常返回false仍已设置mEnableWiap=false，返回值只代表后续disconnect结果；策略应用应区别false与Binder异常/超时，不可让正常重应用被当成启动失败。


### 当前实机验收通过，多车型适配继续

- 永久禁用原厂 WIAP 策略已落盘，助手重启自动重应用；完整 RFCOMM 接收与单次关闭门控已安装。新配对08:15:32.714自动关联、08:15:34.980首帧；随后两次正常退出均确认AP进程0/地址移除，再打开各18.1秒首帧且保持65秒。手机自动关联08:17:28.921与08:19:11.105，缓存持续1辆、无新原厂HONDA。最后保留会话运行。
- 本轮完整CI shared1602(0fail/1skip)、home6pass、required lint/build均通过；common1462仍为既有Bonjour第116行1fail，完整CI未通过。连接相关22测试与legacy/helper构建通过。
- 用户追加明确多款Android4.2.2车型通用、避免死编码。参考ROM根目录为其提供的ROM_BUILD下六套honda_*；当前model/device/固件hash/固定212/213门控只适合已验设备，必须改为实际接口/能力发现，不能让不适用车型直接阻断DiPlay。多车型审计尚在进行，勿把当前实机成功等同全车型验证。

### 多车型能力适配完成，通用版实机回归通过

- 六套 honda_* ROM 审计：URV/URV2019/twCrv 官方 WIAP 事务号212/213，breeze/civic2019为217/218，GuanDao无公开停用方法但仍有WIAP。生产实现通过 optional uses-library 加载本机接口/Stub，验证签名/descriptor后调用，不固定事务号、型号或ODEX哈希；unsupported返回applied=false且允许启动，不能声称冠道已禁无线。
- HF按实际角色/API发现，不把HEADSET(AG)当HF；音源按AvConst命名常量解析，DiPlay、助手开机/按键入口共用解析器。无线端点准备后以同一实际iface统一AP/deviceID/Bonjour/4301；有线遗留host的固定硬件/固件描述也已改为运行时值。
- 热点动态发现iface/backend/工具/PHY，专属PID、精确NUL argv、私有恢复journal，只收回本会话资源；root层锁、取消检查和boot分离防超时/跨开机恢复串扰。原Wi-Fi/mDNS/supplicant及修改过的硬件参数按记录恢复，第三方冲突保留记录并失败，不全局替换/dev/random。
- 实机root mksh无printf，须验证print并逐行raw写配置；su umask077需父目录711供wifi/nobody穿越，凭据/熵文件仍600。该toolbox的多参数rm -f遇首个ENOENT立即返回0且跳过后续参数，清理必须逐项rm并验证；不能把桌面Bash行为当API17行为。对应回归夹具已保留。
- 通用版本已安装，助手重启后官方WIAP策略重应用成功。正式首连20.1秒、两轮正常退出重开均18.0秒首帧，各保持65秒；退出AP0/地址清除/journal清空/Wi-Fi及mDNS恢复均通过；手机三轮auto-join、车辆cache始终1。未清配对、未手动Wi-Fi、无外部HFP connect，末轮留运行，采集已停止。
- 助手29项测试（13+6+10）及release、legacy debug构建通过；规定CI shared1607(0fail/1skip)、home6pass、lint/assemble通过；common1462既有Bonjour第116行1fail，完整CI未通过。其他车型仅静态审计，USB插拔及整车重启未测。细节见兼容性说明和自动入网验证文档。
