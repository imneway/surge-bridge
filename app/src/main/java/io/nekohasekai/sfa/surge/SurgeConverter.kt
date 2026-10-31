package io.nekohasekai.sfa.surge

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

internal fun obj(vararg values: Pair<String, Any?>): JSONObject = JSONObject().apply {
    values.forEach { (key, value) -> put(key, if (value is Collection<*>) JSONArray(value) else value) }
}

class SurgeConverter(private val fetchRules: (String, Int) -> String) {
    data class Result(val json: String, val warnings: List<String>, val sourceFormat: String)

    companion object {
        /** Tag prefix of the HTTPS resolvers dialed through each proxy policy ("surge-proxy-dns-N"). */
        const val PROXY_DNS = "surge-proxy-dns"

        /** Hidden selector mirroring a DIRECT-capable group without DIRECT; the proxy-side resolver dials through it. */
        const val RESOLVER_GROUP = "surge-resolver"
        const val LOCAL_DNS = "surge-dns-0"
        fun isSurge(source: String): Boolean = source.removePrefix("\uFEFF").lineSequence()
            .map(SurgeSyntax::clean).firstOrNull { it.isNotEmpty() }?.let { it.length > 2 && it.startsWith('[') && it.endsWith(']') && it[1].isLetter() } == true
        fun digest(source: String): String = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun convert(source: String): Result {
        if (!isSurge(source)) return Result(source, emptyList(), "sing-box")
        return Conversion(fetchRules).convert(source)
    }
}

private class Conversion(private val fetchRules: (String, Int) -> String) {
    private val warnings = linkedSetOf<String>()
    private val outbounds = mutableListOf<JSONObject>()
    private val routes = mutableListOf<JSONObject>()
    private val ruleSets = linkedMapOf<String, JSONObject>()
    private val rejectPolicies = setOf("REJECT", "REJECT-DROP", "REJECT-TINYGIF", "REJECT-NO-DROP")
    private val policies = linkedSetOf("DIRECT").apply { addAll(rejectPolicies) }
    private val references = mutableListOf<Pair<Int, String>>()
    private val groupGraph = linkedMapOf<String, List<String>>()

    /** Policy group membership only; groupGraph also carries node dial chains (underlying-proxy) for cycle checks. */
    private val groupMembers = linkedMapOf<String, List<String>>()
    private val general = linkedMapOf<String, String>()
    private var defaultPolicy = "DIRECT"
    private var ipv6 = false

    /**
     * Whether a profile-DNS resolve action already ran for every connection reaching this point.
     * sing-box dials every outbound by the resolved addresses once a resolve action ran, so a rule
     * that routes such a connection to a proxy first re-resolves it, scoped to the same matcher,
     * through that policy's own HTTPS resolver. Traffic continuing to DIRECT keeps local answers.
     */
    private var locallyResolved = false

    /** Proxy policy -> tag of its HTTPS resolver, allocated on first use. */
    private val proxyDnsTags = linkedMapOf<String, String>()
    private val resolverGroups = linkedMapOf<String, String>()

    /** Set once a matcher-scoped proxy re-resolve exists; DIRECT fall-through then restores local answers. */
    private var proxyReResolved = false

    /** Platform matchers with no Android equivalent; rules containing them are omitted with a notice. */
    private val skippable = setOf("PROCESS-NAME", "USER-AGENT", "IP-ASN", "URL-REGEX", "IN-PORT", "DEVICE-NAME", "SCRIPT")
    private val ignorableModifiers = setOf("extended-matching", "pre-matching")

    private fun isReject(policy: String) = policy in rejectPolicies
    private fun isProxyPolicy(policy: String) = policy != "DIRECT" && !isReject(policy)
    private fun strategy() = if (ipv6) "prefer_ipv4" else "ipv4_only"

