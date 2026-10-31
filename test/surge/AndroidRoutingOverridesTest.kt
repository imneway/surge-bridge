import io.nekohasekai.sfa.surge.AndroidRoutingOverrides
import io.nekohasekai.sfa.surge.SurgeConverter
import org.json.JSONObject
import java.io.File

fun main() {
    var count = 0
    fun expect(condition: Boolean, message: String) { check(condition) { message }; count++ }
    val source = "[Proxy]\nNode=snell,example.invalid,443,version=6,psk=test-only\n[Proxy Group]\nProxy=select,Node\n[Rule]\nIP-CIDR,0.0.0.0/0,DIRECT,no-resolve\nGEOIP,CN,DIRECT\nFINAL,Proxy"
    val original = SurgeConverter { _, _ -> error("unexpected fetch") }.convert(source)
    val overrides = JSONObject("""{"policy":"Proxy","packages":["com.openai.chatgpt"],"domains":["google.com","openai.com"],"dns":"https://9.9.9.9/dns-query"}""")
    val result = AndroidRoutingOverrides.apply(original, overrides)
    val c = JSONObject(result.json)
    val rules = c.getJSONObject("route").getJSONArray("rules")
    expect(rules.getJSONObject(0).getString("action") == "hijack-dns", "DNS hijack stays ahead of package capture")
    expect(rules.getJSONObject(1).getJSONArray("package_name").getString(0) == "com.openai.chatgpt", "package rule precedes catch-all IP direct")
    expect(rules.getJSONObject(2).getJSONArray("domain_suffix").getString(0) == "google.com", "domain route precedes GEOIP resolve")
    expect(rules.getJSONObject(1).getString("outbound") == "Proxy", "package uses explicit policy")
    expect(c.getJSONObject("dns").getString("final") == SurgeConverter.LOCAL_DNS, "profile DNS remains final for direct traffic")
    expect(c.getJSONObject("route").getJSONObject("default_domain_resolver").getString("server") == SurgeConverter.LOCAL_DNS, "node bootstrap does not recurse into proxy DNS")
    val servers = c.getJSONObject("dns").getJSONArray("servers")
    val proxyDns = (0 until servers.length()).map { servers.getJSONObject(it) }.filter { it.optString("tag").startsWith(SurgeConverter.PROXY_DNS) }
    expect(proxyDns.size == 1 && proxyDns[0].getString("server") == "9.9.9.9" && proxyDns[0].getString("detour") == "Proxy", "override reconfigures the single proxy-side resolver")
    expect(result.warnings.size == original.warnings.size + 3, "overrides disclosed")
    expect(AndroidRoutingOverrides.apply(original, JSONObject()).json == original.json, "empty overrides preserve source behavior exactly")
    expect(AndroidRoutingOverrides.apply(original, overrides).json == result.json, "deterministic overrides")
    val withoutDns = AndroidRoutingOverrides.apply(original, JSONObject("""{"policy":"Proxy","domains":["google.com"]}"""))
    expect(JSONObject(withoutDns.json).getJSONObject("dns").getJSONArray("servers").let { s -> (0 until s.length()).any { s.getJSONObject(it).optString("tag").startsWith(SurgeConverter.PROXY_DNS) && s.getJSONObject(it).getString("server") == "1.1.1.1" } }, "default proxy-side resolver kept without a dns override")
    val neverResolves = SurgeConverter { _, _ -> "" }.convert("[Proxy]\nNode=snell,example.invalid,443,version=6,psk=test-only\n[Proxy Group]\nProxy=select,Node\n[Rule]\nFINAL,Proxy")
    val ineffective = AndroidRoutingOverrides.apply(neverResolves, JSONObject("""{"policy":"Proxy","dns":"https://9.9.9.9/dns-query"}"""))
    expect(ineffective.warnings.any { "has no effect" in it } && !ineffective.json.contains("9.9.9.9"), "dns override without any proxy-bound resolution is kept but disclosed as ineffective")
    for (bad in listOf("""{"policy":"Missing"}""", """{"policy":"DIRECT"}""", """{"packages":["com.example"]}""", """{"policy":"Proxy","packages":["bad package"]}""", """{"policy":"Proxy","domains":["*.google.com"]}""", """{"policy":"Proxy","dns":"http://1.1.1.1/dns-query"}""", """{"policy":"Proxy","dns":"https://example.com/dns-query"}""", """{"policy":"Proxy","dns":"https://999.1.1.1/dns-query"}""", """{"policy":"Proxy","unknown":true}""")) {
        expect(runCatching { AndroidRoutingOverrides.apply(original, JSONObject(bad)) }.isFailure, "invalid override rejected")
    }
    expect(runCatching { AndroidRoutingOverrides.apply(SurgeConverter { _, _ -> "" }.convert("{}"), overrides) }.isFailure, "native JSON not silently overridden")
    File(".local-tools/overrides-test.json").writeText(result.json)
    println("PASS: $count Android routing override assertions")
}
