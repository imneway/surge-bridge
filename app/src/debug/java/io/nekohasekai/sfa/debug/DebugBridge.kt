package io.nekohasekai.sfa.debug

import android.net.VpnService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.ServiceConnection
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.surge.ProfileStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Debug variant only. adb forward + run-as authenticate access; no config bodies in responses. */
object DebugBridge : ServiceConnection.Callback {
    private const val PORT = 19090
    private val events = ArrayDeque<JSONObject>()
    private val mutationLock = Any()
    @Volatile private var observed: Status? = null
    private lateinit var connection: ServiceConnection
    private lateinit var token: String

    fun start() {
        if (::connection.isInitialized) return
        val app = Application.application
        token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val tokenFile = File(app.filesDir, "debug-api-token")
        tokenFile.writeText(token)
        tokenFile.setReadable(false, false)
        tokenFile.setReadable(true, true)
        connection = ServiceConnection(app, this)
        connection.connect()
        val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4))
        Thread({
            try {
                ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")).use { server ->
                    event("ready")
                    while (true) {
                        val socket = server.accept()
                        try { workers.execute { serve(socket) } }
                        catch (_: java.util.concurrent.RejectedExecutionException) { socket.close() }
                    }
                }
            } catch (_: Exception) { event("listener_failed") }
        }, "SurgeDebugAPI").start()
    }

    override fun onServiceStatusChanged(status: Status) { observed = status; event("service_${status.name.lowercase()}") }
    override fun onServiceAlert(type: Alert, message: String?) { event("service_alert_${type.name}") }

    @Synchronized
    private fun event(code: String) {
        if (events.size >= 200) events.removeFirst()
        events.addLast(JSONObject().put("time", System.currentTimeMillis()).put("event", code))
    }

    private class Failure(val status: Int, val code: String) : Exception()
    private fun fail(status: Int, code: String): Nothing = throw Failure(status, code)

    private fun serve(socket: Socket) {
        socket.use {
            socket.soTimeout = 10000
            try {
                val input = BufferedInputStream(socket.getInputStream())
                fun line(): String {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (bytes.size() < 8192) {
                        val b = input.read()
                        if (b == -1) fail(400, "incomplete_request")
                        if (b == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
                        bytes.write(b)
                    }
                    fail(431, "header_too_large")
                }
                val request = line().split(' ')
                if (request.size != 3 || request[2] != "HTTP/1.1") fail(400, "bad_request")
                val headers = linkedMapOf<String, String>()
                var headerBytes = 0
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    headerBytes += header.length
                    if (headerBytes > 16384 || ':' !in header) fail(431, "invalid_headers")
                    val key = header.substringBefore(':').lowercase()
                    if (headers.put(key, header.substringAfter(':').trim()) != null) fail(400, "duplicate_header")
                }
                if ("origin" in headers || "transfer-encoding" in headers) fail(403, "unsupported_request")
                if (headers["host"] !in setOf("127.0.0.1:$PORT", "localhost:$PORT")) fail(403, "invalid_host")
                val provided = headers["authorization"].orEmpty().toByteArray()
                if (!MessageDigest.isEqual(provided, "Bearer $token".toByteArray())) fail(401, "unauthorized")
                val size = headers["content-length"]?.toIntOrNull() ?: if ("content-length" in headers) fail(400, "bad_length") else 0
                if (size !in 0..ProfileStore.MAX_BYTES) fail(413, "body_too_large")
                val body = ByteArray(size)
                var offset = 0
                while (offset < size) { val read = input.read(body, offset, size - offset); if (read < 0) fail(400, "incomplete_body"); offset += read }
                val method = request[0]
                if (method !in setOf("GET", "POST")) fail(405, "method_not_allowed")
                val result = if (method == "POST") synchronized(mutationLock) { runBlocking { route(method, request[1], body.toString(Charsets.UTF_8)) } }
                    else runBlocking { route(method, request[1], "") }
                respond(socket, result.first, result.second)
            } catch (e: Failure) { runCatching { respond(socket, e.status, JSONObject().put("error", e.code)) } }
            catch (_: Exception) {
                event("request_failed")
                runCatching { respond(socket, 422, JSONObject().put("error", "operation_failed").put("detail", "No credentials or raw parser diagnostics are returned.")) }
            }
        }
    }

    private fun respond(socket: Socket, status: Int, data: JSONObject) {
        val bytes = data.toString().toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray()); write(bytes); flush() }
    }

    private suspend fun route(method: String, path: String, raw: String): Pair<Int, JSONObject> {
        val app = Application.application
        fun ok(data: JSONObject = JSONObject().put("ok", true)) = 200 to data
        if (method == "GET") return when (path) {
            "/v1/health" -> ok(JSONObject().put("apiVersion", 1).put("appVersion", BuildConfig.VERSION_NAME).put("coreVersion", Libbox.version()))
            "/v1/status" -> ok(JSONObject().put("service", observed?.name ?: "Unknown").put("selectedProfileId", Settings.selectedProfile)
                .put("vpnPermissionGranted", VpnService.prepare(app) == null))
            "/v1/profiles" -> ok(JSONObject().put("profiles", JSONArray(ProfileManager.list().map { profile ->
                JSONObject().put("id", profile.id).put("type", profile.typed.type.name)
                    .put("selected", profile.id == Settings.selectedProfile).put("updatedAt", profile.typed.lastUpdated.time)
                    .put("conversion", ProfileStore.report(File(profile.typed.path)))
            })))
            "/v1/logs" -> ok(JSONObject().put("events", synchronized(this) { JSONArray(events.toList()) }))
            else -> fail(404, "not_found")
        }
        val body = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        when (path) {
            "/v1/profiles/import" -> {
                val source = if (body.has("source")) body.getString("source") else ProfileStore.download(body.getString("url"))
                val typed = TypedProfile().apply {
                    this.path = File(File(app.filesDir, "configs"), "${UUID.randomUUID()}.json").path
                    if (body.has("url")) {
                        type = TypedProfile.Type.Remote; remoteURL = body.getString("url"); autoUpdate = true
                        autoUpdateInterval = body.optInt("intervalMinutes", 60)
                        if (autoUpdateInterval < 15) fail(400, "interval_minimum_15_minutes")
                    }
                    lastUpdated = Date()
                }
                if (body.has("source") && body.has("url")) fail(400, "specify_source_or_url")
                ProfileStore.save(File(typed.path), source)
                val profile = Profile(name = body.optString("name", "Surge Profile"), typed = typed).apply { userOrder = ProfileManager.nextOrder() }
                ProfileManager.create(profile, andSelect = false)
                UpdateProfileWork.reconfigureUpdater()
                event("profile_imported")
                return 201 to JSONObject().put("id", profile.id).put("conversion", ProfileStore.report(File(typed.path)))
            }
            "/v1/profiles/select" -> {
                if (observed != Status.Stopped) fail(409, "stop_service_before_selection")
                val profile = ProfileManager.get(body.getLong("id")) ?: fail(404, "profile_not_found")
                Settings.selectedProfile = profile.id
                ProfileManager.update(profile) // Notify active profile-list observers.
                event("profile_selected")
                return ok()
            }
            "/v1/profiles/update" -> {
                val profile = ProfileManager.get(body.getLong("id")) ?: fail(404, "profile_not_found")
                val source = if (body.has("source")) {
                    if (profile.typed.type != TypedProfile.Type.Local) fail(409, "remote_profile_requires_url_refresh")
                    body.getString("source")
                } else {
                    if (profile.typed.type != TypedProfile.Type.Remote) fail(400, "local_profile_requires_source")
                    ProfileStore.download(profile.typed.remoteURL)
                }
                val changed = ProfileStore.save(File(profile.typed.path), source)
                profile.typed.lastUpdated = Date(); ProfileManager.update(profile)
                event("profile_updated")
                return ok(JSONObject().put("changed", changed).put("reloadRequired", changed && Settings.selectedProfile == profile.id && observed == Status.Started)
                    .put("conversion", ProfileStore.report(File(profile.typed.path))))
            }
            "/v1/service/start" -> {
                if (observed != Status.Stopped) fail(409, "service_not_stopped")
                if (ProfileManager.get(Settings.selectedProfile) == null) fail(409, "select_profile_first")
                if (VpnService.prepare(app) != null) fail(409, "vpn_permission_required_open_app")
                BoxService.start().join()
            }
            "/v1/service/stop" -> BoxService.stop()
            "/v1/service/reload" -> {
                if (observed != Status.Started) fail(409, "service_not_started")
                Libbox.newStandaloneCommandClient().serviceReload()
            }
            else -> fail(404, "not_found")
        }
        event("service_command_accepted")
        return 202 to JSONObject().put("accepted", true).put("observe", "/v1/status")
    }
}
