package io.nekohasekai.sfa.surge

import android.util.AtomicFile
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * All import, edit and subscription paths publish only a validated runtime configuration.
 * Network downloads run outside the store lock so a slow subscription never blocks VPN start.
 */
object ProfileStore {
    const val MAX_BYTES = 16 * 1024 * 1024

    /** Generated-config generation; older snapshots are regenerated offline from their source at VPN start. */
    const val GENERATION = 3
    private const val TAG = "ProfileStore"
    private val lock = Any()

    fun validate(source: String): SurgeConverter.Result {
        val (converted, pending) = convert(source)
        val result = AndroidRuntimeFeatures.apply(AndroidRoutingOverrides.apply(converted, JSONObject()), JSONObject())
        check(result)
        // Rule sets fetched for a successful check are reusable; the editor must not re-download on every pause.
        // Cache files are shared by URL, so publishing them serializes under the store lock.
        synchronized(lock) { pending.forEach { (cache, text) -> runCatching { atomicWrite(cache, text) } } }
        return result
    }

    fun save(file: File, source: String, overrides: JSONObject? = null): Boolean {
        val (converted, pending) = convert(source)
        synchronized(lock) {
            val previous = if (file.exists()) file.readText() else ""
            val effectiveOverrides = overrides ?: snapshots(file).optJSONObject(SurgeConverter.digest(previous))?.optJSONObject("androidOverrides") ?: JSONObject()
            val result = AndroidRuntimeFeatures.scopeToProfile(
                AndroidRuntimeFeatures.apply(AndroidRoutingOverrides.apply(converted, effectiveOverrides), effectiveOverrides),
                file.name,
            )
            check(result)
            val digest = SurgeConverter.digest(result.json)
            val oldDigest = SurgeConverter.digest(previous)
            val oldSnapshots = snapshots(file)
            // Keep the previous source until the atomic runtime rename completes. A crash between
            // writes still finds the correct source by digest, never by a stale sidecar filename.
            val retained = JSONObject()
            oldSnapshots.optJSONObject(oldDigest)?.let { retained.put(oldDigest, it) }
            retained.put(digest, snapshot(source, result, effectiveOverrides))
            atomicWrite(snapshotFile(file), retained.toString())
            if (previous != result.json) atomicWrite(file, result.json)
            AtomicFile(rejectedFile(file)).delete()
            // Cache failures cannot turn a successfully published profile into a reported failure.
            pending.forEach { (cache, text) -> runCatching { atomicWrite(cache, text) } }
            return previous != result.json
        }
    }

    private fun snapshot(source: String, result: SurgeConverter.Result, overrides: JSONObject) = obj(
        "source" to source,
        "format" to result.sourceFormat,
        "warnings" to result.warnings,
        "androidOverrides" to overrides,
        "generation" to GENERATION,
    )

    fun source(file: File): String {
        synchronized(lock) {
            val runtime = file.readText()
            return snapshots(file).optJSONObject(SurgeConverter.digest(runtime))?.optString("source") ?: runtime
        }
    }

    /** Source text whose runtime the core refused to load, kept so the user can correct it. */
    fun rejectedSource(file: File): String? {
        synchronized(lock) {
            val rejected = rejectedFile(file)
            if (!rejected.exists()) return null
            return runCatching { AtomicFile(rejected).openRead().bufferedReader().use { it.readText() } }.getOrNull()
        }
    }

    fun report(file: File): JSONObject {
        synchronized(lock) {
            val runtime = file.readText()
            val snapshot = snapshots(file).optJSONObject(SurgeConverter.digest(runtime))
            return obj(
                "format" to (snapshot?.optString("format") ?: "sing-box"),
                "warnings" to (snapshot?.optJSONArray("warnings") ?: JSONArray()),
                "runtimeSha256" to SurgeConverter.digest(runtime),
                "androidOverrides" to (snapshot?.optJSONObject("androidOverrides") ?: JSONObject()),
                "generation" to (snapshot?.optInt("generation", 1) ?: 0),
                "rejectedEdit" to rejectedFile(file).exists(),
            )
        }
    }

    fun markRunning(file: File) {
        synchronized(lock) {
            val runtime = file.readText()
            val snapshot = snapshots(file).optJSONObject(SurgeConverter.digest(runtime)) ?: return
            atomicWrite(File(file.path + ".running"), obj("runtime" to runtime, "snapshot" to snapshot).toString())
        }
    }

    fun restoreRunning(file: File): Boolean = synchronized(lock) {
        restore(file)
    }

