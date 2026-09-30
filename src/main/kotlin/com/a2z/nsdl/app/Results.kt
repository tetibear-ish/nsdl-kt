package com.a2z.nsdl.app

/** Stable across the whole app -- these are the codes ever reported to a caller. */
enum class ErrorCode {
    INVALID_REQUEST, INVALID_ID, DUPLICATE_ID, UNKNOWN_TYPE, UNKNOWN_OBJECT, UNKNOWN_ENDPOINT,
    MISSING_PROPERTY, INVALID_PROPERTY, UNKNOWN_PROPERTY, IMMUTABLE_PROPERTY,
    NOT_A_CABLE, NOT_POWERABLE, INCOMPATIBLE_MEDIA, SELF_CONNECTION, CABLE_OCCUPIED, ENDPOINT_OCCUPIED,
    LIMIT_EXCEEDED, IDEMPOTENCY_CONFLICT, CURSOR_EXPIRED, UNSUPPORTED_VERSION, UNKNOWN_OP, INTERNAL,
}

data class CommandError(val code: ErrorCode, val message: String, val details: Map<String, Any?> = emptyMap())

/**
 * The outcome of one [Command]. [Ok.changed] distinguishes an actual mutation from an idempotent
 * no-op (e.g. connecting an already-connected pair again); [Ok.data] is the command-specific payload.
 */
sealed interface CommandResult {
    data class Ok(val data: Any? = null, val changed: Boolean = true) : CommandResult
    data class Rejected(val error: CommandError) : CommandResult
}
