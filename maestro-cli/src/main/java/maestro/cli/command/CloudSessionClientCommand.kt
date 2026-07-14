package maestro.cli.command

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import maestro.cli.cloud.ArtifactRef
import maestro.cli.cloud.CloudFlowPlanner
import maestro.cli.cloud.CloudServerClient
import maestro.cli.cloud.CreateSessionRequest
import maestro.cli.cloud.FlowPlan
import maestro.cli.cloud.SessionStatus
import maestro.orchestra.yaml.DevicePlanService
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

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
            val created = runBlocking {
                client.createSession(
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
            }
            sessionId = created.sessionId
            println("Cloud session ${created.sessionId} created (status=${created.status})")

            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            var last: SessionStatus = created.status
            var lastProgressLogMs = 0L
            while (System.currentTimeMillis() < deadline) {
                val view = runBlocking { client.getSession(created.sessionId) }
                last = view.status
                val now = System.currentTimeMillis()
                if (now - lastProgressLogMs >= 5000) {
                    lastProgressLogMs = now
                    val completed = view.flowResults.size
                    val failed = view.flowResults.count { !it.success }
                    val pending = plan.orderedFlows.filter { flow ->
                        flow !in view.flowResults.map { it.flowPath }.toSet()
                    }
                    val current = pending.firstOrNull()
                    println(
                        "Session ${view.status}: $completed/$totalFlows completed" +
                            (if (failed > 0) " ($failed failed)" else "") +
                            (current?.let { ", running or next: $it" } ?: ""),
                    )
                    view.flowResults.lastOrNull()?.let { result ->
                        val mark = if (result.success) "ok" else "FAILED"
                        println("  last finished: ${result.flowPath} [$mark]")
                    }
                }
                when (view.status) {
                    SessionStatus.COMPLETED -> {
                        writeJunit(view.junitXml)
                        return if (view.flowResults.all { it.success }) 0 else 1
                    }
                    SessionStatus.FAILED, SessionStatus.CANCELLED -> {
                        writeJunit(view.junitXml)
                        view.error?.let { System.err.println(it) }
                        return 1
                    }
                    else -> runBlocking { delay(5000) }
                }
            }
            System.err.println("Timed out waiting for session after ${timeoutSeconds}s (last=$last)")
            return 1
        } finally {
            sessionId?.let { id ->
                runCatching { runBlocking { client.cancelSession(id) } }
            }
            client.close()
        }
    }

    private fun writeJunit(xml: String?) {
        if (xml.isNullOrBlank()) return
        junitReport.parent?.let { Files.createDirectories(it) }
        Files.writeString(junitReport, xml)
    }
}
