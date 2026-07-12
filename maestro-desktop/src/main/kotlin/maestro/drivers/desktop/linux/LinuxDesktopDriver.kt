package maestro.drivers.desktop.linux

import maestro.Driver
import maestro.MaestroException

/**
 * Linux Flutter Desktop driver (AT-SPI). Scaffold — implement DBus/AT-SPI bindings next.
 */
class LinuxDesktopDriver : Driver by UnsupportedDesktopDriver(
    platformName = "Linux",
    hint = "Linux desktop driver scaffold is present; AT-SPI implementation is in progress.",
)

private class UnsupportedDesktopDriver(
    private val platformName: String,
    private val hint: String,
) : Driver {
    private fun fail(): Nothing = throw MaestroException.InvalidCommand(
        "$hint Run with --platform desktop on macOS for the current fully supported driver.",
    )

    override fun name(): String = "Flutter Desktop ($platformName)"
    override fun open() = fail()
    override fun close() = Unit
    override fun deviceInfo() = fail()
    override fun launchApp(appId: String, launchArguments: Map<String, Any>) = fail()
    override fun stopApp(appId: String) = Unit
    override fun killApp(appId: String) = Unit
    override fun clearAppState(appId: String) = Unit
    override fun clearKeychain() = Unit
    override fun tap(point: maestro.Point) = fail()
    override fun longPress(point: maestro.Point) = fail()
    override fun pressKey(code: maestro.KeyCode) = fail()
    override fun contentDescriptor(excludeKeyboardElements: Boolean) = fail()
    override fun scrollVertical() = fail()
    override fun isKeyboardVisible(): Boolean = false
    override fun swipe(start: maestro.Point, end: maestro.Point, durationMs: Long) = fail()
    override fun swipe(swipeDirection: maestro.SwipeDirection, durationMs: Long) = fail()
    override fun swipe(elementPoint: maestro.Point, direction: maestro.SwipeDirection, durationMs: Long) = fail()
    override fun backPress() = Unit
    override fun inputText(text: String) = fail()
    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) = fail()
    override fun hideKeyboard() = Unit
    override fun takeScreenshot(out: okio.Sink, compressed: Boolean) = fail()
    override fun startScreenRecording(out: okio.Sink): maestro.ScreenRecording = fail()
    override fun setLocation(latitude: Double, longitude: Double) = Unit
    override fun setOrientation(orientation: maestro.device.DeviceOrientation) = Unit
    override fun eraseText(charactersToErase: Int) = fail()
    override fun setProxy(host: String, port: Int) = Unit
    override fun resetProxy() = Unit
    override fun isShutdown(): Boolean = true
    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean = false
    override fun waitForAppToSettle(
        initialHierarchy: maestro.ViewHierarchy?,
        appId: String?,
        timeoutMs: Int?,
    ): maestro.ViewHierarchy? = null
    override fun capabilities(): List<maestro.Capability> = emptyList()
    override fun setPermissions(appId: String, permissions: Map<String, String>) = Unit
    override fun addMedia(mediaFiles: List<java.io.File>) = Unit
    override fun isAirplaneModeEnabled(): Boolean = false
    override fun setAirplaneMode(enabled: Boolean) = Unit
}
