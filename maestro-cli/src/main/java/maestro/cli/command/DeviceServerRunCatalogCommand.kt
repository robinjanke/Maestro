package maestro.cli.command

import kotlinx.coroutines.runBlocking
import maestro.cli.CliError
import maestro.cli.deviceserver.CatalogRunner
import maestro.cli.deviceserver.DeviceServerClient
import picocli.CommandLine
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "run-catalog",
    description = ["Execute all flows from the device catalog via registered workers"],
)
class DeviceServerRunCatalogCommand : Callable<Int> {

    @CommandLine.Option(names = ["--url"], required = true)
    private lateinit var serverUrl: String

    @CommandLine.Option(names = ["--catalog"], required = true)
    private lateinit var catalogPath: Path

    @CommandLine.Option(names = ["--flows-root"], required = true)
    private lateinit var flowsRoot: Path

    @CommandLine.Option(names = ["--junit-report"])
    private var junitReport: Path? = null

    override fun call(): Int {
        val (plan, catalog) = CatalogRunner.loadPlanAndCatalog(flowsRoot, catalogPath)
        val env = buildEnv()

        DeviceServerClient(serverUrl).use { client ->
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
            if (!report.success) throw CliError("Catalog run failed")
        }
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