    private fun restore(file: File): Boolean {
        val backup = File(file.path + ".running")
        if (!backup.exists()) return false
        val saved = JSONObject(AtomicFile(backup).openRead().bufferedReader().use { it.readText() })
        val runtime = saved.getString("runtime")
        val current = file.readText()
        if (current == runtime) return false
        Libbox.checkConfig(runtime)
        val retained = snapshots(file)
        val result = JSONObject().put(SurgeConverter.digest(runtime), saved.getJSONObject("snapshot"))
        val currentSnapshot = retained.optJSONObject(SurgeConverter.digest(current))
        currentSnapshot?.let { result.put(SurgeConverter.digest(current), it) }
        // The rejected edit stays visible in the editor instead of silently disappearing.
        currentSnapshot?.optString("source")?.takeIf { it.isNotEmpty() && it != saved.getJSONObject("snapshot").optString("source") }
            ?.let { runCatching { atomicWrite(rejectedFile(file), it) } }
        atomicWrite(snapshotFile(file), result.toString())
        atomicWrite(file, runtime)
        return true
    }

    /**
     * Upgrade cached generated JSON at VPN start without downloading subscriptions or rule sets.
     * Any failure keeps the current runtime; startup must never depend on this step.
     */
    fun ensureRuntimeFeatures(file: File) {
        synchronized(lock) {
            runCatching { upgrade(file) }.onFailure { Log.w(TAG, "runtime upgrade skipped: ${it.javaClass.simpleName}") }
        }
    }

    private fun upgrade(file: File) {
        val runtime = file.readText()
        val oldDigest = SurgeConverter.digest(runtime)
        val snapshot = snapshots(file).optJSONObject(oldDigest) ?: return
        val overrides = snapshot.optJSONObject("androidOverrides") ?: JSONObject()
        val format = snapshot.optString("format")
        val generation = snapshot.optInt("generation", 1)
        // Regenerate old generations from the stored source using cached rule sets only, whatever
        // their age. A missing cache falls back to migrating the existing runtime JSON in place.
        val regenerated = if (format == "surge" && generation < GENERATION) {
            runCatching {
                val (converted, _) = convert(snapshot.getString("source"), offline = true)
                AndroidRuntimeFeatures.scopeToProfile(AndroidRuntimeFeatures.apply(AndroidRoutingOverrides.apply(converted, overrides), overrides), file.name)
                    .also { if (it.json != runtime) Libbox.checkConfig(it.json) }
            }.onFailure { Log.w(TAG, "offline regeneration unavailable: ${it.javaClass.simpleName}") }.getOrNull()
        } else {
            null
        }
        val result = regenerated ?: AndroidRuntimeFeatures.scopeToProfile(
            AndroidRuntimeFeatures.apply(SurgeConverter.Result(runtime, emptyList(), format), overrides),
            file.name,
        )
        if (result.json == runtime && regenerated == null) return
        if (regenerated == null && result.json != runtime) Libbox.checkConfig(result.json)
        val upgraded = JSONObject(snapshot.toString())
        if (regenerated != null) upgraded.put("generation", GENERATION).put("warnings", JSONArray(result.warnings))
        val retained = JSONObject().put(oldDigest, snapshot).put(SurgeConverter.digest(result.json), upgraded)
        atomicWrite(snapshotFile(file), retained.toString())
        if (result.json != runtime) atomicWrite(file, result.json)
    }

    /** Removes the runtime and its sidecars; the shared rule-set cache is keyed by URL and kept. */
    fun deleteFiles(file: File) {
        synchronized(lock) {
            listOf(file, snapshotFile(file), rejectedFile(file), File(file.path + ".running"), File(file.path + ".refresh"))
                .forEach { AtomicFile(it).delete() }
        }
    }

    private fun check(result: SurgeConverter.Result) {
        try {
            Libbox.checkConfig(result.json)
        } catch (_: Exception) {
            // Native parser diagnostics can include passwords and subscription query strings.
            throw IllegalArgumentException("Generated configuration rejected by sing-box; no changes saved")
        }
    }

    private fun convert(source: String, offline: Boolean = false): Pair<SurgeConverter.Result, Map<File, String>> {
        require(source.toByteArray().size <= MAX_BYTES) { "Configuration exceeds 16 MiB" }
        val pending = linkedMapOf<File, String>()
        val cacheDir = File(Application.application.filesDir, "surge-rule-cache")
        val converted = SurgeConverter { url, ttl ->
            val cache = File(cacheDir, SurgeConverter.digest(url))
            val age = System.currentTimeMillis() - cache.lastModified()
            when {
                cache.isFile && (offline || age in 0 until ttl.toLong() * 1000) -> cache.readText()
                offline -> error("rule set cache unavailable")
                else -> pending.getOrPut(cache) { download(url) }
            }
        }.convert(source)
        return converted to pending
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
    private fun rejectedFile(file: File) = File(file.path + ".rejected")
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
