package com.a2z.nsdl.scenario.teaching

import com.a2z.nsdl.Composition
import com.a2z.nsdl.scenario.AssertionOutcome
import com.a2z.nsdl.scenario.ScenarioResult
import com.a2z.nsdl.scenario.ScenarioRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the SSH teaching scenario documented in docs/SCENARIOS.md: it runs the same scripted
 * session [com.a2z.nsdl.ssh.SshSessionIntegrationTest] drives by hand, but as runnable scenario data
 * over a full DHCP'd topology, resolving the server's address/MAC live instead of hard-coding them.
 */
class SshSessionScenarioTest {
    private val compositions = mutableListOf<Composition>()

    private fun newRunner(seed: Long = 1L): ScenarioRunner {
        val composition = Composition(seed)
        compositions += composition
        return ScenarioRunner(composition.runtime)
    }

    @AfterEach
    fun tearDown() = compositions.forEach { it.close() }

    @Test
    fun `the ssh session teaching scenario completes with every assertion passing`() {
        val result = newRunner().run(sshSessionScenario())

        val completed = result as? ScenarioResult.Completed ?: error("expected Completed, got $result")
        val failures = completed.outcomes.filterIsInstance<AssertionOutcome.Fail>()
        assertTrue(failures.isEmpty(), failures.joinToString("\n") { it.message })
        assertTrue(completed.passed)
    }

    @Test
    fun `replaying the ssh session scenario with the same seed is deterministic`() {
        val first = newRunner(seed = 3L).run(sshSessionScenario()) as ScenarioResult.Completed
        val second = newRunner(seed = 3L).run(sshSessionScenario()) as ScenarioResult.Completed

        org.junit.jupiter.api.Assertions.assertEquals(first.events, second.events)
    }
}
