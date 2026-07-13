package maestro.orchestra.yaml

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.streams.toList

data class DevicePlanFlow(
    val path: String,
    val device: YamlDevice,
    val url: String? = null,
    val appId: String? = null,
)

data class DevicePlanDevice(
    val name: String,
    val type: String? = null,
    val version: String? = null,
    val category: String? = null,
    val flows: List<String> = emptyList(),
)

data class DevicePlan(
    val devices: Map<String, DevicePlanDevice> = emptyMap(),
    val errors: List<String> = emptyList(),
) {
    val isValid: Boolean get() = errors.isEmpty() && devices.isNotEmpty()
}

data class DeviceCatalogEntry(
    val category: String? = null,
    val type: String? = null,
    val version: String? = null,
    val runnerTags: List<String> = emptyList(),
    val maestroPlatform: String? = null,
    val targetUrlVar: String? = null,
    val flowsSubpath: String? = null,
    val useLifecycleRunner: Boolean = false,
    val enabledVar: String? = null,
)

data class DeviceCatalog(
    val devices: Map<String, DeviceCatalogEntry> = emptyMap(),
    val executionWaves: List<List<String>> = emptyList(),
)

object DevicePlanService {
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    fun plan(flowsRoot: Path, deviceFilter: String? = null): DevicePlan {
        if (!Files.isDirectory(flowsRoot)) {
            return DevicePlan(errors = listOf("Flows root does not exist: $flowsRoot"))
        }

        val flowsByDevice = linkedMapOf<String, MutableList<DevicePlanFlow>>()
        val errors = mutableListOf<String>()

        val flowFiles = Files.walk(flowsRoot).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { path ->
                    val name = path.name
                    (name.endsWith(".yaml") || name.endsWith(".yml")) && name != "config.yaml"
                }
                .toList()
        }

        if (flowFiles.isEmpty()) {
            return DevicePlan(errors = listOf("No Maestro flow files found under $flowsRoot"))
        }

        for (flowPath in flowFiles.sorted()) {
            val flowContent = Files.readString(flowPath)
            try {
                val config = MaestroFlowParser.parseConfigOnly(flowPath, flowContent)
                val device = config.device
                if (device == null) {
                    errors += "${relativePath(flowsRoot, flowPath)}: missing device block"
                    continue
                }
                if (deviceFilter != null && device.name != deviceFilter) {
                    continue
                }
                val entry = DevicePlanFlow(
                    path = relativePath(flowsRoot, flowPath),
                    device = device,
                    url = config.url,
                    appId = config.appId.takeIf { config.url == null },
                )
                flowsByDevice.getOrPut(device.name) { mutableListOf() }.add(entry)
            } catch (e: Exception) {
                errors += "${relativePath(flowsRoot, flowPath)}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        val devices = flowsByDevice.mapValues { (name, flows) ->
            val first = flows.first().device
            DevicePlanDevice(
                name = name,
                type = first.type,
                version = first.version,
                category = first.category,
                flows = flows.map { it.path }.sorted(),
            )
        }

        if (deviceFilter != null && devices.isEmpty() && errors.isEmpty()) {
            errors += "No flows found for device '$deviceFilter' under $flowsRoot"
        }

        return DevicePlan(devices = devices, errors = errors)
    }

    fun loadCatalog(catalogPath: Path): DeviceCatalog {
        if (!Files.isRegularFile(catalogPath)) {
            throw IllegalArgumentException("Device catalog not found: $catalogPath")
        }
        val raw: Map<String, Any> = yamlMapper.readValue(Files.readString(catalogPath))
        val devicesRaw = raw["devices"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val wavesRaw = raw["execution_waves"] as? List<*> ?: emptyList<Any>()

        val devices = devicesRaw.mapKeys { (key, _) -> key.toString() }.mapValues { (_, value) ->
            val entry = value as Map<*, *>
            DeviceCatalogEntry(
                category = entry["category"] as? String,
                type = entry["type"] as? String,
                version = entry["version"] as? String,
                runnerTags = (entry["runner_tags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                maestroPlatform = entry["maestro_platform"] as? String,
                targetUrlVar = entry["target_url_var"] as? String,
                flowsSubpath = entry["flows_subpath"] as? String,
                useLifecycleRunner = entry["use_lifecycle_runner"] as? Boolean ?: false,
                enabledVar = entry["enabled_var"] as? String,
            )
        }

        val executionWaves = wavesRaw.mapNotNull { wave ->
            (wave as? List<*>)?.mapNotNull { it as? String }
        }

        return DeviceCatalog(devices = devices, executionWaves = executionWaves)
    }

    private fun relativePath(root: Path, flowPath: Path): String {
        return flowPath.relativeTo(root).toString().replace('\\', '/')
    }
}
