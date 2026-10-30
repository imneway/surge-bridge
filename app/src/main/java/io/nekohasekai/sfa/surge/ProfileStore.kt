package io.nekohasekai.sfa.surge

import android.util.AtomicFile
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** All import, edit and subscription paths publish only a validated runtime configuration. */
object ProfileStore {
    const val MAX_BYTES = 16 * 1024 * 1024

    @Synchronized
    fun validate(source: String): SurgeConverter.Result = compile(source).first

    @Synchronized
    fun save(file: File, source: String, overrides: JSONObject? = null): Boolean {
        val previous = if (file.exists()) file.readText() else ""
        val effectiveOverrides = overrides ?: snapshots(file).optJSONObject(SurgeConverter.digest(previous))?.optJSONObject("androidOverrides") ?: JSONObject()
        val (converted, pending) = compile(source, effectiveOverrides)
        val result = AndroidRuntimeFeatures.scopeToProfile(converted, file.name)
        val digest = SurgeConverter.digest(result.json)
        val oldDigest = SurgeConverter.digest(previous)
        val oldSnapshots = snapshots(file)
        // Keep the previous source until the atomic runtime rename completes. A crash between
        // writes still finds the correct source by digest, never by a stale sidecar filename.
        val retained = JSONObject()
        oldSnapshots.optJSONObject(oldDigest)?.let { retained.put(oldDigest, it) }
        retained.put(digest, obj("source" to source, "format" to result.sourceFormat, "warnings" to result.warnings, "androidOverrides" to effectiveOverrides))
        atomicWrite(snapshotFile(file), retained.toString())
        if (previous != result.json) atomicWrite(file, result.json)
        // Cache failures cannot turn a successfully published profile into a reported failure.
        pending.forEach { (cache, text) -> runCatching { atomicWrite(cache, text) } }
        return previous != result.json
    }

    @Synchronized
    fun source(file: File): String {
        val runtime = file.readText()
        return snapshots(file).optJSONObject(SurgeConverter.digest(runtime))?.optString("source") ?: runtime
    }

    @Synchronized
    fun report(file: File): JSONObject {
        val runtime = file.readText()
        val snapshot = snapshots(file).optJSONObject(SurgeConverter.digest(runtime))
        return obj(
            "format" to (snapshot?.optString("format") ?: "sing-box"),
            "warnings" to (snapshot?.optJSONArray("warnings") ?: JSONArray()),
            "runtimeSha256" to SurgeConverter.digest(runtime),
            "androidOverrides" to (snapshot?.optJSONObject("androidOverrides") ?: JSONObject()),
        )
    }

    @Synchronized
    fun markRunning(file: File) {
        val runtime = file.readText()
        val snapshot = snapshots(file).optJSONObject(SurgeConverter.digest(runtime)) ?: return
        atomicWrite(File(file.path + ".running"), obj("runtime" to runtime, "snapshot" to snapshot).toString())
    }

    @Synchronized
    fun restoreRunning(file: File): Boolean {
        val backup = File(file.path + ".running")
        if (!backup.exists()) return false
        val saved = JSONObject(AtomicFile(backup).openRead().bufferedReader().use { it.readText() })
        val runtime = saved.getString("runtime")
        val current = file.readText()
        if (current == runtime) return false
        Libbox.checkConfig(runtime)
        val retained = snapshots(file)
        val result = JSONObject().put(SurgeConverter.digest(runtime), saved.getJSONObject("snapshot"))
        retained.optJSONObject(SurgeConverter.digest(current))?.let { result.put(SurgeConverter.digest(current), it) }
        atomicWrite(snapshotFile(file), result.toString())
        atomicWrite(file, runtime)
        return true
    }

    /** Upgrade cached generated JSON without downloading subscriptions/rule sets at VPN start. */
    @Synchronized
    fun ensureRuntimeFeatures(file: File) {
        val runtime = file.readText()
        val oldDigest = SurgeConverter.digest(runtime)
        val old = snapshots(file)
        val snapshot = old.optJSONObject(oldDigest) ?: return
        val result = AndroidRuntimeFeatures.scopeToProfile(
            AndroidRuntimeFeatures.apply(
                SurgeConverter.Result(runtime, emptyList(), snapshot.optString("format")),
                snapshot.optJSONObject("androidOverrides") ?: JSONObject(),
            ),
            file.name,
        )
        if (result.json == runtime) return
        Libbox.checkConfig(result.json)
        val retained = JSONObject().put(oldDigest, snapshot).put(SurgeConverter.digest(result.json), snapshot)
        atomicWrite(snapshotFile(file), retained.toString())
        atomicWrite(file, result.json)
    }

    private fun compile(source: String, overrides: JSONObject = JSONObject()): Pair<SurgeConverter.Result, Map<File, String>> {
        require(source.toByteArray().size <= MAX_BYTES) { "Configuration exceeds 16 MiB" }
        val pending = linkedMapOf<File, String>()
        val cacheDir = File(Application.application.filesDir, "surge-rule-cache")
        val converted = SurgeConverter { url, ttl ->
            val cache = File(cacheDir, SurgeConverter.digest(url))
            val age = System.currentTimeMillis() - cache.lastModified()
            if (cache.isFile && age in 0 until ttl.toLong() * 1000) {
                cache.readText()
            } else {
                pending.getOrPut(cache) { download(url) }
            }
        }.convert(source)
        val result = AndroidRuntimeFeatures.apply(AndroidRoutingOverrides.apply(converted, overrides), overrides)
        try {
            Libbox.checkConfig(result.json)
        } catch (_: Exception) {
            // Native parser diagnostics can include passwords and subscription query strings.
            throw IllegalArgumentException("Generated configuration rejected by sing-box; no changes saved")
        }
        return result to pending
    }

    fun download(url: String): String {
        try {
            var current = URI(url)
            repeat(6) {
                require(current.scheme in setOf("https", "http") && current.host != null && current.userInfo == null)
                val connection = current.toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 20000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "SurgeBridge/1")
                try {
                    if (connection.responseCode in setOf(301, 302, 303, 307, 308)) {
                        val next = current.resolve(connection.getHeaderField("Location") ?: error("redirect"))
                        require(current.scheme != "https" || next.scheme == "https")
                        current = next
                    } else {
                        require(connection.responseCode in 200..299)
                        require(connection.contentLength <= MAX_BYTES)
                        return connection.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                require(output.size() + size <= MAX_BYTES)
                                output.write(buffer, 0, size)
                            }
                            output.toString("UTF-8")
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            error("redirect limit")
        } catch (_: Exception) {
            throw IllegalArgumentException("Configuration download failed; address and response redacted")
        }
    }

    private fun snapshotFile(file: File) = File(file.path + ".source")
    private fun snapshots(file: File): JSONObject = runCatching {
        JSONObject(AtomicFile(snapshotFile(file)).openRead().bufferedReader().use { it.readText() })
    }.getOrDefault(JSONObject())

    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}
