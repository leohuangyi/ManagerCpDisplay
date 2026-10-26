# Legacy 无线 CarPlay：原厂 WIAP 冲突与自动入网验证

更新日期：2026-10-26。目标为 Android 4.2.2 / API 17 车机。

下文按时间保留诊断和单车安装版本的证据；其中型号/固件哈希门控与旧版热点脚本是历史实现。最新多车型设计和验证边界以 [Android 4.2.2 兼容性](LEGACY_ANDROID42_COMPATIBILITY.md) 为准。

**07:50 首次受控自动入网成功；恢复原厂 WIAP 后重复车辆及重连失败再次出现。该设备的永久协调策略已完成两轮正常退出后的自动重连验收；多车型能力适配继续进行。**
热点启动、蓝牙 iAP2 认证及发送 Wi-Fi 配置成功，不能证明 iPhone 已关联热点。

## 本轮改动

- 热点配置增加 2.4 GHz Apple Device IE `dd0800a0400000020022` 和 Interworking IE `6b03040a01`。配置写入与实际空口广播、iPhone 识别仍是不同的验证环节。
- 0x4301 参数 4 虽名为 `PublicKey`，实际传配对身份：legacy 从 `publicKeyHex` 改为 `pairingId`，与 Bonjour `pi` 一致。
- helper 在热点接口拉起后瞬时启用 `wlan0` IPv6；接口恢复交由 OEM Wi-Fi restart，不写持久配置。
- `LegacyNetwork` 在热点新建及复用路径上最多等待 2 秒，收集同接口完成 DAD 的 IPv6 link-local 地址，排除 tentative / DADFAILED。保留 scope 供监听绑定，IPv6 与 IPv4 均可监听；0x4301 有 IPv6LL 时只发送去掉 zone 的 IPv6，无 IPv6 时回退 IPv4。
- 蓝牙控制返回超时或通道关闭、且尚无 Wi-Fi 会话接入时，保留热点和监听器，以 2 秒、5 秒退避最多尝试 3 轮（含首轮）。活动 AirPlay 会话保持控制；恢复轮次不主动断开 HFP。

## 现场结果与证据边界

| 验证条件 | 自动入网结果 |
| --- | --- |
| 单独增加上述热点 IE | zero stations |
| PI 修正及保留热点的控制重试 | zero stations |
| IPv6LL / IPv4 双栈监听 | zero stations |
| 0x4301 有 IPv6LL 时仅发送 IPv6 | zero stations |
| 用户从 Ultra 切为普通，观测 `themeAssets=true → false` | zero stations |
| HFP 重连及完整蓝牙重连 | zero stations |

这些结果尚未确定根因，也未证明 Ultra、OEM 抢占或某个 iAP2 字段导致不入网。
标准运输参数模式现已实施：组17为 0/1/2/3、组24为 0/1/2/4。LIVI 宏同样会补入扩展字段，因此本改动不能称为对齐 LIVI，也不能将某一字段称为已确认的 Ultra 标志。

既往手机手动加入热点后，约 90ms 出现首个 AirPlay TCP，只能证明该次入网后的后半链路开始工作，不能证明自动关联成功。项目记忆中较早的“全自动链路已验证”不应据此解读为 iPhone 自动入网已解决。其他设备的 5 GHz IE 对照结果见 [热点入网说明](WIRELESS_HOTSPOT_JOIN.md)，不能替代本 API 17 / 2.4 GHz 现场验收。

保留边界：未激活但仍打开的 AirPlay TCP 可阻止后续蓝牙重试；若其 Wi-Fi iAP2 隧道失败而 TCP 不关闭，当前不会重新启动蓝牙恢复。TCP 真正关闭后仍走既有会话结束重建路径。

## 构建与测试（前一轮记录）

本机日志：`/tmp/diplay-autojoin-ci-final.log`。使用 `--continue` 后，shared / home 单测，以及要求的 mobile / home / maphost lint 和构建均通过；legacy 构建也通过。最新 `:legacy:assembleDebug` 成功。

前一轮全量 CI **未通过**：common 共 1462 条测试，1 条现有测试失败：`CarPlayBonjourDualStackTest.dualLanThenSingleP2pThenDualLanServesAirPlayInfo` 第 116 行预期 HTTP 200，实际 `readLine()` 返回 null。Robolectric socket FD shadow 是排查假设，尚未证实最终因果，也未证明与设备自动入网失败有关。最新运行结论见文末。

