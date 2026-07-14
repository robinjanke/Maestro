package maestro.cli.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SessionEventHubTest {

    private val hub = SessionEventHub(maxEventsPerSession = 5)

    @Test
    fun `seq is monotonic per session`() {
        val first = hub.publish("s1", SessionEventType.STATUS_CHANGED, message = "a")
        val second = hub.publish("s1", SessionEventType.LOG_LINE, message = "b")
        assertThat(first.seq).isLessThan(second.seq)
    }

    @Test
    fun `eventsSince filters by seq`() {
        hub.publish("s1", SessionEventType.STATUS_CHANGED, message = "1")
        val second = hub.publish("s1", SessionEventType.LOG_LINE, message = "2")
        hub.publish("s1", SessionEventType.LOG_LINE, message = "3")

        val events = hub.eventsSince("s1", second.seq)
        assertThat(events).hasSize(1)
        assertThat(events.first().message).isEqualTo("3")
    }

    @Test
    fun `ring buffer drops oldest events`() {
        repeat(6) { index ->
            hub.publish("s1", SessionEventType.LOG_LINE, message = "line-$index")
        }
        val events = hub.eventsSince("s1", 0)
        assertThat(events).hasSize(5)
        assertThat(events.first().message).isEqualTo("line-1")
        assertThat(events.last().message).isEqualTo("line-5")
    }

    @Test
    fun `flow started and finished track current flow`() {
        hub.publish(
            "s1",
            SessionEventType.FLOW_STARTED,
            flowPath = "auth/login.yaml",
            deviceName = "chrome-1",
        )
        assertThat(hub.currentFlow("s1")).isEqualTo("auth/login.yaml")
        assertThat(hub.currentFlowSince("s1")).isNotNull()

        hub.publish(
            "s1",
            SessionEventType.FLOW_FINISHED,
            flowPath = "auth/login.yaml",
            success = true,
        )
        assertThat(hub.currentFlow("s1")).isNull()
        assertThat(hub.currentFlowSince("s1")).isNull()
    }
}
