package com.a2z.nsdl.scenario.teaching

import com.a2z.nsdl.Composition
import com.a2z.nsdl.scenario.ArpRequestsSent
import com.a2z.nsdl.scenario.AssertionOutcome
import com.a2z.nsdl.scenario.Scenario
import com.a2z.nsdl.scenario.ScenarioResult
import com.a2z.nsdl.scenario.ScenarioRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Every beat of the ARP lesson, and printing by name, runs from the catalog with all assertions passing. */
class ArpLessonScenariosTest {
    private val compositions = mutableListOf<Composition>()

    private fun run(scenario: Scenario, seed: Long = 1L): ScenarioResult.Completed {
        val composition = Composition(seed).also { compositions += it }
        val result = ScenarioRunner(composition.runtime).run(scenario)
        return result as? ScenarioResult.Completed ?: error("expected Completed, got $result")
    }

    @AfterEach
    fun tearDown() = compositions.forEach { it.close() }

    @ParameterizedTest
    @ValueSource(strings = ["arp-first-contact", "arp-cache", "arp-forget", "arp-unanswered", "arp-via-router", "print-by-name"])
    fun `the lesson scenario passes`(name: String) {
        val completed = run(ScenarioCatalog.all.getValue(name)())

        val failures = completed.outcomes.filterIsInstance<AssertionOutcome.Fail>()
        assertTrue(failures.isEmpty(), failures.joinToString("\n") { "${it.assertion.description}: ${it.message}" })
    }

    @Test
    fun `a wrong expectation about ARP fails with a useful message`() {
        val wrong = arpCacheScenario().let { it.copy(assertions = listOf(ArpRequestsSent("computer1.eth0", 2))) }

        val outcome = run(wrong).outcomes.single()

        assertTrue(outcome is AssertionOutcome.Fail)
        assertEquals("'computer1.eth0' sent 1 ARP request(s), expected 2", outcome.message)
    }
}
