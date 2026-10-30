import io.nekohasekai.sfa.surge.AndroidRoutingOverrides
import io.nekohasekai.sfa.surge.SurgeConverter
import org.json.JSONObject
import java.io.File

fun main() {
    var count = 0
    fun expect(condition: Boolean, message: String) { check(condition) { message }; count++ }
    val source = "[Proxy]\nNode=snell,example.invalid,443,version=6,psk=test-only\n[Proxy Group]\nProxy=select,Node\n[Rule]\nIP-CIDR,0.0.0.0/0,DIRECT,no-resolve\nGEOIP,CN,DIRECT\nFINAL,Proxy"
    val original = SurgeConverter { _, _ -> error("unexpected fetch") }.convert(source)
    val overrides = JSONObject("""{"policy":"Proxy","packages":["com.openai.chatgpt"],"domains":["google.com","openai.com"],"dns":"https://1.1.1.1/dns-query"}""")
    val result = AndroidRoutingOverrides.apply(original, overrides)
    val c = JSONObject(result.json)
    val rules = c.getJSONObject("route").getJSONArray("rules")
    expect(rules.getJSONObject(0).getString("action") == "hijack-dns", "DNS hijack stays ahead of package capture")
    expect(rules.getJSONObject(1).getJSONArray("package_name").getString(0) == "com.openai.chatgpt", "package rule precedes catch-all IP direct")
    expect(rules.getJSONObject(2).getJSONArray("domain_suffix").getString(0) == "google.com", "domain route precedes GEOIP resolve")
    expect(rules.getJSONObject(1).getString("outbound") == "Proxy", "package uses explicit policy")
    expect(c.getJSONObject("dns").getString("final") == "android-proxy-dns", "trusted DNS selected")
    expect(c.getJSONObject("route").getJSONObject("default_domain_resolver").getString("server") == "surge-dns-0", "node bootstrap does not recurse into proxy DNS")
    val servers = c.getJSONObject("dns").getJSONArray("servers")
    expect(servers.getJSONObject(servers.length() - 1).getString("detour") == "Proxy", "DoH transported through proxy")
    expect(result.warnings.size == original.warnings.size + 3, "overrides disclosed")
    expect(AndroidRoutingOverrides.apply(original, JSONObject()).json == original.json, "empty overrides preserve source behavior exactly")
    expect(AndroidRoutingOverrides.apply(original, overrides).json == result.json, "deterministic overrides")
    for (bad in listOf("""{"policy":"Missing"}""", """{"policy":"DIRECT"}""", """{"policy":"Proxy","packages":["bad package"]}""", """{"policy":"Proxy","domains":["*.google.com"]}""", """{"policy":"Proxy","dns":"http://1.1.1.1/dns-query"}""", """{"policy":"Proxy","dns":"https://example.com/dns-query"}""", """{"policy":"Proxy","dns":"https://999.1.1.1/dns-query"}""", """{"policy":"Proxy","unknown":true}""")) {
        expect(runCatching { AndroidRoutingOverrides.apply(original, JSONObject(bad)) }.isFailure, "invalid override rejected")
    }
    expect(runCatching { AndroidRoutingOverrides.apply(SurgeConverter { _, _ -> "" }.convert("{}"), overrides) }.isFailure, "native JSON not silently overridden")
    File(".local-tools/overrides-test.json").writeText(result.json)
    println("PASS: $count Android routing override assertions")
}
