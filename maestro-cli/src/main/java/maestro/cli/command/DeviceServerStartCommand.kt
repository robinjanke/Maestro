package maestro.cli.command

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import kotlinx.coroutines.runBlocking
import maestro.cli.CliError
import maestro.cli.deviceserver.CatalogRunner
import maestro.cli.deviceserver.DeviceServer
import maestro.cli.deviceserver.DeviceServerClient
import maestro.cli.deviceserver.JunitReportWriter
import maestro.cli.deviceserver.resolveDeviceServerToken
import picocli.CommandLine
import java.net.InetAddress
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "start",
    description = ["Start the Maestro device-server coordinator"],
)
class DeviceServerStartCommand : Callable<Int> {

    @CommandLine.Option(names = ["--port"], defaultValue = "8765")
    private var port: Int = 8765

    @CommandLine.Option(names = ["--host"], defaultValue = "0.0.0.0")
    private var host: String = "0.0.0.0"

    @CommandLine.Option(names = ["--catalog"], description = ["Device catalog YAML path"])
    private var catalogPath: Path? = null

    @CommandLine.Option(names = ["--flows-root"], description = ["Collected flows root"])
    private var flowsRoot: Path? = null

    @CommandLine.Option(names = ["--run-catalog"], description = ["Run full catalog after workers are ready"])
    private var runCatalog: Boolean = false

    @CommandLine.Option(names = ["--wait-timeout"], defaultValue = "600")
    private var waitTimeoutSeconds: Long = 600

    @CommandLine.Option(names = ["--junit-report"], description = ["JUnit XML output path"])
    private var junitReport: Path? = null

    @CommandLine.Option(names = ["--url-out"], description = ["Write device-server URL to this file"])
    private var urlOut: Path? = null

    @CommandLine.Option(names = ["--token"], description = ["Auth token (or DEVICE_SERVER_TOKEN env)"])
    private var token: String? = null

    override fun call(): Int {
        val (plan, catalog) = if (catalogPath != null && flowsRoot != null) {
            CatalogRunner.loadPlanAndCatalog(flowsRoot!!, catalogPath!!)
        } else {
            null to null
        }

        val enabledEnv = System.getenv().filterKeys { it.startsWith("E2E_TEST_") || it.startsWith("TARGET_") }
        val expectedDevices = if (plan != null && catalog != null) {
            CatalogRunner.resolveExpectedDevices(catalog, plan, enabledEnv)
        } else {
            emptySet()
        }

        val authToken = resolveDeviceServerToken(token)
        val server = DeviceServer.start(
            host = host,
            port = port,
            expectedDevices = expectedDevices,
            authToken = authToken,
        )
        val publicHost = System.getenv("E2E_DEVICE_SERVER_HOST")
            ?: InetAddress.getLocalHost().hostAddress
        val publicUrl = "http://$publicHost:${server.port}"
        println("Device server started at $publicUrl")
        urlOut?.toFile()?.writeText("$publicUrl\n")

        if (!runCatalog || plan == null || catalog == null || flowsRoot == null) {
            println("Waiting for workers. Press Ctrl+C to stop.")
            while (!server.registry().isShutdownRequested()) {
                Thread.sleep(1000)
            }
            server.close()
            return 0
        }

        DeviceServerClient(publicUrl, authToken).use { client ->
            println("Waiting for devices: $expectedDevices")
            runBlocking {
                client.waitUntilReady(expectedDevices, waitTimeoutSeconds)
            }
            println("All expected devices registered. Running catalog...")
            val env = buildEnv()
            val report = CatalogRunner.runCatalog(
                client = client,
                flowsRoot = flowsRoot!!,
                catalog = catalog,
                plan = plan,
                env = env,
                junitReportPath = junitReport,
            )
            printReport(report)
            runBlocking { client.shutdown() }
            if (!report.success) throw CliError("Catalog run failed")
        }
        server.close()
        return 0
    }

    private fun buildEnv(): Map<String, String> {
        val keys = listOf(
            "TARGET_BASE_URL", "TARGET_WEB_URL", "TARGET_FRONTEND_URL",
            "E2E_BACKEND_BASE_URL", "E2E_RUN_ID", "E2E_USER_EMAIL",
            "E2E_USER_NAME", "E2E_USER_PASSWORD",
        )
        return keys.mapNotNull { key ->
            System.getenv(key)?.let { key to it }
        }.toMap()
    }

    private fun printReport(report: maestro.cli.deviceserver.CatalogRunReport) {
        report.flowResults.forEach { result ->
            val status = if (result.success) "PASS" else "FAIL"
            println("[$status] ${result.deviceName} :: ${result.flowPath} (${result.durationMs}ms)")
            if (!result.success && result.output.isNotBlank()) {
                println(result.output.take(2000))
            }
        }
        report.junitPath?.let { println("JUnit report: $it") }
    }
}
