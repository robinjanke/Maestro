package maestro.orchestra.yaml

/**
 * Declares which physical or virtual device a Maestro flow must run on.
 *
 * Example:
 * ```yaml
 * device:
 *   name: iphone-1
 *   type: iPhone 17 Pro Max
 *   version: iOS 26
 *   category: ios
 * appId: ${APP_ID}
 * ---
 * - launchApp
 * ```
 */
data class YamlDevice(
    val name: String,
    val type: String? = null,
    val version: String? = null,
    val category: String? = null,
) {
    init {
        if (name.isBlank()) {
            throw ConfigParseError("missing_device_name")
        }
    }
}
