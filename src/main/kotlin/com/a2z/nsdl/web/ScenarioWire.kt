package com.a2z.nsdl.web

import com.a2z.nsdl.events.EventRecord
import com.a2z.nsdl.ipc.WireMapper
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.scenario.AssertionOutcome
import com.a2z.nsdl.scenario.ScenarioResult

/** JSON-safe encoding of a [ScenarioResult], for the browser -- the structured counterpart to [com.a2z.nsdl.scenario.render]. */
fun ScenarioResult.toWireData(): Map<String, Any?> = when (this) {
    is ScenarioResult.Malformed -> mapOf("name" to name, "status" to "MALFORMED", "reason" to reason)
    is ScenarioResult.Errored -> mapOf(
        "name" to name, "status" to "ERRORED", "stepIndex" to stepIndex,
        "error" to mapOf("code" to error.code.name, "message" to error.message, "details" to error.details),
    )
    is ScenarioResult.Completed -> mapOf(
        "name" to name,
        "status" to if (passed) "PASSED" else "FAILED",
        "outcomes" to outcomes.map { it.toWireData() },
        "snapshots" to snapshots.mapValues { (_, snapshot) -> snapshot.toWireData() },
    )
}

private fun AssertionOutcome.toWireData(): Map<String, Any?> = mapOf(
    "status" to if (this is AssertionOutcome.Pass) "PASS" else "FAIL",
    "description" to assertion.description,
    "message" to message,
    "evidence" to evidence.map { it.toWireData() },
)

private fun EventRecord.toWireData(): Map<String, Any?> = mapOf(
    "seq" to seq, "timeMs" to timeMs, "source" to source.value, "type" to type,
    "data" to WireMapper.toData(payload),
)

private fun ObjectSnapshot.toWireData(): Map<String, Any?> = mapOf(
    "id" to id.value, "type" to type, "kind" to kind.name,
    "state" to state,
    "relations" to relations.mapValues { (_, ids) -> ids.map { it.value } },
)
