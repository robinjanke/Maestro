package maestro.cli.cloud

import java.nio.file.Files
import java.nio.file.Path

data class FlowRunReport(
    val deviceName: String,
    val flowPath: String,
    val success: Boolean,
    val exitCode: Int,
    val durationMs: Long,
    val output: String = "",
)

object JunitReportWriter {
    fun write(path: Path, results: List<FlowRunReport>) {
        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            val failures = results.count { !it.success }
            val totalTime = results.sumOf { it.durationMs } / 1000.0
            appendLine("""<testsuite name="maestro-cloud" tests="${results.size}" failures="$failures" time="$totalTime">""")
            results.forEach { result ->
                val className = result.deviceName
                val name = result.flowPath
                appendLine("""  <testcase classname="$className" name="$name" time="${result.durationMs / 1000.0}">""")
                if (!result.success) {
                    val message = result.output.take(500).replace("\"", "'")
                    appendLine("""    <failure message="$message"/>""")
                }
                appendLine("  </testcase>")
            }
            appendLine("</testsuite>")
        }
        Files.createDirectories(path.parent)
        Files.writeString(path, xml)
    }
}
