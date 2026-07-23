package maestro.drivers.desktop.linux

import maestro.TreeNode
import maestro.drivers.desktop.DesktopHierarchy
import maestro.drivers.desktop.RobotDesktopDriver
import java.awt.event.KeyEvent
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Linux Flutter Desktop driver using AT-SPI (Python helper) + AWT Robot.
 *
 * Requires a graphical session with accessibility bus (at-spi2). Flutter apps must
 * enable semantics; Semantics(identifier) maps to accessible id → Maestro resource-id.
 */
class LinuxDesktopDriver : RobotDesktopDriver("Linux") {
    private var processHandle: Process? = null

    override fun pasteModifierKey(): Int = KeyEvent.VK_CONTROL

    override fun open() {
        ensureDisplay()
        super.open()
    }

    override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
        ensureOpen()
        stopApp(appId)

        val command = resolveLaunchCommand(appId)
        logger.info("Launching Linux desktop app: {}", command.joinToString(" "))
        val builder = ProcessBuilder(command)
            .redirectErrorStream(true)
        File(appId).takeIf { it.isFile }?.parentFile?.let { builder.directory(it) }
        // Headless CI often needs a display; honor existing DISPLAY / prefer :0 or xvfb.
        if (builder.environment()["DISPLAY"].isNullOrBlank()) {
            builder.environment()["DISPLAY"] = System.getenv("DISPLAY") ?: ":0"
        }
        processHandle = builder.start()

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
                ProcessBuilder("kill", pid.toString())
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
            }
            runCatching {
                ProcessBuilder("kill", "-9", pid.toString())
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
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
        val script = extractHelperScript("desktop-helpers/linux-dump-tree.py", "linux-dump-tree.py")
        val process = ProcessBuilder("python3", script.absolutePath, pid.toString())
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(45, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
            error("Timed out dumping AT-SPI tree for pid=$pid")
        }
        if (process.exitValue() != 0) {
            error("AT-SPI dump failed (exit=${process.exitValue()}): ${output.take(800)}")
        }
        val json = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
            ?: error("AT-SPI dump produced no JSON: ${output.take(400)}")
        return DesktopHierarchy.parse(json).root
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        val opener = sequenceOf("xdg-open", "gio")
            .firstOrNull { commandExists(it) }
            ?: error("No URL opener found (xdg-open/gio)")
        val command = if (opener == "gio") listOf("gio", "open", link) else listOf("xdg-open", link)
        ProcessBuilder(command).start().waitFor(10, TimeUnit.SECONDS)
    }

    private fun ensureDisplay() {
        val display = System.getenv("DISPLAY")
        if (!display.isNullOrBlank()) return
        // Allow Robot to initialize in environments that set DISPLAY later; warn loudly.
        logger.warn("DISPLAY is unset; Linux desktop automation typically requires X11/Wayland + AT-SPI")
    }

    private fun resolveLaunchCommand(appId: String): List<String> {
        val file = File(appId)
        return when {
            file.isFile && file.canExecute() -> listOf(file.absolutePath)
            file.isFile && appId.endsWith(".desktop") -> listOf("gtk-launch", file.nameWithoutExtension)
            appId.endsWith(".desktop") -> listOf("gtk-launch", File(appId).nameWithoutExtension)
            File("/usr/bin/$appId").canExecute() -> listOf("/usr/bin/$appId")
            else -> listOf(appId)
        }
    }

    private fun resolvePid(appId: String): Int? {
        if (appPid != null && isProcessRunning(appPid!!)) {
            return appPid
        }
        val handlePid = processHandle?.pid()?.toInt()
        if (handlePid != null && isProcessRunning(handlePid)) {
            return handlePid
        }
        val base = File(appId).name
        return runCatching {
            val process = ProcessBuilder("pgrep", "-n", "-f", base)
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS)
            process.inputStream.bufferedReader().readText().trim().lines().firstOrNull()?.toIntOrNull()
        }.getOrNull() ?: handlePid
    }

    private fun isProcessRunning(pid: Int): Boolean {
        return runCatching {
            val process = ProcessBuilder("kill", "-0", pid.toString()).start()
            process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    private fun commandExists(name: String): Boolean {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "command -v $name").start()
            process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }
}
