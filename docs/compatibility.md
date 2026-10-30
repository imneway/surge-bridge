# Surge / Mihomo / Surge Bridge 配置兼容范围

比较基于 Surge 官方格式、Mihomo 文档及最初 FlClash 检出的固定内核；不是对未来所有版本的兼容声明。选择 SFA 的关键是可构建的 sing-box 1.14.4 原生 Snell v6 实现。初始 FlClash 固定 Mihomo 内核接受 Snell 4/5 兼容路径，不接受 version=6。

| Surge feature | Mihomo 对应能力 | 此分支映射/限制 |
|---|---|---|
| Snell v6 | 初始固定内核不支持 | 原生 snell version=6；default/unshaped/unsafe-raw；TCP/UDP/reuse；未填 mode 使用 default，不自动尝试降级 |
| Snell v4 / v5 | v4 兼容 | v5 映射 v4 兼容并提示；不提供 v5 QUIC Proxy Mode；仅 HTTP obfs |
| Shadowsocks、Trojan、HTTP(S)、SOCKS5、AnyTLS | 有对应出站 | 基础认证/TLS 参数；未知参数显式拒绝。HTTP/SOCKS 目前要求 username=/password= 形式 |
| select / url-test | select / url-test | selector / urltest；支持 URL/interval/tolerance；策略引用和环检查 |
| fallback/load-balance、远程策略组 | 有相近能力 | 尚不支持，主配置导入报错 |
| DOMAIN/SUFFIX/KEYWORD | 同类规则 | 保留顺序及目标策略 |
| IP-CIDR/IP-CIDR6、no-resolve | 同类规则 | 不带 no-resolve 时插入解析动作；解析失败和 Surge 的 FINAL,dns-failed 语义存在差异 |
| AND/OR/NOT、DEST-PORT/SRC-PORT、SRC-IP、TCP/UDP | 同类逻辑/匹配 | 嵌套逻辑规则，最大深度 16；复杂子条件触发 DNS 的时机可能与 Surge 短路求值不同 |
| RULE-SET URL | rule-providers | 下载并转为索引化 inline rule-set；TTL 缓存；每个集最多 20 万条、单响应最多 16 MiB；Android 下载使用 HTTPS，debug 额外允许回环 HTTP 测试 |
| 外部规则集内的 PROCESS-NAME、USER-AGENT、IP-ASN | 平台/数据支持不同 | 当前省略，并按类型和数量提示，匹配会落到后续规则；主配置中出现则拒绝导入。逻辑规则包含不支持的子条件时省略整条 |
| 外部规则集带重复策略列 | 标准格式不同 | 仅当列值与外层策略一致时移除并提示；不接受冲突策略 |
| GEOIP | GEOIP/geodata | SagerNet country SRS；数据范围与 Surge 不完全相同；首次运行需能下载规则 |
| DIRECT / REJECT / FINAL | 对应路由/兜底 | direct 出站、reject action、route.final |
| dns-server / encrypted-dns-server | DNS nameserver | UDP/local、HTTPS/H3/TLS/QUIC；保留所有端点但只选择第一项；未实现 Surge DNS 并发竞速/故障切换 |
| Host 精确域名→IP | hosts | DNS hosts server；不支持通配符/别名 |
| ipv6=false | IPv6 控制 | IPv4 TUN、拒绝 AAAA、IPv4 resolver strategy |
| tun-excluded-routes / hijack-dns | TUN exclusions/DNS hijack | 路由排除及 DNS 劫持；Android VPN 路由最终受系统约束 |
| skip-proxy | HTTP 代理 bypass | 仅展示提示；Android 使用 TUN，需依赖 tun-excluded-routes 和 DIRECT 规则 |
| test-timeout / internet-test-url | 相近测试配置 | 当前仅提示，不改变内核超时或 Android 系统探测 |
| Script/MITM/URL Rewrite/Header Rewrite | 无等价完整语义 | 不支持，拒绝导入 |

域名分流使用 fake-IP 恢复域名，并由内核在需要时解析真实 IP。原始域名/IP 规则顺序保持；外部同一策略规则集内部按“不需要 DNS”和“需要 DNS”两组建立索引。这允许域名命中先返回，但与 Surge 精确的 DNS 失败/竞速行为并非完全等价。

参考：

- [Surge 外部规则集格式及非法行行为](https://manual.nssurge.com/rules/ruleset.html)
- [Surge 逻辑规则](https://manual.nssurge.com/rules/logical.html)
- [sing-box Snell](https://sing-box.sagernet.org/configuration/outbound/snell/)
- [sing-box rule-set](https://sing-box.sagernet.org/configuration/rule-set/)
- [Mihomo Snell](https://wiki.metacubex.one/config/proxies/snell/)
