package maestro.cli.deviceserver

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay

class DeviceServerClient(
    private val baseUrl: String,
) : AutoCloseable {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val client = HttpClient(CIO)

    suspend fun health(): DeviceServerHealth {
        val response = client.get("$baseUrl/health")
        return mapper.readValue(response.bodyAsText())
    }

    suspend fun register(request: WorkerRegistrationRequest) {
        client.post("$baseUrl/register") {
            contentType(ContentType.Application.Json)
            setBody(mapper.writeValueAsString(request))
        }
    }

    suspend fun pollJob(workerId: String): ExecuteFlowRequest? {
        val response = client.post("$baseUrl/poll") {
            contentType(ContentType.Application.Json)
            setBody(mapper.writeValueAsString(mapOf("workerId" to workerId)))
        }
        if (response.status == HttpStatusCode.NoContent) return null
        return mapper.readValue(response.bodyAsText())
    }

    suspend fun completeJob(result: ExecuteFlowResult) {
        client.post("$baseUrl/complete") {
            contentType(ContentType.Application.Json)
            setBody(mapper.writeValueAsString(result))
        }
    }

    suspend fun enqueueJob(request: ExecuteFlowRequest) {
        client.post("$baseUrl/execute") {
            contentType(ContentType.Application.Json)
            setBody(mapper.writeValueAsString(request))
        }
    }

    suspend fun jobResult(jobId: String): ExecuteFlowResult? {
        val response = client.get("$baseUrl/jobs/$jobId")
        if (response.status == HttpStatusCode.NotFound) return null
        return mapper.readValue(response.bodyAsText())
    }

    suspend fun shutdown() {
        client.post("$baseUrl/shutdown")
    }

    suspend fun waitUntilReady(
        expectedDevices: Set<String>,
        timeoutSeconds: Long = 600,
        pollIntervalMs: Long = 2000,
    ): DeviceServerHealth {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        var last: DeviceServerHealth? = null
        while (System.currentTimeMillis() < deadline) {
            last = health()
            if (expectedDevices.isEmpty() && last.registeredDevices.isNotEmpty()) return last
            if (expectedDevices.isNotEmpty() && expectedDevices.all { it in last.registeredDevices }) {
                return last
            }
            delay(pollIntervalMs)
        }
        throw IllegalStateException(
            "Device server not ready after ${timeoutSeconds}s. " +
                "Expected: $expectedDevices, registered: ${last?.registeredDevices ?: emptyList<String>()}"
        )
    }

    override fun close() {
        client.close()
    }
}
