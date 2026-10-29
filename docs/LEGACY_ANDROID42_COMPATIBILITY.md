# Android 4.2.2 Honda 连接链路兼容性

本表基于用户提供的 `ROM_BUILD/honda_*` 六套反编译归档。归档缺文件不等于固件缺能力；当前只有一台车机进行了 ADB 实测，其他车型仅静态核对。

## 官方接口差异

| ROM | 官方 WIAP 停用/恢复方法 | 系统 HF 角色 | 蓝牙音源常量 |
| --- | --- | --- | --- |
| honda_GuanDao | 无公开 disableWiap/enableWiap；仍有原厂 WIAP | HEADSET_HF | AV_AP_SOURCE_BLUETOOTH |
| honda_URV | 有 | HEADSET_HF | 在实际 API 库中定义 |
| honda_URV2019 | 有 | HEADSET_HF | AV_AP_SOURCE_BLUETOOTH |
| honda_breeze | 有 | HEADSET_HF | AV_AP_SOURCE_BLUETOOTH |
| honda_civic2019 | 有 | HEADSET_HF | AV_AP_SOURCE_BLUETOOTH |
| honda_twCrv | 有 | HEADSET_HF | AV_AP_SOURCE_BLUETOOTH |

六份 `AvApServiceApiLib.odex` 中的 Bluetooth 音源常量值均为 19，framework 中 HEADSET_HF 均为 101。但实现从当前系统的命名常量和真实 API 读取，不以这些数字作为车型识别或猜测回退。

WIAP 方法存在时都是公开、无参、返回 primitive boolean。URV/URV2019/twCrv 的 Binder 编号为 212/213，breeze/civic2019 为 217/218；固定发送事务号会调用错误方法。代码通过可选 `avapservice.lib` 加载本机实际 Stub，由它自己的 Proxy 决定事务号，不打包一份旧 OEM Stub，不依赖型号或 ODEX 哈希白名单。

冠道没有公开控制方法，但原厂 `DeviceManager.connectWiap` 仍会发起无线 iAP。因此必须返回 `not_applicable / unsupported_operation / applied=false`，允许 DiPlay 继续启动，同时保留“原厂无线状态未确认”的边界，不能宣称已禁用。权限、服务身份、Binder descriptor 或接口签名错误以及调用超时也不能当成成功。

## 运行时能力与资源所有权

- HF 与 AG 角色不同。代码探测 HEADSET_HF / HEADSET_CLIENT 及实际 API/proxy 能力；没有 HF 能力时保留系统连接，不盲用 HEADSET(AG) 角色。代理获取有界，迟到的回调也释放资源。
- 媒体源从当前系统库读取 `AV_AP_SOURCE_BLUETOOTH`。小助手的开机动作、按键动作也共用运行时常量解析，八种音源不再各维护固定编号。缺能力不猜源编号；派发广播的回执只称 `dispatched`。源请求在后台执行，只有有效回执才继续已有的 HFP 重链流程，避免阻塞 AirPlay 控制线程。
- 热点选择实际 Wi-Fi 接口与可执行后端；AP、IPv4/IPv6、Bonjour、0x4301 和 AirPlay deviceID 使用同一个准备完成的端点。
- 热点生命周期使用专属配置/PID 与恢复记录，只清理本会话创建的资源；恢复原来 Wi-Fi、mDNS、supplicant 和硬件参数状态，遇到第三方配置冲突不覆盖。
- 每次新建无线控制器都先执行 `n2` 清理旧的自有热点，再执行媒体恢复和 `n1` 完整重建；不再根据进程和 IP 直接复用热点。`n1` 自身也不再提前返回旧 AP，启动脚本必须先确认旧进程已退出再创建新进程。清理失败或阶段间取消时不继续启动。SSID、密码、信道和 CarPlay 配对身份保留，原网络恢复义务继续由 journal 管理。已有会话的界面恢复不触发重建。
- root 层锁覆盖命令超时后仍存活的 shell，撤销标记阻止后续启动阶段；同一次开机的遗孤锁不擅自夺取。锁按内核 boot ID 分离，跨开机仅恢复持久文件，不把旧开机的临时状态写回当前系统。
- 不全局替换 `/dev/random`；硬件补偿仅在发现对应接口/PHY/驱动能力时应用。缺少已知媒体恢复工具时不自行拼造 OEM daemon 操作。

## API 17 shell 实机差异

编译和桌面 Bash 测试不能代替 Android toolbox 验证。本机 root mksh 没有 `printf`，脚本验证并使用内建 `print`，配置逐行原样写入；hostapd 支持 `-e` 时使用独立 64 字节熵文件。`su` 的 `umask 077` 会令父目录不可穿越，专属命名空间使用 711，配置和熵文件仍为 wifi 属主的 600。

此 ROM 的 `rm -f a missing c` 在遇到不存在的 `missing` 时立即返回 0，并跳过 `c`。实机临时文件探测与 toolbox 二进制审阅一致。清理必须逐个文件执行并确认不存在，最后检查目录删除结果；不能用一条多参数 `rm -f` 的返回 0 判定恢复完成。回归夹具模拟该行为以及失败启动缺失部分文件的情形。

## 可以保留的协议与产品常量

iAP2 服务 UUID、消息 ID、AirPlay 默认端口、标准 A2DP 枚举、WPA2 类型和有效的重试上限是协议或实现常量。应用 SSID、2.4 GHz 信道与私有 AP 网段可以作为明确默认值，前提是先验证接口、后端、信道范围及地址冲突。这些不应被误当作某一台车的唯一标识。

动态硬件/接口值、系统方法编号、设备 MAC、型号、固件哈希与特定 PHY 路径不得锁死在通用启动路径中。

## 验证边界

通用版已在当前 API 17 实机安装，助手进程重启后使用本机 Stub 重新应用持久 WIAP 策略成功。正式验收未清手机配对、未手动连接 Wi-Fi，也未使用外部 HFP connect。

| 通用版验收 | 启动至首帧（约） | 首帧后观察 | 退出恢复 |
| --- | --- | --- | --- |
| 首次启动 | 20.1 秒 | 65 秒 | — |
| 第一次正常退出再打开 | 18.0 秒 | 65 秒 | AP 进程 0、地址清除、journal 清空、Wi-Fi/mDNS 与启动前一致 |
| 第二次正常退出再打开 | 18.0 秒 | 65 秒 | 同上 |

手机侧三轮均有 CarPlay 自动入网记录，验收期间车辆缓存只出现 1 条。运行时音源回执及 HF=101 重链成功；末轮保留会话运行。时间来自 2 秒间隔的验收采样，不能解读为精确性能基准。最初上车暴露的目录权限、toolbox 删除语义与部分启动恢复问题已在这次正式验收前修正，并增加回归夹具。

助手 29 项测试（WIAP 13、音源 6、热点 10）通过，release 与 legacy debug 构建通过。规定的完整 CI 已执行：shared 1607（0 失败、1 skipped）、home 6 通过；要求的 lint/assemble 均通过；common 1462 中此前已有的 `CarPlayBonjourDualStackTest.dualLanThenSingleP2pThenDualLanServesAirPlayInfo:116` 仍失败 1 条，因此完整 CI 未通过。未修改该测试掩盖失败。

六份静态归档不能代替六台车的实测；目前仍只有这台实机通过连接回归。冠道缺官方 WIAP 停用接口的限制仍然存在。USB 有线插拔与整车重启未执行；助手未运行时不保证抢在原厂无线发起之前应用策略。原始日志保存在本机临时诊断目录并限制访问，不纳入仓库。
