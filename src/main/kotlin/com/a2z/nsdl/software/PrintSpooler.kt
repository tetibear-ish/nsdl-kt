package com.a2z.nsdl.software

import com.a2z.nsdl.model.ActionOutcome
import com.a2z.nsdl.model.ObjectId

/**
 * The print queue. "print" takes a [document] name, a [printer] host name, and an optional page
 * count; each page is modeled as [BYTES_PER_PAGE] bytes. Jobs run concurrently and are kept (most
 * recent [JOB_LIMIT]) so their outcome can be inspected.
 */
class PrintSpooler internal constructor(id: ObjectId, system: SystemContext) : Application(id, system) {
    /** [ACCEPTED]: the printer confirmed it received the whole job; this model has no paper. */
    enum class Status { RESOLVING, SENDING, ACCEPTED, FAILED }

    data class Job(val id: String, val document: String, val printer: String, val pages: Int, val status: Status, val detail: String = "")

    override val name = "print-spooler"
    override val actions = mapOf("print" to ::print)

    private val jobs = linkedMapOf<String, Job>()
    private var nextJob = 1

    val queue: List<Job> get() = jobs.values.toList()

    private fun print(params: Map<String, Any?>): ActionOutcome {
        val document = params.text("document") ?: return ActionOutcome(false, "missing 'document'")
        val printer = params.text("printer") ?: return ActionOutcome(false, "missing 'printer'")
        val pages = (params["pages"] as? Number)?.toInt() ?: 1
        if (pages !in 1..MAX_PAGES) return ActionOutcome(false, "'pages' must be between 1 and $MAX_PAGES")

        val jobId = "job-${nextJob++}"
        update(Job(jobId, document, printer, pages, Status.RESOLVING))
        system.emit(this, "queued", "$jobId \"$document\" ($pages page${if (pages == 1) "" else "s"}) for $printer")

        system.request(printer, null, { resolve(printer, it) }) { resolved ->
            when (resolved) {
                is Outcome.Failure -> finish(jobId, Status.FAILED, describe(resolved, "looking up $printer", "can't find printer $printer"))
                is Outcome.Success -> {
                    update(jobs.getValue(jobId).copy(status = Status.SENDING))
                    system.request(printer, resolved.value, { printDocument(resolved.value, jobId, document, pages * BYTES_PER_PAGE, it) }) { printed ->
                        when (printed) {
                            is Outcome.Success -> finish(jobId, Status.ACCEPTED, "accepted by $printer")
                            is Outcome.Failure -> finish(jobId, Status.FAILED, describe(printed, "printing $document on $printer", "can't print on $printer"))
                        }
                    }
                }
            }
        }
        return ActionOutcome(true, "$jobId queued", mapOf("jobId" to jobId))
    }

    private fun finish(jobId: String, status: Status, detail: String) {
        val job = jobs[jobId] ?: return
        update(job.copy(status = status, detail = detail))
        system.emit(this, status.name.lowercase(), "$jobId: $detail")
    }

    private fun update(job: Job) {
        jobs[job.id] = job
        while (jobs.size > JOB_LIMIT) jobs.remove(jobs.keys.first())
    }

    override fun reset() {
        jobs.clear()
    }

    override fun state() = mapOf(
        "jobs" to jobs.values.map {
            mapOf("id" to it.id, "document" to it.document, "printer" to it.printer, "pages" to it.pages, "status" to it.status.name, "detail" to it.detail)
        },
    )

    companion object {
        const val BYTES_PER_PAGE = 3_000
        private const val MAX_PAGES = 500
        private const val JOB_LIMIT = 20
    }
}
