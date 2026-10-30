import io.nekohasekai.sfa.surge.*
import org.json.JSONObject
import java.io.File

fun main() {
    var count = 0
    fun expect(ok: Boolean, label: String) { check(ok) { label }; count++ }
    val source = "[Proxy]\nA=snell,192.0.2.1,443,version=6,psk=test-only\nB=snell,192.0.2.2,443,version=6,psk=test-only\n[Proxy Group]\nProxy=select,A,B\n[Rule]\nIP-CIDR,0.0.0.0/0,DIRECT,no-resolve\nFINAL,Proxy"
    val converted = SurgeConverter { _, _ -> error("unexpected fetch") }.convert(source)
    val options = JSONObject("""{"policy":"Proxy","packages":["com.openai.chatgpt"],"dns":"https://1.1.1.1/dns-query","failoverGroups":["Proxy"]}""")
    val result = AndroidRuntimeFeatures.apply(AndroidRoutingOverrides.apply(converted, options), options)
    val config = JSONObject(result.json)
    val rules = config.getJSONObject("route").getJSONArray("rules")
    expect(rules.getJSONObject(0).getString("action") == "hijack-dns", "DNS interception first")
    expect(rules.getJSONObject(1).getString("clash_mode") == "Direct", "Direct overrides package forcing")
    expect(rules.getJSONObject(2).getString("clash_mode") == "Global", "Global overrides catch-all direct")
    expect(rules.getJSONObject(2).getString("outbound") == "Proxy", "Global uses explicit proxy policy")
    expect(config.getJSONObject("experimental").getJSONObject("clash_api").optString("external_controller").isEmpty(), "No network API listener")
    expect(config.getJSONObject("experimental").getJSONObject("clash_api").getString("default_mode") == "Rule", "Rule initial default")
    expect(AndroidRuntimeFeatures.apply(result, options).json == result.json, "idempotent cached upgrade")
    expect(AndroidRuntimeFeatures.eligibleGroups(config) == listOf("Proxy"), "only leaf proxy selectors eligible")
    val directMember = SurgeConverter { _, _ -> "" }.convert(source.replace("Proxy=select,A,B", "Proxy=select,A,DIRECT"))
    expect(AndroidRuntimeFeatures.eligibleGroups(JSONObject(directMember.json)).isEmpty(), "at least two proxy nodes required")
    expect(runCatching { AndroidRoutingOverrides.apply(directMember, options) }.isFailure, "reject fewer than two proxy nodes")
    val mixed = SurgeConverter { _, _ -> "" }.convert(source.replace("Proxy=select,A,B", "Proxy=select,A,B,DIRECT"))
    expect(AndroidRuntimeFeatures.eligibleGroups(JSONObject(mixed.json)) == listOf("Proxy"), "manual DIRECT may coexist with two proxies")
    expect(runCatching { AndroidRoutingOverrides.apply(mixed, options) }.isSuccess, "mixed selector accepted without rewriting source members")
    val dnsRules = config.getJSONObject("dns").getJSONArray("rules")
    val direct = (0 until dnsRules.length()).map { dnsRules.getJSONObject(it) }.first { it.optString("clash_mode") == "Direct" }
    expect(direct.getString("server") == "surge-dns-0", "Direct DNS bypasses proxy override")
    val one = AndroidRuntimeFeatures.scopeToProfile(result, "profile-one.json")
    val two = AndroidRuntimeFeatures.scopeToProfile(result, "profile-two.json")
    expect(one.json != two.json, "profile caches isolated even with identical group names")
    expect(AndroidRuntimeFeatures.scopeToProfile(one, "profile-one.json").json == one.json, "cache identity survives refresh")
    val engine = FailoverDecision()
    val members = listOf("A", "B", "C")
    expect(engine.evaluate("A", members, setOf("A", "B", "C"), 0).next == null, "healthy selected never switches")
    expect(engine.evaluate("A", members, setOf("B", "C"), 1).next == null, "first failure stays")
    expect(engine.evaluate("A", members, setOf("B", "C"), 2).next == "B", "second failure uses configuration order")
    expect(engine.evaluate("B", members, setOf("A", "B", "C"), 3).next == null, "no automatic failback")
    expect(engine.evaluate("B", members, setOf("A", "C"), 4).reason == "cooldown", "anti-flapping cooldown")
    expect(engine.evaluate("B", members, emptySet(), 130_000).next == null, "all failed stays")
    expect(engine.evaluate("B", members, emptySet(), 131_000).reason == "all_unavailable", "all failed exposed")
    expect(engine.evaluate("C", members, setOf("A"), 132_000).next == null, "manual selection resets failure count")
    engine.reset()
    expect(engine.evaluate("A", members, setOf("B"), 133_000).next == null, "lifecycle/mode reset")
    engine.reset()
    engine.evaluate("A", listOf("A", "B", "DIRECT"), setOf("DIRECT"), 1)
    expect(engine.evaluate("A", listOf("A", "B", "DIRECT"), setOf("DIRECT"), 2).next == null, "DIRECT is never an automatic fallback")
    expect(engine.evaluate("DIRECT", listOf("A", "B", "DIRECT"), setOf("A", "B"), 3).reason == "paused_manual_direct", "manual DIRECT remains selected")
    File(".local-tools/runtime-features-test.json").writeText(one.json)
    println("PASS: $count mode/failure-only assertions")
}
