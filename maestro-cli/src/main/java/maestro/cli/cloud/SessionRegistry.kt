package maestro.cli.cloud

import maestro.cli.cloud.JunitReportWriter
import maestro.cli.cloud.FlowRunReport
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

class SessionRegistry(
    private val store: SessionStore,
    private val gitlab: GitLabOrchestrator?,
    private val eventHub: SessionEventHub = SessionEventHub(),
    private val provisioningTimeoutSeconds: Long = 600,
) {
    private val random = SecureRandom()

    fun createSession(request: CreateSessionRequest): CreateSessionResponse {
        val sessionId = UUID.randomUUID().toString()
        val sessionToken = generateToken()
        val now = Instant.now()
        val record = SessionRecord(
            sessionId = sessionId,
            sessionToken = sessionToken,
            status = SessionStatus.PROVISIONING,
            expectedDevices = request.devices.distinct(),
            flowPlan = request.flowPlan,
            catalogYaml = request.catalogYaml,
            env = request.env,
            artifact = request.artifact,
            clientProjectPath = request.clientProjectPath,
            gitlabPipelineId = null,
            attachedWorkers = emptyMap(),
            flowResults = emptyList(),
            error = null,
            createdAt = now,
            updatedAt = now,
        )
        store.create(record)

        val workerGroups = if (request.workerGroups.isNotEmpty()) {
            request.workerGroups
        } else {
            resolveWorkerGroups(request.devices)
        }
        val pipelineId = gitlab?.triggerDevicesPipeline(
            sessionId = sessionId,
            sessionToken = sessionToken,
            devices = request.devices,
            workerGroups = workerGroups,
            artifact = request.artifact,
            clientProjectPath = request.clientProjectPath,
            env = request.env,
        )
        if (pipelineId == null && gitlab != null) {
            val failed = record.copy(
                status = SessionStatus.FAILED,
                error = "Failed to trigger maestro-devices pipeline",
                updatedAt = Instant.now(),
            )
            store.update(failed)
            return CreateSessionResponse(
                sessionId = sessionId,
                sessionToken = sessionToken,
                status = SessionStatus.FAILED,
                error = failed.error,
            )
        }

        val updated = record.copy(gitlabPipelineId = pipelineId, updatedAt = Instant.now())
        store.update(updated)

        eventHub.publish(
            sessionId = sessionId,
            type = SessionEventType.STATUS_CHANGED,
            message = "session created, status=PROVISIONING",
        )

        return CreateSessionResponse(
            sessionId = sessionId,
            sessionToken = sessionToken,
            status = SessionStatus.PROVISIONING,
        )
    }

    fun getSession(sessionId: String): SessionView? {
        val record = store.get(sessionId) ?: return null
        refreshProvisioningTimeout(record)
        val refreshed = store.get(sessionId) ?: return null
        return toView(refreshed)
    }

    fun eventHub(): SessionEventHub = eventHub

    fun publishEvent(sessionId: String, request: SessionEventRequest): SessionEventResponse {
        if (store.get(sessionId) == null) throw NoSuchElementException("session not found")
        val event = eventHub.publish(
            sessionId = sessionId,
            type = request.type,
            flowPath = request.flowPath,
            deviceName = request.deviceName,
            message = request.message,
            success = request.success,
        )
        return SessionEventResponse(seq = event.seq)
    }

    fun attachWorker(sessionId: String, request: WorkerAttachRequest): WorkerAttachResponse {
        val record = store.get(sessionId) ?: throw NoSuchElementException("session not found")
        if (record.status == SessionStatus.CANCELLED || record.status == SessionStatus.FAILED) {
            throw IllegalStateException("session is ${record.status}")
        }

        val attachedDevices = request.devices.map { it.catalogName }.toSet()
        val assignedFlows = record.flowPlan.orderedFlows.mapNotNull { flowPath ->
            val deviceName = record.flowPlan.flowDeviceByPath[flowPath] ?: return@mapNotNull null
            if (!attachedDevices.contains(deviceName)) return@mapNotNull null
            FlowAssignment(
                flowPath = flowPath,
                deviceName = deviceName,
                platform = resolvePlatform(deviceName, record.catalogYaml),
            )
        }

        val updatedWorkers = record.attachedWorkers + (request.workerId to request)
        val allAttached = record.expectedDevices.all { expected ->
            updatedWorkers.values.any { worker -> worker.devices.any { it.catalogName == expected } }
        }
        val newStatus = when {
            record.status == SessionStatus.RUNNING -> SessionStatus.RUNNING
            allAttached -> SessionStatus.READY
            else -> SessionStatus.PROVISIONING
        }

        val updated = record.copy(
            attachedWorkers = updatedWorkers,
            status = newStatus,
            updatedAt = Instant.now(),
        )
        store.update(updated)

        if (newStatus != record.status) {
            eventHub.publish(
                sessionId = sessionId,
                type = SessionEventType.STATUS_CHANGED,
                message = "status=${newStatus.name}",
            )
        }

        return WorkerAttachResponse(status = "attached", assignedFlows = assignedFlows, env = record.env)
    }

    fun recordResult(sessionId: String, request: FlowResultRequest): FlowResultResponse {
        val record = store.get(sessionId) ?: throw NoSuchElementException("session not found")
        val result = FlowResultRecord(
            flowPath = request.flowPath,
            deviceName = request.deviceName,
            success = request.success,
            exitCode = request.exitCode,
            durationMs = request.durationMs,
            output = request.output,
        )
        val results = record.flowResults.filter { it.flowPath != request.flowPath } + result
        val expectedFlowCount = record.flowPlan.orderedFlows.size
        val completedFlows = results.map { it.flowPath }.toSet()
        val terminal = completedFlows.size >= expectedFlowCount
        val anyFailed = results.any { !it.success }
        val newStatus = when {
            !terminal && record.status == SessionStatus.READY -> SessionStatus.RUNNING
            !terminal -> record.status
            anyFailed -> SessionStatus.FAILED
            else -> SessionStatus.COMPLETED
        }
        val updated = record.copy(
            flowResults = results,
            status = newStatus,
            updatedAt = Instant.now(),
        )
        store.update(updated)

        eventHub.publish(
            sessionId = sessionId,
            type = SessionEventType.FLOW_FINISHED,
            flowPath = request.flowPath,
            deviceName = request.deviceName,
            success = request.success,
        )
        if (newStatus != record.status) {
            eventHub.publish(
                sessionId = sessionId,
                type = SessionEventType.STATUS_CHANGED,
                message = "status=${newStatus.name}",
            )
        }

        if (terminal) {
            cancelDevicesPipeline(updated)
        }

        return FlowResultResponse(status = "recorded", sessionStatus = newStatus)
    }

    fun cancelSession(sessionId: String): SessionView? {
        val record = store.get(sessionId) ?: return null
        if (record.status == SessionStatus.COMPLETED || record.status == SessionStatus.FAILED) {
            return toView(record)
        }
        val updated = record.copy(status = SessionStatus.CANCELLED, updatedAt = Instant.now())
        store.update(updated)
        eventHub.publish(
            sessionId = sessionId,
            type = SessionEventType.STATUS_CHANGED,
            message = "status=CANCELLED",
        )
        cancelDevicesPipeline(updated)
        return toView(updated)
    }

    fun activeSessionCount(): Int = store.listActive().size

    fun getSessionToken(sessionId: String): String? = store.get(sessionId)?.sessionToken

    private fun refreshProvisioningTimeout(record: SessionRecord) {
        if (record.status != SessionStatus.PROVISIONING) return
        val elapsed = Instant.now().epochSecond - record.createdAt.epochSecond
        if (elapsed < provisioningTimeoutSeconds) return
        val failed = record.copy(
            status = SessionStatus.FAILED,
            error = "provisioning timeout after ${provisioningTimeoutSeconds}s",
            updatedAt = Instant.now(),
        )
        store.update(failed)
        eventHub.publish(
            sessionId = record.sessionId,
            type = SessionEventType.STATUS_CHANGED,
            message = "status=FAILED, error=provisioning timeout",
        )
        cancelDevicesPipeline(failed)
    }

    private fun cancelDevicesPipeline(record: SessionRecord) {
        val pipelineId = record.gitlabPipelineId ?: return
        gitlab?.cancelPipeline(pipelineId)
    }

    private fun toView(record: SessionRecord): SessionView {
        val attached = record.attachedWorkers.values
            .flatMap { it.devices.map { device -> device.catalogName } }
            .distinct()
            .sorted()
        val assignedFlows = record.attachedWorkers.mapValues { (_, worker) ->
            val deviceNames = worker.devices.map { it.catalogName }.toSet()
            record.flowPlan.orderedFlows.filter { flow ->
                deviceNames.contains(record.flowPlan.flowDeviceByPath[flow])
            }
        }
        val junit = if (record.flowResults.isNotEmpty()) {
            buildJunitXml(record.flowResults)
        } else {
            null
        }
        return SessionView(
            sessionId = record.sessionId,
            status = record.status,
            expectedDevices = record.expectedDevices,
            attachedDevices = attached,
            assignedFlows = assignedFlows,
            flowResults = record.flowResults,
            junitXml = junit,
            error = record.error,
            gitlabPipelineId = record.gitlabPipelineId,
            currentFlow = eventHub.currentFlow(record.sessionId),
            currentFlowSince = eventHub.currentFlowSince(record.sessionId),
            lastEventSeq = eventHub.lastEventSeq(record.sessionId),
        )
    }

    private fun buildJunitXml(results: List<FlowResultRecord>): String {
        val reports = results.map {
            FlowRunReport(
                deviceName = it.deviceName,
                flowPath = it.flowPath,
                success = it.success,
                exitCode = it.exitCode,
                durationMs = it.durationMs,
                output = it.output,
            )
        }
        val temp = java.nio.file.Files.createTempFile("maestro-junit", ".xml")
        try {
            JunitReportWriter.write(temp, reports)
            return java.nio.file.Files.readString(temp)
        } finally {
            java.nio.file.Files.deleteIfExists(temp)
        }
    }

    private fun resolveWorkerGroups(devices: List<String>): List<String> {
        // Worker groups are passed explicitly from the client based on catalog; fallback heuristic.
        return devices.mapNotNull { name ->
            when {
                name.startsWith("iphone") || name.startsWith("macos") -> "macos"
                name.startsWith("windows") -> "windows"
                else -> "linux"
            }
        }.distinct()
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    private fun resolvePlatform(deviceName: String, catalogYaml: String?): String {
        if (catalogYaml.isNullOrBlank()) return "web"
        return runCatching {
            val catalog = maestro.orchestra.yaml.DevicePlanService.loadCatalog(
                java.nio.file.Files.createTempFile("catalog", ".yaml").also {
                    java.nio.file.Files.writeString(it, catalogYaml)
                },
            )
            catalog.devices[deviceName]?.maestroPlatform ?: "web"
        }.getOrDefault("web")
    }
}