    fun convert(source: String): SurgeConverter.Result {
        val sections = SurgeSyntax.sections(source)
        val ignoredSections = sections.keys.filter { it !in SurgeSyntax.CONVERTED_SECTIONS }
        if (ignoredSections.isNotEmpty()) {
            warnings.add("Sections without an Android equivalent are ignored: ${ignoredSections.joinToString()}. They do not affect routing.")
        }
        for (entry in sections["general"].orEmpty()) {
            val (name, value) = SurgeSyntax.assignment(entry)
            if (general.put(name.lowercase(), value) != null) SurgeSyntax.fail(entry.line, "duplicate general option $name")
        }
        ipv6 = general["ipv6"]?.let { SurgeSyntax.boolean(it, 0) } ?: false
        validateGeneral()
        outbounds.add(obj("type" to "direct", "tag" to "DIRECT"))
        for (entry in sections["proxy"].orEmpty()) proxy(entry)
        for (entry in sections["proxy group"].orEmpty()) group(entry)
        routes.add(obj("port" to 53, "action" to "hijack-dns"))
        if (general["exclude-simple-hostnames"]?.let { SurgeSyntax.boolean(it, 0) } == true) {
            routes.add(obj("domain_regex" to listOf("^[^.]+$"), "action" to "route", "outbound" to "DIRECT"))
        }
        var finalSeen = false
        for (entry in sections["rule"].orEmpty()) {
            if (finalSeen) SurgeSyntax.fail(entry.line, "rules after FINAL are unreachable")
            val fields = SurgeSyntax.split(entry.text, entry.line)
            if (fields[0].uppercase() == "FINAL") {
                if (fields.size !in 2..3 || (fields.size == 3 && fields[2] != "dns-failed")) SurgeSyntax.fail(entry.line, "unsupported FINAL parameters")
                defaultPolicy = SurgeSyntax.value(fields[1])
                references.add(entry.line to defaultPolicy)
                if (fields.size == 3) warnings.add("FINAL,dns-failed uses sing-box final routing; DNS failure behavior can differ from Surge.")
                finalSeen = true
            } else {
                rule(entry)
            }
        }
        if (!finalSeen) SurgeSyntax.fail(0, "a FINAL policy is required")
        references.forEach { (line, name) -> if (name !in policies) SurgeSyntax.fail(line, "unknown policy reference") }
        checkCycles()
        if (isReject(defaultPolicy)) {
            routes.add(rejectAction(defaultPolicy))
        } // Fall-through traffic reaching a proxy FINAL must not carry locally resolved addresses.
        else if (hasProxyExit(defaultPolicy) && locallyResolved) {
            routes.add(obj("action" to "resolve", "strategy" to strategy(), "server" to proxyDns(defaultPolicy)))
        } // Connections re-resolved for a proxy rule that no longer matched them fall back to DIRECT
        // with the profile's own answers, not addresses seen from the proxy exit.
        else if (!hasProxyExit(defaultPolicy) && proxyReResolved) {
            routes.add(obj("action" to "resolve", "strategy" to strategy()))
        }
        val tun = obj(
            "type" to "tun",
            "tag" to "surge-tun",
            "address" to
                (if (ipv6) listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126") else listOf("172.19.0.1/30")),
            "auto_route" to true,
            "strict_route" to true,
            "stack" to "mixed",
        )
        general["tun-excluded-routes"]?.let { value ->
            val cidrs = SurgeSyntax.split(value, 0).map { cidr(it, 0) }
            tun.put("route_exclude_address", JSONArray(cidrs))
        }
        val dns = dns(sections["host"].orEmpty())
        val route = obj(
            "rules" to routes,
            "rule_set" to ruleSets.values.toList(),
            "auto_detect_interface" to true,
            "default_domain_resolver" to obj("server" to SurgeConverter.LOCAL_DNS, "strategy" to strategy()),
            "final" to if (isReject(defaultPolicy)) "DIRECT" else defaultPolicy,
        )
        val config = obj(
            "log" to obj("level" to logLevel()),
            "dns" to dns,
            "inbounds" to listOf(tun),
            "outbounds" to outbounds,
            "route" to route,
            "experimental" to obj("cache_file" to obj("enabled" to true, "store_fakeip" to true)),
        )
        return SurgeConverter.Result(config.toString(2), warnings.toList(), "surge")
    }

    private fun validateGeneral() {
        val mapped = setOf(
            "loglevel", "ipv6", "exclude-simple-hostnames", "dns-server", "encrypted-dns-server",
            "skip-proxy", "tun-excluded-routes", "hijack-dns", "internet-test-url", "proxy-test-url", "test-timeout",
        )
        val unmapped = general.keys.filter { it !in mapped }
        if (unmapped.isNotEmpty()) warnings.add("General options without an Android equivalent are ignored: ${unmapped.joinToString()}.")
        general["hijack-dns"]?.let { if (it != "*:53") SurgeSyntax.fail(0, "only hijack-dns = *:53 is supported") }
        if ("skip-proxy" in general) warnings.add("skip-proxy is an HTTP proxy bypass list; Android uses TUN. Use tun-excluded-routes and DIRECT rules for VPN bypass.")
        if ("internet-test-url" in general) warnings.add("internet-test-url does not change the Android system connectivity probe.")
        if ("test-timeout" in general) {
            SurgeSyntax.positive(general.getValue("test-timeout"), 0)
            warnings.add("test-timeout is not mapped; sing-box owns probe timeouts.")
        }
    }

    private fun logLevel(): String = when (general["loglevel"] ?: "notify") {
        "notify", "info" -> "info"
        "verbose", "debug" -> "debug"
        "warning", "error" -> general.getValue("loglevel")
        else -> SurgeSyntax.fail(0, "unsupported loglevel")
    }

    private fun addName(name: String, line: Int) {
        if (name.isBlank() || !policies.add(name)) SurgeSyntax.fail(line, "empty or duplicate policy name")
        // Internal outbounds (resolver mirrors) and DNS servers live in the surge- namespace.
        if (name.startsWith("surge-", ignoreCase = true)) SurgeSyntax.fail(line, "policy names starting with surge- are reserved")
    }

    private fun proxy(entry: Entry) {
        val (name, value) = SurgeSyntax.assignment(entry)
        addName(name, entry.line)
        val f = SurgeSyntax.split(value, entry.line)
        if (f.size < 3) SurgeSyntax.fail(entry.line, "proxy requires type, server and port")
        val kind = f[0].lowercase()
        val port = SurgeSyntax.positive(f[2], entry.line)
        if (port > 65535) SurgeSyntax.fail(entry.line, "port is out of range")
        val credentialKinds = setOf("http", "https", "socks5", "socks5-tls")
        // Surge allows "http, host, port, username, password" positionally.
        val positional = f.drop(3).filter { '=' !in it }
        if (positional.isNotEmpty() && (kind !in credentialKinds || positional.size > 2)) SurgeSyntax.fail(entry.line, "unexpected positional parameter")
        val p = SurgeSyntax.options(f.drop(3).filter { '=' in it }, entry.line).toMutableMap()
        positional.getOrNull(0)?.let { if (p.put("username", SurgeSyntax.value(it)) != null) SurgeSyntax.fail(entry.line, "duplicate parameter username") }
        positional.getOrNull(1)?.let { if (p.put("password", SurgeSyntax.value(it)) != null) SurgeSyntax.fail(entry.line, "duplicate parameter password") }
        val common = setOf("tfo", "underlying-proxy", "udp-relay", "test-url")
        // Client-side tuning with no connectivity impact on Android; ignored with a notice.
        val ignorable = setOf("no-error-alert", "block-quic", "ip-version", "tos", "ecn", "allow-other-interface", "interface", "test-timeout", "always-use-connect")
        val specific = when (kind) {
            "snell" -> setOf("psk", "version", "mode", "reuse", "obfs", "obfs-host")
            "ss" -> setOf("encrypt-method", "password")
            "trojan", "anytls" -> setOf("password", "sni", "skip-cert-verify")
            in credentialKinds -> setOf("username", "password", "sni", "skip-cert-verify")
            else -> SurgeSyntax.fail(entry.line, "unsupported proxy protocol $kind")
        }
        p.keys.forEach {
            if (it in ignorable) {
                warnings.add("Proxy parameter $it is not mapped on Android and is ignored.")
            } else if (it !in common + specific) {
                SurgeSyntax.fail(entry.line, "unsupported proxy parameter $it")
            }
        }
        val type = when (kind) {
            "ss" -> "shadowsocks"
            "https" -> "http"
            "socks5", "socks5-tls" -> "socks"
            else -> kind
        }
        val node = obj("type" to type, "tag" to name, "server" to SurgeSyntax.value(f[1]), "server_port" to port)
        fun required(key: String): String = p[key]?.takeIf { it.isNotEmpty() } ?: SurgeSyntax.fail(entry.line, "missing $key")
        when (kind) {
            "snell" -> {
                val version = p["version"]?.toIntOrNull() ?: 1
                if (version !in setOf(4, 5, 6)) SurgeSyntax.fail(entry.line, "sing-box supports Snell v4/v5-compatible and v6 only")
                node.put("version", if (version == 5) 4 else version).put("psk", required("psk"))
                if (version == 5) warnings.add("Snell v5 uses v4 compatibility; QUIC Proxy Mode is unavailable.")
                p["reuse"]?.let { node.put("reuse", SurgeSyntax.boolean(it, entry.line)) }
                if (version == 6) {
                    if ("obfs" in p || "obfs-host" in p) SurgeSyntax.fail(entry.line, "Snell v6 does not accept obfs")
                    val mode = p["mode"] ?: "default"
                    if (mode !in setOf("default", "unshaped", "unsafe-raw")) SurgeSyntax.fail(entry.line, "invalid Snell v6 mode")
                    node.put("mode", mode)
                } else {
                    if ("mode" in p) SurgeSyntax.fail(entry.line, "mode requires Snell v6")
                    p["obfs"]?.let {
                        if (it != "http") SurgeSyntax.fail(entry.line, "only HTTP obfs is supported for Snell v4")
                        node.put("obfs_mode", it)
                    }
                    p["obfs-host"]?.let { node.put("obfs_host", it) }
                }
            }
            "ss" -> node.put("method", required("encrypt-method")).put("password", required("password"))
            "trojan", "anytls" -> node.put("password", required("password"))
            else -> {
                p["username"]?.let { node.put("username", it) }
                p["password"]?.let { node.put("password", it) }
            }
        }
        if (kind in setOf("trojan", "anytls", "https", "socks5-tls")) {
            node.put(
                "tls",
                obj(
                    "enabled" to true,
                    "server_name" to (p["sni"] ?: SurgeSyntax.value(f[1])),
                    "insecure" to (p["skip-cert-verify"]?.let { SurgeSyntax.boolean(it, entry.line) } ?: false),
                ),
            )
        }
        p["tfo"]?.let { node.put("tcp_fast_open", SurgeSyntax.boolean(it, entry.line)) }
        p["underlying-proxy"]?.let {
            node.put("detour", it)
            references.add(entry.line to it)
            groupGraph[name] = listOf(it)
        }
        // Surge does not relay UDP unless udp-relay=true; keep that default so behavior matches.
        // HTTP and AnyTLS outbounds are TCP-only and reject the network field.
        if (p["udp-relay"]?.let { SurgeSyntax.boolean(it, entry.line) } != true && type in setOf("snell", "shadowsocks", "trojan", "socks")) {
            node.put("network", "tcp")
            if ("udp-relay" !in p) warnings.add("Nodes without udp-relay=true carry TCP only, as in Surge; UDP to them is dropped.")
        }
        if ("test-url" in p) warnings.add("Per-node test-url is not mapped; use a url-test group's URL.")
        outbounds.add(node)
    }

    private fun group(entry: Entry) {
        val (name, value) = SurgeSyntax.assignment(entry)
        addName(name, entry.line)
        val fields = SurgeSyntax.split(value, entry.line)
        val kind = fields.first().lowercase()
        if (kind !in setOf("select", "url-test")) SurgeSyntax.fail(entry.line, "unsupported group type $kind")
        val options = SurgeSyntax.options(fields.drop(1).filter { '=' in it }, entry.line)
        val members = fields.drop(1).filter { '=' !in it }.map(SurgeSyntax::value)
        if (members.isEmpty() || members.any(::isReject)) SurgeSyntax.fail(entry.line, "group requires usable outbound members")
        val testOptions = setOf("url", "interval", "tolerance")
        // Display/UX options have no routing effect; membership-changing options still fail.
        val ignorable = setOf("hidden", "no-alert", "timeout", "evaluate-before-use", "persistent", "update-interval")
        options.keys.forEach {
            if (it in ignorable || (kind == "select" && it in testOptions)) {
                warnings.add("Group option $it is not mapped on Android and is ignored.")
            } else if (it !in testOptions) {
                SurgeSyntax.fail(entry.line, "unsupported group option $it")
            }
        }
        references.addAll(members.map { entry.line to it })
        groupGraph[name] = members
        groupMembers[name] = members
        val node = obj("type" to if (kind == "select") "selector" else "urltest", "tag" to name, "outbounds" to members)
        if (kind == "url-test") {
            node.put("url", options["url"] ?: general["proxy-test-url"] ?: "https://www.gstatic.com/generate_204")
            node.put("interval", "${SurgeSyntax.positive(options["interval"] ?: "300", entry.line)}s")
            options["tolerance"]?.let { node.put("tolerance", SurgeSyntax.positive(it, entry.line)) }
        }
        outbounds.add(node)
    }

    private fun checkCycles() {
        val active = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun visit(name: String) {
            if (name in done) return
            if (!active.add(name)) SurgeSyntax.fail(0, "cyclic group or underlying-proxy reference")
            groupGraph[name].orEmpty().forEach(::visit)
            active.remove(name)
            done.add(name)
        }
        groupGraph.keys.forEach(::visit)
    }

    private data class Condition(val json: JSONObject, val resolve: Boolean)

    /** Returns null when the rule uses a platform matcher that is omitted on Android. */
    private fun skippableCondition(fields: List<String>, line: Int, context: String): Condition? = try {
        condition(fields, line)
    } catch (e: SurgeException) {
        val kind = e.reason.removePrefix("unsupported rule type ")
        if (e.reason.startsWith("unsupported rule type ") && kind in skippable) {
            warnings.add("$context: rules containing $kind are omitted on Android; matching falls through to later rules.")
            null
        } else {
            throw e
        }
    }

    private fun rule(entry: Entry) {
        val fields = SurgeSyntax.split(entry.text, entry.line)
        val kind = fields[0].uppercase()
        if (kind == "CELLULAR") {
            // CELLULAR,Policy has no value column.
            if (fields.size != 2) SurgeSyntax.fail(entry.line, "CELLULAR requires a policy")
            val policy = SurgeSyntax.value(fields[1])
            references.add(entry.line to policy)
            append(Condition(obj("network_type" to listOf("cellular")), false), policy)
            return
        }
        if (fields.size < 3) SurgeSyntax.fail(entry.line, "rule requires type, value and policy")
        val policy = SurgeSyntax.value(fields[2])
        references.add(entry.line to policy)
        when (kind) {
            "RULE-SET" -> ruleSet(entry, fields, policy)
            "DOMAIN-SET" -> domainSet(entry, fields, policy)
            else -> skippableCondition(fields.take(2) + fields.drop(3), entry.line, "Line ${entry.line}")?.let { append(it, policy) }
        }
    }

    private fun setOptions(entry: Entry, fields: List<String>): Pair<Boolean, Int> {
        val opts = fields.drop(3)
        val flags = opts.filter { '=' !in it }
        flags.forEach {
            if (it in ignorableModifiers) {
                warnings.add("Rule modifier $it is not mapped on Android and is ignored.")
            } else if (it != "no-resolve") {
                SurgeSyntax.fail(entry.line, "unsupported rule-set option $it")
            }
        }
        val params = SurgeSyntax.options(opts.filter { '=' in it }, entry.line)
        params.keys.forEach { if (it != "update-interval") SurgeSyntax.fail(entry.line, "unsupported rule-set option $it") }
        return ("no-resolve" in flags) to SurgeSyntax.positive(params["update-interval"] ?: "86400", entry.line)
    }

    private fun fetch(entry: Entry, url: String, interval: Int): String {
        checkedURL(url, entry.line)
        return try {
            fetchRules(url, interval)
        } catch (_: Exception) {
            SurgeSyntax.fail(entry.line, "could not fetch rule set (URL redacted)")
        }
    }

    private fun ruleSet(entry: Entry, fields: List<String>, policy: String) {
        val (noResolve, interval) = setOptions(entry, fields)
        val name = SurgeSyntax.value(fields[1])
        when (name.uppercase()) {
            "LAN" -> {
                append(Condition(obj("ip_is_private" to true), !noResolve), policy)
                return
            }
            "SYSTEM" -> {
                warnings.add("Line ${entry.line}: RULE-SET,SYSTEM is an Apple system rule set and is omitted on Android.")
                return
            }
        }
        val text = fetch(entry, name, interval)
        var count = 0
        val conditions = mutableListOf<Condition>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = SurgeSyntax.clean(raw)
            if (line.isEmpty()) return@forEachIndexed
            count++
            if (count > 200000) SurgeSyntax.fail(entry.line, "rule set exceeds 200000 entries")
            var parts = SurgeSyntax.split(line, entry.line)
            if (parts.size >= 3 && SurgeSyntax.value(parts[2]) == policy) {
                parts = parts.take(2) + parts.drop(3)
                warnings.add("Rule set at line ${entry.line}: redundant per-entry policies matching the enclosing policy were removed.")
            }
            val modifiers = parts.drop(2)
            if (parts.size < 2 || modifiers.any { it != "no-resolve" && it !in ignorableModifiers }) SurgeSyntax.fail(entry.line, "unsupported rule-set entry at remote line ${index + 1}")
            val entryNoResolve = noResolve || "no-resolve" in modifiers
            skippableCondition(parts.take(2) + if (entryNoResolve) listOf("no-resolve") else emptyList(), entry.line, "Rule set at line ${entry.line}")?.let(conditions::add)
        }
        if (conditions.isEmpty()) SurgeSyntax.fail(entry.line, "rule set has no supported entries")
        inlineSet(entry, policy, conditions)
    }

