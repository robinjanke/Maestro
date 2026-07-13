package maestro.cli.deviceserver

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalDeviceInfo(
    val instanceId: String,
    val description: String,
    val platform: String,
    val deviceType: String,
    val hostOs: String,
    val capabilities: List<String> = emptyList(),
    val catalogName: String? = null,
)

data class RegisteredDevice(
    val catalogName: String,
    val workerId: String,
    val instanceId: String,
    val platform: String,
    val hostOs: String,
)

data class WorkerRegistrationRequest(
    val workerId: String,
    val workerGroup: String,
    val hostOs: String,
    val devices: List<WorkerDeviceRegistration>,
)

data class WorkerDeviceRegistration(
    val catalogName: String,
    val instanceId: String,
    val platform: String,
)

data class ExecuteFlowRequest(
    val jobId: String,
    val catalogDeviceName: String,
    val instanceId: String? = null,
    val flowPath: String,
    val flowContent: String,
    val platform: String,
    val env: Map<String, String> = emptyMap(),
    val headless: Boolean = true,
)

data class ExecuteFlowResult(
    val jobId: String,
    val success: Boolean,
    val exitCode: Int,
    val output: String,
    val durationMs: Long,
)

data class FlowRunReport(
    val deviceName: String,
    val flowPath: String,
    val success: Boolean,
    val exitCode: Int,
    val durationMs: Long,
    val output: String = "",
)

data class CatalogRunReport(
    val success: Boolean,
    val flowResults: List<FlowRunReport>,
    val junitPath: String? = null,
)

data class DeviceServerHealth(
    val status: String,
    val registeredDevices: List<String>,
    val workers: List<String>,
    val ready: Boolean,
)

data class ShutdownRequest(
    val reason: String? = null,
)
