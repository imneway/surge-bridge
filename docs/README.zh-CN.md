# Surge Bridge 开发说明

这是基于 SagerNet/sing-box-for-android 的实验性配置兼容项目，可导入、订阅更新和编辑部分 Surge `.conf`。界面保留原始 Surge 源文件，VPN 内核读取转换后的 sing-box JSON。它不是 Surge 或 SagerNet 官方客户端，也不是 Surge 的完整 Android 移植版。

## 实测范围与限制（先读）

**本项目只有 Snell v6 做过代理协议的端到端连通性验证。** Snell v4/v5、Shadowsocks、Trojan、HTTP(S)、SOCKS5、AnyTLS 虽然存在配置转换路径，但没有完成对应真实代理服务的端到端验证；能导入不等于已验证可用。

- Snell v6 的受控测试覆盖 default / unshaped / unsafe-raw、TCP/UDP 和 reuse 开关；另有 Android 真机节点连通性验证，不代表所有服务端或服务商均兼容。UDP 需在配置中显式设置 `udp-relay=true`。
- 设备验证主要是一台 OPPO 手机和 Android 模拟器；其他机型、系统后台策略及网络环境未经验证。
- 不实现 MITM、脚本、URL/Header Rewrite 等完整 Surge 能力。部分不支持的规则或选项会跳过并提示，其他情况会拒绝导入；必须阅读兼容性提示，不能把导入成功视为行为完全一致。
- GeoIP 数据、DNS 解析时机、解析失败处理和部分规则语义与 Surge 有差异；同一配置可能需要 Android 本机的包名/域名覆盖。Google Play 的实测案例见下文。
- 定期更新和开机恢复受 Android/OEM 限制，不保证即时执行或自动重连；测试中的 OPPO 未证明仅靠应用自启动就能可靠恢复 VPN。
- 当前构建只有 ARM64 / x86_64；Naive、OpenVPN、OpenConnect、Tailscale、USB/IP 未启用，即使仍能看到相关上游界面也不代表可用。

具体支持项以 [兼容性说明](compatibility.md) 为准，实测证据和未验证范围见 [验证记录](validation.md)。

## 基线与构建

- SFA 发布基线：1.14.4，`f87c2ebdb7e34754be751bb5e58926db2fed8f8c`。
- sing-box 内核：v1.14.4，`c005f35300ce76427dc285481ec5695f5b179cd2`；gomobile v0.1.12。
- 公开分支：`main`。公开历史从清理后的源码快照开始，旧开发历史仅保留在本机；保留上游归属说明和 GPL-3.0 许可证。
- 调试包名：`io.github.liuw.surgebridge.debug`；应用名 Surge Bridge，与官方 SFA 并存。
- 本分支构建 ARM64 和 x86_64；没有 32 位 APK。调试版使用系统默认 debug 签名，升级须保留同一 debug keystore。正式版使用独立本地签名，详见下方发布构建。
- 开发构建关闭官方 SFA 的应用更新入口，防止把官方包误当成此分支更新。