    private fun domainSet(entry: Entry, fields: List<String>, policy: String) {
        val (_, interval) = setOptions(entry, fields)
        val text = fetch(entry, SurgeSyntax.value(fields[1]), interval)
        val suffixes = linkedSetOf<String>()
        val exact = linkedSetOf<String>()
        var count = 0
        text.lineSequence().forEachIndexed { index, raw ->
            val line = SurgeSyntax.clean(raw).lowercase()
            if (line.isEmpty()) return@forEachIndexed
            count++
            if (count > 200000) SurgeSyntax.fail(entry.line, "domain set exceeds 200000 entries")
            val domain = line.removePrefix(".")
            if (domain.isEmpty() || !domain.matches(Regex("[a-z0-9_](?:[a-z0-9_.-]*[a-z0-9_])?"))) SurgeSyntax.fail(entry.line, "unsupported domain-set entry at remote line ${index + 1}")
            if (line.startsWith('.')) suffixes.add(domain) else exact.add(domain)
        }
        val conditions = mutableListOf<Condition>()
        if (suffixes.isNotEmpty()) conditions.add(Condition(obj("domain_suffix" to suffixes.toList()), false))
        if (exact.isNotEmpty()) conditions.add(Condition(obj("domain" to exact.toList()), false))
        if (conditions.isEmpty()) SurgeSyntax.fail(entry.line, "domain set has no entries")
        inlineSet(entry, policy, conditions)
    }

