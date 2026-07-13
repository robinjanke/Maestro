package maestro.cli.command

import picocli.CommandLine

@CommandLine.Command(
    name = "cloud-server",
    description = ["Manage the Maestro Cloud device coordination service"],
    subcommands = [
        CloudServerStartCommand::class,
        CloudSessionClientCommand::class,
    ],
)
class CloudServerCommand
