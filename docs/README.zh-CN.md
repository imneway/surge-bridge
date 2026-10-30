# Surge Bridge 开发说明

这是基于 SagerNet/sing-box-for-android 的 Android 分支，目标是直接导入、订阅更新和编辑同一份 Surge `.conf`。界面保留原始 Surge 源文件，VPN 内核读取经过校验的 sing-box JSON。原 FlClash 仓库保留作参考，未删除。

## 基线与构建

- SFA 发布基线：1.14.4，`f87c2ebdb7e34754be751bb5e58926db2fed8f8c`。
- sing-box 内核：v1.14.4，`c005f35300ce76427dc285481ec5695f5b179cd2`；gomobile v0.1.12。
- 分支：`feat/surge-profiles`。保留 upstream Git 历史和 GPL-3.0 许可证。
- 调试包名：`io.github.liuw.surgebridge.debug`；应用名 Surge Bridge，与官方 SFA 并存。
- 本分支构建 ARM64 和 x86_64；没有 32 位 APK。使用系统默认 debug 签名，升级须保留同一 debug keystore。
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

订阅文本的规则集按自身 `update-interval` 缓存，只有下一次配置刷新时才检查到期；这并不是每个规则集独立后台计时器。详情中展示不能等价转换的项目。更新为新配置后若服务重载失败，文件已通过静态校验但不代表运行成功，应查看服务状态/日志。

## 自动调试

见 [debug-api.md](debug-api.md)。API 只在 debug 变体启动；release 使用空实现。只监听 Android `127.0.0.1:19090`，每次应用进程启动生成新的 bearer token，电脑经 `adb forward` 和 `run-as` 访问。API 不返回源配置、PSK、订阅 URL 或配置解析器原始错误。新增的 core-logs 接口会包含访问目标、节点标签等敏感元数据，保存后需脱敏再分享。

```sh
python3 tools/android-debug.py install app/build/outputs/apk/other/debug/SurgeBridge-1.14.4-surge.2-arm64-v8a-debug.apk
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
- 私有真实配置验证报告保存在 `.private/`。实际节点连通性与受控协议测试分别记录，不能互相替代。

详见 [compatibility.md](compatibility.md) 和 [validation.md](validation.md)。

## 真机修正（1.14.4-surge.2）

OPPO Android 17 真机验证发现：原目标 DNS 返回错误 IP，且 `IP-CIDR,0.0.0.0/0,DIRECT,no-resolve` 会让 ChatGPT 的纯 IP 连接直连。为此增加独立的 Android 包名/域名/代理 DNS 设置，原始 Surge 文件不改。当前测试配置让 Chrome 和 ChatGPT 全部流量使用 Proxy，Google/OpenAI 域名在 IP/GEOIP 之前匹配；默认目标 DNS 使用代理内 HTTPS。节点解析器保持原配置，避免循环依赖。

这些设置可由 debug API 写入，在配置详情里可见并可移除；同一配置的源文件更新保留它们。重新导入为新配置不会自动套用。Google 三个 Snell v6 节点和网络切换测试、ChatGPT 实际回复已在真机验证，具体范围见 [validation.md](validation.md)。