## 操作范围

本轮没有重启车机、没有执行 `pm disable`，保留用户已有修改。本文不记录 MFi 凭证、密码或设备唯一标识。

## 最新现场校正：手动会话成功，自动重连仍失败

以下保留现场日志原时间。当前标准运输参数模式及 modern-only 连接消息模式均已实施。

| 时间 | 观察结果 |
| --- | --- |
| 06:23:31.870 | 首次标准识别版本在用户手动入网后渲染完整首帧；IPv6 TCP 为 ESTABLISHED，Wi-Fi iAP2 MFi accepted。 |
| 至 06:25:59 | AirPlay feedback 持续正常，已验证该次手动入网后的完整投屏及超过 60 秒的会话保持。 |
| 06:26:18 | 通过 ADB 双返回执行正常退出。 |
| 06:26:24 / 06:26:27 | 分别记录 `Wifi restart rc=0`、`Media reset rc=0`；随后未发现热点进程或 ARP 条目。 |
| 06:27:02–06:30:21 | 再次启动后三轮蓝牙认证、0x5703、0x4301 均成功，但一直 zero stations，最终 exhausted。 |
| 06:36:43 | modern-only Identification accepted、MFi accepted，只发送 0x4301，没有 0x5703，仍为 zero stations。 |

这次手动验证已超出“仅首个 TCP 可达”的证据范围，但退出后的自动重连测试仍失败。车机侧清理完成也不代表 iPhone 的网络或车辆关联缓存已恢复。

### 当前实现与诊断边界

- `includeExtendedTransportParameters=false` 已在 legacy 启用：蓝牙组17只发送 0/1/2/3，无线组24只发送 0/1/2/4。现场 assets 为 unset，用户当前未见 Ultra 模式选项；这些观察不能证明某一字段就是 Ultra 标志。
- `includeLegacyConnectionMessages=false` 已实施：首轮只声明现代连接消息 0x4300 / 0x4301，不声明旧四消息 0x5703 / 0x5702 / 0x4e0d / 0x4e0e。若任一现代消息被 Identification 拒绝，重试同时移除现代消息对、恢复旧四消息；其他被拒消息继续剔除。默认调用行为保留，control client 仍容忍旧消息。这是依据 R9 §4.2 的现代消息替代及兼容回退，不是 LIVI 对齐结论。
- 0x4301 DeviceIdentifier 改用 Bluetooth MAC 的 A/B 未成功，现已回退 WLAN MAC，与 AirPlay Info 身份保持一致。
- 首次手动会话的 `wifiUUID` 非空、`eligible=Y` 只证明该次评估状态，未证明 Bluetooth vehicle 映射持久修复。后续 iPhone 日志 36029 仍出现 `NoNetworkSSID`，carkit 日志 35970 仍有相同 Bluetooth vehicle 的凭据关联警告。
- 当前自动入网与自动重连根因仍未确定。用户已忽略 HONDA 后重新配对；原厂主动 WIAP 再次创建了 HONDA 车辆记录。它确实是新会话，不能归为静态缓存。

### 最新测试状态

40 条针对性测试通过，0 失败。最新完整 CI 日志 `/tmp/diplay-modern-full-ci.log`：shared 1595 条、0 失败、1 skipped；home 单测任务通过，要求的 mobile / home / maphost lint 与构建及 legacy 构建均通过。common 1462 条、1 失败，仍为上述 `CarPlayBonjourDualStackTest` 第 116 行预期 HTTP 200、实际 null。全量 CI 未通过；未改动该测试掩盖结果，尚未证明它与设备自动入网失败有关。


## 原厂 WIAP 隔离对照（07:39，仍未自动入网）

