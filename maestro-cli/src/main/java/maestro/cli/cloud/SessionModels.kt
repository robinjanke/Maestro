package maestro.cli.cloud

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

enum class SessionStatus {
    PROVISIONING,
    READY,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class ArtifactRef(
    val projectId: String,
    val pipelineId: String,
    val jobName: String,
)

data class FlowPlan(
    val orderedFlows: List<String> = emptyList(),
    val flowDeviceByPath: Map<String, String> = emptyMap(),
    val devices: List<String> = emptyList(),
)

data class CreateSessionRequest(
    val devices: List<String>,
    val flowPlan: FlowPlan,
    val catalogYaml: String? = null,
    val env: Map<String, String> = emptyMap(),
    val artifact: ArtifactRef,
    val clientProjectPath: String? = null,
    val workerGroups: List<String> = emptyList(),
)

data class CreateSessionResponse(
    val sessionId: String,
    val sessionToken: String,
    val status: SessionStatus,
)

data class AttachedDevice(
    val catalogName: String,
    val instanceId: String,
    val platform: String,
)

data class WorkerAttachRequest(
    val workerId: String,
    val workerGroup: String,
    val hostOs: String,
    val devices: List<AttachedDevice>,
)

data class FlowAssignment(
    val flowPath: String,
    val deviceName: String,
    val platform: String,
)

data class WorkerAttachResponse(
    val status: String,
    val assignedFlows: List<FlowAssignment>,
)

data class FlowResultRequest(
    val flowPath: String,
    val deviceName: String,
    val success: Boolean,
    val exitCode: Int,
    val durationMs: Long,
    val output: String = "",
)

data class FlowResultResponse(
    val status: String,
    val sessionStatus: SessionStatus,
)

data class FlowResultRecord(
    val flowPath: String,
    val deviceName: String,
    val success: Boolean,
    val exitCode: Int,
    val durationMs: Long,
    val output: String = "",
)

data class SessionView(
    val sessionId: String,
    val status: SessionStatus,
    val expectedDevices: List<String>,
    val attachedDevices: List<String>,
    val assignedFlows: Map<String, List<String>>,
    val flowResults: List<FlowResultRecord>,
    val junitXml: String? = null,
    val error: String? = null,
    val gitlabPipelineId: Long? = null,
)

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

data class CloudHealth(
    val status: String,
    val activeSessions: Int,
)
