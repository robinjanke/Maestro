package maestro.cli.deviceserver

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class DeviceServer private constructor(
    val port: Int,
    val baseUrl: String,
    private val registry: DeviceServerRegistry,
    private val server: ApplicationEngine,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    override fun close() {
        registry.requestShutdown()
        scope.cancel()
        server.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
    }

    fun registry(): DeviceServerRegistry = registry

    companion object {
        fun start(
            host: String = "0.0.0.0",
            port: Int = 8765,
            expectedDevices: Set<String> = emptySet(),
        ): DeviceServer {
            val registry = DeviceServerRegistry(expectedDevices)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val mapper = jacksonObjectMapper()

            val engine = embeddedServer(Netty, host = host, port = port) {
                routing {
                    get("/health") {
                        call.respond(registry.health())
                    }

                    get("/devices") {
                        call.respond(registry.registeredDeviceNames().sorted())
                    }

                    post("/register") {
                        val body = call.receiveText()
                        val request = mapper.readValue<WorkerRegistrationRequest>(body)
                        registry.registerWorker(request)
                        call.respond(mapOf("status" to "registered", "devices" to request.devices.size))
                    }

                    post("/poll") {
                        val body = call.receiveText()
                        val payload = mapper.readValue<Map<String, String>>(body)
                        val workerId = payload["workerId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        val job = registry.pollJobForWorker(workerId)
                        if (job == null) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respond(job)
                        }
                    }

                    post("/complete") {
                        val body = call.receiveText()
                        val result = mapper.readValue<ExecuteFlowResult>(body)
                        registry.completeJob(result)
                        call.respond(mapOf("status" to "ok"))
                    }

                    get("/jobs/{jobId}") {
                        val jobId = call.parameters["jobId"]
                        val result = jobId?.let { registry.jobResult(it) }
                        if (result == null) {
                            call.respond(HttpStatusCode.NotFound)
                        } else {
                            call.respond(result)
                        }
                    }

                    post("/execute") {
                        val body = call.receiveText()
                        val request = mapper.readValue<ExecuteFlowRequest>(body)
                        registry.enqueueJob(request)
                        call.respond(mapOf("status" to "queued", "jobId" to request.jobId))
                    }

                    post("/shutdown") {
                        registry.requestShutdown()
                        call.respond(mapOf("status" to "shutting_down"))
                    }
                }
            }

            engine.start(wait = false)
            val resolvedPort = runBlocking {
                engine.resolvedConnectors().firstOrNull()?.port ?: port
            }
            val displayHost = if (host == "0.0.0.0") "127.0.0.1" else host
            val baseUrl = "http://$displayHost:$resolvedPort"

            return DeviceServer(
                port = resolvedPort,
                baseUrl = baseUrl,
                registry = registry,
                server = engine,
                scope = scope,
            )
        }
    }
}
