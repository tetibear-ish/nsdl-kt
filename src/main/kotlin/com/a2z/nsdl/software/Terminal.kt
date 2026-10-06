package com.a2z.nsdl.software

import com.a2z.nsdl.model.ActionOutcome
import com.a2z.nsdl.model.ObjectId

/**
 * A terminal with an SSH command: "ssh" takes a [target] ("user@host"), a [password] and one
 * [command] to run remotely, and appends the exchange to a transcript, the way a user would see it.
 * One SSH command runs at a time. "clear" empties the transcript.
 */
class Terminal internal constructor(id: ObjectId, system: SystemContext) : Application(id, system) {
    override val name = "terminal"
    override val actions = mapOf("ssh" to ::ssh, "clear" to ::clear)

    private val lines = ArrayDeque<String>()
    var busy = false
        private set

    val transcript: List<String> get() = lines.toList()

    private fun ssh(params: Map<String, Any?>): ActionOutcome {
        if (busy) return ActionOutcome(false, "an ssh command is already running")
        val target = params.text("target") ?: return ActionOutcome(false, "missing 'target' (user@host)")
        val password = params["password"] as? String ?: return ActionOutcome(false, "missing 'password'")
        val command = params.text("command") ?: return ActionOutcome(false, "missing 'command'")
        val at = target.indexOf('@')
        if (at <= 0 || at == target.length - 1) return ActionOutcome(false, "'target' must look like user@host")
        val user = target.substring(0, at)
        val host = target.substring(at + 1)

        busy = true
        print("$ ssh $target $command")
        system.emit(this, "ssh", "$target $command")

        system.request(host, null, { resolve(host, it) }) { resolved ->
            when (resolved) {
                is Outcome.Failure -> done(describe(resolved, "ssh: looking up $host", "ssh: Could not resolve hostname $host"))
                is Outcome.Success -> system.request(host, resolved.value, { runRemoteCommand(resolved.value, user, password, command, it) }) { ran ->
                    when (ran) {
                        is Outcome.Failure -> done(describe(ran, "ssh: connect to host $host", "ssh: $host"))
                        is Outcome.Success -> {
                            ran.value.text.lines().forEach(::print)
                            done(if (ran.value.exitCode == 0) null else "[exit ${ran.value.exitCode}]")
                        }
                    }
                }
            }
        }
        return ActionOutcome(true, "running '$command' on $host")
    }

    private fun clear(params: Map<String, Any?>): ActionOutcome {
        lines.clear()
        return ActionOutcome(true, "cleared")
    }

    private fun done(finalLine: String?) {
        finalLine?.let(::print)
        busy = false
        system.emit(this, "finished", finalLine ?: "ok")
    }

    private fun print(line: String) {
        lines.addLast(line)
        while (lines.size > LINE_LIMIT) lines.removeFirst()
    }

    override fun reset() {
        lines.clear()
        busy = false
    }

    override fun state() = mapOf("busy" to busy, "transcript" to lines.toList())

    private companion object {
        const val LINE_LIMIT = 100
    }
}
