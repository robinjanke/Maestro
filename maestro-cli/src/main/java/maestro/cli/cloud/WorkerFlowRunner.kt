package maestro.cli.cloud

import kotlinx.coroutines.runBlocking
import maestro.cli.cloud.LocalDeviceService
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

object WorkerFlowRunner {
    fun attachAndRun(
        serverUrl: String,
        sessionId: String,
        sessionToken: String,
        workerGroup: String,
        flowsRoot: Path,
        catalogPath: Path?,
        catalogDeviceNames: List<String>,
    ): Int {
        val client = CloudServerClient(serverUrl, sessionToken = sessionToken)
        try {
            val catalogPlatforms = catalogPath?.let { path ->
                val catalog = maestro.orchestra.yaml.DevicePlanService.loadCatalog(path)
                catalogDeviceNames.associateWith { name ->
                    catalog.devices[name]?.maestroPlatform ?: "web"
                }
            } ?: catalogDeviceNames.associateWith { "web" }

            val locals = LocalDeviceService.listForWorkerGroup(
                workerGroup = workerGroup,
                catalogDeviceNames = catalogDeviceNames,
                catalogPlatforms = catalogPlatforms,
            )
            if (locals.isEmpty()) {
                System.err.println("No local devices matched for worker group '$workerGroup'")
                return 1
            }

            val workerId = "${workerGroup}-${UUID.randomUUID()}"
            val attachResponse = runBlocking {
                client.attachWorker(
                    sessionId,
                    WorkerAttachRequest(
                        workerId = workerId,
                        workerGroup = workerGroup,
                        hostOs = LocalDeviceService.hostOs(),
                        devices = locals.map {
                            AttachedDevice(
                                catalogName = it.catalogName ?: "unknown",
                                instanceId = it.instanceId,
                                platform = it.platform,
                            )
                        },
                    ),
                )
            }

            var exitCode = 0
            val sessionEnv = attachResponse.env
            for (assignment in attachResponse.assignedFlows) {
                val flowFile = flowsRoot.resolve(assignment.flowPath).toFile()
                if (!flowFile.exists()) {
                    System.err.println("Missing flow: $flowFile")
                    postFailure(client, sessionId, assignment.flowPath, assignment.deviceName, "flow file missing")
                    exitCode = 1
                    continue
                }
                System.out.println(
                    "Running flow ${assignment.flowPath} on device ${assignment.deviceName} (${assignment.platform})",
                )
                val instanceId = locals.firstOrNull { it.catalogName == assignment.deviceName }?.instanceId
                val result = executeFlow(flowFile, assignment.platform, instanceId, sessionEnv)
                runBlocking {
                    client.postResult(
                        sessionId,
                        FlowResultRequest(
                            flowPath = assignment.flowPath,
                            deviceName = assignment.deviceName,
                            success = result.success,
                            exitCode = result.exitCode,
                            durationMs = result.durationMs,
                            output = result.output,
                        ),
                    )
                }
                if (!result.success) exitCode = 1
            }
            return exitCode
        } finally {
            client.close()
        }
    }

    private fun postFailure(
        client: CloudServerClient,
        sessionId: String,
        flowPath: String,
        deviceName: String,
        message: String,
    ) {
        runBlocking {
            client.postResult(
                sessionId,
                FlowResultRequest(
                    flowPath = flowPath,
                    deviceName = deviceName,
                    success = false,
                    exitCode = 1,
                    durationMs = 0,
                    output = message,
                ),
            )
        }
    }

    private data class LocalFlowResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val durationMs: Long,
    )

    private fun executeFlow(
        flowFile: File,
        platform: String,
        instanceId: String?,
        sessionEnv: Map<String, String>,
    ): LocalFlowResult {
        val start = System.currentTimeMillis()
        return try {
            val command = buildMaestroCommand(flowFile, platform, instanceId)
            val processBuilder = ProcessBuilder(command)
                .directory(flowFile.parentFile)
                .redirectErrorStream(true)
            if (sessionEnv.isNotEmpty()) {
                processBuilder.environment().putAll(sessionEnv)
            }
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.MINUTES)
            val exitCode = if (finished) process.exitValue() else {
                process.destroyForcibly()
                -1
            }
            LocalFlowResult(
                success = exitCode == 0,
                exitCode = exitCode,
                output = output,
                durationMs = System.currentTimeMillis() - start,
            )
        } catch (e: Exception) {
            LocalFlowResult(
                success = false,
                exitCode = 1,
                output = e.message ?: e.javaClass.simpleName,
                durationMs = System.currentTimeMillis() - start,
            )
        }
    }

    private fun buildMaestroCommand(flowFile: File, platform: String, instanceId: String?): List<String> {
        val maestroBin = System.getenv("MAESTRO_BIN") ?: "maestro"
        val command = mutableListOf(maestroBin, "test")
        when (platform.lowercase()) {
            "web" -> command += "--headless"
            "desktop" -> command += listOf("--platform", "desktop")
            "ios" -> command += listOf("--platform", "ios")
            "android" -> command += listOf("--platform", "android")
        }
        instanceId?.let { command += listOf("--device", it) }
        command += flowFile.absolutePath
        return command
    }
}
