package com.a2z.nsdl.model

/** Stable causal links shared by node decision logs. Null means the decision has no known parent at that level. */
data class DecisionParents(
    val intentionId: String? = null,
    val processId: String? = null,
    val sessionId: String? = null,
    val exchangeId: String? = null,
    val packetId: String? = null,
)

enum class Responsibility(val wireName: String) {
    SWITCHING("switching"),
}

enum class DecisionAction {
    LEARN_SOURCE,
    FLOOD,
    FORWARD,
    DROP,
}

/**
 * A component-authored explanation of one deterministic choice. [attributes] contains only stable,
 * serializable facts used by the decision; presentation code must not reconstruct the explanation.
 */
data class DecisionRecord(
    val id: String,
    val responsibility: Responsibility,
    val decision: DecisionAction,
    val reason: String,
    val parents: DecisionParents = DecisionParents(),
    val attributes: Map<String, String> = emptyMap(),
) {
    fun toState(): Map<String, Any?> = mapOf(
        "id" to id,
        "responsibility" to responsibility.wireName,
        "decision" to decision.name,
        "reason" to reason,
        "parents" to mapOf(
            "intentionId" to parents.intentionId,
            "processId" to parents.processId,
            "sessionId" to parents.sessionId,
            "exchangeId" to parents.exchangeId,
            "packetId" to parents.packetId,
        ),
        "attributes" to attributes,
    )
}
