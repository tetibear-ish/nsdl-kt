package com.a2z.nsdl.software

import com.a2z.nsdl.model.ActionOutcome
import com.a2z.nsdl.model.Actionable
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.Inspectable
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.sim.WorkScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The software running on one host: a small set of applications that reach the network only
 * through [NetworkServices]. It runs while the host is powered: [boot] after the network services
 * start, [shutdown] when power is removed. Everything an application holds is volatile and is
 * cleared at shutdown, like a real machine losing its RAM.
 *
 * Application ids are children of the host, e.g. "pc1.browser", so they are inspected and
 * invoked like any other component.
 */
class OperatingSystem(
    hostId: ObjectId,
    network: NetworkServices,
    events: EventSink,
    operationTimeout: Duration = 10.seconds,
) : Inspectable {
    override val id = hostId.child("os")
    private val system = SystemContext(network, events, operationTimeout)

    val browser = WebBrowser(hostId.child("browser"), system)
    val printSpooler = PrintSpooler(hostId.child("print-spooler"), system)
    val terminal = Terminal(hostId.child("terminal"), system)
    val applications: List<Application> = listOf(browser, printSpooler, terminal)

    val running get() = system.running

    fun boot(scope: WorkScope) {
        system.start(scope)
    }

    fun shutdown() {
        system.stop()
        applications.forEach { it.reset() }
    }

    override fun snapshot() = ObjectSnapshot(
        id, "operating-system", ObjectKind.APPLICATION,
        state = mapOf("running" to running, "applications" to applications.map { it.id.value }),
        relations = mapOf("applications" to applications.map { it.id }),
    )
}

/**
 * What an [Application] may use: the network (in user-level terms), its event stream, and
 * [request], which bounds every network operation with a timeout and discards results that arrive
 * after the timeout or after the host has been switched off.
 */
class SystemContext internal constructor(
    private val network: NetworkServices,
    private val events: EventSink,
    private val operationTimeout: Duration,
) {
    private var scope: WorkScope? = null
    private var generation = 0

    val running get() = scope != null

    internal fun start(scope: WorkScope) {
        this.scope = scope
        generation++
    }

    internal fun stop() {
        scope = null
        generation++
    }

    fun emit(app: Application, activity: String, detail: String) =
        events.emit(app.id, EventPayload.ApplicationEvent(app.name, activity, detail))

    /**
     * Runs one network [operation] towards [peer] (a name for messages; [server], when known, is asked
     * about via [NetworkServices.diagnose]), delivering exactly one outcome to [done] unless the host
     * stops first. If nothing answers within the timeout, the outcome is a timed-out [Outcome.Failure]
     * whose reason is the diagnosed cause, or "<peer> did not respond".
     */
    fun <T> request(
        peer: String,
        server: HostAddress?,
        operation: NetworkServices.((Outcome<T>) -> Unit) -> Unit,
        done: (Outcome<T>) -> Unit,
    ) {
        val currentScope = scope ?: return done(Outcome.Failure("the computer is off"))
        val startedIn = generation
        var settled = false
        val timer = currentScope.schedule(operationTimeout) {
            if (!settled) {
                settled = true
                done(Outcome.Failure(server?.let(network::diagnose) ?: "$peer did not respond", timedOut = true))
            }
        }
        network.operation { outcome ->
            if (!settled && generation == startedIn) {
                settled = true
                timer.cancel()
                done(outcome)
            }
        }
    }
}

/** A program with user-level actions. Its state is volatile: [reset] runs when the host shuts down. */
abstract class Application(final override val id: ObjectId, protected val system: SystemContext) : Inspectable, Actionable {
    /** Short program name used in events, e.g. "browser". */
    abstract val name: String

    /** The actions this application accepts, mapped to their handlers. */
    protected abstract val actions: Map<String, (Map<String, Any?>) -> ActionOutcome>

    internal abstract fun reset()

    /** Application-specific inspection state. */
    protected abstract fun state(): Map<String, Any?>

    final override fun perform(action: String, params: Map<String, Any?>): ActionOutcome {
        val handler = actions[action]
            ?: return ActionOutcome(false, "unknown action '$action'; $name supports ${actions.keys.joinToString()}")
        if (!system.running) return ActionOutcome(false, "the computer is off")
        return handler(params)
    }

    final override fun snapshot() = ObjectSnapshot(
        id, name, ObjectKind.APPLICATION,
        state = mapOf("actions" to actions.keys.toList()) + state(),
    )

    protected fun Map<String, Any?>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** "<what> timed out: <cause>" for a timeout, otherwise "<otherwise>: <reason>". */
    protected fun describe(failure: Outcome.Failure, what: String, otherwise: String) =
        if (failure.timedOut) "$what timed out: ${failure.reason}" else "$otherwise: ${failure.reason}"
}
