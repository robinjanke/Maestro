package maestro.cli.cloud

import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class SessionEventHub(
    private val maxEventsPerSession: Int = 10_000,
) {
    private data class SessionSlot(
        val events: ArrayDeque<SessionEvent> = ArrayDeque(),
        val nextSeq: AtomicLong = AtomicLong(0),
        val listeners: CopyOnWriteArrayList<(SessionEvent) -> Unit> = CopyOnWriteArrayList(),
        @Volatile var currentFlow: String? = null,
        @Volatile var currentFlowSince: Instant? = null,
    )

    private val sessions = ConcurrentHashMap<String, SessionSlot>()

    fun publish(
        sessionId: String,
        type: SessionEventType,
        flowPath: String? = null,
        deviceName: String? = null,
        message: String? = null,
        success: Boolean? = null,
    ): SessionEvent {
        val slot = sessions.computeIfAbsent(sessionId) { SessionSlot() }
        val event = synchronized(slot) {
            val seq = slot.nextSeq.incrementAndGet()
            when (type) {
                SessionEventType.FLOW_STARTED -> {
                    slot.currentFlow = flowPath
                    slot.currentFlowSince = Instant.now()
                }
                SessionEventType.FLOW_FINISHED -> {
                    slot.currentFlow = null
                    slot.currentFlowSince = null
                }
                else -> Unit
            }
            SessionEvent(
                seq = seq,
                type = type,
                timestamp = Instant.now(),
                flowPath = flowPath,
                deviceName = deviceName,
                message = message,
                success = success,
            ).also { ev ->
                slot.events.addLast(ev)
                while (slot.events.size > maxEventsPerSession) {
                    slot.events.removeFirst()
                }
            }
        }
        slot.listeners.forEach { listener ->
            runCatching { listener(event) }
        }
        return event
    }

    fun eventsSince(sessionId: String, since: Long): List<SessionEvent> {
        val slot = sessions[sessionId] ?: return emptyList()
        return synchronized(slot) {
            slot.events.filter { it.seq > since }
        }
    }

    fun lastEventSeq(sessionId: String): Long {
        val slot = sessions[sessionId] ?: return 0L
        return slot.nextSeq.get()
    }

    fun currentFlow(sessionId: String): String? = sessions[sessionId]?.currentFlow

    fun currentFlowSince(sessionId: String): Instant? = sessions[sessionId]?.currentFlowSince

    fun subscribe(sessionId: String, listener: (SessionEvent) -> Unit): () -> Unit {
        val slot = sessions.computeIfAbsent(sessionId) { SessionSlot() }
        slot.listeners.add(listener)
        return { slot.listeners.remove(listener) }
    }

    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)
    }
}
