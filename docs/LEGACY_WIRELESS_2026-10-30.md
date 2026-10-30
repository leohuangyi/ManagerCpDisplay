# 2026-10-30 无线 CarPlay 启动日志分析

日志：`a8e77df4eced_log_2026_1030_0341.log`。时间均为日志内的车机时间，不能从文件名推算故障时间。

## 已确认

| 时间 | 日志行 | 结果 |
| --- | --- | --- |
| 12:05:24.986 | 13777 | 热点清理 rc=0 |
| 12:05:36.106–38.036 | 15690、15746、15749 | hostapd AP-ENABLED，启动 rc=0，实际接口 wlan0 就绪 |
| 12:05:40.656–40.786 | 16148、16157 | 蓝牙 MFi 认证通过，发送 0x4301 |
| 12:06:39–12:08:47 | 16683、17331、17983 | 三次控制尝试均 TIMED_OUT at CARPLAY_START_SENT |
| 17:44:45–17:45:37 | 23808、25405、26298 | 第二次清理/启动成功；在控制等待期间退出，不能计作完整超时轮次 |
| 17:47:22–17:50:42 | 28254、29346、29946、30531、31130 | 第三次清理/启动成功，另外三次完整控制尝试同样超时 |
| 17:53:59.589 | 34839 | 原厂 MCS 的 AirPlay 经 usb0 启动，为有线会话，不能计作 DiPlay 无线成功 |

两轮完整重试均为 `wifiConfigs=0 startSessions=1 transportNotified=false airPlayConnected=false wifiTunnels=0`。
Legacy 使用现代连接消息，0x4301 自身包含热点凭据；`wifiConfigs=0` 统计的是 0x5703，不能据此认定没发送凭据。
日志没有 hostapd 关联或 DHCP 交换，没有完整 IPv4 邻居，也没有 DiPlay AirPlay 接入。
缺少站点计数、接口收包增量及手机日志，仍不能仅凭空 ARP 确定手机未关联（IPv6 无需 IPv4 ARP）。

本次没有发现 EADDRINUSE/5353 绑定失败，清理重建已生效。原厂无线策略返回已应用；原厂经 usb0 的后续会话不证明原厂无线重新启用。
这份日志无法确定最终原因为手机车辆/凭据关联、AP 关联/链路，还是 IP/发现阶段，也不能证明或彻底排除 mDNS 改动的关联。

## 社区核对（2026-10-30）

查阅公开 main `615b32ce`（0.2.22）及相关 issue/PR。该版 mobile 的 minSdk=24，不能直接替代 Android 4.2.2/API 17 的 Legacy 移植版。

- [#1119](https://github.com/shihabal3amri/DiPlay/issues/1119)：0.2.21 错选 rmnet 数据接口，#936/#950 修复，车主确认有效。本日志使用正确的 wlan0，不匹配。
- [#394](https://github.com/shihabal3amri/DiPlay/pull/394)：已合并，解决 IPv6-only Bonjour。本 Legacy 在 IPv4 上创建 JmDNS、同时监听两族，不能直接等同此回归。
- [#474](https://github.com/shihabal3amri/DiPlay/pull/474)：已合并，等待首个 TCP 超时后切换通告地址族。Legacy 此前虽然监听两族，但三次均只通告 IPv6，缺少该恢复路径。
- [#1071](https://github.com/shihabal3amri/DiPlay/pull/1071)：同时通告所有地址的提案仍未合并；维护者要求真实失败设备的有效验证，现有测试未证明修复目标故障。不能称为已验证方案。
- [#1178](https://github.com/shihabal3amri/DiPlay/pull/1178)：RFCOMM EOF 后保留已开始的 AirPlay；本日志没有 TCP/SETUP，故不匹配。
- [#1103](https://github.com/shihabal3amri/DiPlay/issues/1103)：用户称手机 Wi-Fi 重开可恢复，但关于断电残留会话是推测；其随后更换官方包后关闭 issue，不能直接认定本次同根因。

## 本次代码改动与验证边界

保留首次 IPv6 策略，仅在已发送 StartSession、控制等待超时、且还没有交接时，在已有三次上限内尝试另一族。
回退只使用仍在本次热点接口、且启动时已经绑定监听的地址，保持 SSID/凭据/接收器身份/端口不变；后续 Wi-Fi iAP2 控制隧道读取最新端点。
不改 JmDNS 顺序、蓝牙识别和认证协议，不清配对，不引入额外热点复用。

每次蓝牙控制前后记录现有接收计数诊断。接口计数只能说明接口收到多少数据，UDP 计数覆盖设备网络命名空间；均不能识别特定手机。
读取不支持时只报告 unavailable，不使启动失败。
本改动是有依据的兼容恢复及证据补足，尚无故障真机验证，不宣称 iPhone 自动入网根因已解决。

验证：新增 6 项端点回退测试通过；shared 1613 项、0 失败、1 跳过，home 6 项通过；要求的三个 lint 和 mobile/home/maphost/legacy Debug 构建通过。
完整 CI 未通过：common 1462 项中，既有 `CarPlayBonjourDualStackTest.dualLanThenSingleP2pThenDualLanServesAirPlayInfo` 第 116 行仍失败 1 项。
构建日志为 `/tmp/diplay-20261030-endpoint-recovery-ci.log`。未在故障设备安装或验证。
