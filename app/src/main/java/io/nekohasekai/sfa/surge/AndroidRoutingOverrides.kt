package io.nekohasekai.sfa.surge

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Explicit per-profile Android settings; kept beside the unchanged Surge source snapshot. */
object AndroidRoutingOverrides {
    val FIELDS = setOf("policy", "packages", "domains", "dns", "failoverGroups")

    fun apply(result: SurgeConverter.Result, overrides: JSONObject): SurgeConverter.Result {
        if (overrides.length() == 0) return result
        require(result.sourceFormat == "surge") { "Android routing overrides require a Surge profile" }
        require(overrides.keys().asSequence().all { it in FIELDS }) { "Unknown Android override field" }
        val config = JSONObject(result.json)
        val policy = overrides.optString("policy")
        require(policy.isNotEmpty()) { "Overrides require a policy" }
        val outbounds = config.getJSONArray("outbounds")
        require((0 until outbounds.length()).any { outbounds.getJSONObject(it).getString("tag") == policy }) { "Unknown override policy" }
        require(policy != "DIRECT") { "Overrides require a proxy policy" }
        fun values(key: String): List<String> {
            if (!overrides.has(key)) return emptyList()
            val array = overrides.getJSONArray(key)
            require(array.length() <= 128) { "Too many override entries" }
            return (0 until array.length()).map { array.getString(it).lowercase() }.distinct()
        }
        val packages = values("packages")
        val domains = values("domains")
        require(packages.all { it.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")) }) { "Invalid package name" }
        require(domains.all { it.length <= 253 && it.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) && '.' in it && ".." !in it }) { "Invalid domain suffix" }
        val failoverGroups = overrides.optJSONArray("failoverGroups") ?: JSONArray()
        val eligible = AndroidRuntimeFeatures.eligibleGroups(config)
        require((0 until failoverGroups.length()).all { failoverGroups.getString(it) in eligible }) { "Failover needs a selector with at least two proxy nodes and no nested groups; DIRECT is excluded from automatic selection" }
        for (i in 0 until outbounds.length()) {
            val group = outbounds.getJSONObject(i)
            if ((0 until failoverGroups.length()).any { failoverGroups.getString(it) == group.optString("tag") }) {
                group.put("interrupt_exist_connections", true)
            }
        }
        val route = config.getJSONObject("route")
        val originalRules = route.getJSONArray("rules")
        val rules = JSONArray()
        // DNS interception must precede package rules, so app DNS is not forwarded as raw UDP.
        for (i in 0 until originalRules.length()) {
            val rule = originalRules.getJSONObject(i)
            if (rule.optString("action") == "hijack-dns") rules.put(rule)
        }
        if (packages.isNotEmpty()) rules.put(obj("package_name" to packages, "action" to "route", "outbound" to policy))
        if (domains.isNotEmpty()) rules.put(obj("domain_suffix" to domains, "action" to "route", "outbound" to policy))
        for (i in 0 until originalRules.length()) {
            val rule = originalRules.getJSONObject(i)
            if (rule.optString("action") != "hijack-dns") rules.put(rule)
        }
        route.put("rules", rules)
        val warnings = result.warnings.toMutableList()
        if (failoverGroups.length() > 0) warnings.add("Android failure-only failover: two failed probe rounds; healthy nodes stay selected; no latency ranking or automatic failback.")
        if (packages.isNotEmpty()) warnings.add("Android override: all traffic from ${packages.joinToString()} uses $policy, including IP-only connections.")
        if (domains.isNotEmpty()) warnings.add("Android override: ${domains.joinToString()} use $policy before IP/GEOIP DNS lookup.")
        if (overrides.has("dns")) {
            val uri = try {
                URI(overrides.getString("dns"))
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid override DNS URL")
            }
            // An IP endpoint avoids a resolver -> proxy -> resolver bootstrap cycle.
            require(uri.scheme == "https" && uri.host?.matches(Regex("[0-9]+(?:\\.[0-9]+){3}")) == true && uri.userInfo == null && uri.fragment == null && uri.query == null) { "Override DNS requires an HTTPS IPv4 endpoint without credentials/query" }
            require(uri.host.split('.').all { it.toIntOrNull() in 0..255 }) { "Invalid override DNS IP" }
            val dns = config.getJSONObject("dns")
            val servers = dns.getJSONArray("servers")
            // Reconfigure the converter's per-policy proxy-side resolvers; each keeps its own detour.
            var reconfigured = 0
            for (i in 0 until servers.length()) {
                val server = servers.getJSONObject(i)
                if (!server.optString("tag").startsWith(SurgeConverter.PROXY_DNS)) continue
                server.put("server", uri.host).put("path", uri.rawPath.ifEmpty { "/dns-query" })
                if (uri.port != -1) {
                    require(uri.port in 1..65535)
                    server.put("server_port", uri.port)
                } else {
                    server.remove("server_port")
                }
                reconfigured++
            }
            // route.default_domain_resolver and dns.final remain the profile DNS for direct traffic.
            warnings.add(
                if (reconfigured > 0) {
                    "Android override: proxy-bound IP/GEOIP re-resolution uses ${uri.host} over HTTPS through each proxy policy; node bootstrap DNS remains from the Surge profile."
                } else {
                    "Android override: the DNS setting has no effect because this profile never resolves proxy-bound traffic (no IP/GEOIP rule precedes a proxy policy)."
                },
            )
        }
        return result.copy(json = config.toString(2), warnings = warnings)
    }
}