    /** One indexed headless rule per matcher avoids 100k+ linear route entries. Non-DNS matches evaluate first. */
    private fun inlineSet(entry: Entry, policy: String, conditions: List<Condition>) {
        for (resolve in listOf(false, true)) {
            val selected = conditions.filter { it.resolve == resolve }
            if (selected.isEmpty()) continue
            val merged = linkedMapOf<String, MutableList<Any>>()
            val other = mutableListOf<JSONObject>()
            for (item in selected) {
                val keys = item.json.keys().asSequence().toList()
                val array = if (keys.size == 1) item.json.optJSONArray(keys[0]) else null
                if (array != null) {
                    val values = merged.getOrPut(keys[0]) { mutableListOf() }
                    for (i in 0 until array.length()) values.add(array.get(i))
                } else {
                    other.add(item.json)
                }
            }
            val tag = "surge-list-${entry.line}-${if (resolve) "resolve" else "literal"}"
            ruleSets[tag] = obj(
                "type" to "inline",
                "tag" to tag,
                "rules" to (merged.map { (key, values) -> obj(key to values.distinct()) } + other),
            )
            append(Condition(obj("rule_set" to listOf(tag)), resolve), policy)
        }
    }

    private fun rejectAction(policy: String): JSONObject = obj("action" to "reject").apply {
        if (policy == "REJECT-DROP") put("method", "drop")
    }

