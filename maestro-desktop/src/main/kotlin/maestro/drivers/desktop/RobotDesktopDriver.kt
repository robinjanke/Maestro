package maestro.drivers.desktop

import maestro.Capability
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.OnDeviceElementQuery
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.DeviceOrientation
import maestro.device.Platform
import okio.Buffer
import okio.Sink
import okio.source
import org.slf4j.LoggerFactory
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO

/**
 * Shared desktop driver primitives (Robot for input/screenshot).
 * OS-specific subclasses supply launch/stop and accessibility hierarchy.
 */
abstract class RobotDesktopDriver(
    private val platformLabel: String,
) : Driver {
    protected val logger = LoggerFactory.getLogger(javaClass)
    protected val robot = Robot()
    protected var open = false
    protected var appPid: Int? = null
    protected var appId: String? = null

    override fun name(): String = "Flutter Desktop ($platformLabel)"

    override fun open() {
        open = true
    }

    override fun close() {
        open = false
        appPid = null
        appId = null
    }

    override fun deviceInfo(): DeviceInfo {
        val screen = Toolkit.getDefaultToolkit().screenSize
        return DeviceInfo(
            platform = Platform.DESKTOP,
            widthPixels = screen.width,
            heightPixels = screen.height,
            widthGrid = screen.width,
            heightGrid = screen.height,
        )
    }

    protected fun ensureOpen() {
        if (!open) error("Driver is not open")
    }

    override fun clearAppState(appId: String) = Unit
    override fun clearKeychain() = Unit
    override fun backPress() = Unit
    override fun hideKeyboard() = Unit
    override fun setLocation(latitude: Double, longitude: Double) = Unit
    override fun setOrientation(orientation: DeviceOrientation) = Unit
    override fun setProxy(host: String, port: Int) = Unit
    override fun resetProxy() = Unit
    override fun isShutdown(): Boolean = !open
    override fun capabilities(): List<Capability> = emptyList()
    override fun setPermissions(appId: String, permissions: Map<String, String>) = Unit
    override fun addMedia(mediaFiles: List<File>) = Unit
    override fun isAirplaneModeEnabled(): Boolean = false
    override fun setAirplaneMode(enabled: Boolean) = Unit
    override fun isKeyboardVisible(): Boolean = false
    override fun queryOnDeviceElements(query: OnDeviceElementQuery): List<TreeNode> = emptyList()

    override fun tap(point: Point) {
        robot.mouseMove(point.x, point.y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(200)
    }

    override fun longPress(point: Point) {
        robot.mouseMove(point.x, point.y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(1500)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    override fun pressKey(code: KeyCode) {
        val keyEvent = when (code) {
            KeyCode.ENTER -> KeyEvent.VK_ENTER
            KeyCode.BACKSPACE -> KeyEvent.VK_BACK_SPACE
            KeyCode.ESCAPE -> KeyEvent.VK_ESCAPE
            KeyCode.TAB -> KeyEvent.VK_TAB
            else -> error("Keycode $code is not supported on $platformLabel desktop yet")
        }
        robot.keyPress(keyEvent)
        robot.keyRelease(keyEvent)
    }

    override fun scrollVertical() {
        swipe(SwipeDirection.UP, 400)
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        robot.mouseMove(start.x, start.y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        val steps = (durationMs / 16).coerceAtLeast(1)
        val dx = (end.x - start.x) / steps.toDouble()
        val dy = (end.y - start.y) / steps.toDouble()
        var x = start.x.toDouble()
        var y = start.y.toDouble()
        repeat(steps.toInt()) {
            x += dx
            y += dy
            robot.mouseMove(x.toInt(), y.toInt())
            Thread.sleep(16)
        }
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val info = deviceInfo()
        val centerX = info.widthGrid / 2
        val centerY = info.heightGrid / 2
        val deltaY = info.heightGrid / 3
        val (start, end) = when (swipeDirection) {
            SwipeDirection.UP -> Point(centerX, centerY + deltaY) to Point(centerX, centerY - deltaY)
            SwipeDirection.DOWN -> Point(centerX, centerY - deltaY) to Point(centerX, centerY + deltaY)
            SwipeDirection.LEFT -> Point(centerX + deltaY, centerY) to Point(centerX - deltaY, centerY)
            SwipeDirection.RIGHT -> Point(centerX - deltaY, centerY) to Point(centerX + deltaY, centerY)
        }
        swipe(start, end, durationMs)
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        val delta = 120
        val end = when (direction) {
            SwipeDirection.UP -> Point(elementPoint.x, elementPoint.y - delta)
            SwipeDirection.DOWN -> Point(elementPoint.x, elementPoint.y + delta)
            SwipeDirection.LEFT -> Point(elementPoint.x - delta, elementPoint.y)
            SwipeDirection.RIGHT -> Point(elementPoint.x + delta, elementPoint.y)
        }
        swipe(elementPoint, end, durationMs)
    }

    override fun inputText(text: String) {
        text.forEach { typeChar(it) }
    }

    override fun eraseText(charactersToErase: Int) {
        repeat(charactersToErase) { pressKey(KeyCode.BACKSPACE) }
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        val capture = robot.createScreenCapture(Rectangle(Toolkit.getDefaultToolkit().screenSize))
        val tmp = File.createTempFile("maestro-desktop", ".png")
        try {
            ImageIO.write(capture, "png", tmp)
            tmp.inputStream().use { input ->
                val buffer = Buffer()
                buffer.writeAll(input.source())
                out.write(buffer, buffer.size)
            }
        } finally {
            tmp.delete()
        }
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        error("Screen recording is not supported for $platformLabel desktop yet")
    }

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var previous = ""
        while (System.currentTimeMillis() < deadline) {
            val current = hierarchyFingerprint()
            if (current.isNotEmpty() && current == previous) {
                return true
            }
            previous = current
            Thread.sleep(250)
        }
        return false
    }

    override fun waitForAppToSettle(
        initialHierarchy: ViewHierarchy?,
        appId: String?,
        timeoutMs: Int?,
    ): ViewHierarchy? {
        val timeout = timeoutMs?.toLong() ?: 3000L
        return if (waitUntilScreenIsStatic(timeout)) {
            ViewHierarchy.from(this, excludeKeyboardElements = true)
        } else {
            null
        }
    }

    private fun hierarchyFingerprint(): String {
        return runCatching {
            contentDescriptor(excludeKeyboardElements = true).aggregate().joinToString("|") {
                listOfNotNull(
                    it.attributes["resource-id"],
                    it.attributes["text"],
                    it.attributes["role"],
                ).joinToString(":")
            }
        }.getOrDefault("")
    }

    protected fun typeChar(char: Char) {
        val keyCode = KeyEvent.getExtendedKeyCodeForChar(char.code)
        if (keyCode == KeyEvent.VK_UNDEFINED) {
            pasteText(char.toString())
            return
        }
        val needsShift = char.isUpperCase() || "!@#$%^&*()_+{}|:\"<>?".contains(char)
        if (needsShift) robot.keyPress(KeyEvent.VK_SHIFT)
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
        if (needsShift) robot.keyRelease(KeyEvent.VK_SHIFT)
    }

    protected open fun pasteText(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
        val modifier = pasteModifierKey()
        robot.keyPress(modifier)
        robot.keyPress(KeyEvent.VK_V)
        robot.keyRelease(KeyEvent.VK_V)
        robot.keyRelease(modifier)
    }

    protected open fun pasteModifierKey(): Int = KeyEvent.VK_CONTROL

    protected fun extractHelperScript(resourcePath: String, fileName: String): File {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Missing desktop helper resource: $resourcePath")
        val out = File.createTempFile("maestro-$fileName-", ".${fileName.substringAfterLast('.')}")
        out.deleteOnExit()
        stream.use { input -> out.outputStream().use { output -> input.copyTo(output) } }
        out.setExecutable(true)
        return out
    }
}
