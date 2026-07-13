package maestro.cli.deviceserver

import java.nio.file.Files
import java.nio.file.Path

object JunitReportWriter {
    fun write(path: Path, results: List<FlowRunReport>) {
        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            val failures = results.count { !it.success }
            val totalTime = results.sumOf { it.durationMs } / 1000.0
            appendLine("""<testsuite name="maestro-device-server" tests="${results.size}" failures="$failures" time="$totalTime">""")
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