    private fun proxyDns(policy: String): String = proxyDnsTags.getOrPut(policy) { "${SurgeConverter.PROXY_DNS}-${proxyDnsTags.size}" }

    private fun append(condition: Condition, policy: String) {
        if (condition.resolve && !locallyResolved) {
            // Local resolution follows the profile DNS rules (hosts included), as Surge resolves for IP rules.
            locallyResolved = true
            routes.add(obj("action" to "resolve", "strategy" to strategy()))
        }
        if (hasProxyExit(policy) && locallyResolved) {
            // Only connections this rule routes to the proxy are re-resolved through that proxy, so
            // DIRECT traffic never depends on the proxy or carries addresses seen from its exit.
            routes.add(JSONObject(condition.json.toString()).put("action", "resolve").put("strategy", strategy()).put("server", proxyDns(policy)))
            if (condition.resolve) proxyReResolved = true
        }
        if (isReject(policy)) {
            condition.json.put("action", "reject")
            if (policy == "REJECT-DROP") condition.json.put("method", "drop")
        } else {
            condition.json.put("action", "route").put("outbound", policy)
        }
        routes.add(condition.json)
    }

    private fun condition(fields: List<String>, line: Int, depth: Int = 0): Condition {
        if (depth > 16 || fields.size < 2) SurgeSyntax.fail(line, "invalid rule condition")
        val kind = fields[0].uppercase()
        val value = SurgeSyntax.value(fields[1])
        val modifiers = fields.drop(2)
        modifiers.forEach {
            if (it in ignorableModifiers) {
                warnings.add("Rule modifier $it is not mapped on Android and is ignored.")
            } else if (it != "no-resolve") {
                SurgeSyntax.fail(line, "unsupported rule modifier")
            }
        }
        val noResolve = "no-resolve" in modifiers
        val json: JSONObject
        var resolve = false
        when (kind) {
            "AND", "OR", "NOT" -> {
                if (!value.startsWith('(') || !value.endsWith(')')) SurgeSyntax.fail(line, "invalid logical rule")
                val children = SurgeSyntax.split(value.substring(1, value.length - 1), line).map { part ->
                    if (!part.startsWith('(') || !part.endsWith(')')) SurgeSyntax.fail(line, "invalid logical child")
                    condition(SurgeSyntax.split(part.substring(1, part.length - 1), line), line, depth + 1)
                }
                if (children.isEmpty() || (kind == "NOT" && children.size != 1)) SurgeSyntax.fail(line, "invalid logical rule arity")
                json = obj("type" to "logical", "mode" to if (kind == "OR") "or" else "and", "rules" to children.map { it.json })
                if (kind == "NOT") json.put("invert", true)
                resolve = children.any { it.resolve }
            }
            "DOMAIN" -> json = obj("domain" to listOf(value))
            "DOMAIN-SUFFIX" -> json = obj("domain_suffix" to listOf(value.removePrefix(".")))
            "DOMAIN-KEYWORD" -> json = obj("domain_keyword" to listOf(value))
            "DOMAIN-WILDCARD" -> json = obj(
                "domain_regex" to listOf(
                    "^" + value.lowercase().map { c ->
                        when {
                            c == '*' -> ".*"
                            c == '?' -> "."
                            c.isLetterOrDigit() -> c.toString()
                            else -> "\\$c"
                        }
                    }.joinToString("") + "$",
                ),
            )
            "IP-CIDR", "IP-CIDR6" -> {
                json = obj("ip_cidr" to listOf(cidr(value, line)))
                resolve = true
            }
            "SRC-IP" -> json = obj("source_ip_cidr" to listOf(cidr(value, line)))
            "DEST-PORT", "SRC-PORT" -> {
                val range = value.split('-')
                val ports = range.map { SurgeSyntax.positive(it.trim(), line).also { p -> if (p > 65535) SurgeSyntax.fail(line, "rule port out of range") } }
                json = when {
                    ports.size == 1 -> obj((if (kind == "DEST-PORT") "port" else "source_port") to ports[0])
                    ports.size == 2 && ports[0] <= ports[1] -> obj((if (kind == "DEST-PORT") "port_range" else "source_port_range") to listOf("${ports[0]}:${ports[1]}"))
                    else -> SurgeSyntax.fail(line, "invalid port range")
                }
            }
            "PROTOCOL" -> {
                if (value.uppercase() !in setOf("TCP", "UDP")) SurgeSyntax.fail(line, "unsupported PROTOCOL")
                json = obj("network" to value.lowercase())
            }
            "SUBNET" -> {
                val (type, target) = value.split(':', limit = 2).let { if (it.size != 2) SurgeSyntax.fail(line, "SUBNET requires TYPE:value") else it[0].uppercase() to it[1] }
                json = when (type) {
                    "SSID" -> obj("wifi_ssid" to listOf(target))
                    "BSSID" -> obj("wifi_bssid" to listOf(target.lowercase()))
                    "TYPE" -> obj(
                        "network_type" to listOf(
                            when (target.uppercase()) {
                                "WIFI" -> "wifi"
                                "CELLULAR" -> "cellular"
                                "WIRED" -> "ethernet"
                                else -> SurgeSyntax.fail(line, "unsupported SUBNET type")
                            },
                        ),
                    )
                    else -> SurgeSyntax.fail(line, "unsupported SUBNET matcher")
                }
            }
            "GEOIP" -> {
                val country = value.lowercase()
                if (!country.matches(Regex("[a-z]{2}"))) SurgeSyntax.fail(line, "GEOIP requires a country code")
                val tag = "surge-geoip-$country"
                ruleSets[tag] = obj(
                    "type" to "remote",
                    "tag" to tag,
                    "format" to "binary",
                    "url" to "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-$country.srs",
                    "http_client" to obj("engine" to "go"),
                    "update_interval" to "1d",
                )
                json = obj("rule_set" to listOf(tag))
                resolve = true
                warnings.add("GEOIP uses SagerNet country datasets, which can differ from Surge's database.")
            }
            else -> SurgeSyntax.fail(line, "unsupported rule type $kind")
        }
        if (noResolve && kind !in setOf("IP-CIDR", "IP-CIDR6", "GEOIP", "AND", "OR", "NOT")) SurgeSyntax.fail(line, "no-resolve is invalid for $kind")
        return Condition(json, resolve && !noResolve)
    }

