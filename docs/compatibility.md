# Surge / Mihomo / Surge Bridge 配置兼容范围

**以下表格描述转换能力，不等于全部已实测可用。代理协议的端到端验证目前仅覆盖 Snell v6；其他协议尚未完成对应服务端的端到端验证。** 真机覆盖主要是一台 OPPO 手机，另有模拟器和受控内核测试；详见 [验证记录](validation.md)。

比较基于 Surge 官方格式、Mihomo 文档及最初 FlClash 检出的固定内核；不是对未来所有版本的兼容声明。选择 SFA 的关键是可构建的 sing-box 1.14.4 原生 Snell v6 实现。初始 FlClash 固定 Mihomo 内核接受 Snell 4/5 兼容路径，不接受 version=6。

| Surge feature | Mihomo 对应能力 | 此分支映射/限制 |
|---|---|---|
| Snell v6 | 初始固定内核不支持 | 原生 snell version=6；default/unshaped/unsafe-raw；TCP/UDP/reuse；未填 mode 使用 default，不自动尝试降级 |
| Snell v4 / v5 | v4 兼容 | v5 映射 v4 兼容并提示；不提供 v5 QUIC Proxy Mode；仅 HTTP obfs |
| Shadowsocks、Trojan、HTTP(S)、SOCKS5、AnyTLS | 有对应出站 | 基础认证/TLS 参数；HTTP/SOCKS 支持 username=/password= 和位置参数两种写法。影响连通性的未知参数仍拒绝；纯客户端调节项（no-error-alert、block-quic、ip-version、tos、ecn、interface 等）忽略并提示。udp-relay 未显式为 true 时与 Surge 一致仅走 TCP |
| select / url-test | select / url-test | selector / urltest；支持 URL/interval/tolerance；策略引用和环检查。hidden、no-alert、timeout、evaluate-before-use、persistent 忽略并提示；policy-path/include-all-proxies 等改变成员的选项仍拒绝 |
| fallback/load-balance、远程策略组 | 有相近能力 | 尚不支持，主配置导入报错 |
| DOMAIN/SUFFIX/KEYWORD/WILDCARD、DOMAIN-SET | 同类规则 | 保留顺序及目标策略；DOMAIN-WILDCARD 转为正则；DOMAIN-SET 下载后按前导点区分后缀/精确匹配并建立索引 |
| IP-CIDR/IP-CIDR6、no-resolve | 同类规则 | 不带 no-resolve 时插入解析动作，按目标策略分级：DIRECT/REJECT 规则前用配置 DNS（含 Host），代理策略规则和代理 FINAL 前改用代理侧 HTTPS DNS 重新解析（见下文）。解析失败和 Surge 的 FINAL,dns-failed 语义存在差异 |
| AND/OR/NOT、DEST-PORT/SRC-PORT（含范围）、SRC-IP、PROTOCOL、CELLULAR、SUBNET | 同类逻辑/匹配 | 嵌套逻辑规则，最大深度 16；端口范围映射 port_range；CELLULAR/SUBNET,TYPE 映射 network_type，SUBNET,SSID/BSSID 映射 wifi_ssid/wifi_bssid；复杂子条件触发 DNS 的时机可能与 Surge 短路求值不同。extended-matching/pre-matching 修饰忽略并提示 |
| RULE-SET URL | rule-providers | 下载并转为索引化 inline rule-set；TTL 缓存；每个集最多 20 万条、单响应最多 16 MiB；Android 下载使用 HTTPS，debug 额外允许回环 HTTP 测试 |
| PROCESS-NAME、USER-AGENT、IP-ASN、URL-REGEX、IN-PORT、DEVICE-NAME、SCRIPT | 平台/数据支持不同 | 主配置和外部规则集中均省略并提示，匹配落到后续规则（与 Surge 在无法匹配的平台上行为一致）。逻辑规则包含这类子条件时省略整条。RULE-SET,LAN 映射 ip_is_private；RULE-SET,SYSTEM 省略并提示 |
| 外部规则集带重复策略列 | 标准格式不同 | 仅当列值与外层策略一致时移除并提示；不接受冲突策略 |
| GEOIP | GEOIP/geodata | SagerNet country SRS；数据范围与 Surge 不完全相同；首次运行需能下载规则 |
| DIRECT / REJECT / REJECT-DROP / REJECT-TINYGIF / REJECT-NO-DROP / FINAL | 对应路由/兜底 | direct 出站、reject action（REJECT-DROP 使用 drop）、route.final |
| dns-server / encrypted-dns-server | DNS nameserver | UDP/local、HTTPS/H3/TLS/QUIC；保留所有端点但只选择第一项；未实现 Surge DNS 并发竞速/故障切换 |
| Host 精确域名→IP | hosts | DNS hosts server；通配符/别名条目忽略并提示 |
| ipv6=false | IPv6 控制 | IPv4 TUN、拒绝 AAAA、IPv4 resolver strategy |
| tun-excluded-routes / hijack-dns | TUN exclusions/DNS hijack | 路由排除及 DNS 劫持；Android VPN 路由最终受系统约束 |
| skip-proxy | HTTP 代理 bypass | 仅展示提示；Android 使用 TUN，需依赖 tun-excluded-routes 和 DIRECT 规则 |
| test-timeout / internet-test-url | 相近测试配置 | 当前仅提示，不改变内核超时或 Android 系统探测 |
| Script/MITM/URL Rewrite/Header Rewrite/Panel/Replica 等节 | 无等价完整语义 | 整节忽略并提示，不影响分流；[General] 中未映射的键同样忽略并提示（hijack-dns 非 *:53 仍拒绝） |

