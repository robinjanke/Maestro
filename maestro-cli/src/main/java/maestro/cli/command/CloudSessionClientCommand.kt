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
        val useLocalMacosWorker = System.getenv("E2E_LOCAL_MACOS_WORKER")
            ?.equals("true", ignoreCase = true) == true
        val macosDevices = enabledDevices.filter { it.startsWith("macos") }
        // Avoid triggering remote macOS GitLab workers when the developer Mac runs
        // cloud-worker locally (needed when shell hosts lack Accessibility / are locked).
        val triggerWorkerGroups = if (useLocalMacosWorker && macosDevices.isNotEmpty()) {
            workerGroups.filterNot { it == "macos" }.ifEmpty { listOf("__local__") }
        } else {
            workerGroups
        }

        val catalog = Files.readString(catalogPath)

        val totalFlows = plan.orderedFlows.size
        println("${Ansi.CYAN}📋 Cloud flow plan: ${totalFlows} flow(s) on device(s): ${enabledDevices.joinToString()}${Ansi.RESET}")
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
        var localMacosWorker: Process? = null
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
                        workerGroups = triggerWorkerGroups,
                    ),
                )
                sessionId = created.sessionId
                println("${Ansi.CYAN}☁️  Cloud session ${created.sessionId} created (status=${created.status})${Ansi.RESET}")

                if (useLocalMacosWorker && macosDevices.isNotEmpty()) {
                    val maestroBin = System.getenv("MAESTRO_BINARY")?.takeIf { it.isNotBlank() } ?: "maestro"
                    println(
                        "${Ansi.CYAN}🖥️  Starting local macOS cloud-worker for ${macosDevices.joinToString()}${Ansi.RESET}",
                    )
                    val pb = ProcessBuilder(
                        maestroBin,
                        "cloud-worker",
                        "run-flows",
                        "--url",
                        serverUrl.trimEnd('/'),
                        "--session",
                        created.sessionId,
                        "--token",
                        created.sessionToken,
                        "--group",
                        "macos",
                        "--flows-root",
                        flowsRoot.toAbsolutePath().toString(),
                        "--catalog",
                        catalogPath.toAbsolutePath().toString(),
                        "--devices",
                        macosDevices.joinToString(","),
                    )
                    pb.redirectErrorStream(true)
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    localMacosWorker = pb.start()
                }

                val lastEventAt = AtomicLong(System.currentTimeMillis())
                val lastEventSeq = AtomicLong(0)
                var lastStatus = created.status
                var lastCompletedCount = 0
                var workerPipelineLogged = false
                var failFastTriggered = false

                coroutineScope {
                    val streamJob = async {
                        while (true) {
                            val result = runCatching {
                                client.streamEvents(created.sessionId, lastEventSeq.get()) { event ->
                                    lastEventAt.set(System.currentTimeMillis())
                                    lastEventSeq.set(event.seq)
                                    printEvent(event)
                                    if (
                                        event.type == SessionEventType.FLOW_FINISHED &&
                                        event.success == false &&
                                        !failFastTriggered
                                    ) {
                                        failFastTriggered = true
                                        println(
                                            "${Ansi.YELLOW}⚠️  Fail-fast: cancelling session after first failure${Ansi.RESET}",
                                        )
                                        runCatching {
                                            runBlocking { client.cancelSession(created.sessionId) }
                                        }
                                    }
                                }
                            }
                            if (result.isSuccess) break
                            System.err.println(
                                "${Ansi.YELLOW}⚠️  SSE stream ended: ${result.exceptionOrNull()?.message} (reconnecting)${Ansi.RESET}",
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
                                "${Ansi.YELLOW}⚠️  No cloud session activity for ${idleSeconds}s (limit ${stallSeconds}s) — aborting${Ansi.RESET}",
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
                                "${Ansi.CYAN}🔗 Maestro devices worker pipeline: " +
                                    "https://${host}/internal/maestro-devices/-/pipelines/${view.gitlabPipelineId}${Ansi.RESET}",
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
                                if (exitCode == 0) {
                                    println("${Ansi.GREEN}✅ Cloud session completed successfully${Ansi.RESET}")
                                } else {
                                    println("${Ansi.RED}❌ Cloud session completed with failures${Ansi.RESET}")
                                }
                                break
                            }
                            SessionStatus.FAILED, SessionStatus.CANCELLED -> {
                                writeJunit(view.junitXml)
                                view.error?.let {
                                    System.err.println("${Ansi.RED}❌ $it${Ansi.RESET}")
                                }
                                exitCode = 1
                                break
                            }
                            else -> delay(5000)
                        }
                    }

                    streamJob.cancel()
                    if (System.currentTimeMillis() >= deadline) {
                        System.err.println(
                            "${Ansi.YELLOW}⚠️  Timed out waiting for session after ${timeoutSeconds}s (last=$lastStatus)${Ansi.RESET}",
                        )
                    }
                    exitCode
                }
            }
        } finally {
            localMacosWorker?.let { proc ->
                if (proc.isAlive) {
                    proc.destroy()
                    proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    if (proc.isAlive) proc.destroyForcibly()
                }
            }
            sessionId?.let { id ->
                runCatching { runBlocking { client.cancelSession(id) } }
            }
            client.close()
        }
    }

    private fun printEvent(event: SessionEvent) {
        when (event.type) {
            SessionEventType.FLOW_STARTED -> {
                println("${Ansi.CYAN}🚀 START ${event.flowPath} on ${event.deviceName}${Ansi.RESET}")
            }
            SessionEventType.LOG_LINE -> {
                println(colorizeLogLine(event.message.orEmpty()))
            }
            SessionEventType.FLOW_FINISHED -> {
                if (event.success == true) {
                    println("${Ansi.GREEN}✅ DONE ${event.flowPath}${Ansi.RESET}")
                } else {
                    println("${Ansi.RED}❌ DONE ${event.flowPath} [FAILED]${Ansi.RESET}")
                }
            }
            SessionEventType.STATUS_CHANGED -> {
                event.message?.let { println("${Ansi.CYAN}ℹ️  $it${Ansi.RESET}") }
            }
        }
    }

    /** Color status words; map legacy COMPLETED/WARNED for mixed worker versions. */
    private fun colorizeLogLine(message: String): String {
        val normalized = message
            .replace(Regex("""\bCOMPLETED\b""", RegexOption.IGNORE_CASE), "PASSED")
            .replace(Regex("""\bWARNED\b""", RegexOption.IGNORE_CASE), "WARN")

        if (Regex("""\bFAILED\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) {
            return "${Ansi.RED}$normalized${Ansi.RESET}"
        }

        return normalized
            .replace(Regex("""\bPASSED\b"""), "${Ansi.GREEN}PASSED${Ansi.RESET}")
            .replace(Regex("""\bWARN\b"""), "${Ansi.ORANGE}WARN${Ansi.RESET}")
    }

    private fun writeJunit(xml: String?) {
        if (xml.isNullOrBlank()) return
        junitReport.parent?.let { Files.createDirectories(it) }
        Files.writeString(junitReport, xml)
    }

    private object Ansi {
        private val enabled: Boolean =
            System.getenv("NO_COLOR").isNullOrBlank() &&
                (System.getenv("CI") != null || System.console() != null || System.getenv("TERM") != null)

        val RESET: String = if (enabled) "\u001B[0m" else ""
        val RED: String = if (enabled) "\u001B[31m" else ""
        val GREEN: String = if (enabled) "\u001B[32m" else ""
        val YELLOW: String = if (enabled) "\u001B[33m" else ""
        val ORANGE: String = if (enabled) "\u001B[38;5;208m" else ""
        val CYAN: String = if (enabled) "\u001B[36m" else ""
    }
}