    private fun dns(hosts: List<Entry>): JSONObject {
        val plain = general["dns-server"]?.let { SurgeSyntax.split(it, 0) } ?: listOf("system")
        val encrypted = general["encrypted-dns-server"]?.let { SurgeSyntax.split(it, 0) }.orEmpty()
        val sources = encrypted.ifEmpty { plain }
        val servers = mutableListOf<JSONObject>()
        val bootstrap = plain.firstOrNull { it != "system" } ?: "system"
        servers.add(
            if (bootstrap == "system") {
                obj("type" to "local", "tag" to "surge-bootstrap")
            } else {
                plainServer(bootstrap).put("type", "udp").put("tag", "surge-bootstrap")
            },
        )
        for ((i, source) in sources.withIndex()) {
            val tag = "surge-dns-$i"
            if (source == "system") {
                servers.add(obj("type" to "local", "tag" to tag))
            } else if ("://" !in source) {
                servers.add(plainServer(source).put("type", "udp").put("tag", tag))
            } else {
                val uri = dnsURL(source)
                val type = uri.scheme
                val server = obj("type" to type, "tag" to tag, "server" to uri.host, "domain_resolver" to "surge-bootstrap")
                if (uri.port != -1) server.put("server_port", uri.port)
                if (uri.scheme in setOf("https", "h3")) server.put("path", uri.rawPath.ifEmpty { "/dns-query" })
                servers.add(server)
            }
        }
        if (sources.size > 1) warnings.add("All DNS endpoints are retained; the first is used. Surge's concurrent DNS racing is not reproduced.")
        val rules = mutableListOf<JSONObject>()
        if (!ipv6) rules.add(obj("query_type" to listOf("AAAA"), "action" to "reject"))
        if (hosts.isNotEmpty()) {
            val map = JSONObject()
            for (entry in hosts) {
                val (host, value) = SurgeSyntax.assignment(entry)
                val ips = SurgeSyntax.split(value, entry.line)
                if (host.contains('*') || ips.any { !it.matches(Regex("[0-9a-fA-F:.]+")) }) {
                    warnings.add("Host entry at line ${entry.line} is ignored: only exact hostnames mapped to IP literals are supported on Android.")
                    continue
                }
                map.put(host, JSONArray(ips))
            }
            if (map.length() > 0) {
                servers.add(obj("type" to "hosts", "tag" to "surge-hosts", "predefined" to map))
                rules.add(obj("domain" to map.keys().asSequence().toList(), "action" to "route", "server" to "surge-hosts"))
            }
        }
        servers.add(
            obj("type" to "fakeip", "tag" to "surge-fakeip", "inet4_range" to "198.18.0.0/15").apply {
                if (ipv6) put("inet6_range", "fc00::/18")
            },
        )
        // The proxy-side resolver must answer from the node's vantage point (local and China-side resolvers return
        // polluted answers for blocked names), so it dials 1.1.1.1 DoH through the policy; a DIRECT-capable group is
        // mirrored by a hidden selector so the resolver never falls onto the direct path, where 1.1.1.1 is unreachable.
        for ((policy, tag) in proxyDnsTags) {
            servers.add(obj("type" to "https", "tag" to tag, "server" to "1.1.1.1", "path" to "/dns-query", "detour" to resolverDetour(policy)))
        }
        if (proxyDnsTags.isNotEmpty()) {
            warnings.add("Connections resolved for IP/GEOIP matching and then routed to ${proxyDnsTags.keys.joinToString()} are re-resolved over HTTPS DNS (1.1.1.1) through that policy's selected node; DIRECT/REJECT rules use the profile DNS as in Surge.")
            if (proxyReResolved) warnings.add("IP/GEOIP rules targeting a proxy are re-evaluated with the proxy-side answer; a connection that no longer matches continues to later rules.")
            if (resolverGroups.isNotEmpty()) warnings.add("While ${resolverGroups.keys.joinToString()} has DIRECT selected, re-resolution keeps using the last selected node (hidden group ${SurgeConverter.RESOLVER_GROUP}-*) and those connections then go direct to node-side addresses; use Direct mode for full local resolution.")
        }
        rules.add(obj("query_type" to (if (ipv6) listOf("A", "AAAA") else listOf("A")), "action" to "route", "server" to "surge-fakeip"))
        // sing-box 1.14 keys its DNS cache by server, so a locally resolved (possibly polluted)
        // answer never serves the proxy-side resolver.
        return obj("servers" to servers, "rules" to rules, "final" to SurgeConverter.LOCAL_DNS, "strategy" to strategy())
    }

