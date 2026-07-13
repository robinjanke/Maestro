package maestro.cli.command

import kotlinx.coroutines.runBlocking
import maestro.cli.CliError
import maestro.cli.deviceserver.CatalogRunner
import maestro.cli.deviceserver.DeviceServerClient
import maestro.cli.deviceserver.resolveDeviceServerToken
import picocli.CommandLine
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "run-client",
    description = [
        "Join the device-server as a test client: wait for workers, dispatch catalog flows, then shutdown",
    ],
)
class DeviceServerRunClientCommand : Callable<Int> {

    @CommandLine.Option(names = ["--url"], required = true, description = ["Device server base URL"])
    private lateinit var serverUrl: String

    @CommandLine.Option(names = ["--catalog"], required = true)
    private lateinit var catalogPath: Path

    @CommandLine.Option(names = ["--flows-root"], required = true)
    private lateinit var flowsRoot: Path

    @CommandLine.Option(names = ["--timeout"], defaultValue = "600")
    private var timeoutSeconds: Long = 600

    @CommandLine.Option(names = ["--junit-report"])
    private var junitReport: Path? = null

    @CommandLine.Option(names = ["--token"], description = ["Auth token (or DEVICE_SERVER_TOKEN env)"])
    private var token: String? = null

    override fun call(): Int {
        val authToken = resolveDeviceServerToken(token)
            ?: throw CliError("DEVICE_SERVER_TOKEN is required for the test client")

        val (plan, catalog) = CatalogRunner.loadPlanAndCatalog(flowsRoot, catalogPath)
        val enabledEnv = System.getenv().filterKeys { it.startsWith("E2E_TEST_") }
        val expected = CatalogRunner.resolveExpectedDevices(catalog, plan, enabledEnv)
        val env = buildEnv()

        DeviceServerClient(serverUrl, authToken).use { client ->
            println("Test client waiting for devices: $expected")
            runBlocking {
                client.waitUntilReady(expected, timeoutSeconds)
            }
            println("All expected devices registered. Running catalog...")
            val report = CatalogRunner.runCatalog(
                client = client,
                flowsRoot = flowsRoot,
                catalog = catalog,
                plan = plan,
                env = env,
                junitReportPath = junitReport,
            )
            report.flowResults.forEach { result ->
                val status = if (result.success) "PASS" else "FAIL"
                println("[$status] ${result.deviceName} :: ${result.flowPath}")
            }
            report.junitPath?.let { println("JUnit report: $it") }
            runBlocking { client.shutdown() }
            if (!report.success) throw CliError("Catalog run failed")
        }
        println("Test client finished and signaled device-server shutdown.")
        return 0
    }

    private fun buildEnv(): Map<String, String> {
        return listOf(
            "TARGET_BASE_URL", "TARGET_WEB_URL", "TARGET_FRONTEND_URL",
            "E2E_BACKEND_BASE_URL", "E2E_RUN_ID", "E2E_USER_EMAIL",
            "E2E_USER_NAME", "E2E_USER_PASSWORD",
        ).mapNotNull { key -> System.getenv(key)?.let { key to it } }.toMap()
    }
}
