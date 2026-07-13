package maestro.cli.command

import kotlinx.coroutines.runBlocking
import maestro.cli.CliError
import maestro.cli.deviceserver.DeviceServerClient
import maestro.orchestra.yaml.DevicePlanService
import picocli.CommandLine
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "wait-ready",
    description = ["Wait until all expected devices are registered on the device-server"],
)
class DeviceServerWaitReadyCommand : Callable<Int> {

    @CommandLine.Option(names = ["--url"], required = true)
    private lateinit var serverUrl: String

    @CommandLine.Option(names = ["--catalog"], required = true)
    private lateinit var catalogPath: Path

    @CommandLine.Option(names = ["--flows-root"], required = true)
    private lateinit var flowsRoot: Path

    @CommandLine.Option(names = ["--timeout"], defaultValue = "600")
    private var timeoutSeconds: Long = 600

    override fun call(): Int {
        val plan = DevicePlanService.plan(flowsRoot)
        if (!plan.isValid) throw CliError(plan.errors.joinToString("\n"))
        val catalog = DevicePlanService.loadCatalog(catalogPath)
        val enabledEnv = System.getenv().filterKeys { it.startsWith("E2E_TEST_") }
        val expected = maestro.cli.deviceserver.CatalogRunner.resolveExpectedDevices(catalog, plan, enabledEnv)

        DeviceServerClient(serverUrl).use { client ->
            val health = runBlocking {
                client.waitUntilReady(expected, timeoutSeconds)
            }
            println("Device server ready: ${health.registeredDevices}")
        }
        return 0
    }
}
