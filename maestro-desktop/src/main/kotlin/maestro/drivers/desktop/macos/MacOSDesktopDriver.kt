package maestro.drivers.desktop.macos

import com.sun.jna.Pointer
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
import java.awt.event.KeyEvent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * macOS Flutter Desktop driver using the native Accessibility API (AXUIElement).
 *
 * Flutter apps must enable semantics (SemanticsBinding.instance.ensureSemantics())
 * and expose testable widgets via Semantics(identifier: '...'), which maps to AXIdentifier.
 */
class MacOSDesktopDriver : Driver {
    private val logger = LoggerFactory.getLogger(MacOSDesktopDriver::class.java)

    private var open = false
    private var appPid: Int? = null
    private var appId: String? = null
    private var rootElement: Pointer? = null
    private val robot = Robot()

    override fun name(): String = "Flutter Desktop (macOS)"

    override fun open() {
        if (!MacAxLibrary.INSTANCE.AXIsProcessTrusted()) {
            throw IllegalStateException(
                "macOS Accessibility permission is required. " +
                    "Grant access to your terminal/Java in System Settings → Privacy & Security → Accessibility.",
            )
        }
        open = true
    }

    override fun close() {
        open = false
        appPid = null
        appId = null
        rootElement = null
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

    override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
        ensureOpen()
        stopApp(appId)

        when {
            appId.endsWith(".app") -> {
                ProcessBuilder("open", appId).inheritIO().start().waitFor(30, TimeUnit.SECONDS)
            }
            appId.contains(".") && !File(appId).exists() -> {
                ProcessBuilder("open", "-b", appId).inheritIO().start().waitFor(30, TimeUnit.SECONDS)
            }
            else -> {
                ProcessBuilder("open", appId).inheritIO().start().waitFor(30, TimeUnit.SECONDS)
            }
        }

        Thread.sleep(1500)
        val pid = resolvePid(appId) ?: error("Could not find running process for app '$appId'")
        appPid = pid
        this.appId = appId
        rootElement = MacAxLibrary.INSTANCE.AXUIElementCreateApplication(pid)
            ?: error("Failed to create AXUIElement for pid $pid")
        waitUntilScreenIsStatic(5000)
    }

    override fun stopApp(appId: String) {
        val pid = resolvePid(appId) ?: appPid
        if (pid != null) {
            ProcessBuilder("kill", pid.toString()).start().waitFor(5, TimeUnit.SECONDS)
        }
        if (this.appId == appId) {
            this.appPid = null
            this.rootElement = null
            this.appId = null
        }
    }

    override fun killApp(appId: String) = stopApp(appId)

    override fun clearAppState(appId: String) = Unit

    override fun clearKeychain() = Unit

    override fun tap(point: Point) {
        robot.mouseMove(point.x, point.y)
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(200)
    }

    override fun longPress(point: Point) {
        robot.mouseMove(point.x, point.y)
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(1500)
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
    }