    /** Validates an IP prefix; a bare address becomes a host prefix as Surge accepts for SRC-IP. */
    private fun cidr(value: String, line: Int): String {
        val parts = value.split('/')
        val address = parts[0]
        if (parts.size > 2 || !address.matches(Regex("[0-9a-fA-F:.]+"))) SurgeSyntax.fail(line, "invalid IP prefix")
        val bits = if (':' in address) 128 else 32
        val prefix = if (parts.size == 2) parts[1].toIntOrNull() ?: SurgeSyntax.fail(line, "invalid IP prefix") else bits
        if (prefix !in 0..bits) SurgeSyntax.fail(line, "invalid IP prefix")
        return "$address/$prefix"
    }

    private fun reachesDirect(policy: String, seen: MutableSet<String> = mutableSetOf()): Boolean = policy == "DIRECT" || (seen.add(policy) && groupMembers[policy].orEmpty().any { reachesDirect(it, seen) })

    /** False for DIRECT/REJECT and for groups whose every branch ends there; nodes (including unknown names) count as exits. */
    private fun hasProxyExit(policy: String, seen: MutableSet<String> = mutableSetOf()): Boolean {
        if (!isProxyPolicy(policy)) return false
        val members = groupMembers[policy] ?: return true
        return seen.add(policy) && members.any { hasProxyExit(it, seen) }
    }

