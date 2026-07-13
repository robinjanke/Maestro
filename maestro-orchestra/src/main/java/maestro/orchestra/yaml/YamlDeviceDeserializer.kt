package maestro.orchestra.yaml

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode

/**
 * Accepts either a catalog device reference (`device: chrome-1`) or a legacy object block.
 */
class YamlDeviceDeserializer : JsonDeserializer<YamlDevice>() {
    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): YamlDevice {
        return when (parser.currentToken) {
            JsonToken.VALUE_STRING -> YamlDevice(name = parser.text.trim())
            JsonToken.START_OBJECT -> {
                val node: JsonNode = parser.codec.readTree(parser)
                val name = node.get("name")?.asText()?.trim().orEmpty()
                YamlDevice(
                    name = name,
                    type = node.get("type")?.asText(),
                    version = node.get("version")?.asText(),
                    category = node.get("category")?.asText(),
                )
            }
            else -> throw ConfigParseError("invalid_device")
        }
    }
}
