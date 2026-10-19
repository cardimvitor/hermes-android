package dev.hermes.android.ui

import org.json.JSONObject

/** One delegated child agent running next to the main one, shown as a card in the agents tile. */
data class SubagentCard(
    val id: String,
    val goal: String,
    /** Gateway lifecycle: queued, running, completed, failed, error, timeout or interrupted. */
    val status: String,
    val lastTool: String = "",
    val toolCount: Int = 0,
    val seconds: Double? = null,
) {
    val finished: Boolean get() = status in FINISHED
}

private val FINISHED = setOf("completed", "failed", "error", "timeout", "interrupted")

/**
 * Folds the gateway's subagent.* events into one card per child. The child is identified by
 * subagent_id, or by delegation_id plus task_index for older emitters that omit it.
 */
object SubagentFold {

    fun apply(cards: List<SubagentCard>, type: String, payload: JSONObject): List<SubagentCard> {
        val id = identity(payload) ?: return cards
        val current = cards.firstOrNull { it.id == id }
        // A finished child keeps its outcome; only subagent.complete may still change it.
        if (current != null && current.finished && type != "subagent.complete") return cards
        val goal = payload.str("goal")
        val base = current ?: SubagentCard(id, goal, "queued")
        val withGoal = base.copy(goal = base.goal.ifEmpty { goal })

        val updated = when (type) {
            "subagent.spawn_requested" -> withGoal
            "subagent.start", "subagent.thinking" -> withGoal.copy(status = "running")
            "subagent.tool" -> withGoal.copy(
                status = "running",
                lastTool = payload.str("tool_name").ifEmpty { payload.str("tool_preview") }.ifEmpty { withGoal.lastTool },
                toolCount = withGoal.toolCount + 1,
            )
            "subagent.complete" -> withGoal.copy(
                status = payload.str("status").ifEmpty { "completed" },
                seconds = if (payload.isNull("duration_seconds")) withGoal.seconds else payload.optDouble("duration_seconds"),
            )
            else -> return cards
        }
        return cards.upsert(updated)
    }

    private fun identity(payload: JSONObject): String? {
        val subagent = payload.str("subagent_id")
        if (subagent.isNotEmpty()) return subagent
        val delegation = payload.str("delegation_id")
        if (delegation.isEmpty()) return null
        return "$delegation#${payload.optInt("task_index")}"
    }

    private fun List<SubagentCard>.upsert(card: SubagentCard): List<SubagentCard> =
        if (any { it.id == card.id }) map { if (it.id == card.id) card else it } else this + card

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)
}
