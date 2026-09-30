package com.a2z.nsdl.testing

import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.ObjectId

class RecordingSink : EventSink {
    val events = mutableListOf<Pair<ObjectId, EventPayload>>()
    override fun emit(source: ObjectId, payload: EventPayload) { events += source to payload }

    inline fun <reified T : EventPayload> of(source: ObjectId? = null): List<T> =
        events.filter { source == null || it.first == source }.map { it.second }.filterIsInstance<T>()

    fun names(source: ObjectId? = null) = events.filter { source == null || it.first == source }.map { it.second.name }
    fun clear() = events.clear()
}
