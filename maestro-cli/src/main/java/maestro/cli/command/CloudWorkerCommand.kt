package maestro.cli.command

import picocli.CommandLine

@CommandLine.Command(
    name = "cloud-worker",
    description = ["Run Maestro Cloud worker jobs in CI"],
    subcommands = [
        CloudWorkerRunFlowsCommand::class,
    ],
)
class CloudWorkerCommand
