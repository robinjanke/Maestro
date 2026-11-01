package maestro.drivers.desktop

import java.io.File

/**
 * Shared launch target for every desktop driver.
 *
 * Flows keep a bundle id such as `ai.mainpath.toolbox`. CI points at the
 * built app with MAESTRO_DESKTOP_APP or launchArguments["executable"].
 * A `.app` bundle is a directory; Windows executables are files.
 */
object DesktopAppLocator {
    fun configured(launchArguments: Map<String, Any>): File? {
        val fromArgs = launchArguments["executable"]?.toString()?.trim().orEmpty()
        val raw = if (fromArgs.isNotEmpty()) {
            fromArgs
        } else {
            System.getenv("MAESTRO_DESKTOP_APP")?.trim().orEmpty()
        }
        if (raw.isEmpty()) return null
        val file = File(raw)
        val appBundle = file.isDirectory && raw.endsWith(".app")
        if (!file.isFile && !appBundle) {
            error("Desktop executable does not exist: $raw")
        }
        return file
    }
}
