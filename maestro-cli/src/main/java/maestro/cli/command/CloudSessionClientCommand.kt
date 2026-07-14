package maestro.cli.command

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import maestro.cli.cloud.ArtifactRef
import maestro.cli.cloud.CloudFlowPlanner
import maestro.cli.cloud.CloudServerClient
import maestro.cli.cloud.CreateSessionRequest
import maestro.cli.cloud.FlowPlan
import maestro.cli.cloud.SessionEvent
import maestro.cli.cloud.SessionEventType
import maestro.cli.cloud.SessionStatus
import maestro.orchestra.yaml.DevicePlanService
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicLong

@CommandLine.Command(
    name = "run-session",
    description = ["Create a cloud session, wait for completion, and write JUnit output"],
)
class CloudSessionClientCommand : Callable<Int> {

    @CommandLine.Option(names = ["--url"], required = true)
    private lateinit var serverUrl: String

    @CommandLine.Option(names = ["--api-key"], required = true)
    private lateinit var apiKey: String

    @CommandLine.Option(names = ["--flows-root"], required = true)
    private lateinit var flowsRoot: Path

    @CommandLine.Option(names = ["--catalog"], required = true)
    private lateinit var catalogPath: Path

    @CommandLine.Option(names = ["--project-id"], required = true)
    private lateinit var projectId: String

    @CommandLine.Option(names = ["--pipeline-id"], required = true)
    private lateinit var pipelineId: String

    @CommandLine.Option(names = ["--job-name"], defaultValue = "e2e-test")
    private lateinit var jobName: String

    @CommandLine.Option(names = ["--timeout"], defaultValue = "3600")
    private var timeoutSeconds: Long = 3600

    @CommandLine.Option(names = ["--stall-seconds"], defaultValue = "1200")
    private var stallSeconds: Long = 1200

    @CommandLine.Option(names = ["--junit-report"], defaultValue = "maestro-cloud-junit.xml")
    private lateinit var junitReport: Path

    override fun call(): Int {
        val catalogData = DevicePlanService.loadCatalog(catalogPath)
        val plan = CloudFlowPlanner.planForCloud(flowsRoot, catalogData)
        if (!plan.isValid) {
            plan.errors.forEach { System.err.println(it) }
            return 1
        }

        val enabledDevices = plan.devices.keys.toList()
        val workerGroups = enabledDevices.mapNotNull { deviceName ->
            catalogData.devices[deviceName]?.workerGroup
        }.distinct()

        val catalog = Files.readString(catalogPath)

        val totalFlows = plan.orderedFlows.size
        println("Cloud flow plan: ${totalFlows} flow(s) on device(s): ${enabledDevices.joinToString()}")
        plan.orderedFlows.forEachIndexed { index, flow ->
            println("  ${index + 1}. ${flow} (${plan.flowDeviceByPath[flow]})")
        }

        val flowPlan = FlowPlan(
            orderedFlows = plan.orderedFlows,
            flowDeviceByPath = plan.flowDeviceByPath,
            devices = enabledDevices,
        )

        val env = System.getenv().filterKeys { key ->
            key.startsWith("TARGET_") ||
                key.startsWith("E2E_") ||
                key == "MAESTRO_DEVICE_CATALOG"
        }

        val client = CloudServerClient(serverUrl.trimEnd('/'), apiKey = apiKey)
        var sessionId: String? = null
        try {
            return runBlocking {
                val created = client.createSession(
                    CreateSessionRequest(
                        devices = enabledDevices,
                        flowPlan = flowPlan,
                        catalogYaml = catalog,
                        env = env,
                        artifact = ArtifactRef(
                            projectId = projectId,
                            pipelineId = pipelineId,
                            jobName = jobName,
                        ),
                        clientProjectPath = System.getenv("CI_PROJECT_PATH"),
                        workerGroups = workerGroups,
                    ),
                )
                sessionId = created.sessionId
                println("Cloud session ${created.sessionId} created (status=${created.status})")

                val lastEventAt = AtomicLong(System.currentTimeMillis())
                val lastEventSeq = AtomicLong(0)
                var lastStatus = created.status
                var lastCompletedCount = 0
                var workerPipelineLogged = false

                coroutineScope {
                    val streamJob = async {
                        while (true) {
                            val result = runCatching {
                                client.streamEvents(created.sessionId, lastEventSeq.get()) { event ->
                                    lastEventAt.set(System.currentTimeMillis())
                                    lastEventSeq.set(event.seq)
                                    printEvent(event)
                                }
                            }
                            if (result.isSuccess) break
                            System.err.println(
                                "SSE stream ended: ${result.exceptionOrNull()?.message} (reconnecting)",
                            )
                            delay(2000)
                        }
                    }

                    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
                    var exitCode = 1
                    while (System.currentTimeMillis() < deadline) {
                        val idleSeconds = (System.currentTimeMillis() - lastEventAt.get()) / 1000
                        if (idleSeconds >= stallSeconds) {
                            System.err.println(
                                "No cloud session activity for ${idleSeconds}s (limit ${stallSeconds}s) — aborting",
                            )
                            client.cancelSession(created.sessionId)
                            streamJob.cancel()
                            return@coroutineScope 1
                        }

                        val view = client.getSession(created.sessionId)
                        if (!workerPipelineLogged && view.gitlabPipelineId != null) {
                            workerPipelineLogged = true
                            val host = System.getenv("CI_SERVER_HOST") ?: "gitlab.doppelt-digital.com"
                            println(
                                "Maestro devices worker pipeline: " +
                                    "https://${host}/internal/maestro-devices/-/pipelines/${view.gitlabPipelineId}",
                            )
                        }
                        if (view.status != lastStatus) {
                            lastEventAt.set(System.currentTimeMillis())
                            lastStatus = view.status
                        }
                        if (view.flowResults.size > lastCompletedCount) {
                            lastEventAt.set(System.currentTimeMillis())
                            lastCompletedCount = view.flowResults.size
                        }

                        when (view.status) {
                            SessionStatus.COMPLETED -> {
                                writeJunit(view.junitXml)
                                exitCode = if (view.flowResults.all { it.success }) 0 else 1
                                break
                            }
                            SessionStatus.FAILED, SessionStatus.CANCELLED -> {
                                writeJunit(view.junitXml)
                                view.error?.let { System.err.println(it) }
                                exitCode = 1
                                break
                            }
                            else -> delay(5000)
                        }
                    }

                    streamJob.cancel()
                    if (System.currentTimeMillis() >= deadline) {
                        System.err.println("Timed out waiting for session after ${timeoutSeconds}s (last=$lastStatus)")
                    }
                    exitCode
                }
            }
        } finally {
            sessionId?.let { id ->
                runCatching { runBlocking { client.cancelSession(id) } }
            }
            client.close()
        }
    }

    private fun printEvent(event: SessionEvent) {
        when (event.type) {
            SessionEventType.FLOW_STARTED -> {
                println(">>> START ${event.flowPath} on ${event.deviceName}")
            }
            SessionEventType.LOG_LINE -> {
                println("[${event.flowPath}] ${event.message}")
            }
            SessionEventType.FLOW_FINISHED -> {
                val mark = if (event.success == true) "ok" else "FAILED"
                println("<<< DONE ${event.flowPath} [$mark]")
            }
            SessionEventType.STATUS_CHANGED -> Unit
        }
    }

    private fun writeJunit(xml: String?) {
        if (xml.isNullOrBlank()) return
        junitReport.parent?.let { Files.createDirectories(it) }
        Files.writeString(junitReport, xml)
    }
}
