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
            else -> (1..10000).joinToString("\n") { "DOMAIN-SUFFIX,host$it.example.cn" } + "\nIP-CIDR,198.51.100.0/24,no-resolve\nUSER-AGENT,Test*"
        }
    }
    val result = converter().convert(example)
    val c = JSONObject(result.json)
    val snell = c.getJSONArray("outbounds").getJSONObject(1)
    expect(snell.getInt("version") == 6 && snell.getString("mode") == "default", "v6 remains v6; absent mode defaults")
    expect(snell.getString("psk") == "test-key,with#punctuation", "quoted credentials preserved")
    expect(snell.getBoolean("tcp_fast_open"), "TFO retained")
    expect(c.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("type") == "h3", "DoH3 exact native transport")
    val rules = c.getJSONObject("route").getJSONArray("rules")
    val logical = (0 until rules.length()).map { rules.getJSONObject(it) }.first { it.optString("type") == "logical" }
    expect(logical.getString("mode") == "and" && logical.getJSONArray("rules").length() == 2, "AND structure")
    expect(rules.length() < 20, "large sets use indexed rules")
    val sets = c.getJSONObject("route").getJSONArray("rule_set")
    expect((0 until sets.length()).any { sets.getJSONObject(it).optJSONArray("rules")?.let { rs ->
        (0 until rs.length()).any { rs.getJSONObject(it).optJSONArray("domain_suffix")?.length() == 10000 }
    } == true }, "all domain entries retained")
    val geo = (0 until sets.length()).map { sets.getJSONObject(it) }.first { it.optString("type") == "remote" }
    expect(!geo.has("download_detour") && geo.getJSONObject("http_client").getString("detour") == "DIRECT", "GEOIP downloads use explicit modern direct HTTP client")
    expect(geo.getString("update_interval") == "1d", "GEOIP update interval preserved")
    expect(result.warnings.any { "IP-ASN omitted" in it } && result.warnings.any { "USER-AGENT omitted" in it }, "coverage gaps visible")
    expect(result.warnings.any { "redundant per-entry policies" in it }, "nonstandard per-entry policies visible")
    expect(converter().convert(example).json == result.json, "deterministic conversion")
    expect(converter().convert("{\"outbounds\":[]}").sourceFormat == "sing-box", "native JSON passthrough")
    expect(!SurgeConverter.isSurge("[]"), "JSON array is not a Surge section")
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
    rejects(example + "\n[Script]\na=script-path=test.js", "unsupported section fails")
    rejects(example.replace("DOMAIN,example.net,Proxy", "IP-ASN,64512,Proxy"), "unsupported primary rule fails")
    rejects(example.replace("psk=\"test-key,with#punctuation\"", "psk=\"unterminated"), "malformed quote fails")
    val failing = SurgeConverter { _, _ -> error("secret-url") }
    expect(runCatching { failing.convert(example) }.exceptionOrNull()?.message?.contains("secret-url") == false, "download failures redacted")
    val noResolve = converter().convert("[Proxy]\np=snell,192.0.2.1,443,psk=x,version=6\n[Rule]\nIP-CIDR,0.0.0.0/0,DIRECT,no-resolve\nFINAL,p")
    expect(!noResolve.json.contains("\"action\": \"resolve\""), "no-resolve never forces DNS")
    val resolving = converter().convert("[Rule]\nIP-CIDR,198.51.100.0/24,DIRECT\nFINAL,DIRECT")
    expect(resolving.json.contains("\"action\": \"resolve\""), "IP matching resolves when requested")
    File(args.firstOrNull() ?: ".local-tools/test-converted.json").writeText(result.json)
    println("PASS: $checks converter assertions")
}
