package maestro.drivers.desktop

import maestro.Driver
import maestro.drivers.desktop.linux.LinuxDesktopDriver
import maestro.drivers.desktop.macos.MacOSDesktopDriver
import maestro.drivers.desktop.windows.WindowsDesktopDriver

object DesktopDriverFactory {
    fun create(): Driver {
        return when (DesktopOs.current()) {
            DesktopOs.MACOS -> MacOSDesktopDriver()
            DesktopOs.WINDOWS -> WindowsDesktopDriver()
            DesktopOs.LINUX -> LinuxDesktopDriver()
        }
    }
}