## 解析与代理拿到的目标地址（surge.8）

sing-box 在任何 `resolve` 动作之后都会按解析出的 IP 拨号，包括 Snell 等代理出站（`route/conn.go`），而 Surge 始终把域名交给节点。因此转换器分级解析：第一条需要解析的 IP/GEOIP 规则前插入一次本地 `resolve`（配置 DNS，Host 有效，与 Surge 相同）；此后任何把连接路由到代理策略的规则，前面都加一条**同匹配器**的 `resolve`，经该策略专属的 `surge-proxy-dns-N`（1.1.1.1 DoH，经该策略当前所选节点拨号）重新解析，只影响真正命中该规则的连接；代理 FINAL 前再加一条无匹配器的重解析。直连流量始终保留本地结果，不依赖代理可用性。IP/GEOIP 规则的重解析会用代理侧结果重新判定该规则：不再命中的连接继续后续规则（代理视角求值，与 Surge 按本地结果一次判定不同）；FINAL 为 DIRECT 时在末尾再恢复一次本地解析。含 DIRECT 成员的策略组被手动切到 DIRECT 时，重解析仍走公共 DoH 直连；完全本地解析请使用 Direct 模式。没有任何 IP 规则命中之前的域名连接不会被解析，代理直接收到域名。内核 1.14 的 DNS 缓存按服务器隔离。Android 覆盖设置中的 `dns` 字段替换所有代理侧解析器的端点，各自保持自己的 detour；若配置从不解析走代理的流量，该字段无效并给出提示。

域名分流使用 fake-IP 恢复域名，并由内核在需要时解析真实 IP。原始域名/IP 规则顺序保持；外部同一策略规则集内部按“不需要 DNS”和“需要 DNS”两组建立索引。这允许域名命中先返回，但与 Surge 精确的 DNS 失败/竞速行为并非完全等价。

Android 可选覆盖设置独立于 Surge 原文保存，并随同一配置的编辑和订阅刷新保留。包名与域名规则置于原规则之前（DNS 劫持除外）；可让 ChatGPT/Chrome 的纯 IP 连接也使用指定策略，并避免 Google/OpenAI 域名在 GEOIP 阶段被提前解析为错误 IP。配置详情展示覆盖范围并支持移除。可选的代理 HTTPS DNS 覆盖会替换上述代理侧解析端点，但不替换节点启动解析器；详见 [debug-api.md](debug-api.md)。

**组选中 DIRECT 时的解析出口（surge.9）**：`detour` 指向策略组时会跟随用户选择，一旦选中 DIRECT，1.1.1.1:443 从国内直连超时，`resolve` 失败会丢弃连接（真机复现：`dial tcp 1.1.1.1:443: i/o timeout`，Outlook 在该状态下零连接建立）。换用国内可达的解析器不可行：真机验证 AliDNS 经 LAX 节点查询被封锁域名仍返回污染结果（Google 解析到 104.244.42.197），而解析器缓存按服务器标签共享，切换节点后污染答案会沿用到 TTL 到期。因此转换器为每个（递归）可选 DIRECT 的策略组生成隐藏 `selector` `surge-resolver-<组名>`，成员为去掉 DIRECT 的成员（嵌套组替换为其镜像），解析器 `detour` 指向镜像；应用在 VPN 服务内订阅分组变化，把镜像保持在用户为该组所选的节点上（选中 DIRECT 时保留上一个节点）。镜像在分组界面、故障切换候选与覆盖策略中均被隐藏。镜像只按策略组成员递归（节点的 underlying-proxy 链不参与），没有任何代理出口的分支（如 `D=select,DIRECT`）不进入镜像，且这类策略不再触发代理侧重解析；以 `surge-` 开头的策略名被保留给内部对象并拒绝。明文 DNS 的 `ip:port`、`[v6]:port`、带 %zone 的 IPv6 现在正确拆分；DoH URL 带查询参数会被拒绝，因为 sing-box 只设置请求路径。

参考：

- [Surge 外部规则集格式及非法行行为](https://manual.nssurge.com/rules/ruleset.html)
- [Surge 逻辑规则](https://manual.nssurge.com/rules/logical.html)
- [sing-box Snell](https://sing-box.sagernet.org/configuration/outbound/snell/)
- [sing-box rule-set](https://sing-box.sagernet.org/configuration/rule-set/)
- [Mihomo Snell](https://wiki.metacubex.one/config/proxies/snell/)

1.14.4-surge.3 adds Android mode rules after conversion: DNS interception remains first; Direct/Global precede Android package/domain overrides and source rules. Global uses the configured Android policy, otherwise a proxy FINAL policy, selector or first proxy. Source TUN exclusions still apply outside the VPN. Mode and node cache identities are per profile, not per source text. Failure-only switching is opt-in for leaf-only select groups; imported `url-test` semantics are unchanged, and Surge `fallback` syntax is still unsupported.