    override fun pressKey(code: KeyCode) {
        val keyEvent = when (code) {
            KeyCode.ENTER -> KeyEvent.VK_ENTER
            KeyCode.BACKSPACE -> KeyEvent.VK_BACK_SPACE
            else -> error("Keycode $code is not supported on macOS desktop yet")
        }
        robot.keyPress(keyEvent)
        robot.keyRelease(keyEvent)
    }

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        val root = rootElement ?: error("No desktop application is running. Call launchApp first.")
        return buildTreeNode(root)
    }

    override fun scrollVertical() {
        swipe(SwipeDirection.UP, 400)
    }

    override fun isKeyboardVisible(): Boolean = false

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        robot.mouseMove(start.x, start.y)
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
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
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
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

    override fun backPress() = Unit

    override fun inputText(text: String) {
        val focused = findFocusedElement()
        if (focused != null) {
            MacAxLibrary.INSTANCE.AXUIElementSetAttributeValue(
                focused,
                MacAxLibrary.AXValueAttribute,
                createCFString(text),
            )
            return
        }
        text.forEach { char ->
            typeChar(char)
        }
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        ProcessBuilder("open", link).start().waitFor(10, TimeUnit.SECONDS)
    }

    override fun hideKeyboard() = Unit

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
        error("Screen recording is not supported for macOS desktop yet")
    }

    override fun setLocation(latitude: Double, longitude: Double) = Unit

    override fun setOrientation(orientation: DeviceOrientation) = Unit

    override fun eraseText(charactersToErase: Int) {
        repeat(charactersToErase) {
            pressKey(KeyCode.BACKSPACE)
        }
    }

    override fun setProxy(host: String, port: Int) = Unit

    override fun resetProxy() = Unit

    override fun isShutdown(): Boolean = !open

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

    override fun capabilities(): List<Capability> = emptyList()

    override fun setPermissions(appId: String, permissions: Map<String, String>) = Unit

    override fun addMedia(mediaFiles: List<File>) = Unit

    override fun isAirplaneModeEnabled(): Boolean = false

    override fun setAirplaneMode(enabled: Boolean) = Unit

    override fun queryOnDeviceElements(query: OnDeviceElementQuery): List<TreeNode> = emptyList()

    private fun ensureOpen() {
        if (!open) error("Driver is not open")
    }

    private fun hierarchyFingerprint(): String {
        return runCatching {
            contentDescriptor().aggregate().joinToString("|") {
                listOfNotNull(
                    it.attributes["resource-id"],
                    it.attributes["text"],
                    it.attributes["role"],
                ).joinToString(":")
            }
        }.getOrDefault("")
    }

    private fun buildTreeNode(element: Pointer, depth: Int = 0): TreeNode {
        if (depth > 40) {
            return TreeNode()
        }

        val role = readAttribute(element, MacAxLibrary.AXRoleAttribute)
        val title = readAttribute(element, MacAxLibrary.AXTitleAttribute)
        val description = readAttribute(element, MacAxLibrary.AXDescriptionAttribute)
        val identifier = readAttribute(element, MacAxLibrary.AXIdentifierAttribute)
        val value = readAttribute(element, MacAxLibrary.AXValueAttribute)
        val enabled = readBoolAttribute(element, MacAxLibrary.AXEnabledAttribute)
        val focused = readBoolAttribute(element, MacAxLibrary.AXFocusedAttribute)
        val selected = readBoolAttribute(element, MacAxLibrary.AXSelectedAttribute)

        val text = listOfNotNull(title, description, value)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        val bounds = readBounds(element)
        val attributes = mutableMapOf(
            "text" to text,
            "bounds" to bounds,
            "role" to (role ?: ""),
        )
        if (!identifier.isNullOrBlank()) {
            attributes["resource-id"] = identifier
        }

        val childrenPointers = MacAxValue.readChildren(
            MacAxElement.copyAttribute(element, MacAxLibrary.AXChildrenAttribute),
        )
        val children = childrenPointers.map { buildTreeNode(it, depth + 1) }

        return TreeNode(
            attributes = attributes,
            children = children,
            clickable = role in CLICKABLE_ROLES,
            enabled = enabled,
            focused = focused,
            selected = selected,
        )
    }

    private fun readBounds(element: Pointer): String {
        val positionPtr = MacAxElement.copyAttribute(element, MacAxLibrary.AXPositionAttribute) ?: return "[0,0][0,0]"
        val sizePtr = MacAxElement.copyAttribute(element, MacAxLibrary.AXSizeAttribute) ?: return "[0,0][0,0]"
        val position = readAxPoint(positionPtr) ?: return "[0,0][0,0]"
        val size = readAxSize(sizePtr) ?: return "[0,0][0,0]"
        val left = position.x.toInt()
        val top = position.y.toInt()
        val right = left + size.width.toInt()
        val bottom = top + size.height.toInt()
        return "[$left,$top][$right,$bottom]"
    }

    private fun readAxPoint(pointer: Pointer): MacAxCGPoint? {
        val point = MacAxCGPoint()
        val ok = AxValueReader.INSTANCE.AXValueGetValue(pointer, AX_VALUE_TYPE_CGPOINT, point.pointer)
        return if (ok) point else null
    }

    private fun readAxSize(pointer: Pointer): MacAxCGSize? {
        val size = MacAxCGSize()
        val ok = AxValueReader.INSTANCE.AXValueGetValue(pointer, AX_VALUE_TYPE_CGSIZE, size.pointer)
        return if (ok) size else null
    }

    private fun readAttribute(element: Pointer, attribute: Pointer): String? {
        return MacAxValue.readString(MacAxElement.copyAttribute(element, attribute))
    }

    private fun readBoolAttribute(element: Pointer, attribute: Pointer): Boolean? {
        return MacAxValue.readBool(MacAxElement.copyAttribute(element, attribute))
    }

    private fun findFocusedElement(): Pointer? {
        val root = rootElement ?: return null
        return MacAxElement.copyAttribute(root, MacAxLibrary.AXFocusedUIElementAttribute)
    }

    private fun resolvePid(appId: String): Int? {
        if (appPid != null && isProcessRunning(appPid!!)) {
            return appPid
        }

        val script = when {
            appId.endsWith(".app") -> {
                val name = File(appId).nameWithoutExtension
                "tell application \"System Events\" to get unix id of first application process whose name is \"$name\""
            }
            appId.contains(".") && !File(appId).exists() -> {
                "tell application \"System Events\" to get unix id of first application process whose bundle identifier is \"$appId\""
            }
            else -> {
                "tell application \"System Events\" to get unix id of first application process whose name is \"$appId\""
            }
        }

        return runCatching {
            val process = ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(true)
                .start()
            process.waitFor(10, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText().trim()
            output.toIntOrNull()
        }.getOrNull()
    }

    private fun isProcessRunning(pid: Int): Boolean {
        return runCatching {
            ProcessBuilder("ps", "-p", pid.toString())
                .start()
                .waitFor(2, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }

    private fun typeChar(char: Char) {
        val keyCode = KeyEvent.getExtendedKeyCodeForChar(char.code)
        if (keyCode == KeyEvent.VK_UNDEFINED) {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val original = clipboard.getContents(null)
            clipboard.setContents(java.awt.datatransfer.StringSelection(char.toString()), null)
            robot.keyPress(KeyEvent.VK_META)
            robot.keyPress(KeyEvent.VK_V)
            robot.keyRelease(KeyEvent.VK_V)
            robot.keyRelease(KeyEvent.VK_META)
            return
        }
        val needsShift = char.isUpperCase() || "!@#$%^&*()_+{}|:\"<>?".contains(char)
        if (needsShift) robot.keyPress(KeyEvent.VK_SHIFT)
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
        if (needsShift) robot.keyRelease(KeyEvent.VK_SHIFT)
    }

    private fun createCFString(value: String): Pointer = MacAxLibrary.cfString(value)

    private interface AxValueReader : com.sun.jna.Library {
        fun AXValueGetValue(value: Pointer, type: Int, valuePtr: Pointer): Boolean

        companion object {
            val INSTANCE: AxValueReader = com.sun.jna.Native.load("ApplicationServices", AxValueReader::class.java)
        }
    }

    companion object {
        private const val AX_VALUE_TYPE_CGPOINT = 1
        private const val AX_VALUE_TYPE_CGSIZE = 2

        private val CLICKABLE_ROLES = setOf(
            "AXButton",
            "AXLink",
            "AXMenuItem",
            "AXTextField",
            "AXCheckBox",
            "AXRadioButton",
            "AXPopUpButton",
            "AXCell",
        )
    }
}
