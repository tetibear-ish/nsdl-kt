package com.a2z.nsdl.model

/**
 * The outcome of one [Actionable.perform] call. [accepted] distinguishes a domain-level success from
 * a rejection (e.g. link unavailable, invalid arguments); [data] is the action-specific payload.
 */
data class ActionOutcome(val accepted: Boolean, val detail: String = "", val data: Map<String, Any?> = emptyMap())

/**
 * A component (protocol client, server, or other application service) that exposes named, scriptable
 * actions beyond its normal passive simulation behavior -- e.g. a print client's "submit job". This is
 * the seam [com.a2z.nsdl.app.Command.Invoke] dispatches to; it lives in `model` (not `app`) because
 * components below `app` in the dependency order (dhcp, print, ...) must be able to implement it.
 */
interface Actionable {
    fun perform(action: String, params: Map<String, Any?>): ActionOutcome
}
