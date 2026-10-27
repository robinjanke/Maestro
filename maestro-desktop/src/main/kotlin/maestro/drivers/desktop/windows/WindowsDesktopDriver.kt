package maestro.drivers.desktop.windows

import maestro.TreeNode
import maestro.drivers.desktop.DesktopHierarchy
import maestro.drivers.desktop.RobotDesktopDriver
import java.awt.event.KeyEvent
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Windows Flutter Desktop driver using UI Automation (via PowerShell helper) + AWT Robot.
 *
 * Flutter Semantics(identifier) maps to UIA AutomationId → Maestro resource-id.
 */
class WindowsDesktopDriver : RobotDesktopDriver("Windows") {
    private var processHandle: Process? = null
    private var launchedExecutable: File? = null

    override fun pasteModifierKey(): Int = KeyEvent.VK_CONTROL

    override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
        ensureOpen()
        stopApp(appId)

        val command = resolveLaunchCommand(appId, launchArguments)
        val executable = File(command.first()).takeIf { it.isFile }
        launchedExecutable = executable
        logger.info("Launching Windows desktop app: {}", command.joinToString(" "))
        processHandle = ProcessBuilder(command)
            .directory(executable?.parentFile ?: File(appId).takeIf { it.isFile }?.parentFile)
            .inheritIO()
            .start()

        Thread.sleep(3000)
        val pid = resolvePid(appId) ?: processHandle?.pid()?.toInt()
            ?: error("Could not find running process for app '$appId'")
        appPid = pid
        this.appId = appId
        waitUntilScreenIsStatic(5000)
    }

    override fun stopApp(appId: String) {
        val pid = resolvePid(appId) ?: appPid
        if (pid != null) {
            runCatching {
                ProcessBuilder("taskkill", "/PID", pid.toString(), "/T", "/F")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS)
            }
        }
        processHandle?.destroyForcibly()
        processHandle = null
        if (this.appId == appId) {
            this.appPid = null
            this.appId = null
        }
    }

    override fun killApp(appId: String) = stopApp(appId)

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        val pid = appPid ?: error("No desktop application is running. Call launchApp first.")
        val script = extractHelperScript("desktop-helpers/windows-dump-tree.ps1", "windows-dump-tree.ps1")
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            script.absolutePath,
            "-ProcessId",
            pid.toString(),
        ).redirectErrorStream(true).start()
        val finished = process.waitFor(45, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
            error("Timed out dumping UI Automation tree for pid=$pid")
        }
        if (process.exitValue() != 0) {
            error("UI Automation dump failed (exit=${process.exitValue()}): ${output.take(800)}")
        }
        // PowerShell may emit BOM / warnings before JSON — take last JSON object.
        val json = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
            ?: output.trim().substringAfter("{").let { "{$it" }
        return DesktopHierarchy.parse(json).root
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        ProcessBuilder("cmd", "/c", "start", "", link)
            .start()
            .waitFor(10, TimeUnit.SECONDS)
    }

    private fun resolveLaunchCommand(appId: String, launchArguments: Map<String, Any>): List<String> {
        configuredExecutable(launchArguments)?.let { return listOf(it.absolutePath) }
        val file = File(appId)
        return when {
            file.isFile && appId.endsWith(".exe", ignoreCase = true) -> listOf(file.absolutePath)
            file.isFile -> listOf(file.absolutePath)
            appId.contains("\\") || appId.contains("/") -> listOf(appId)
            else -> listOf("cmd", "/c", "start", "", appId)
        }
    }

    /**
     * Flows keep the bundle id (`ai.mainpath.toolbox`). Windows has no bundle
     * lookup, so CI passes the built exe via MAESTRO_DESKTOP_APP or
     * launchArguments["executable"].
     */
    private fun configuredExecutable(launchArguments: Map<String, Any>): File? {
        val fromArgs = launchArguments["executable"]?.toString()?.trim().orEmpty()
        val raw = if (fromArgs.isNotEmpty()) {
            fromArgs
        } else {
            System.getenv("MAESTRO_DESKTOP_APP")?.trim().orEmpty()
        }
        if (raw.isEmpty()) return null
        val file = File(raw)
        if (!file.isFile) error("Desktop executable does not exist: $raw")
        return file
    }

    private fun resolvePid(appId: String): Int? {
        if (appPid != null && isProcessRunning(appPid!!)) {
            return appPid
        }
        val handlePid = processHandle?.pid()?.toInt()
        if (handlePid != null && isProcessRunning(handlePid)) {
            return handlePid
        }

        val exeName = launchedExecutable?.name
            ?: File(appId).name.takeIf { it.endsWith(".exe", ignoreCase = true) }
            ?: return handlePid
        return runCatching {
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-Command",
                "(Get-Process -Name '${exeName.removeSuffix(".exe").removeSuffix(".EXE")}' -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Id)",
            ).redirectErrorStream(true).start()
            process.waitFor(10, TimeUnit.SECONDS)
            process.inputStream.bufferedReader().readText().trim().toIntOrNull()
        }.getOrNull() ?: handlePid
    }

    private fun isProcessRunning(pid: Int): Boolean {
        return runCatching {
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-Command",
                "if (Get-Process -Id $pid -ErrorAction SilentlyContinue) { '1' } else { '0' }",
            ).redirectErrorStream(true).start()
            process.waitFor(5, TimeUnit.SECONDS)
            process.inputStream.bufferedReader().readText().trim() == "1"
        }.getOrDefault(false)
    }
}
