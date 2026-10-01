package com.a2z.nsdl.scenario

import com.a2z.nsdl.app.CommandError
import com.a2z.nsdl.app.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScenarioReportTest {
    private val passingAssertion = LinkIsUp("printer1.eth0")
    private val failingAssertion = AddressInPool(
        "printer1.eth0",
        com.a2z.nsdl.net.Ipv4Address.parse("10.0.0.100"),
        com.a2z.nsdl.net.Ipv4Address.parse("10.0.0.110"),
    )

    @Test
    fun `a completed result renders one line per assertion with its pass-fail mark and message`() {
        val result = ScenarioResult.Completed(
            name = "demo",
            outcomes = listOf(
                AssertionOutcome.Pass(passingAssertion, "link up at t=10ms"),
                AssertionOutcome.Fail(failingAssertion, "no IPv4 address configured"),
            ),
            events = emptyList(),
            snapshots = emptyMap(),
        )

        val rendered = result.render()

        assertEquals(
            """
            scenario: demo
            status: FAILED
              [PASS] the link at 'printer1.eth0' comes up -- link up at t=10ms
              [FAIL] 'printer1.eth0' has an address in 10.0.0.100..10.0.0.110 -- no IPv4 address configured

            """.trimIndent(),
            rendered,
        )
    }

    @Test
    fun `a malformed result renders its reason instead of assertions`() {
        val result = ScenarioResult.Malformed("demo", "steps advance past the declared bound")

        assertEquals("scenario: demo\nstatus: MALFORMED -- steps advance past the declared bound\n", result.render())
    }

    @Test
    fun `an errored result renders the failing step index and error`() {
        val result = ScenarioResult.Errored("demo", stepIndex = 2, error = CommandError(ErrorCode.UNKNOWN_OBJECT, "no object with id 'x'"))

        assertEquals("scenario: demo\nstatus: ERRORED at step 2 -- UNKNOWN_OBJECT no object with id 'x'\n", result.render())
    }
}
