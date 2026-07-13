package maestro.cli.command

import maestro.cli.cloud.CloudServer
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "start",
    description = ["Start the Maestro Cloud server"],
)
class CloudServerStartCommand : Callable<Int> {

    @CommandLine.Option(names = ["--host"], defaultValue = "0.0.0.0")
    private lateinit var host: String

    @CommandLine.Option(names = ["--port"], defaultValue = "8765")
    private var port: Int = 8765

    @CommandLine.Option(
        names = ["--public-url"],
        description = ["Public URL advertised to workers and CI clients"],
    )
    private var publicUrl: String? = null

    override fun call(): Int {
        val server = CloudServer.start(host = host, port = port, publicUrl = publicUrl)
        println("Maestro Cloud server listening on ${server.baseUrl}")
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        Thread.currentThread().join()
        return 0
    }
}
