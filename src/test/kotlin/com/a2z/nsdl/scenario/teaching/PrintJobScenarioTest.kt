package com.a2z.nsdl.scenario.teaching

import com.a2z.nsdl.Composition
import com.a2z.nsdl.scenario.AssertionOutcome
import com.a2z.nsdl.scenario.ScenarioResult
import com.a2z.nsdl.scenario.ScenarioRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the teaching scenario documented in docs/SCENARIOS.md: it wraps the same exchange
 * [com.a2z.nsdl.print.PrintJobIntegrationTest] drives by hand, but as runnable scenario data instead
 * of bespoke test code -- the same scenario the CLI and the browser can run.
 */
class PrintJobScenarioTest {
    private val compositions = mutableListOf<Composition>()

    private fun newRunner(seed: Long = 1L): ScenarioRunner {
        val composition = Composition(seed)
        compositions += composition
        return ScenarioRunner(composition.runtime)
    }

    @AfterEach
    fun tearDown() = compositions.forEach { it.close() }

    @Test
    fun `the print job teaching scenario completes with every assertion passing`() {
        val result = newRunner().run(printJobScenario())

        val completed = result as? ScenarioResult.Completed ?: error("expected Completed, got $result")
        val failures = completed.outcomes.filterIsInstance<AssertionOutcome.Fail>()
        assertTrue(failures.isEmpty(), failures.joinToString("\n") { it.message })
        assertTrue(completed.passed)
    }

    @Test
    fun `replaying the print job scenario with the same seed is deterministic`() {
        val first = newRunner(seed = 7L).run(printJobScenario()) as ScenarioResult.Completed
        val second = newRunner(seed = 7L).run(printJobScenario()) as ScenarioResult.Completed

        org.junit.jupiter.api.Assertions.assertEquals(first.events, second.events)
    }
}
