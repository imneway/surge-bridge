package io.nekohasekai.sfa.surge

import android.util.AtomicFile
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.Date

/** One serialized refresh path for UI, periodic work and debug automation. */
object ProfileRefresh {
    private val access = Mutex()
    suspend fun refresh(profile: Profile, onlyIfDue: Boolean = false): Boolean = access.withLock {
        val latest = ProfileManager.get(profile.id) ?: error("Profile removed during update")
        if (onlyIfDue && (
                !latest.typed.autoUpdate || latest.typed.type != TypedProfile.Type.Remote ||
                    System.currentTimeMillis() - latest.typed.lastUpdated.time < latest.typed.autoUpdateInterval.toLong() * 60_000
                )
        ) {
            return@withLock false
        }
        require(latest.typed.type == TypedProfile.Type.Remote) { "Only remote profiles have a subscription URL" }
        val attempted = System.currentTimeMillis()
        try {
            val source = ProfileStore.download(latest.typed.remoteURL)
            val beforePublish = ProfileManager.get(profile.id) ?: error("Profile removed during update")
            require(beforePublish.typed.type == TypedProfile.Type.Remote && beforePublish.typed.remoteURL == latest.typed.remoteURL) { "Subscription changed during update; retry" }
            val changed = ProfileStore.save(File(profile.typed.path), source)
            // Fetch current metadata again so an in-flight download cannot undo a user's switch/interval edit.
            val current = ProfileManager.get(profile.id) ?: error("Profile removed during update")
            current.typed.lastUpdated = Date()
            ProfileManager.update(current)
            profile.typed.lastUpdated = current.typed.lastUpdated
            record(profile, JSONObject().put("attemptedAt", attempted).put("success", true).put("changed", changed))
            changed
        } catch (e: Exception) {
            record(
                profile,
                JSONObject().put("attemptedAt", attempted).put("success", false)
                    .put("message", "Download or validation failed; previous configuration retained"),
            )
            throw e
        }
    }
    fun report(profile: Profile): JSONObject = runCatching {
        JSONObject(AtomicFile(File(profile.typed.path + ".refresh")).openRead().bufferedReader().use { it.readText() })
    }.getOrDefault(JSONObject())
    fun recordApplyRollback(profile: Profile) {
        record(
            profile,
            JSONObject().put("attemptedAt", System.currentTimeMillis()).put("success", false)
                .put("message", "Core reload failed; last running configuration restored"),
        )
    }
    private fun record(profile: Profile, value: JSONObject) {
        // Diagnostics must never convert a successful configuration update into a failure.
        runCatching {
            val file = AtomicFile(File(profile.typed.path + ".refresh"))
            val out = file.startWrite()
            try {
                out.write(value.toString().toByteArray())
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        }
    }
}