- 实际设备 AV APK/odex 反编译证实：`DeviceManager.connectWiap` 主动启动原厂 WIAP；`disableWiap()` / `enableWiap()` 是内存开关，不改变 HFP/A2DP 配对或持久设置。小助手新增手动诊断 opcode n7/n8，尚未接入 DiPlay 自动生命周期。
- 07:38–07:40 的受控测试已实际确认 `disableWiap=true`、HFP connected。DiPlay RFCOMM 直接成功，无 EBUSY、无清除 HFP；手机仅看到 DiPlay WirelessIAP，没有原厂 HONDA 识别端点。
- 07:39:04 DiPlay Identification / MFi accepted，07:39:05 发送 0x4301。手机实际接收、解析并保存 SSID / channel 6 / security 2 的凭据，但仍报告 `not posting in-car notification, WiFi credentials were not received while Bluetooth connected`。80 秒观察无 ARP / station / 首帧。
- 手机线上收到的 Identification group17 包含 0/1/2/3，6 字节 MAC 与底层蓝牙对端逐字节相等；group24 包含 0/1/2/4，SSID 非空。未发现字段丢失或 MAC 逆序；CarKit 内部车辆/蓝牙地址集合被 iOS 隐去，尚不能证明内部映射一致。
- 此次 `NoNetworkSSID` 是 AirPlay 查询手机当前已连接网络为空，不表示 0x4301 中缺少 SSID，也不属于原厂 HONDA 的启动消息。
- 07:40:22 已明确 `enableWiap=true` 恢复原厂开关。早前使用 Android 4.2 不支持的 `am broadcast -p` 的脚本没有执行 n7，不能算有效隔离对照。
- 顶层 iAP2 Name 已匹配实际 Bluetooth EIR LocalName，AirPlay Info.name 仍为协议要求的 CarPlay。临时 SDP/EIR probe 发布 caff/ec88 后，空口实际存在两 UUID，但这只是一项诊断探针：没有 accept/入站仲裁，不能作为完整实现交付。
- 下一项对照已完成，结果见下。


## 07:50 自动入网成功与恢复原厂后的复现

- 原厂 WIAP 保持暂停，caff/ec88 通告保持时，用户同时移除两车辆及旧蓝牙配对后重新配对。手机在 07:50:26 创建新的唯一车辆，07:50:29 允许 CarPlay 连接。
- 07:50:29.898 `wifid` 明确记录 `AUTO-JOIN trigger=carplay`，07:50:30 目标网络 `IsCarPlay:Y`、会话 `WiFi eligible:Y`；07:50:32.180 车机首帧，Wi-Fi iAP2 Identification/MFi accepted。用户未手动加入热点。会话持续至 07:53:06 主动正常退出，超过 150 秒。
- 原厂恢复调用一度超时，随后 07:52:42.920 明确记录 `enableWiap=true`。07:53:10 原厂无线 iAP 重新 Identification，创建 HONDA 车辆，使缓存从 1 变回 2；07:54:30 DiPlay 冷启动重新出现凭据/蓝牙关联警告，未自动入网。
- 原厂与 DiPlay 的实际 group17 MAC 相同；原厂没有 group24，DiPlay 有。两者 manufacturer/model/serial 与 MFi certificate serial 均不同，不能把它们视为仅名称重复，也不能认定改名即可合并。
- 该对照定位到原厂无线 iAP 重建第二车辆与自动入网失败之间的冲突；iOS 内部如何选择车辆仍属私有实现，未声称已反编译其具体匹配算法。
- 用户明确要求长期禁用原厂无线 CarPlay，保留有线。将使用助手自己的持久策略，在助手启动及原厂 AV 重新连接时重应用官方 `disableWiap`，不使用 `pm disable`，不禁用原厂 USB CarPlay 服务，不改 HFP/A2DP。
- 官方 `disableWiap` 是先设置 `mEnableWiap=false`，再返回 `disconnectWiap` 结果。因此正常返回 false 只说明当前无线连接未成功断开/没有活动连接，不表示禁止自动拨号未生效；异常或超时仍须明确报告。


## 最终代码安装与首次自动连接（08:15）

- 小助手持久策略 `n9` 禁止原厂无线 iAP，`na` 显式撤销；默认仅在已验证的 API17 / MY16ADA / vcm30t30a 与 AV.odex 哈希匹配时执行。偏好仅保存在助手自己的 SharedPreferences，不使用 `pm disable` 或 OEM 持久设置。
- 助手启动时及保留的 AV ServiceConnection 重新连接时按事件重应用；普通 DiPlay 退出不撤销策略。已实测重启助手后 08:10:37 自动重新应用成功。未重启整车，因此不把这次验证称为完整整车开机验收；策略依赖助手启动，助手未运行时不承诺抢在 OEM 之前应用。
- `mEnableWiap` 在实际反编译中只出现在构造、connectWiap、disable/enable；USB CarPlay、HFP/A2DP 的开关未改动。本轮未进行 USB 有线实机插拔测试。
- 临时 probe 已替换为 caff/ec88 真实 secure RFCOMM 服务，在等待配对前发布；已配对且选定手机的入站与主动 cafe 连接共享单一控制链路仲裁。所有底层 socket 关闭经过同一个原子 gate，防止 native 并发重复关闭。
- LocalAdb 命令/session 编号改为进程共用，并以单调时钟初始化以避免重启前延迟回执碰撞。并发回执路由、入站/出站竞争及 socket 关闭测试均通过。
- 用户在永久策略生效后重新清理两车辆/旧 BT 并配对：08:15:29 唯一车辆保存、cache=1；08:15:31 首次 0x4301；08:15:32.714 手机自动关联；08:15:34.980 车机首帧。未手动连接 Wi-Fi，也未用外部 CLI 发起 HFP（只读确认 state2）。无原厂 HONDA 新 Identification。
- Wi-Fi iAP 隧道上的第二次 0x4301 之后仍可出现同一条凭据/蓝牙警告，已关联且投屏成功时不能单靠这条警告判失败；须区分首次蓝牙启动、后续 Wi-Fi 控制以及实际关联结果。