    /** Outbound for a policy's proxy-side resolver: the policy itself unless it can select DIRECT, then a hidden mirror without DIRECT branches. */
    private fun resolverDetour(policy: String): String {
        if (policy !in groupMembers || !reachesDirect(policy)) return policy
        resolverGroups[policy]?.let { return it }
        val members = groupMembers.getValue(policy).filter(::hasProxyExit).map(::resolverDetour)
        if (members.isEmpty()) return policy
        val tag = "${SurgeConverter.RESOLVER_GROUP}-$policy"
        resolverGroups[policy] = tag
        outbounds.add(obj("type" to "selector", "tag" to tag, "outbounds" to members))
        return tag
    }

    /** Plain DNS endpoint `ip`, `ip:port`, `[ipv6]:port` or a bare IPv6 literal as sing-box server/server_port fields. */
    private fun plainServer(source: String): JSONObject {
        val text = source.trim()
        if (text.isEmpty()) SurgeSyntax.fail(0, "empty DNS server entry")
        val host: String
        val portText: String?
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end < 0 || (text.length > end + 1 && text[end + 1] != ':')) SurgeSyntax.fail(0, "invalid DNS server $text")
            host = text.substring(1, end)
            portText = text.substring(end + 1).removePrefix(":").ifEmpty { null }
        } else if (text.count { it == ':' } == 1) {
            host = text.substringBefore(':')
            portText = text.substringAfter(':')
        } else {
            host = text
            portText = null
        }
        // IPv4, IPv6 (including embedded IPv4 and zone suffixes such as %wlan0); sing-box parses the literal.
        if (!host.matches(Regex("[0-9.]+|[0-9a-fA-F:.]+(%[A-Za-z0-9_.-]+)?"))) SurgeSyntax.fail(0, "invalid DNS server $text")
        val server = obj("server" to host)
        if (portText != null) server.put("server_port", portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: SurgeSyntax.fail(0, "invalid DNS server port in $text"))
        return server
    }

    private fun dnsURL(source: String): URI {
        val uri = checkedURL(source, 0, setOf("https", "h3", "tls", "quic"))
        // sing-box sets only the request path; query parameters would be percent-encoded into it.
        if (uri.rawQuery != null || uri.rawFragment != null) SurgeSyntax.fail(0, "DNS URL query parameters are not supported: $source")
        return uri
    }

    private fun checkedURL(value: String, line: Int, schemes: Set<String> = setOf("https", "http")): URI {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            SurgeSyntax.fail(line, "invalid URL (redacted)")
        }
        if (uri.scheme !in schemes || uri.host == null || uri.userInfo != null || uri.fragment != null) SurgeSyntax.fail(line, "unsupported URL (redacted)")
        return uri
    }
}
