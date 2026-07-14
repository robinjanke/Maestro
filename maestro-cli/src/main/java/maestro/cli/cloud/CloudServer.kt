package maestro.cli.cloud

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.io.Writer
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CloudServer private constructor(
    val port: Int,
    val baseUrl: String,
    private val registry: SessionRegistry,
    private val apiKeyValidator: ApiKeyValidator,
    private val server: ApplicationEngine,
) : AutoCloseable {
    private val mapper: ObjectMapper = cloudObjectMapper()

    override fun close() {
        server.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
    }

    companion object {
        fun start(
            host: String = "0.0.0.0",
            port: Int = 8765,
            publicUrl: String? = null,
            dbPath: Path = Paths.get(
                System.getenv("MAESTRO_SESSION_DB_PATH") ?: "/var/lib/maestro/sessions.db",
            ),
            apiKeys: Set<String> = resolveApiKeysFromEnv(),
        ): CloudServer {
            val deviceServerUrl = publicUrl ?: "http://${if (host == "0.0.0.0") "127.0.0.1" else host}:$port"
            val gitlab = GitLabOrchestrator.fromEnv(deviceServerUrl)
            val store = SessionStore(dbPath)
            val registry = SessionRegistry(store, gitlab)
            val apiKeyValidator = ApiKeyValidator(apiKeys)
            val mapper = cloudObjectMapper()

            val engine = embeddedServer(Netty, host = host, port = port) {
                routing {
                    get("/v1/health") {
                        call.respondJson(mapper, CloudHealth("ok", registry.activeSessionCount()))
                    }

                    post("/v1/sessions") {
                        if (!call.requireApiKey(apiKeyValidator)) return@post
                        try {
                            val body = call.receiveText()
                            val request = mapper.readValue<CreateSessionRequest>(body)
                            val response = registry.createSession(request)
                            if (response.status == SessionStatus.FAILED) {
                                call.respondJson(
                                    mapper,
                                    mapOf(
                                        "sessionId" to response.sessionId,
                                        "status" to response.status,
                                        "error" to response.error,
                                    ),
                                    HttpStatusCode.BadGateway,
                                )
                                return@post
                            }
                            call.respondJson(mapper, response, HttpStatusCode.Created)
                        } catch (error: Exception) {
                            System.out.println("createSession failed: ${error.message}")
                            error.printStackTrace(System.out)
                            call.respondJson(
                                mapper,
                                mapOf("error" to (error.message ?: "create session failed")),
                                HttpStatusCode.InternalServerError,
                            )
                        }
                    }

                    get("/v1/sessions/{id}") {
                        if (!call.requireApiKey(apiKeyValidator)) return@get
                        val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        val view = registry.getSession(sessionId)
                        if (view == null) {
                            call.respond(HttpStatusCode.NotFound)
                        } else {
                            call.respondJson(mapper, view)
                        }
                    }

                    delete("/v1/sessions/{id}") {
                        if (!call.requireApiKey(apiKeyValidator)) return@delete
                        val sessionId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                        val view = registry.cancelSession(sessionId)
                        if (view == null) {
                            call.respond(HttpStatusCode.NotFound)
                        } else {
                            call.respondJson(mapper, view)
                        }
                    }

                    post("/v1/sessions/{id}/workers/attach") {
                        val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        if (registry.getSession(sessionId) == null) return@post call.respond(HttpStatusCode.NotFound)
                        if (!call.requireSessionToken(registry.getSessionToken(sessionId))) return@post
                        val body = call.receiveText()
                        val request = mapper.readValue<WorkerAttachRequest>(body)
                        val response = registry.attachWorker(sessionId, request)
                        call.respondJson(mapper, response)
                    }

                    post("/v1/sessions/{id}/results") {
                        val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        if (registry.getSession(sessionId) == null) return@post call.respond(HttpStatusCode.NotFound)
                        if (!call.requireSessionToken(registry.getSessionToken(sessionId))) return@post
                        val body = call.receiveText()
                        val request = mapper.readValue<FlowResultRequest>(body)
                        val response = registry.recordResult(sessionId, request)
                        call.respondJson(mapper, response)
                    }

                    post("/v1/sessions/{id}/events") {
                        val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        if (registry.getSession(sessionId) == null) return@post call.respond(HttpStatusCode.NotFound)
                        if (!call.requireSessionToken(registry.getSessionToken(sessionId))) return@post
                        val body = call.receiveText()
                        val request = mapper.readValue<SessionEventRequest>(body)
                        val response = registry.publishEvent(sessionId, request)
                        call.respondJson(mapper, response, HttpStatusCode.Accepted)
                    }

                    get("/v1/sessions/{id}/stream") {
                        if (!call.requireApiKey(apiKeyValidator)) return@get
                        val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        if (registry.getSession(sessionId) == null) return@get call.respond(HttpStatusCode.NotFound)
                        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                        call.response.headers.append("X-Accel-Buffering", "no")
                        call.response.headers.append(HttpHeaders.Connection, "keep-alive")
                        val hub = registry.eventHub()
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            hub.eventsSince(sessionId, since).forEach { event ->
                                writeSseEvent(mapper, event)
                            }
                            val queue = LinkedBlockingQueue<SessionEvent>()
                            val unsubscribe = hub.subscribe(sessionId) { queue.offer(it) }
                            try {
                                while (coroutineContext.isActive) {
                                    val event = queue.poll(15, TimeUnit.SECONDS)
                                    if (event != null) {
                                        writeSseEvent(mapper, event)
                                        if (event.type == SessionEventType.STATUS_CHANGED &&
                                            isTerminalStatusMessage(event.message)
                                        ) {
                                            break
                                        }
                                    } else {
                                        write(": keepalive\n\n")
                                        flush()
                                    }
                                }
                            } finally {
                                unsubscribe()
                            }
                        }
                    }
                }
            }

            engine.start(wait = false)
            val resolvedPort = runBlocking {
                engine.resolvedConnectors().firstOrNull()?.port ?: port
            }
            val displayHost = if (host == "0.0.0.0") "127.0.0.1" else host
            val baseUrl = publicUrl ?: "http://$displayHost:$resolvedPort"

            return CloudServer(
                port = resolvedPort,
                baseUrl = baseUrl,
                registry = registry,
                apiKeyValidator = apiKeyValidator,
                server = engine,
            )
        }

    }
}

private suspend fun ApplicationCall.respondJson(
    mapper: ObjectMapper,
    payload: Any,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(mapper.writeValueAsString(payload), ContentType.Application.Json, status)
}

private fun Writer.writeSseEvent(mapper: ObjectMapper, event: SessionEvent) {
    write("event: ${event.type.name.lowercase()}\n")
    write("id: ${event.seq}\n")
    write("data: ${mapper.writeValueAsString(event)}\n\n")
    flush()
}

private fun isTerminalStatusMessage(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    return message.contains("status=COMPLETED") ||
        message.contains("status=FAILED") ||
        message.contains("status=CANCELLED")
}
