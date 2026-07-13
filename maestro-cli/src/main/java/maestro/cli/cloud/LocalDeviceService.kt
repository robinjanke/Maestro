package maestro.cli.cloud

import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.Platform

object LocalDeviceService {
    fun hostOs(): String {
        val os = System.getProperty("os.name", "unknown").lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("win") -> "windows"
            else -> "linux"
        }
    }

    fun listLocalDevices(includeWeb: Boolean = true): List<LocalDeviceInfo> {
        val hostOs = hostOs()
        return DeviceService.listDevices(includeWeb = includeWeb)
            .mapNotNull { device -> toLocalDeviceInfo(device, hostOs) }
            .distinctBy { "${it.instanceId}:${it.platform}" }
    }

    fun listForWorkerGroup(
        workerGroup: String,
        catalogDeviceNames: List<String>,
        catalogPlatforms: Map<String, String>,
    ): List<LocalDeviceInfo> {
        val hostOs = hostOs()
        if (hostOs != workerGroup) {
            return emptyList()
        }
        val locals = listLocalDevices(includeWeb = workerGroup == "linux")
        return catalogDeviceNames.mapNotNull { catalogName ->
            val platform = catalogPlatforms[catalogName] ?: return@mapNotNull null
            matchCatalogDevice(catalogName, platform, locals, hostOs)
        }
    }

    private fun matchCatalogDevice(
        catalogName: String,
        platform: String,
        locals: List<LocalDeviceInfo>,
        hostOs: String,
    ): LocalDeviceInfo? {
        return when (platform.lowercase()) {
            "web" -> locals.firstOrNull { it.platform == "WEB" && hostOs == "linux" }
                ?.copy(catalogName = catalogName)
            "ios" -> {
                val index = catalogName.removePrefix("iphone-").toIntOrNull() ?: 1
                val iosDevices = locals.filter { it.platform == "IOS" }
                iosDevices.getOrNull(index - 1)?.copy(catalogName = catalogName)
                    ?: iosDevices.firstOrNull()?.copy(catalogName = catalogName)
            }
            "android" -> locals.firstOrNull { it.platform == "ANDROID" }
                ?.copy(catalogName = catalogName)
            "desktop" -> {
                when {
                    catalogName.startsWith("macos") && hostOs == "macos" ->
                        locals.firstOrNull { it.platform == "DESKTOP" }?.copy(catalogName = catalogName)
                    catalogName.startsWith("windows") && hostOs == "windows" ->
                        locals.firstOrNull { it.platform == "DESKTOP" }?.copy(catalogName = catalogName)
                    catalogName.startsWith("linux") && hostOs == "linux" ->
                        locals.firstOrNull { it.platform == "DESKTOP" }?.copy(catalogName = catalogName)
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun toLocalDeviceInfo(device: Device, hostOs: String): LocalDeviceInfo? {
        return when (device) {
            is Device.Connected -> LocalDeviceInfo(
                instanceId = device.instanceId,
                description = device.description,
                platform = device.platform.name,
                deviceType = device.deviceType.name,
                hostOs = hostOs,
                capabilities = capabilitiesFor(device.platform),
            )
            is Device.AvailableForLaunch -> LocalDeviceInfo(
                instanceId = device.modelId,
                description = device.description,
                platform = device.platform.name,
                deviceType = device.deviceType.name,
                hostOs = hostOs,
                capabilities = capabilitiesFor(device.platform),
            )
            else -> null
        }
    }

    private fun capabilitiesFor(platform: Platform): List<String> = when (platform) {
        Platform.WEB -> listOf("web")
        Platform.IOS -> listOf("ios")
        Platform.ANDROID -> listOf("android")
        Platform.DESKTOP -> listOf("desktop")
    }
}