构建需 JDK（已用 Temurin 21.0.13 验证）、Go（已用 1.26.1 验证）、Android SDK platform 37.1、platform 24（gomobile 绑定）、build-tools 36/37、NDK 28.0.13004108。Gradle 和插件版本沿用发布基线。SDK/NDK/JDK 路径建议不含空格（可使用短路径符号链接）。

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"
# PATH 中需有 Go。
bash tools/build-android.sh
```

输出在 `app/build/outputs/apk/other/debug/`。脚本构建的内核包含 Snell、QUIC/HTTP3、gVisor、WireGuard、uTLS、Clash API；为控制构建依赖，未启用官方全量构建中的 Naive、OpenVPN、OpenConnect、Tailscale、USB/IP。对应上游界面仍存在，这些高级功能不在本分支验收范围内。

`.local-tools/`、`.private/`、`app/libs/` 和构建目录均忽略。个人配置、PSK、订阅地址及日志不得提交。下载工具链约占数 GB；它们仅在项目私有工具目录中使用。

## 使用与同步

1. 安装调试 APK。应用内通过文件选择导入 `.conf`，或建立 Remote Profile，填入返回原始 Surge 文本的 HTTPS URL。
2. 进入配置详情查看转换兼容性提示；无法支持的主配置字段会使导入失败。
3. 选择配置并启动；首次启动需要 Android 系统的 VPN 授权。
4. 修改远端同一份 Surge 文件后，可手动刷新或启用自动更新。更新周期由 Android WorkManager 调度，最小约 15 分钟，受后台节电限制，不保证即时。
5. 原始 Surge 内容保存在应用私有文件区，编辑器读写原文。配置详情的文件导出保留 `.conf`；部分上游分享/QR/JSON 导出入口仍导出生成的原生 JSON。

电脑上的 Dropbox 路径不能直接成为 Android 的远程订阅。可让 Dropbox 客户端把同一文件同步到 Android 后手动重新导入；持续自动同步需要能获取原始文本的订阅 URL。开发阶段可运行 `tools/android-debug.py update ID --file /path/to/Work.conf` 将电脑上的最新文件更新到已导入的本地配置，不必维护另一份 Android 配置。

## 转换与更新保障

`ProfileStore` 是导入、编辑和订阅更新的统一入口：获取规则集 → 转换 → `Libbox.checkConfig` → 原子发布。下载、转换或验证失败不会覆盖之前可用的运行配置。

主文件始终是内核 JSON；`.source` 快照通过生成内容的 SHA-256 关联原始源文件，最多保留前后两代。规则集缓存在私有文件区，只在整个配置通过校验后写入缓存。重启 VPN 使用已生成配置，不需要重新下载 Surge 文本规则集。GEOIP 使用内核远程 SRS，有自己的首次下载/更新过程。

订阅文本的规则集按自身 `update-interval` 缓存，只有下一次配置刷新时才检查到期；这并不是每个规则集独立后台计时器。详情中展示不能等价转换的项目。新配置通过静态校验后若内核加载失败，会尝试恢复该配置上次成功运行的版本，并记录回滚结果；仍应查看服务状态/日志确认恢复。

## 自动调试

见 [debug-api.md](debug-api.md)。API 只在 debug 变体启动；release 使用空实现。只监听 Android `127.0.0.1:19090`，每次应用进程启动生成新的 bearer token，电脑经 `adb forward` 和 `run-as` 访问。API 不返回源配置、PSK、订阅 URL 或配置解析器原始错误。新增的 core-logs 接口会包含访问目标、节点标签等敏感元数据，保存后需脱敏再分享。

```sh
python3 tools/android-debug.py install app/build/outputs/apk/other/debug/SurgeBridge-1.14.4-surge.5-arm64-v8a-debug.apk
python3 tools/android-debug.py import /path/to/Work.conf
python3 tools/android-debug.py select 1
python3 tools/android-debug.py start
python3 tools/android-debug.py status
python3 tools/android-debug.py logs
python3 tools/android-debug.py update 1 --file /path/to/Work.conf
python3 tools/android-debug.py reload
python3 tools/android-debug.py groups
python3 tools/android-debug.py core-logs .private/core.json
python3 tools/android-debug.py overrides 1 /path/to/android-settings.json
python3 tools/android-debug.py reload
python3 tools/android-debug.py logcat .private/device.log
```

多设备时指定 `--serial SERIAL`。API 的 start/reload 返回接受请求后，客户端轮询观察服务状态；`Started` 只说明内核启动成功，仍须测试实际出站网络。

## 测试

- `tools/test-converter.sh`：Kotlin 编译器和 org.json 20250517，测试脱敏样例、万级规则合并策略、嵌套 AND、no-resolve、密钥引用/转义、非法参数、循环引用、原文识别和失败脱敏；并由 sing-box 对输出执行 `check`。
- `tools/test-snell.py CORE`：临时回环 Snell v6 服务端与客户端，验证三种 mode、两种 reuse 状态下 TCP/UDP，不修改主机系统代理或 VPN。
- `tools/test-android-api.py --serial emulator-PORT`：已安装 APK 的鉴权、导入、原文快照、失败回滚、远程刷新、规则集 TTL 回归测试。仅允许模拟器。
- `tools/test-android-snell.py CORE --serial emulator-PORT`：Android TUN 到本机受控 Snell v6 服务端的 TCP/UDP 链路。先通过模拟器系统界面授予 VPN 权限。
- `tools/test-android-features.py --serial emulator-PORT`：独立更新、三模式实际路由、受控 Snell v6 节点失效/恢复与选择持久化；测试 CA 只加入临时配置。仅允许模拟器。
- `tools/test-android-boot.py --serial emulator-PORT`：真实模拟器重启，以及内核加载失败后恢复上次运行配置。仅允许模拟器。
- 私有真实配置验证报告保存在 `.private/`。实际节点连通性与受控协议测试分别记录，不能互相替代。

详见 [compatibility.md](compatibility.md) 和 [validation.md](validation.md)。

## 真机修正（1.14.4-surge.2）

OPPO Android 17 真机验证发现：原目标 DNS 返回错误 IP，且 `IP-CIDR,0.0.0.0/0,DIRECT,no-resolve` 会让 ChatGPT 的纯 IP 连接直连。为此增加独立的 Android 包名/域名/代理 DNS 设置，原始 Surge 文件不改。当前测试配置让 Chrome 和 ChatGPT 全部流量使用 Proxy，Google/OpenAI 域名在 IP/GEOIP 之前匹配；默认目标 DNS 使用代理内 HTTPS。节点解析器保持原配置，避免循环依赖。

这些设置可由 debug API 写入，在配置详情里可见并可移除；同一配置的源文件更新保留它们。重新导入为新配置不会自动套用。Google 三个 Snell v6 节点和网络切换测试、ChatGPT 实际回复已在真机验证，具体范围见 [validation.md](validation.md)。

## 1.14.4-surge.3：日常使用功能

- **URL 导入**：配置「＋」→「从 URL 导入」。输入名称和返回完整 Surge 文本的链接；文件导入、扫码入口保留。
- **每份配置独立更新**：远程配置详情中的「自动更新此配置」及周期只作用于该配置。新建默认 6 小时，最短 15 分钟、最长 7 天；已有周期不变。关闭后仍可点击手动更新。打开 App／启动 VPN 会异步检查已到期且开启自动更新的配置，连接先使用缓存，不等待订阅下载。Android 后台调度可能延迟。
- **更新结果**：详情显示最近检查时间及有无变化／失败。下载或转换失败保留旧配置；新配置通过静态校验但内核加载失败时，尝试恢复该配置上次成功运行的版本。不能保证任意联网故障都可据此自动回滚。
- **开机恢复**：设置 → 服务 →「开机恢复连接」。只有重启前仍在运行才恢复；主动停止后不恢复。另有系统 VPN 设置入口，可自行配置系统“始终开启 VPN”。OPPO 实测存在开机广播延迟，见下方真机记录；系统始终开启 VPN 是独立选项。
- **三模式**：启动 Surge 配置后，仪表中的模式卡可选 Rule／Global／Direct。Rule 保留源分流及 Android 修正；Global 使用 Android 指定的 policy，未指定时优先使用源 FINAL 的代理策略；Direct 优先于 Android 强制代理设置。保留 TUN 排除范围及已有 hosts/fake-IP/IPv6 策略，DNS 上游随模式变化。切换会关闭已建立的路由连接，应用需重连。
- **仅故障切换**：编辑配置 →「仅故障切换」→ 对指定 select 策略组开启。约每分钟检查，两轮失败才按源配置顺序选健康备用；不比较速度、不自动切回、不降级直连。Direct 暂停检查。首版要求 select 组至少含两个代理节点，不支持嵌套组。源配置中的 DIRECT 保留供手动选择，但不参与故障切换候选；手动选中 DIRECT 时暂停故障切换。
- **多配置隔离**：模式、所选节点和 fake-IP 缓存按配置文件身份隔离；更新同一配置保留身份。旧版共享缓存首次迁移时，可能回到默认 Rule 和组内首节点，请核对选择；后续重启会保留各自选择。

更新、模式、故障切换和开机设置均已补充 debug API，见 [debug-api.md](debug-api.md)。开发计划见 [DEVELOPMENT-2026-10-31.md](DEVELOPMENT-2026-10-31.md)。


## 1.14.4-surge.4：真机回归修正

- 重新配置自动更新任务时，使用新的计时起点，并以毫秒计算距离最早到期配置的等待时间。避免 WorkManager 保留旧入队时间，导致提前检查后又额外等待一个周期。
- 删除配置后重新计算后台调度；删除最后一份开启自动更新的远程配置后，撤销周期任务。
- OPPO Android 17 真机实际观察到：中途重新调度后，开启更新的配置在约 900.7 秒时更新，关闭更新的配置不变。测试范围与限制见 [validation.md](validation.md)。


OPPO 真机的开机广播曾被系统以 `DEFER_BY_OPLUS / PRESSURE` 延迟，解锁后观察超过 5 分钟仍未启动。因此仅打开应用内「开机恢复连接」不能保证这台手机及时自动连接。系统「始终开启的 VPN」是另一种选择，会由 Android 持续维持连接，手动停止的使用方式也会改变。

## 1.14.4-surge.5：规则下载配置兼容性

Surge GEOIP 规则现在使用 `http_client: {"detour": "DIRECT"}`，替代 sing-box 1.14 弃用、1.16 将移除的 `download_detour`。保留原来的 DIRECT 下载线路和一天更新周期。

已导入的 Surge 配置会在下一次启动或重载 VPN 时自动迁移缓存 JSON，并经内核校验后原子保存，不需要重新导入或下载订阅。原始 Surge 文本、Android 覆盖设置、缓存身份和模式规则保留；直接导入的原生 sing-box JSON 不在此次自动迁移范围内。弃用警告没有被禁用或隐藏。

## 1.14.4-surge.6：URL 图标与正式版

“从 URL 导入”入口增加链接图标，颜色和布局与其他导入入口一致。

正式版包名为 `io.github.liuw.surgebridge`，可与 `.debug` 调试版共存，配置和 VPN 授权独立，首次使用需要导入配置并授权 VPN。正式版开启 R8 优化，不包含本地 Debug API，不会覆盖调试版数据。

发布签名保存在本机 `.private/signing/surge-bridge-release.p12`，配套构建属性在 `.private/signing/release.properties`；二者均不入 Git。后续升级必须沿用这套签名，请将整个签名目录安全备份。公开仓库不包含任何签名文件；发布时必须通过本地属性提供自己的签名路径和密码。

设置好 JDK、Android SDK 和 Gradle 缓存环境后，可用以下方式重建，避免把签名密码放入命令行参数或提交到仓库：

```sh
python3 - <<'PY'
import base64, os, subprocess
from pathlib import Path
env = os.environ.copy()
env["LOCAL_PROPERTIES"] = base64.b64encode(Path(".private/signing/release.properties").read_bytes()).decode()
subprocess.run(["./gradlew", ":app:assembleOtherRelease"], env=env, check=True)
PY
```

产物位于 `app/build/outputs/apk/other/release/`。OPPO 真机使用 `arm64-v8a` 包；`x86_64` 主要供模拟器使用，`universal` 同时包含两种架构。正式版不使用调试 API，自动化诊断仍使用保留的调试版。

## 1.14.4-surge.7：修复首次启动下载 GEOIP 失败

surge.5/6 的 `http_client.detour = DIRECT` 会在没有 GEOIP 缓存、首次下载时被内核拒绝，导致 VPN 无法启动；这是应用生成配置的兼容问题，不是订阅内容错误。现在使用显式 `http_client: {"engine": "go"}`，由 HTTP 客户端直接拨号，保留原规则下载地址和更新周期。不能用空对象替代，它会触发旧的默认出口回退逻辑。

启动或重载时会迁移旧 `download_detour` 和 surge.5/6 已生成的 DIRECT detour，不修改原始 Surge 文本。明确指定的代理 HTTP 客户端和带自定义拨号选项的 direct 出口保留。原生 sing-box JSON 不自动迁移。

用同一签名覆盖安装新版后，再点击启动即可，不需要删除或重新导入现有订阅。构建产物交付到维护者指定的本地目录；该路径和本机操作约定不纳入公开仓库。

`tools/test-converter.sh` 现在还会运行真实内核的无缓存 HTTP 规则下载回归：旧配置必须复现错误，新生成和迁移配置必须实际下载成功并完成启动；测试用不可用默认代理验证规则下载不依赖最终代理出口。

## 1.14.4-surge.8：完整 review 修正

本版按一次完整代码 review 的结论修正了分流正确性、稳定性与兼容性三类问题，并经 Codex 两轮审查修正了 9 处实现缺陷（HTTP/AnyTLS 不接受 network 字段、重解析范围过宽、代理 DNS 共用、证书固定被丢弃、缓存写入无锁、离线重建无回退、BOM 处理被写坏、直连兜底拿到代理侧地址、重建结果校验未纳入回退）。

**分流正确性**

- 根因：sing-box 在任何 `resolve` 动作之后对所有出站（含 Snell）按解析出的 IP 拨号，而 Surge 始终把域名交给节点。此前只有被域名规则命中的站点能避开这个问题，其他落到代理的域名会带着本地 DNS 的结果（可能被污染或地域错误）发给节点。现在转换器分级解析：第一条 IP/GEOIP 规则前用配置 DNS 解析一次（Host 有效）；之后每条把连接送往代理策略的规则前，加一条同匹配器的重解析，经该策略专属的代理侧 DoH（`surge-proxy-dns-N`，默认 1.1.1.1，经该代理策略拨号）；代理 FINAL 前再整体重解析一次。只有真正走代理的连接才重解析，直连流量保留本地结果、不依赖代理可用；IP/GEOIP 规则在重解析后按代理侧结果重新判定，不再命中的连接继续后续规则，FINAL 为 DIRECT 时会先恢复本地解析结果。含 DIRECT 成员的策略组被手动切到 DIRECT 时，其重解析仍走公共 DoH 直连，完全本地解析请用 Direct 模式。没有 IP 规则命中的域名连接不解析，代理直接收到域名。
- 代理侧 DoH 现在是每份 Surge 配置的内置能力，每个代理策略各一个，不再依赖 debug API 写入，正式版同样具备。`dns.final` 和节点 bootstrap 解析器保持配置 DNS，GEOIP,CN 等直连判定不再因覆盖设置改走境外解析。`server-cert-fingerprint-sha256` 仍拒绝导入，不会被静默丢弃。
- 配置详情新增「Android 分流覆盖」卡片：可在界面里选择策略、填写应用包名、域名后缀和代理侧 DNS 端点，保存后即刻重载；原 debug API 入口保留。关闭最后一个故障切换组时，不再残留空的覆盖设置。
- `udp-relay` 与 Surge 默认一致：未显式写 `udp-relay=true` 的 Snell/SS/Trojan/SOCKS5 节点仅走 TCP，并给出提示。原先默认放行 UDP 与 Surge 行为不同。HTTP 和 AnyTLS 出站本身只有 TCP。

**稳定性**

- 订阅/规则集下载移到 ProfileStore 锁外执行；启动 VPN、读取配置详情不再被慢速订阅阻塞。
- 编辑器后台校验下载的规则集会写入缓存，不再每次停顿都重新下载全部过期规则集。
- 启动时的运行配置迁移失败只记录日志并沿用现有配置，不再导致 VPN 无法启动。旧版生成的配置会用缓存中的规则集离线重建（不下载），以获得新的分级解析规则；缓存缺失时退回原有的 JSON 就地迁移。
- 内核加载失败回滚后，被拒绝的编辑内容保留在编辑器中并给出提示，不再无声消失；再次保存成功后清除。
- 删除配置时同时删除运行配置及 `.source`/`.running`/`.refresh`/`.rejected` 侧文件。
- 打开 App/启动 VPN 触发的一次性到期检查不再在失败后无限重试；周期任务负责重试。

**兼容性**（以下写法以前会让整份配置导入失败，现在正常导入）

- `RULE-SET,LAN`（映射 ip_is_private）、`RULE-SET,SYSTEM`（省略并提示）、`DOMAIN-SET`、`DOMAIN-WILDCARD`、`DEST-PORT,8000-9000`、`SRC-IP` 裸地址、`CELLULAR`、`SUBNET,SSID/BSSID/TYPE`。
- `REJECT-DROP`（reject drop）、`REJECT-TINYGIF`、`REJECT-NO-DROP`。
- 规则修饰 `extended-matching`、`pre-matching`；策略组选项 `hidden`、`no-alert`、`timeout`、`evaluate-before-use`、`persistent`；HTTP/SOCKS5 位置参数 `user, pass`；节点参数 `no-error-alert`、`block-quic`、`ip-version`、`tos`、`ecn`、`interface` 等忽略并提示。
- 主配置中的 PROCESS-NAME、USER-AGENT、IP-ASN、URL-REGEX、IN-PORT、DEVICE-NAME、SCRIPT 规则与规则集一致：省略并提示。
- `[Panel]`、`[Replica]`、`[MITM]`、`[Script]`、`[URL Rewrite]` 等节整节忽略并提示；`[General]` 中未映射的键忽略并提示。`[Host]` 通配符/别名条目忽略并提示。
- 仍然拒绝：会改变连通性的未知节点参数（如 ss 的 obfs）、改变成员的策略组选项（policy-path、include-all-proxies）、`hijack-dns` 非 `*:53`、不支持的协议或 Snell 版本。

仍存在的差异：`resolve` 失败时 sing-box 直接断开连接，没有 Surge 的 `FINAL,dns-failed` 兜底；经代理侧 DoH 解析需要代理可用。详见 [compatibility.md](compatibility.md)。

## 1.14.4-surge.9：代理侧解析器不再跟随组进入 DIRECT，Outlook 排查

- 根因：surge.8 的代理侧重解析（1.1.1.1 DoH）`detour` 直接指向策略组。组选中 DIRECT 时该请求走直连，国内直连 1.1.1.1:443 超时，`resolve` 失败使连接被丢弃（真机日志 `pre-match[25] => resolve(surge-proxy-dns-0): dial tcp 1.1.1.1:443: i/o timeout`）。
- 否决的方案：改用配置文件自身 DNS 经代理拨号。真机验证 AliDNS 经 LAX 节点对 Google 返回污染结果 104.244.42.197，且该结果会在解析器缓存中沿用到 TTL 到期，不可接受。
- 修正：为每个可选 DIRECT 的策略组生成隐藏 `selector` `surge-resolver-<组名>`（成员去掉 DIRECT，嵌套组递归替换为镜像），解析器经镜像拨号；VPN 服务内的分组订阅把镜像同步到用户所选节点，选中 DIRECT 时保留上一个节点，此时经解析的连接会直连到节点侧地址（Direct 模式仍是完全本地解析）。镜像不出现在分组界面、故障切换候选和覆盖策略里；只按组成员递归（不含 underlying-proxy 链），无代理出口的分支被剔除，`surge-` 开头的策略名保留给内部对象。
- 明文 DNS 的 `ip:port`、`[v6]:port`、`%zone` 现在正确拆分；DoH URL 带查询参数会被拒绝（sing-box 只设置请求路径）。
- 快照代数升至 3：已安装设备在下次 VPN 启动时用缓存规则集离线重建运行配置，使修正立即生效，不必等订阅刷新。
- 调试版新增 `GET /v1/connections`（`tools/android-debug.py connections <file>`）：每条连接的上下行字节、域名、uid、规则与出站，用于判断应用请求是否拿到响应。
- Outlook 同步排查结论见 `docs/validation.md`：代理数据通路对所有合成请求（大上传、大下载、并发、HTTP/1.1、100 秒空闲复用、微软认证接口 401）均正常；Outlook 自身经 LAX 01/LAX 03、Rule 或 Global 模式的每条 office365 连接都只收到约 7.5 KB（握手加小响应）后反复重开连接，Direct 模式下出现 60 KB/32 KB 的真实数据交换。这不是分流错误，指向微软对该账号经美国机房出口的请求做了拒绝或挑战，需要用户确认 Outlook 界面提示与 iOS/macOS Surge 同节点下的行为。

## Google Play 能浏览但下载卡住

商店接口与 APK 下载、下载协商和连通性探测可能命中不同规则。实测某份配置的 ChinaMax / GEOIP 直连规则让部分 Play 请求走直连并超时；仅代理 APK CDN 域名仍不足以完成下载。这些规则被如实转换，不应通过转换器偷偷绕过用户的 DIRECT 策略。

需要 Play 全部使用代理时，在当前配置的「Android 分流覆盖」中选择代理策略（例如 `Proxy`），应用包名填写 `com.android.vending`。已有的 `xn--ngstr-lra8j.com` 域名后缀覆盖可以保留。无需填写 DNS 覆盖，保持 Rule 模式并保存；若旧下载仍在退避等待，可取消后重试。确保策略组实际选中可用代理节点。

这是按配置保存的 Android 本机设置，不改动 Surge 原文，其他应用继续按原规则分流。2026-10-31 真机验证了 Gmail 完整下载安装，过程保持 Rule 模式；成功阶段采样的 32 条 Play 出站日志均为 Snell。具体规则归因和测试范围见 [validation.md](validation.md#follow-up-exact-rule-attribution-and-completed-rule-mode-download)。
