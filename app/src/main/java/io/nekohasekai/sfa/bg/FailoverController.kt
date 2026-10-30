package io.nekohasekai.sfa.bg

import android.os.SystemClock
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.sfa.surge.FailoverDecision
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Lives in the VPN service, not the activity. Native HTTPS probes use each actual outbound. */
class FailoverController(private val enabledGroups: List<String>) : CommandClient.Handler {
    companion object {
        private val mutableStatus = MutableStateFlow("{}")
        val status = mutableStatus.asStateFlow()
        fun report() = JSONObject(mutableStatus.value)
    }
    private data class Member(val tag: String, val time: Long)
    private data class Group(val selected: String, val members: List<Member>)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = CommandClient(scope, listOf(CommandClient.ConnectionType.Groups, CommandClient.ConnectionType.ClashMode), this, localOnly = true)

    @Volatile private var groups = emptyMap<String, Group>()

    @Volatile private var mode = "Rule"

    @Volatile private var connected = false
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private val decisions = enabledGroups.associateWith { FailoverDecision() }
    override fun onConnected() {
        connected = true
    }
    override fun onDisconnected() {
        connected = false
    }
    override fun initializeClashMode(modeList: List<String>, currentMode: String) {
        if (mode != currentMode) epoch.incrementAndGet()
        mode = currentMode
    }
    override fun updateClashMode(newMode: String) {
        if (mode != newMode) epoch.incrementAndGet()
        mode = newMode
    }
    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        val previous = groups
        val updated = newGroups.filter { it.selectable }.associate { group ->
            val members = mutableListOf<Member>()
            val iterator = group.items
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.tag != "DIRECT") members.add(Member(item.tag, item.urlTestTime))
            }
            group.tag to Group(group.selected, members)
        }
        if (updated.any { (tag, group) -> previous[tag]?.selected != group.selected }) epoch.incrementAndGet()
        groups = updated
    }
    fun start() {
        if (enabledGroups.isEmpty()) {
            mutableStatus.value = "{}"
            return
        }
        client.connect()
        scope.launch {
            delay(3000)
            while (isActive) {
                if (!connected || mode.equals("Direct", true)) {
                    decisions.values.forEach { it.reset() }
                    publishAll(if (connected) "paused_direct" else "waiting_for_core")
                    if (!connected) client.connect()
                    delay(5000)
                    continue
                }
                val snapshot = groups
                if (enabledGroups.all { snapshot[it]?.selected == "DIRECT" }) {
                    decisions.values.forEach { it.reset() }
                    publishAll("paused_manual_direct")
                    delay(5000)
                    continue
                }
                val roundEpoch = epoch.get()
                val started = System.currentTimeMillis() / 1000 + 1
                // A new second prevents a previous probe in the same second counting as fresh.
                delay(1100)
                if (!connected || epoch.get() != roundEpoch || mode.equals("Direct", true)) continue
                val requested = enabledGroups.mapNotNull { snapshot[it] }.filter { it.selected != "DIRECT" }.flatMap { it.members }.map { it.tag }.distinct()
                var accepted = true
                for (tag in requested) {
                    ensureActive()
                    if (epoch.get() != roundEpoch || mode.equals("Direct", true)) {
                        accepted = false
                        break
                    }
                    if (runCatching { Libbox.newStandaloneCommandClient().urlTest(tag) }.isFailure) accepted = false
                }
                publishAll("probing")
                // Native URLTest is asynchronous. A round has a bounded wait and only fresh
                // successful observations qualify. Missing/expired results are probe timeouts.
                delay(35_000)
                ensureActive()
                if (accepted && connected && epoch.get() == roundEpoch && !mode.equals("Direct", true)) {
                    val result = JSONObject()
                    for (tag in enabledGroups) {
                        val before = snapshot[tag] ?: continue
                        val current = groups[tag] ?: continue
                        if (before.selected != current.selected) {
                            decisions.getValue(tag).reset()
                            continue
                        }
                        val healthy = current.members.filter { it.time >= started }.map { it.tag }.toSet()
                        val decision = decisions.getValue(tag).evaluate(current.selected, current.members.map { it.tag }, healthy, SystemClock.elapsedRealtime())
                        val entry = JSONObject().put("reason", decision.reason).put("failedRounds", decision.failures)
                            .put("selected", current.selected).put("checkedAt", System.currentTimeMillis())
                        if (decision.next != null) {
                            ensureActive()
                            if (runCatching { Libbox.newStandaloneCommandClient().selectOutbound(tag, decision.next) }.isSuccess) {
                                entry.put("selected", decision.next)
                            } else {
                                decisions.getValue(tag).reset()
                                entry.put("reason", "selection_failed")
                            }
                        }
                        result.put(tag, entry)
                    }
                    mutableStatus.value = result.toString()
                } else {
                    decisions.values.forEach { it.reset() }
                    publishAll("probe_cancelled")
                }
                delay(25_000)
            }
        }
    }
    private fun publishAll(reason: String) {
        mutableStatus.value = JSONObject().apply { enabledGroups.forEach { put(it, JSONObject().put("reason", reason)) } }.toString()
    }
    fun close() {
        scope.cancel()
        client.disconnect()
        mutableStatus.value = "{}"
    }
}
