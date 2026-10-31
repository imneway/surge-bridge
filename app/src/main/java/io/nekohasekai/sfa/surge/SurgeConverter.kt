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
    private val policies = linkedSetOf("DIRECT", "REJECT")
    private val references = mutableListOf<Pair<Int, String>>()
    private val groupGraph = linkedMapOf<String, List<String>>()
    private val general = linkedMapOf<String, String>()
    private var defaultPolicy = "DIRECT"
    private var ipv6 = false

    fun convert(source: String): SurgeConverter.Result {
        val sections = SurgeSyntax.sections(source)
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
            } else rule(entry)
        }
        if (!finalSeen) SurgeSyntax.fail(0, "a FINAL policy is required")
        if (defaultPolicy == "REJECT") routes.add(obj("action" to "reject"))
        references.forEach { (line, name) -> if (name !in policies) SurgeSyntax.fail(line, "unknown policy reference") }
        checkCycles()
        val tun = obj("type" to "tun", "tag" to "surge-tun", "address" to
            (if (ipv6) listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126") else listOf("172.19.0.1/30")),
            "auto_route" to true, "strict_route" to true, "stack" to "mixed")
        general["tun-excluded-routes"]?.let { value ->
            val cidrs = SurgeSyntax.split(value, 0)
            cidrs.forEach { cidr(it, 0) }
            tun.put("route_exclude_address", JSONArray(cidrs))
        }
        val dns = dns(sections["host"].orEmpty())
        val route = obj("rules" to routes, "rule_set" to ruleSets.values.toList(), "auto_detect_interface" to true,
            "default_domain_resolver" to obj("server" to "surge-dns-0", "strategy" to if (ipv6) "prefer_ipv4" else "ipv4_only"),
            "final" to if (defaultPolicy == "REJECT") "DIRECT" else defaultPolicy)
        val config = obj("log" to obj("level" to logLevel()), "dns" to dns, "inbounds" to listOf(tun),
            "outbounds" to outbounds, "route" to route,
            "experimental" to obj("cache_file" to obj("enabled" to true, "store_fakeip" to true)))
        return SurgeConverter.Result(config.toString(2), warnings.toList(), "surge")
    }

    private fun validateGeneral() {
        val supported = setOf("loglevel", "ipv6", "exclude-simple-hostnames", "dns-server", "encrypted-dns-server",
            "skip-proxy", "tun-excluded-routes", "hijack-dns", "internet-test-url", "proxy-test-url", "test-timeout")
        general.keys.forEach { if (it !in supported) SurgeSyntax.fail(0, "unsupported general option $it") }
        general["hijack-dns"]?.let { if (it != "*:53") SurgeSyntax.fail(0, "only hijack-dns = *:53 is supported") }
        if ("skip-proxy" in general) warnings.add("skip-proxy is an HTTP proxy bypass list; Android uses TUN. Use tun-excluded-routes and DIRECT rules for VPN bypass.")
        if ("internet-test-url" in general) warnings.add("internet-test-url does not change the Android system connectivity probe.")
        if ("test-timeout" in general) { SurgeSyntax.positive(general.getValue("test-timeout"), 0); warnings.add("test-timeout is not mapped; sing-box owns probe timeouts.") }
    }

    private fun logLevel(): String = when (general["loglevel"] ?: "notify") {
        "notify", "info" -> "info"
        "verbose", "debug" -> "debug"
        "warning", "error" -> general.getValue("loglevel")
        else -> SurgeSyntax.fail(0, "unsupported loglevel")
    }

    private fun addName(name: String, line: Int) {
        if (name.isBlank() || !policies.add(name)) SurgeSyntax.fail(line, "empty or duplicate policy name")
    }

    private fun proxy(entry: Entry) {
        val (name, value) = SurgeSyntax.assignment(entry)
        addName(name, entry.line)
        val f = SurgeSyntax.split(value, entry.line)
        if (f.size < 3) SurgeSyntax.fail(entry.line, "proxy requires type, server and port")
        val kind = f[0].lowercase()
        val port = SurgeSyntax.positive(f[2], entry.line)
        if (port > 65535) SurgeSyntax.fail(entry.line, "port is out of range")
        val p = SurgeSyntax.options(f.drop(3), entry.line)
        val common = setOf("tfo", "underlying-proxy", "udp-relay", "test-url")
        val specific = when (kind) {
            "snell" -> setOf("psk", "version", "mode", "reuse", "obfs", "obfs-host")
            "ss" -> setOf("encrypt-method", "password")
            "trojan", "anytls" -> setOf("password", "sni", "skip-cert-verify")
            "http", "https", "socks5", "socks5-tls" -> setOf("username", "password", "sni", "skip-cert-verify")
            else -> SurgeSyntax.fail(entry.line, "unsupported proxy protocol $kind")
        }
        p.keys.forEach { if (it !in common + specific) SurgeSyntax.fail(entry.line, "unsupported proxy parameter $it") }
        val type = when (kind) { "ss" -> "shadowsocks"; "https" -> "http"; "socks5", "socks5-tls" -> "socks"; else -> kind }
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
                    p["obfs"]?.let { if (it != "http") SurgeSyntax.fail(entry.line, "only HTTP obfs is supported for Snell v4"); node.put("obfs_mode", it) }
                    p["obfs-host"]?.let { node.put("obfs_host", it) }
                }
            }
            "ss" -> node.put("method", required("encrypt-method")).put("password", required("password"))
            "trojan", "anytls" -> node.put("password", required("password"))
            else -> { p["username"]?.let { node.put("username", it) }; p["password"]?.let { node.put("password", it) } }
        }
        if (kind in setOf("trojan", "anytls", "https", "socks5-tls")) {
            node.put("tls", obj("enabled" to true, "server_name" to (p["sni"] ?: SurgeSyntax.value(f[1])),
                "insecure" to (p["skip-cert-verify"]?.let { SurgeSyntax.boolean(it, entry.line) } ?: false)))
        }
        p["tfo"]?.let { node.put("tcp_fast_open", SurgeSyntax.boolean(it, entry.line)) }
        p["underlying-proxy"]?.let { node.put("detour", it); references.add(entry.line to it); groupGraph[name] = listOf(it) }
        p["udp-relay"]?.let { if (!SurgeSyntax.boolean(it, entry.line)) node.put("network", "tcp") }
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
        if (members.isEmpty() || "REJECT" in members) SurgeSyntax.fail(entry.line, "group requires usable outbound members")
        options.keys.forEach { if (it !in setOf("url", "interval", "tolerance")) SurgeSyntax.fail(entry.line, "unsupported group option $it") }
        references.addAll(members.map { entry.line to it })
        groupGraph[name] = members
        val node = obj("type" to if (kind == "select") "selector" else "urltest", "tag" to name, "outbounds" to members)
        if (kind == "url-test") {
            node.put("url", options["url"] ?: general["proxy-test-url"] ?: "https://www.gstatic.com/generate_204")
            node.put("interval", "${SurgeSyntax.positive(options["interval"] ?: "300", entry.line)}s")
            options["tolerance"]?.let { node.put("tolerance", SurgeSyntax.positive(it, entry.line)) }
        } else if (options.isNotEmpty()) SurgeSyntax.fail(entry.line, "select group does not accept test options")
        outbounds.add(node)
    }

    private fun checkCycles() {
        val active = mutableSetOf<String>(); val done = mutableSetOf<String>()
        fun visit(name: String) {
            if (name in done) return
            if (!active.add(name)) SurgeSyntax.fail(0, "cyclic group or underlying-proxy reference")
            groupGraph[name].orEmpty().forEach(::visit)
            active.remove(name); done.add(name)
        }
        groupGraph.keys.forEach(::visit)
    }

    private data class Condition(val json: JSONObject, val resolve: Boolean)

    private fun rule(entry: Entry) {
        val fields = SurgeSyntax.split(entry.text, entry.line)
        if (fields.size < 3) SurgeSyntax.fail(entry.line, "rule requires type, value and policy")
        val policy = SurgeSyntax.value(fields[2])
        references.add(entry.line to policy)
        if (fields[0].uppercase() == "RULE-SET") {
            val opts = fields.drop(3)
            val noResolve = "no-resolve" in opts
            val params = SurgeSyntax.options(opts.filter { it != "no-resolve" }, entry.line)
            params.keys.forEach { if (it != "update-interval") SurgeSyntax.fail(entry.line, "unsupported rule-set option $it") }
            val interval = SurgeSyntax.positive(params["update-interval"] ?: "86400", entry.line)
            val url = SurgeSyntax.value(fields[1]); checkedURL(url, entry.line)
            val text = try { fetchRules(url, interval) } catch (_: Exception) { SurgeSyntax.fail(entry.line, "could not fetch rule set (URL redacted)") }
            var count = 0
            val conditions = mutableListOf<Condition>()
            val skipped = linkedMapOf<String, Int>()
            text.lineSequence().forEachIndexed { index, raw ->
                val line = SurgeSyntax.clean(raw)
                if (line.isEmpty()) return@forEachIndexed
                count++
                if (count > 200000) SurgeSyntax.fail(entry.line, "rule set exceeds 200000 entries")
                var parts = SurgeSyntax.split(line, entry.line)
                if (parts.size == 3 && SurgeSyntax.value(parts[2]) == policy) {
                    parts = parts.take(2)
                    warnings.add("Rule set at line ${entry.line}: redundant per-entry policies matching the enclosing policy were removed.")
                }
                if (parts.size !in 2..3 || (parts.size == 3 && parts[2] != "no-resolve")) SurgeSyntax.fail(entry.line, "unsupported rule-set entry at remote line ${index + 1}")
                try {
                    conditions.add(condition(parts.take(2) + if (noResolve || parts.last() == "no-resolve") listOf("no-resolve") else emptyList(), entry.line))
                } catch (e: SurgeException) {
                    // These platform-specific / unavailable matchers have no faithful Android
                    // equivalent. Preserve usable rules and explicitly expose the coverage gap.
                    val unsupported = e.reason.removePrefix("unsupported rule type ")
                    if (e.reason.startsWith("unsupported rule type ") && unsupported in setOf("PROCESS-NAME", "USER-AGENT", "IP-ASN")) {
                        skipped[unsupported] = (skipped[unsupported] ?: 0) + 1
                    } else throw e
                }
            }
            if (conditions.isEmpty()) SurgeSyntax.fail(entry.line, "rule set has no supported entries")
            skipped.forEach { (kind, number) -> warnings.add("Rule set at line ${entry.line}: $number rules containing $kind omitted; matching falls through to later rules.") }
            // One indexed headless rule per matcher avoids 100k+ linear route entries.
            // Evaluate matches not requiring DNS before the DNS-resolving IP subset.
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
                    } else other.add(item.json)
                }
                val tag = "surge-list-${entry.line}-${if (resolve) "resolve" else "literal"}"
                ruleSets[tag] = obj("type" to "inline", "tag" to tag,
                    "rules" to (merged.map { (key, values) -> obj(key to values.distinct()) } + other))
                append(Condition(obj("rule_set" to listOf(tag)), resolve), policy)
            }
        } else {
            append(condition(fields.take(2) + fields.drop(3), entry.line), policy)
        }
    }

    private fun append(condition: Condition, policy: String) {
        if (condition.resolve) routes.add(obj("action" to "resolve", "strategy" to if (ipv6) "prefer_ipv4" else "ipv4_only"))
        if (policy == "REJECT") condition.json.put("action", "reject")
        else condition.json.put("action", "route").put("outbound", policy)
        routes.add(condition.json)
    }

    private fun condition(fields: List<String>, line: Int, depth: Int = 0): Condition {
        if (depth > 16 || fields.size !in 2..3) SurgeSyntax.fail(line, "invalid rule condition")
        val kind = fields[0].uppercase(); val value = SurgeSyntax.value(fields[1])
        val noResolve = fields.getOrNull(2) == "no-resolve"
        if (fields.size == 3 && !noResolve) SurgeSyntax.fail(line, "unsupported rule modifier")
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
            "IP-CIDR", "IP-CIDR6" -> { cidr(value, line); json = obj("ip_cidr" to listOf(value)); resolve = true }
            "SRC-IP" -> { cidr(value, line); json = obj("source_ip_cidr" to listOf(value)) }
            "DEST-PORT", "SRC-PORT" -> {
                val port = SurgeSyntax.positive(value, line)
                if (port > 65535) SurgeSyntax.fail(line, "rule port out of range")
                json = obj(if (kind == "DEST-PORT") "port" to port else "source_port" to port)
            }
            "PROTOCOL" -> {
                if (value.uppercase() !in setOf("TCP", "UDP")) SurgeSyntax.fail(line, "unsupported PROTOCOL")
                json = obj("network" to value.lowercase())
            }
            "GEOIP" -> {
                val country = value.lowercase()
                if (!country.matches(Regex("[a-z]{2}"))) SurgeSyntax.fail(line, "GEOIP requires a country code")
                val tag = "surge-geoip-$country"
                ruleSets[tag] = obj("type" to "remote", "tag" to tag, "format" to "binary",
                    "url" to "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-$country.srs",
                    "http_client" to obj("engine" to "go"), "update_interval" to "1d")
                json = obj("rule_set" to listOf(tag)); resolve = true
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
        servers.add(if (bootstrap == "system") obj("type" to "local", "tag" to "surge-bootstrap")
            else obj("type" to "udp", "tag" to "surge-bootstrap", "server" to bootstrap))
        for ((i, source) in sources.withIndex()) {
            val tag = "surge-dns-$i"
            if (source == "system") servers.add(obj("type" to "local", "tag" to tag))
            else if ("://" !in source) servers.add(obj("type" to "udp", "tag" to tag, "server" to source))
            else {
                val uri = checkedURL(source, 0, setOf("https", "h3", "tls", "quic"))
                val type = uri.scheme
                val server = obj("type" to type, "tag" to tag, "server" to uri.host, "domain_resolver" to "surge-bootstrap")
                if (uri.port != -1) server.put("server_port", uri.port)
                if (uri.scheme in setOf("https", "h3")) server.put("path", uri.rawPath.ifEmpty { "/dns-query" } + (uri.rawQuery?.let { "?$it" } ?: ""))
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
                if (host.contains('*')) SurgeSyntax.fail(entry.line, "wildcard hosts are not supported")
                val ips = SurgeSyntax.split(value, entry.line)
                if (ips.any { !it.matches(Regex("[0-9a-fA-F:.]+")) }) SurgeSyntax.fail(entry.line, "hosts must map to IP literals")
                map.put(host, JSONArray(ips))
            }
            servers.add(obj("type" to "hosts", "tag" to "surge-hosts", "predefined" to map))
            rules.add(obj("domain" to hosts.map { SurgeSyntax.assignment(it).first }, "action" to "route", "server" to "surge-hosts"))
        }
        servers.add(obj("type" to "fakeip", "tag" to "surge-fakeip", "inet4_range" to "198.18.0.0/15").apply {
            if (ipv6) put("inet6_range", "fc00::/18")
        })
        rules.add(obj("query_type" to (if (ipv6) listOf("A", "AAAA") else listOf("A")), "action" to "route", "server" to "surge-fakeip"))
        return obj("servers" to servers, "rules" to rules, "final" to "surge-dns-0", "strategy" to if (ipv6) "prefer_ipv4" else "ipv4_only")
    }

    private fun cidr(value: String, line: Int) {
        val parts = value.split('/')
        if (parts.size != 2 || !parts[0].matches(Regex("[0-9a-fA-F:.]+")) ||
            parts[1].toIntOrNull() !in 0..(if (':' in parts[0]) 128 else 32)) SurgeSyntax.fail(line, "invalid IP prefix")
    }

    private fun checkedURL(value: String, line: Int, schemes: Set<String> = setOf("https", "http")): URI {
        val uri = try { URI(value) } catch (_: Exception) { SurgeSyntax.fail(line, "invalid URL (redacted)") }
        if (uri.scheme !in schemes || uri.host == null || uri.userInfo != null || uri.fragment != null) SurgeSyntax.fail(line, "unsupported URL (redacted)")
        return uri
    }
}
