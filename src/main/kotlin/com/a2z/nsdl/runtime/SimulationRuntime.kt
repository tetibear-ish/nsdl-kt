package com.a2z.nsdl.runtime

import com.a2z.nsdl.app.Command
import com.a2z.nsdl.app.CommandError
import com.a2z.nsdl.app.CommandResult
import com.a2z.nsdl.app.ErrorCode
import com.a2z.nsdl.app.Limits
import com.a2z.nsdl.app.SimulationService
import com.a2z.nsdl.app.TypeRegistry
import com.a2z.nsdl.events.EventFilter
import com.a2z.nsdl.events.EventHub
import com.a2z.nsdl.events.SubscribeResult
import com.a2z.nsdl.sim.VirtualScheduler
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class Request(val command: Command, val requestId: String? = null)

/** [revision] is the event hub's lastSeq at the moment [result] was produced: subscribing from it never gaps. */
data class RuntimeResult(val result: CommandResult, val revision: Long)

private sealed interface CacheOutcome {
    data object New : CacheOutcome
    data class Hit(val result: RuntimeResult) : CacheOutcome
    data object Conflict : CacheOutcome
}

/** Bounded LRU of requestId -> (command, result), so a retried request is answered without re-executing. */
private class IdempotencyCache(capacity: Int) {
    private val entries = object : LinkedHashMap<String, Pair<Command, RuntimeResult>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Command, RuntimeResult>>) = size > capacity
    }

    fun check(requestId: String, command: Command): CacheOutcome {
        val existing = entries[requestId] ?: return CacheOutcome.New
        return if (existing.first == command) CacheOutcome.Hit(existing.second) else CacheOutcome.Conflict
    }

    fun record(requestId: String, command: Command, result: RuntimeResult) {
        entries[requestId] = command to result
    }
}

/** Records accepted mutating commands in accepted order, for deterministic replay. */
class InputJournal {
    private val entries = mutableListOf<Command>()

    internal fun record(command: Command) {
        entries += command
    }

    fun snapshot(): List<Command> = entries.toList()

    companion object {
        /** Advance is included: without replaying it, no scheduled work would ever run during replay. */
        fun isMutating(command: Command): Boolean = when (command) {
            is Command.Create, is Command.ApplyTopology, is Command.Connect, is Command.Disconnect,
            is Command.Configure, is Command.PowerOn, is Command.PowerOff, is Command.Advance, is Command.Delete,
            -> true
            Command.ListTypes, Command.ListObjects, is Command.Inspect -> false
        }
    }
}

/**
 * Confines all simulation state changes to one named daemon thread. [submit] and [subscribe] both
 * run their work on that thread (via a single-thread executor), so a snapshot's [RuntimeResult.revision]
 * and a subsequent [subscribe] from it are never racing against an in-flight command.
 *
 * An unexpected exception during a command becomes an INTERNAL result rather than propagating: it is
 * caught inside the submitted task, so the executor's one thread is never killed by it.
 */
class SimulationRuntime(
    scheduler: VirtualScheduler,
    private val eventHub: EventHub,
    registry: TypeRegistry,
    limits: Limits = Limits(),
    randomSeed: Long = 0L,
    idempotencyCapacity: Int = 1000,
) {
    private val service = SimulationService(scheduler, eventHub, registry, limits, randomSeed)
    private val idempotency = IdempotencyCache(idempotencyCapacity)
    private val journal = InputJournal()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }

    fun submit(request: Request): RuntimeResult = executor.submit(Callable { runOnRuntimeThread(request) }).get()

    fun subscribe(filter: EventFilter, from: Long = eventHub.lastSeq, capacity: Int): SubscribeResult =
        executor.submit(Callable { eventHub.subscribe(filter, from, capacity) }).get()

    fun journalSnapshot(): List<Command> = executor.submit(Callable { journal.snapshot() }).get()

    fun currentRevision(): Long = executor.submit(Callable { eventHub.lastSeq }).get()

    fun close() {
        executor.shutdown()
    }

    private fun runOnRuntimeThread(request: Request): RuntimeResult = try {
        val requestId = request.requestId
        if (requestId != null) {
            when (val outcome = idempotency.check(requestId, request.command)) {
                is CacheOutcome.Hit -> return outcome.result
                CacheOutcome.Conflict -> return RuntimeResult(
                    CommandResult.Rejected(CommandError(ErrorCode.IDEMPOTENCY_CONFLICT, "request id '$requestId' was already used with a different command")),
                    eventHub.lastSeq,
                )
                CacheOutcome.New -> Unit
            }
        }

        val result = service.handle(request.command)
        if (result is CommandResult.Ok && InputJournal.isMutating(request.command)) journal.record(request.command)
        val wrapped = RuntimeResult(result, eventHub.lastSeq)
        if (requestId != null) idempotency.record(requestId, request.command, wrapped)
        wrapped
    } catch (e: Exception) {
        RuntimeResult(
            CommandResult.Rejected(CommandError(ErrorCode.INTERNAL, e.message ?: e::class.simpleName ?: "internal error")),
            eventHub.lastSeq,
        )
    }

    companion object {
        const val THREAD_NAME = "nsdl-runtime"
    }
}