### 最终构建与 CI

- 助手 release 构建成功；legacy debug 构建及 22 条针对性测试通过。
- `/tmp/diplay-final-permanent-wiap-ci.log` 完整运行所有仓库要求任务：shared 1602 条、0 失败、1 skipped；home 6 条、0 失败；common 1462 条仍有此前的 Bonjour 双栈用例第 116 行失败 1 条。所有要求的 lint/assemble 均成功，**全量 CI 未通过**。未改动无关测试掩盖失败。
- `scripts/check_public_tree.py` 与两仓 `git diff --check` 均通过。不记录密码、证书或设备唯一标识。


### 本设备两轮退出重连验收通过

| 轮次 | 正常退出确认 | 手机自动关联 | 启动至首帧 | 首帧后保持 |
| --- | --- | --- | --- | --- |
| 1 | AP 进程 0、43 网段地址已移除 | 08:17:28.921 `(auto)` | 18.1 秒 | 65 秒 |
| 2 | AP 进程 0、43 网段地址已移除 | 08:19:11.105 `(auto)` | 18.1 秒 | 65 秒 |

两轮均未手动连接 Wi-Fi、未使用外部 HFP connect，手机车辆缓存始终1条，未再出现原厂 HONDA Identification。第二轮手机自动关联早于新的蓝牙识别及 0x4301。最后一轮保持运行。上述只代表这台实机；用户追加要求跨多款 Android 4.2.2 Honda，正在基于提供的六套 honda_* ROM 审计，尚不宣称其他车型实测通过。

## 通用版最终回归（09:54 起）

六套 ROM 的接口审计和通用能力实现已完成，详见 [兼容性说明](LEGACY_ANDROID42_COMPATIBILITY.md)。已移除生产启动路径中的型号/固件哈希门控、固定 Binder 事务号、固定 WLAN/PHY 选择及重复媒体源编号。接口/端点身份、HF、音源和热点资源均按本机能力处理，协议常量和明确的产品默认值保留。

新助手启动及进程重启后，均通过实际系统 Stub 成功应用持久 WIAP 策略。上车发现 root mksh 缺 printf、su 的 umask 导致 daemon 无法穿越父目录、toolbox `rm -f` 遇缺失文件提前成功退出等差异，已修正并加入测试。失败启动留下的恢复记录已用修正版 n4 清理，Wi-Fi/mDNS 回到启动前状态，再开始正式验收。

正式三轮均自动连接：首次约 20.1 秒首帧，两次正常退出再打开均约 18.0 秒首帧，每轮首帧后保持 65 秒。两次退出均确认 AP 进程 0、热点地址清除、恢复 journal 清空、Wi-Fi/mDNS 与启动前一致。手机侧每轮均记录 CarPlay auto-join，正式验收期间车辆缓存持续为 1；没有重新清配对、手动选 Wi-Fi 或外部 HFP connect。源派发及运行时 HF 重链成功，最后保留会话运行，调试采集已停止。

助手 29 项测试及 release 构建通过；legacy debug 构建通过。最新完整 CI 日志 `/tmp/diplay-multimodel-full-ci.log`：shared1607（0fail/1skip）、home6pass、要求的 lint/assemble 全通过；common1462 仍有同一既有 Bonjour 第116行失败，完整 CI 未通过。两仓 diff 检查和 public-tree 检查通过。其他五款仍只有静态证据，冠道官方 WIAP 停用能力缺失，不宣称全部车型实车通过；USB 插拔和整车重启仍未测试。
