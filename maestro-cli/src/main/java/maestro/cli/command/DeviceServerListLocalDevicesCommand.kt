package maestro.cli.command

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import maestro.cli.deviceserver.LocalDeviceService
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "list-local-devices",
    description = ["List devices available on this machine for device-server workers"],
)
class DeviceServerListLocalDevicesCommand : Callable<Int> {

    @CommandLine.Option(names = ["--json"], description = ["Output JSON"])
    private var json: Boolean = false

    @CommandLine.Option(names = ["--group"], description = ["Filter by worker group host OS"])
    private var workerGroup: String? = null

    override fun call(): Int {
        val hostOs = LocalDeviceService.hostOs()
        if (workerGroup != null && workerGroup != hostOs) {
            if (json) {
                println("[]")
            } else {
                println("No devices: this host is '$hostOs', requested group '$workerGroup'")
            }
            return 0
        }

        val devices = LocalDeviceService.listLocalDevices(includeWeb = hostOs == "linux")
        if (json) {
            val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            println(mapper.writeValueAsString(devices))
        } else {
            println("Host OS: $hostOs")
            devices.forEach { device ->
                println("  ${device.instanceId}  ${device.platform}  ${device.description}")
            }
        }
        return 0
    }
}
