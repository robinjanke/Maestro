package maestro.cli.command

import kotlinx.coroutines.runBlocking
import maestro.cli.CliError
import maestro.cli.deviceserver.DeviceServerClient
import maestro.cli.deviceserver.LocalDeviceService
import maestro.cli.deviceserver.WorkerDeviceRegistration
import maestro.cli.deviceserver.WorkerExecutor
import maestro.cli.deviceserver.WorkerRegistrationRequest
import maestro.orchestra.yaml.DevicePlanService
import picocli.CommandLine
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "join",
    description = ["Join a device-server as a worker and execute dispatched flows"],
)
class DeviceServerJoinCommand : Callable<Int> {

    @CommandLine.Option(names = ["--url"], required = true, description = ["Device server base URL"])
    private lateinit var serverUrl: String

    @CommandLine.Option(names = ["--group"], description = ["Worker group: macos, linux, windows"])
    private var workerGroup: String? = null

    @CommandLine.Option(names = ["--catalog"], description = ["Device catalog YAML path"])
    private var catalogPath: Path? = null

    @CommandLine.Option(names = ["--worker-id"], description = ["Worker identifier"])
    private var workerId: String? = null

    override fun call(): Int {
        val group = workerGroup ?: LocalDeviceService.hostOs()
        val id = workerId ?: "${group}-${UUID.randomUUID()}"

        val catalog = catalogPath?.let { DevicePlanService.loadCatalog(it) }
        val catalogPlatforms = catalog?.devices?.mapValues { (_, entry) ->
            entry.maestroPlatform ?: entry.category ?: "web"
        } ?: emptyMap()

        val catalogDevicesForGroup = catalog?.devices?.filter { (_, entry) ->
            entry.workerGroup == group
        }?.keys?.toList() ?: emptyList()

        val localDevices = if (catalogDevicesForGroup.isNotEmpty()) {
            LocalDeviceService.listForWorkerGroup(group, catalogDevicesForGroup, catalogPlatforms)
        } else {
            LocalDeviceService.listLocalDevices(includeWeb = group == "linux")
        }

        if (localDevices.isEmpty()) {
            throw CliError("No local devices found for worker group '$group'")
        }

        val registrations = localDevices.mapNotNull { device ->
            val catalogName = device.catalogName ?: return@mapNotNull null
            WorkerDeviceRegistration(
                catalogName = catalogName,
                instanceId = device.instanceId,
                platform = catalogPlatforms[catalogName] ?: device.platform.lowercase(),
            )
        }

        if (registrations.isEmpty()) {
            throw CliError("No catalog devices could be mapped for worker group '$group'")
        }

        DeviceServerClient(serverUrl).use { client ->
            runBlocking {
                client.register(
                    WorkerRegistrationRequest(
                        workerId = id,
                        workerGroup = group,
                        hostOs = LocalDeviceService.hostOs(),
                        devices = registrations,
                    ),
                )
            }
            println("Worker '$id' registered ${registrations.size} device(s): ${registrations.map { it.catalogName }}")
            WorkerExecutor.runJoinLoop(client, id)
        }
        println("Worker '$id' shutting down.")
        return 0
    }
}
