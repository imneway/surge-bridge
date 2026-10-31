import io.nekohasekai.sfa.surge.AndroidRoutingOverrides
import io.nekohasekai.sfa.surge.AndroidRuntimeFeatures
import io.nekohasekai.sfa.surge.SurgeConverter
import io.nekohasekai.sfa.surge.SurgeException
import org.json.JSONObject
import java.io.File

fun main(args: Array<String>) {
    var checks = 0
    fun expect(value: Boolean, name: String) { check(value) { name }; checks++ }
    val example = File("test/surge/example.conf").readText()
    fun converter() = SurgeConverter { url, ttl ->
        check(ttl == 86400)
        when {
            url.endsWith("domains.list") -> "DOMAIN-SUFFIX,example.org\nDOMAIN-SUFFIX,example.edu,Proxy\nIP-ASN,64512,no-resolve\nPROCESS-NAME,example.exe\n"
            url.endsWith("set.txt") -> "# comment\n.example.net\nexact.example.com\n"
            else -> (1..10000).joinToString("\n") { "DOMAIN-SUFFIX,host$it.example.cn" } + "\nIP-CIDR,198.51.100.0/24,no-resolve\nUSER-AGENT,Test*"
        }
    }
    fun rulesOf(json: String) = JSONObject(json).getJSONObject("route").getJSONArray("rules").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    val result = converter().convert(example)
    val c = JSONObject(result.json)
    val snell = c.getJSONArray("outbounds").getJSONObject(1)
    expect(snell.getInt("version") == 6 && snell.getString("mode") == "default", "v6 remains v6; absent mode defaults")
    expect(snell.getString("psk") == "test-key,with#punctuation", "quoted credentials preserved")
    expect(snell.getBoolean("tcp_fast_open"), "TFO retained")
    expect(snell.getString("network") == "tcp" && result.warnings.any { "udp-relay=true" in it }, "Surge default udp-relay=false maps to TCP only with a notice")
    expect(!c.getJSONArray("outbounds").getJSONObject(2).has("network"), "udp-relay=true keeps UDP")
    expect(c.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("type") == "h3", "DoH3 exact native transport")
    val rules = rulesOf(result.json)
    val logical = rules.first { it.optString("type") == "logical" }
    expect(logical.getString("mode") == "and" && logical.getJSONArray("rules").length() == 2, "AND structure")
    expect(rules.size < 24, "large sets use indexed rules")
    val sets = c.getJSONObject("route").getJSONArray("rule_set")
    expect((0 until sets.length()).any { sets.getJSONObject(it).optJSONArray("rules")?.let { rs ->
        (0 until rs.length()).any { rs.getJSONObject(it).optJSONArray("domain_suffix")?.length() == 10000 }
    } == true }, "all domain entries retained")
    val geo = (0 until sets.length()).map { sets.getJSONObject(it) }.first { it.optString("type") == "remote" }
    expect(!geo.has("download_detour") && geo.getJSONObject("http_client").similar(JSONObject("""{"engine":"go"}""")), "GEOIP downloads use explicit modern direct HTTP client")
    expect(geo.getString("update_interval") == "1d", "GEOIP update interval preserved")
    expect(result.warnings.any { "IP-ASN are omitted" in it } && result.warnings.any { "USER-AGENT are omitted" in it }, "coverage gaps visible")
    expect(result.warnings.any { "redundant per-entry policies" in it }, "nonstandard per-entry policies visible")
    expect(converter().convert(example).json == result.json, "deterministic conversion")
    expect(converter().convert("{\"outbounds\":[]}").sourceFormat == "sing-box", "native JSON passthrough")
    expect(!SurgeConverter.isSurge("[]"), "JSON array is not a Surge section")

    // Two-stage resolution: DIRECT/REJECT IP rules resolve locally, proxy-bound traffic re-resolves via the proxy.
    val staged = converter().convert("[Proxy]\np=snell,192.0.2.1,443,psk=x,version=6\n[Rule]\nIP-CIDR,10.0.0.0/8,DIRECT\nIP-CIDR,172.16.0.0/12,DIRECT\nGEOIP,CN,DIRECT\nFINAL,p")
    val stagedRules = rulesOf(staged.json)
    val resolves = stagedRules.filter { it.optString("action") == "resolve" }
    expect(resolves.size == 2, "consecutive local IP rules share one resolve; one proxy re-resolve before FINAL")
    expect(!resolves[0].has("server") && resolves[1].getString("server").startsWith(SurgeConverter.PROXY_DNS), "local resolve follows DNS rules; proxy resolve names its server")
    expect(stagedRules.last().optString("action") == "resolve" && stagedRules.last().length() == 3, "fall-through to a proxy FINAL never carries locally resolved addresses")
    val stagedDns = JSONObject(staged.json).getJSONObject("dns")
    val proxyDns = (0 until stagedDns.getJSONArray("servers").length()).map { stagedDns.getJSONArray("servers").getJSONObject(it) }.first { it.optString("tag").startsWith(SurgeConverter.PROXY_DNS) }
    expect(proxyDns.getString("detour") == "p" && proxyDns.getString("server") == "1.1.1.1", "proxy-side DoH dials through the FINAL proxy")
    expect(!stagedDns.has("independent_cache") && stagedDns.getString("final") == SurgeConverter.LOCAL_DNS, "no deprecated cache option; profile DNS stays final")
    val mixed = converter().convert("[Proxy]\np=snell,192.0.2.1,443,psk=x,version=6\nq=snell,192.0.2.2,443,psk=x,version=6\n[Rule]\nGEOIP,CN,DIRECT\nDOMAIN-SUFFIX,google.com,p\nGEOIP,US,q\nIP-CIDR,203.0.113.0/24,DIRECT\nFINAL,DIRECT")
    val mixedRules = rulesOf(mixed.json)
    val scoped = mixedRules.first { it.optString("action") == "resolve" && it.has("domain_suffix") }
    expect(scoped.getJSONArray("domain_suffix").getString(0) == "google.com" && mixedRules.indexOf(scoped) + 1 == mixedRules.indexOfFirst { it.has("domain_suffix") && it.optString("outbound") == "p" }, "re-resolve is scoped to the matcher and directly precedes the proxy route")
    expect(mixedRules.count { it.optString("action") == "resolve" && !it.has("server") } == 2 && mixedRules.last().length() == 2 && mixedRules.last().optString("action") == "resolve", "one local resolve serves every IP rule; DIRECT fall-through restores local answers after a proxy re-resolve")
    val geoipRe = mixedRules.first { it.optString("action") == "resolve" && it.has("rule_set") }
    expect(geoipRe.getJSONArray("rule_set").getString(0) == "surge-geoip-us", "proxy IP rules re-resolve only their own matches")
    expect(mixedRules.none { it.optString("action") == "resolve" && it.has("server") && it.length() == 3 }, "no whole-traffic proxy re-resolve when FINAL is DIRECT")
    expect(mixed.warnings.any { "re-evaluated with the proxy-side answer" in it }, "proxy-view re-evaluation disclosed")
    val domainOnlyProxy = converter().convert("[Proxy]\np=snell,192.0.2.1,443,psk=x,version=6\n[Proxy Group]\nG=select,p,DIRECT\n[Rule]\nGEOIP,CN,DIRECT\nDOMAIN-SUFFIX,google.com,G\nFINAL,DIRECT")
    expect(rulesOf(domainOnlyProxy.json).last().optString("outbound") == "G" && domainOnlyProxy.warnings.any { "has DIRECT selected" in it }, "domain-scoped re-resolve needs no DIRECT restore; DIRECT-capable groups are disclosed")
    expect(SurgeConverter.isSurge("\uFEFF[General]\nloglevel=notify") && converter().convert("\uFEFF" + example).json == result.json, "UTF-8 BOM sources convert identically")
    val mixedServers = JSONObject(mixed.json).getJSONObject("dns").getJSONArray("servers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { it.optString("tag").startsWith(SurgeConverter.PROXY_DNS) }
    expect(mixedServers.map { it.getString("detour") }.toSet() == setOf("p", "q") && scoped.getString("server") == mixedServers.first { it.getString("detour") == "p" }.getString("tag") && geoipRe.getString("server") == mixedServers.first { it.getString("detour") == "q" }.getString("tag"), "each proxy policy resolves through its own detour")
    fun proxyDnsOf(r: SurgeConverter.Result) = JSONObject(r.json).getJSONObject("dns").getJSONArray("servers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.optString("tag").startsWith(SurgeConverter.PROXY_DNS) }
    fun outboundsOf(r: SurgeConverter.Result) = JSONObject(r.json).getJSONArray("outbounds").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    fun dnsProfile(general: String) = converter().convert("[General]\n$general\n[Proxy]\np=snell,192.0.2.1,443,psk=x,version=6\n[Rule]\nGEOIP,CN,DIRECT\nFINAL,p")
    val withDirect = converter().convert("[Proxy]\np=snell,192.0.2.1,443,psk=x,version=6\nq=snell,192.0.2.2,443,psk=x,version=6\n[Proxy Group]\nG=select,p,q,DIRECT\nH=select,G,p\n[Rule]\nGEOIP,CN,DIRECT\nDOMAIN-SUFFIX,example.com,H\nFINAL,G")
    val mirrorG = outboundsOf(withDirect).first { it.getString("tag") == "${SurgeConverter.RESOLVER_GROUP}-G" }
    val mirrorH = outboundsOf(withDirect).first { it.getString("tag") == "${SurgeConverter.RESOLVER_GROUP}-H" }
    expect(mirrorG.getString("type") == "selector" && mirrorG.getJSONArray("outbounds").let { it.length() == 2 && it.getString(0) == "p" && it.getString(1) == "q" }, "DIRECT-capable group gets a hidden mirror without DIRECT")
    expect(mirrorH.getJSONArray("outbounds").let { it.getString(0) == "${SurgeConverter.RESOLVER_GROUP}-G" && it.getString(1) == "p" }, "nested DIRECT-capable members are mirrored recursively")
    val withDirectDns = JSONObject(withDirect.json).getJSONObject("dns").getJSONArray("servers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { it.optString("tag").startsWith(SurgeConverter.PROXY_DNS) }
    expect(withDirectDns.map { it.getString("detour") }.toSet() == setOf("${SurgeConverter.RESOLVER_GROUP}-G", "${SurgeConverter.RESOLVER_GROUP}-H") && withDirectDns.all { it.getString("server") == "1.1.1.1" }, "proxy-side resolvers dial through the mirrors, never through a DIRECT-capable group")
    expect(withDirect.warnings.any { "hidden group" in it && "DIRECT selected" in it }, "mirror behavior disclosed")
    expect(proxyDnsOf(mixed).getString("detour") == "p" && outboundsOf(mixed).none { it.getString("tag").startsWith(SurgeConverter.RESOLVER_GROUP) }, "policies that cannot select DIRECT need no mirror")
    expect(AndroidRuntimeFeatures.eligibleGroups(JSONObject(withDirect.json)) == listOf("G") && runCatching { AndroidRoutingOverrides.apply(withDirect, JSONObject("""{"policy":"${SurgeConverter.RESOLVER_GROUP}-G"}""")) }.isFailure, "mirrors are neither failover candidates nor override policies")
    val chained = converter().convert("[Proxy]\nq=snell,192.0.2.2,443,psk=x,version=6\np=snell,192.0.2.1,443,psk=x,version=6,underlying-proxy=U\n[Proxy Group]\nU=select,q,DIRECT\nD=select,DIRECT\nG=select,p,D,DIRECT\n[Rule]\nGEOIP,CN,DIRECT\nFINAL,G")
    val chainedMirror = outboundsOf(chained).first { it.getString("tag") == "${SurgeConverter.RESOLVER_GROUP}-G" }
    expect(chainedMirror.getJSONArray("outbounds").let { it.length() == 1 && it.getString(0) == "p" } && outboundsOf(chained).none { it.getString("tag") == "${SurgeConverter.RESOLVER_GROUP}-p" || it.getString("tag") == "${SurgeConverter.RESOLVER_GROUP}-U" }, "mirrors keep the chained node itself and drop branches without a proxy exit")
    val directOnlyGroup = converter().convert("[Proxy]\np=snell,192.0.2.1,443,psk=x,version=6\n[Proxy Group]\nD=select,DIRECT\n[Rule]\nGEOIP,CN,DIRECT\nDOMAIN-SUFFIX,example.com,p\nFINAL,D")
    expect(rulesOf(directOnlyGroup.json).none { it.optString("action") == "resolve" && it.has("server") && it.length() == 3 } && !directOnlyGroup.json.contains("${SurgeConverter.RESOLVER_GROUP}-D"), "a group without any proxy exit is not re-resolved through a proxy")
    expect(runCatching { converter().convert("[Proxy]\nsurge-resolver-G=snell,192.0.2.1,443,psk=x,version=6\n[Rule]\nFINAL,surge-resolver-G") }.isFailure, "the surge- policy namespace is reserved")
    val directOnlyFinal = converter().convert("[Proxy]\np=snell,192.0.2.1,443,psk=x,version=6\n[Proxy Group]\nD=select,DIRECT\n[Rule]\nGEOIP,CN,DIRECT\nGEOIP,US,p\nFINAL,D")
    expect(rulesOf(directOnlyFinal.json).last().let { it.optString("action") == "resolve" && !it.has("server") } && rulesOf(directOnlyFinal.json).count { it.optString("action") == "resolve" && it.has("server") } == 1, "a DIRECT-only FINAL group restores local answers after a proxy re-resolve, like FINAL,DIRECT")
    expect(runCatching { converter().convert("[Proxy]\np=snell,192.0.2.1,443,psk=x,version=6\n[Proxy Group]\nSurge-Resolver-X=select,p\n[Rule]\nFINAL,p") }.isFailure, "the reserved namespace is case-insensitive and covers groups")
    val portDns = dnsProfile("dns-server=1.1.1.1:5353")
    val portServers = JSONObject(portDns.json).getJSONObject("dns").getJSONArray("servers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    expect(portServers.first { it.getString("tag") == SurgeConverter.LOCAL_DNS }.let { it.getString("server") == "1.1.1.1" && it.getInt("server_port") == 5353 }, "ip:port DNS entries split into server and server_port")
    expect(dnsProfile("dns-server=::ffff:1.1.1.1, fe80::1%wlan0, [2001:db8::1]:5353").json.let { JSONObject(it).getJSONObject("dns").getJSONArray("servers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.let { servers -> servers.first { it.getString("tag") == SurgeConverter.LOCAL_DNS }.getString("server") == "::ffff:1.1.1.1" && servers.first { it.getString("tag") == "surge-dns-1" }.getString("server") == "fe80::1%wlan0" && servers.first { it.getString("tag") == "surge-dns-2" }.let { it.getString("server") == "2001:db8::1" && it.getInt("server_port") == 5353 } }, "IPv6 DNS literals keep embedded IPv4 and zone suffixes")
    expect(runCatching { dnsProfile("dns-server=") }.isFailure && runCatching { dnsProfile("dns-server=1.1.1.1:0") }.isFailure && runCatching { dnsProfile("encrypted-dns-server=https://dns.example.com/dns-query?token=1") }.isFailure, "empty entries, bad ports and DoH query parameters are rejected")
    val directOnly = converter().convert("[Rule]\nGEOIP,CN,DIRECT\nFINAL,DIRECT")
    expect(!directOnly.json.contains(SurgeConverter.PROXY_DNS), "no proxy resolver without proxies")
    val noResolve = converter().convert("[Proxy]\np=snell,192.0.2.1,443,psk=x,version=6\n[Rule]\nIP-CIDR,0.0.0.0/0,DIRECT,no-resolve\nFINAL,p")
    expect(!noResolve.json.contains("\"action\": \"resolve\""), "no-resolve never forces DNS; proxies receive hostnames")

    // Compatibility: common Surge syntax must not fail the whole import.
    val base = "[Proxy]\nA=snell,192.0.2.1,443,version=6,psk=k,udp-relay=true\n[Proxy Group]\nP=select,A,hidden=1,no-alert=0\n[Rule]\n"
    val compat = converter().convert("[General]\nallow-wifi-access = false\nhttp-listen = 0.0.0.0:6152\n" + base +
        "RULE-SET,LAN,DIRECT\nRULE-SET,SYSTEM,DIRECT\nDOMAIN-SET,https://example.invalid/set.txt,P\nDOMAIN,ad.example,REJECT-DROP\nDOMAIN,gif.example,REJECT-TINYGIF\n" +
        "DOMAIN-SUFFIX,x.example,P,extended-matching\nDEST-PORT,8000-9000,P\nSRC-IP,192.168.1.10,DIRECT\nDOMAIN-WILDCARD,*.cdn-?.example,P\nCELLULAR,P\nSUBNET,SSID:Home,DIRECT\nPROCESS-NAME,Telegram,P\nFINAL,P\n[Panel]\nx = title=1\n[MITM]\nhostname = *.example")
    val compatRules = rulesOf(compat.json)
    expect(compatRules.any { it.optBoolean("ip_is_private") && it.getString("outbound") == "DIRECT" }, "RULE-SET,LAN maps to private IP matching")
    expect(compat.warnings.any { "RULE-SET,SYSTEM" in it }, "RULE-SET,SYSTEM omitted with a notice")
    val domainSet = JSONObject(compat.json).getJSONObject("route").getJSONArray("rule_set").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.optString("type") == "inline" }
    val setRules = domainSet.getJSONArray("rules").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    expect(setRules.any { it.optJSONArray("domain_suffix")?.getString(0) == "example.net" } && setRules.any { it.optJSONArray("domain")?.getString(0) == "exact.example.com" }, "DOMAIN-SET leading dot is suffix, otherwise exact")
    expect(compatRules.any { it.optString("action") == "reject" && it.optString("method") == "drop" }, "REJECT-DROP maps to reject drop")
    expect(compatRules.any { it.optString("action") == "reject" && !it.has("method") && it.optJSONArray("domain")?.getString(0) == "gif.example" }, "REJECT-TINYGIF maps to reject")
    expect(compatRules.any { it.optJSONArray("port_range")?.getString(0) == "8000:9000" }, "DEST-PORT range maps to port_range")
    expect(compatRules.any { it.optJSONArray("source_ip_cidr")?.getString(0) == "192.168.1.10/32" }, "bare SRC-IP becomes a host prefix")
    expect(compatRules.any { it.optJSONArray("domain_regex")?.getString(0) == "^.*\\.cdn\\-.\\.example$" }, "DOMAIN-WILDCARD maps to regex")
    expect(compatRules.any { it.optJSONArray("network_type")?.getString(0) == "cellular" } && compatRules.any { it.optJSONArray("wifi_ssid")?.getString(0) == "Home" }, "CELLULAR and SUBNET,SSID map to network matchers")
    expect(compat.warnings.any { "PROCESS-NAME are omitted" in it }, "main-config platform rules omitted with a notice")
    expect(compat.warnings.any { "Sections without an Android equivalent" in it && "panel" in it && "mitm" in it }, "non-routing sections ignored with a notice")
    expect(compat.warnings.any { "General options without an Android equivalent" in it && "allow-wifi-access" in it }, "unknown general options ignored with a notice")
    expect(compat.warnings.any { "Group option hidden" in it } && compat.warnings.any { "Rule modifier extended-matching" in it }, "display options and modifiers ignored with notices")
    val positional = converter().convert("[Proxy]\nH=http,192.0.2.1,8080,user,pass\nHS=https,192.0.2.1,8443,username=u,password=p,sni=h.example\nS=socks5,192.0.2.1,1080\nSS=ss,192.0.2.1,8388,encrypt-method=aes-128-gcm,password=x\nT=trojan,192.0.2.1,443,password=x,sni=t.example\nA=anytls,192.0.2.1,443,password=x\n[Proxy Group]\nP=select,H,HS,S,SS,T,A\n[Rule]\nGEOIP,CN,DIRECT\nFINAL,P")
    val http = JSONObject(positional.json).getJSONArray("outbounds").getJSONObject(1)
    expect(http.getString("username") == "user" && http.getString("password") == "pass", "positional HTTP credentials accepted")
    expect(!http.has("network") && !JSONObject(positional.json).getJSONArray("outbounds").getJSONObject(6).has("network") && JSONObject(positional.json).getJSONArray("outbounds").getJSONObject(3).getString("network") == "tcp", "network field only on outbounds that accept it")
    File(".local-tools/test-protocols.json").writeText(positional.json)
    expect(converter().convert("[Proxy]\nA=snell,192.0.2.1,443,version=6,psk=k\n[Rule]\nFINAL,REJECT-DROP").json.contains("\"method\": \"drop\""), "FINAL REJECT-DROP")

    fun rejects(source: String, name: String) {
        val error = runCatching { converter().convert(source) }.exceptionOrNull()
        expect(error is SurgeException, name)
        expect(!error!!.message.orEmpty().contains("test-key"), "error redacts credentials")
    }
    rejects(example.replace("version=6", "version=2"), "unsupported Snell fails")
    rejects(example.replace("tfo=true", "tfo=maybe"), "invalid boolean fails")
    rejects(example.replace("mode=unshaped", "mode=made-up"), "invalid v6 mode fails")
    rejects(example.replace("tfo=true", "obfs=http"), "v6 obfs rejected")
    rejects(example.replace("FINAL,Proxy", "FINAL,Missing"), "unknown target fails")
    rejects(example.replace("Proxy = select, Test v6, Test raw, DIRECT", "Proxy = select, Proxy"), "cycle rejected")
    rejects(example + "\nDOMAIN,late.example,DIRECT", "unreachable rules fail")
    rejects(example.replace("DOMAIN,example.net,Proxy", "URL-REGEX-X,^http,Proxy"), "unknown rule type fails")
    rejects(example.replace("psk=\"test-key,with#punctuation\"", "psk=\"unterminated"), "malformed quote fails")
    rejects(base.replace("udp-relay=true", "obfs=tls") + "FINAL,P", "connectivity-relevant unknown proxy parameters still fail")
    rejects(base.replace("hidden=1", "policy-path=https://x.invalid/p") + "FINAL,P", "membership-changing group options still fail")
    rejects(base.replace("udp-relay=true", "server-cert-fingerprint-sha256=AA") + "FINAL,P", "certificate pinning cannot be silently dropped")
    val failing = SurgeConverter { _, _ -> error("secret-url") }
    expect(runCatching { failing.convert(example) }.exceptionOrNull()?.message?.contains("secret-url") == false, "download failures redacted")
    val resolving = converter().convert("[Rule]\nIP-CIDR,198.51.100.0/24,DIRECT\nFINAL,DIRECT")
    expect(resolving.json.contains("\"action\": \"resolve\""), "IP matching resolves when requested")
    File(args.firstOrNull() ?: ".local-tools/test-converted.json").writeText(result.json)
    println("PASS: $checks converter assertions")
}
