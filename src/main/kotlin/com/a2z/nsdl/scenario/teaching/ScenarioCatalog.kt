package com.a2z.nsdl.scenario.teaching

import com.a2z.nsdl.scenario.Scenario

/** Named, built-in teaching scenarios, lookupable by the CLI and the browser alike. */
object ScenarioCatalog {
    val all: Map<String, () -> Scenario> = mapOf(
        "print-job" to ::printJobScenario,
        "ssh-session" to ::sshSessionScenario,
        "arp-first-contact" to ::arpFirstContactScenario,
        "arp-cache" to ::arpCacheScenario,
        "arp-forget" to ::arpForgetScenario,
        "arp-unanswered" to ::arpUnansweredScenario,
        "arp-via-router" to ::arpViaRouterScenario,
        "print-by-name" to ::printByNameScenario,
    )
}
