package maestro.cli.command

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import maestro.cli.cloud.ArtifactRef
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

    @CommandLine.Option(names = ["--job-name"], defaultValue = "collect-component-flows")
    private lateinit var jobName: String

    @CommandLine.Option(names = ["--timeout"], defaultValue = "3600")
    private var timeoutSeconds: Long = 3600

    @CommandLine.Option(names = ["--junit-report"], defaultValue = "maestro-cloud-junit.xml")
    private lateinit var junitReport: Path

    override fun call(): Int {
        val plan = DevicePlanService.plan(flowsRoot)
        if (!plan.isValid) {
            plan.errors.forEach { System.err.println(it) }
            return 1
        }

        val catalog = Files.readString(catalogPath)
        val catalogData = DevicePlanService.loadCatalog(catalogPath)
        val enabledDevices = plan.orderedFlows.mapNotNull { flow ->
            plan.flowDeviceByPath[flow]
        }.distinct().filter { deviceName ->
            val entry = catalogData.devices[deviceName] ?: return@filter true
            val enabledVar = entry.enabledVar ?: return@filter true
            System.getenv(enabledVar) == "true"
        }
        val workerGroups = enabledDevices.mapNotNull { deviceName ->
            catalogData.devices[deviceName]?.workerGroup
        }.distinct()

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
            while (System.currentTimeMillis() < deadline) {
                val view = runBlocking { client.getSession(created.sessionId) }
                last = view.status
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
        Files.createDirectories(junitReport.parent)
        Files.writeString(junitReport, xml)
    }
}
