package io.nekohasekai.sfa.surge

import org.json.JSONArray
import org.json.JSONObject

/** Mode rules are generated after Android overrides so Direct/Global take precedence. */
object AndroidRuntimeFeatures {
    private const val GLOBAL_DNS = "surge-android-global-dns"
    fun apply(result: SurgeConverter.Result, overrides: JSONObject): SurgeConverter.Result {
        if (result.sourceFormat != "surge") return result
        val upgraded = migrateRuleSetClients(result)
        val config = JSONObject(upgraded.json)
        val dns = config.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")
        if ((0 until servers.length()).any { servers.getJSONObject(it).optString("tag") == GLOBAL_DNS }) return upgraded
        val outbounds = config.getJSONArray("outbounds")
        val proxies = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.filter { it.getString("type") != "direct" }
        if (proxies.isEmpty()) return upgraded
        val route = config.getJSONObject("route")
        val policy = overrides.optString("policy").ifEmpty {
            route.getString("final").takeIf { tag -> proxies.any { it.getString("tag") == tag } }
                ?: proxies.firstOrNull { it.getString("type") == "selector" }?.getString("tag")
                ?: proxies.first().getString("tag")
        }
        val before = route.getJSONArray("rules")
        val rules = JSONArray()
        for (i in 0 until before.length()) if (before.getJSONObject(i).optString("action") == "hijack-dns") rules.put(before.getJSONObject(i))
        // Resolve FakeIP back to the domain before Direct, including existing fake-IP mappings.
        rules.put(obj("clash_mode" to "Direct", "action" to "route", "outbound" to "DIRECT"))
        rules.put(obj("clash_mode" to "Global", "action" to "route", "outbound" to policy))
        for (i in 0 until before.length()) if (before.getJSONObject(i).optString("action") != "hijack-dns") rules.put(before.getJSONObject(i))
        route.put("rules", rules)
        servers.put(obj("tag" to GLOBAL_DNS, "type" to "https", "server" to "1.1.1.1", "path" to "/dns-query", "detour" to policy))
        val oldDNSRules = dns.getJSONArray("rules")
        val dnsRules = JSONArray()
        // Preserve explicit hosts/AAAA policy and fake-IP for A/AAAA. Only the remaining DNS
        // traffic changes upstream; direct dialer uses the original bootstrap resolver.
        for (i in 0 until oldDNSRules.length()) dnsRules.put(oldDNSRules.getJSONObject(i))
        dnsRules.put(obj("clash_mode" to "Direct", "action" to "route", "server" to "surge-dns-0"))
        dnsRules.put(obj("clash_mode" to "Global", "action" to "route", "server" to GLOBAL_DNS))
        dns.put("rules", dnsRules)
        val experimental = config.getJSONObject("experimental")
        experimental.put("clash_api", obj("default_mode" to "Rule")) // No TCP management listener.
        return result.copy(json = config.toString(2))
    }

    /** Migrate generated caches without re-fetching the user's source or rule lists. */
    private fun migrateRuleSetClients(result: SurgeConverter.Result): SurgeConverter.Result {
        val config = JSONObject(result.json)
        val sets = config.optJSONObject("route")?.optJSONArray("rule_set") ?: return result
        val outbounds = config.optJSONArray("outbounds") ?: JSONArray()
        val emptyDirectTags = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
            .filter { it.optString("type") == "direct" && it.length() == 2 }
            .map { it.optString("tag") }.toSet()
        var changed = false
        for (i in 0 until sets.length()) {
            val ruleSet = sets.getJSONObject(i)
            if (ruleSet.optString("type") != "remote") continue
            if (ruleSet.has("download_detour")) {
                if (!ruleSet.has("http_client")) {
                    ruleSet.put("http_client", obj("detour" to ruleSet.getString("download_detour")))
                }
                ruleSet.remove("download_detour")
                changed = true
            }
            val client = ruleSet.optJSONObject("http_client") ?: continue
            if (client.optString("detour") in emptyDirectTags) {
                // Modern HTTP clients reject a detour to an unconfigured direct outbound.
                // Explicit Go engine keeps the client nonempty: {} selects the deprecated
                // default-outbound fallback instead of an independent direct dialer.
                client.remove("detour")
                if (!client.has("engine")) client.put("engine", "go")
                changed = true
            }
        }
        return if (changed) result.copy(json = config.toString(2)) else result
    }

    fun scopeToProfile(result: SurgeConverter.Result, identity: String): SurgeConverter.Result {
        if (result.sourceFormat != "surge") return result
        val config = JSONObject(result.json)
        config.getJSONObject("experimental").getJSONObject("cache_file")
            .put("cache_id", "surge-profile-" + SurgeConverter.digest(identity))
        return result.copy(json = config.toString(2))
    }

    fun eligibleGroups(config: JSONObject): List<String> {
        val array = config.getJSONArray("outbounds")
        val nodes = (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("tag") }
        val leafTypes = setOf("snell", "shadowsocks", "trojan", "http", "socks", "anytls")
        return nodes.values.filter { group ->
            group.optString("type") == "selector" && !group.getString("tag").startsWith(SurgeConverter.RESOLVER_GROUP) && group.getJSONArray("outbounds").let { members ->
                (0 until members.length()).count { nodes[members.getString(it)]?.optString("type") in leafTypes } >= 2 &&
                    (0 until members.length()).all { nodes[members.getString(it)]?.optString("type") in leafTypes + "direct" }
            }
        }.map { it.getString("tag") }
    }
}
