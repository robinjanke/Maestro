package maestro.cli.cloud

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

class CloudServerClient(
    private val baseUrl: String,
    private val apiKey: String? = null,
    private val sessionToken: String? = null,
) {
    private val client = HttpClient(CIO)
    private val mapper: ObjectMapper = jacksonObjectMapper()

    suspend fun health(): CloudHealth {
        val response = client.get("$baseUrl/v1/health")
        return mapper.readValue(response.bodyAsText())
    }

    suspend fun createSession(request: CreateSessionRequest): CreateSessionResponse {
        val response = client.post("$baseUrl/v1/sessions") {
            contentType(ContentType.Application.Json)
            apiKey?.let { header(MAESTRO_API_KEY_HEADER, it) }
            setBody(mapper.writeValueAsString(request))
        }
        if (response.status != HttpStatusCode.Created) {
            throw IllegalStateException("create session failed: ${response.status} ${response.bodyAsText()}")
        }
        return mapper.readValue(response.bodyAsText())
    }

    suspend fun getSession(sessionId: String): SessionView {
        val response = client.get("$baseUrl/v1/sessions/$sessionId") {
            apiKey?.let { header(MAESTRO_API_KEY_HEADER, it) }
        }
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("get session failed: ${response.status}")
        }
        return mapper.readValue(response.bodyAsText())
    }

    suspend fun cancelSession(sessionId: String): SessionView {
        val response = client.delete("$baseUrl/v1/sessions/$sessionId") {
            apiKey?.let { header(MAESTRO_API_KEY_HEADER, it) }
        }
        return mapper.readValue(response.bodyAsText())
    }

    suspend fun attachWorker(sessionId: String, request: WorkerAttachRequest): WorkerAttachResponse {
        val response = client.post("$baseUrl/v1/sessions/$sessionId/workers/attach") {
            contentType(ContentType.Application.Json)
            sessionToken?.let { header(MAESTRO_SESSION_TOKEN_HEADER, it) }
            setBody(mapper.writeValueAsString(request))
        }
        return mapper.readValue(response.bodyAsText())
    }

    suspend fun postResult(sessionId: String, request: FlowResultRequest): FlowResultResponse {
        val response = client.post("$baseUrl/v1/sessions/$sessionId/results") {
            contentType(ContentType.Application.Json)
            sessionToken?.let { header(MAESTRO_SESSION_TOKEN_HEADER, it) }
            setBody(mapper.writeValueAsString(request))
        }
        return mapper.readValue(response.bodyAsText())
    }

    fun close() {
        client.close()
    }
}
