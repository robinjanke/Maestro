package maestro.orchestra.yaml

import com.fasterxml.jackson.databind.annotation.JsonDeserialize

/**
 * Declares which device a Maestro flow runs on.
 *
 * Preferred (catalog reference):
 * ```yaml
 * device: iphone-1
 * ```
 *
 * Legacy object form is still accepted.
 */
@JsonDeserialize(using = YamlDeviceDeserializer::class)
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
