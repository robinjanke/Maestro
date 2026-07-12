package maestro.drivers.desktop

enum class DesktopOs(val id: String, val displayName: String) {
    MACOS("macos", "macOS"),
    WINDOWS("windows", "Windows"),
    LINUX("linux", "Linux"),
    ;

    companion object {
        fun current(): DesktopOs {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") || os.contains("darwin") -> MACOS
                os.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}
