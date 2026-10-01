package com.a2z.nsdl.scenario

/**
 * A human-readable, student-facing report of one scenario run -- one line of status, then one line
 * per assertion with its pass/fail mark and message. Used by the CLI; the same [ScenarioResult] is
 * also available as structured data for a browser-side renderer.
 */
fun ScenarioResult.render(): String = buildString {
    appendLine("scenario: ${this@render.name}")
    when (val result = this@render) {
        is ScenarioResult.Malformed -> appendLine("status: MALFORMED -- ${result.reason}")
        is ScenarioResult.Errored -> appendLine("status: ERRORED at step ${result.stepIndex} -- ${result.error.code} ${result.error.message}")
        is ScenarioResult.Completed -> {
            appendLine("status: ${if (result.passed) "PASSED" else "FAILED"}")
            result.outcomes.forEach { outcome ->
                val mark = when (outcome) {
                    is AssertionOutcome.Pass -> "PASS"
                    is AssertionOutcome.Fail -> "FAIL"
                }
                appendLine("  [$mark] ${outcome.assertion.description} -- ${outcome.message}")
            }
        }
    }
}
