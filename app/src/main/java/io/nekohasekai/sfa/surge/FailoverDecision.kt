package io.nekohasekai.sfa.surge

/** Only availability participates in selection; delay is deliberately absent from this model. */
class FailoverDecision(private val threshold: Int = 2, private val cooldownMs: Long = 120_000) {
    private var selected = ""
    private var failures = 0
    private var cooldownUntil = 0L
    data class Result(val next: String?, val reason: String, val failures: Int)
    fun reset() {
        selected = ""
        failures = 0
        cooldownUntil = 0
    }
    fun evaluate(current: String, members: List<String>, healthy: Set<String>, now: Long): Result {
        if (current != selected) {
            selected = current
            failures = 0
            cooldownUntil = 0
        }
        if (current == "DIRECT") {
            failures = 0
            return Result(null, "paused_manual_direct", 0)
        }
        if (current in healthy) {
            failures = 0
            return Result(null, "healthy", 0)
        }
        if (now < cooldownUntil) return Result(null, "cooldown", failures)
        failures = (failures + 1).coerceAtMost(threshold)
        if (failures < threshold) return Result(null, "probe_failed", failures)
        val next = members.firstOrNull { it != current && it != "DIRECT" && it in healthy }
            ?: return Result(null, "all_unavailable", failures)
        selected = next
        failures = 0
        cooldownUntil = now + cooldownMs
        return Result(next, "switched_after_failures", threshold)
    }
}
