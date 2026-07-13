package maestro.cli.command

import picocli.CommandLine

@CommandLine.Command(
    name = "device-server",
    description = ["Manage distributed Maestro device workers for CI E2E"],
    subcommands = [
        DeviceServerStartCommand::class,
        DeviceServerJoinCommand::class,
        DeviceServerListLocalDevicesCommand::class,
        DeviceServerWaitReadyCommand::class,
        DeviceServerRunCatalogCommand::class,
    ],
)
class DeviceServerCommand
