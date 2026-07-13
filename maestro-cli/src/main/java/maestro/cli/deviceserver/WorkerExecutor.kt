package maestro.cli.deviceserver

import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

object WorkerExecutor {
    fun runJoinLoop(
        client: DeviceServerClient,
        workerId: String,
        pollIntervalMs: Long = 1000,
    ) {
        while (true) {
            val health = kotlinx.coroutines.runBlocking { client.health() }
            if (health.status == "shutting_down") break

            val job = kotlinx.coroutines.runBlocking { client.pollJob(workerId) }
            if (job == null) {
                Thread.sleep(pollIntervalMs)
                continue
            }

            val result = executeJob(job)
            kotlinx.coroutines.runBlocking { client.completeJob(result) }
        }
    }

    fun executeJob(job: ExecuteFlowRequest): ExecuteFlowResult {
        val start = System.currentTimeMillis()
        val workDir = Files.createTempDirectory("maestro-worker-").toFile()
        try {
            val flowFile = File(workDir, "flow.yaml")
            flowFile.writeText(job.flowContent)

            val command = buildMaestroCommand(job, flowFile)
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.MINUTES)
            val exitCode = if (finished) process.exitValue() else {
                process.destroyForcibly()
                -1
            }

            return ExecuteFlowResult(
                jobId = job.jobId,
                success = exitCode == 0,
                exitCode = exitCode,
                output = output,
                durationMs = System.currentTimeMillis() - start,
            )
        } catch (e: Exception) {
            return ExecuteFlowResult(
                jobId = job.jobId,
                success = false,
                exitCode = 1,
                output = e.message ?: e.javaClass.simpleName,
                durationMs = System.currentTimeMillis() - start,
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun buildMaestroCommand(job: ExecuteFlowRequest, flowFile: File): List<String> {
        val maestroBin = System.getenv("MAESTRO_BIN") ?: "maestro"
        val command = mutableListOf(
            maestroBin,
            "test",
        )
        when (job.platform.lowercase()) {
            "web" -> if (job.headless) command += "--headless"
            "desktop" -> command += listOf("--platform", "desktop")
            "ios" -> command += listOf("--platform", "ios")
            "android" -> command += listOf("--platform", "android")
        }
        job.instanceId?.let { instanceId ->
            command += listOf("--device", instanceId)
        }
        job.env.forEach { (key, value) ->
            command += listOf("--env", "$key=$value")
        }
        command += flowFile.absolutePath
        return command
    }
}
