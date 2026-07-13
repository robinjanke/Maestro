package maestro.cli.command

import maestro.cli.cloud.WorkerFlowRunner
import picocli.CommandLine
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "run-flows",
    description = ["Attach worker devices to a cloud session and execute assigned flows"],
)
class CloudWorkerRunFlowsCommand : Callable<Int> {

    @CommandLine.Option(names = ["--url"], required = true)
    private lateinit var serverUrl: String

    @CommandLine.Option(names = ["--session"], required = true)
    private lateinit var sessionId: String

    @CommandLine.Option(names = ["--token"], required = true)
    private lateinit var sessionToken: String

    @CommandLine.Option(names = ["--group"], required = true)
    private lateinit var workerGroup: String

    @CommandLine.Option(names = ["--flows-root"], required = true)
    private lateinit var flowsRoot: Path

    @CommandLine.Option(names = ["--catalog"])
    private var catalogPath: Path? = null

    @CommandLine.Option(
        names = ["--devices"],
        description = ["Comma-separated catalog device names for this worker"],
        required = true,
    )
    private lateinit var devicesCsv: String

    override fun call(): Int {
        val devices = devicesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return WorkerFlowRunner.attachAndRun(
            serverUrl = serverUrl.trimEnd('/'),
            sessionId = sessionId,
            sessionToken = sessionToken,
            workerGroup = workerGroup,
            flowsRoot = flowsRoot,
            catalogPath = catalogPath,
            catalogDeviceNames = devices,
        )
    }
}
