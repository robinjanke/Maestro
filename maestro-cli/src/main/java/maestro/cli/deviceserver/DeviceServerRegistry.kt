package maestro.cli.deviceserver

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DeviceServerRegistry(
    private val expectedDevices: Set<String> = emptySet(),
) {
    private val workers = ConcurrentHashMap<String, WorkerRegistrationRequest>()
    private val devices = ConcurrentHashMap<String, RegisteredDevice>()
    private val pendingJobs = ConcurrentHashMap<String, ExecuteFlowRequest>()
    private val completedJobs = ConcurrentHashMap<String, ExecuteFlowResult>()
    private val shutdownRequested = AtomicBoolean(false)

    fun registerWorker(request: WorkerRegistrationRequest) {
        workers[request.workerId] = request
        request.devices.forEach { device ->
            devices[device.catalogName] = RegisteredDevice(
                catalogName = device.catalogName,
                workerId = request.workerId,
                instanceId = device.instanceId,
                platform = device.platform,
                hostOs = request.hostOs,
            )
        }
    }

    fun unregisterWorker(workerId: String) {
        workers.remove(workerId)
        devices.entries.removeIf { it.value.workerId == workerId }
    }

    fun registeredDeviceNames(): Set<String> = devices.keys.toSet()

    fun workerIds(): Set<String> = workers.keys.toSet()

    fun isReady(): Boolean {
        if (expectedDevices.isEmpty()) return devices.isNotEmpty()
        return expectedDevices.all { devices.containsKey(it) }
    }

    fun missingDevices(): Set<String> = expectedDevices - devices.keys

    fun resolveDevice(catalogName: String): RegisteredDevice? = devices[catalogName]

    fun enqueueJob(request: ExecuteFlowRequest) {
        pendingJobs[request.jobId] = request
    }

    fun pollJobForWorker(workerId: String): ExecuteFlowRequest? {
        val entry = pendingJobs.entries.firstOrNull { (_, job) ->
            val device = devices[job.catalogDeviceName]
            device?.workerId == workerId
        } ?: return null
        val job = entry.value
        pendingJobs.remove(entry.key)
        val device = devices[job.catalogDeviceName]
        return job.copy(instanceId = device?.instanceId)
    }

    fun completeJob(result: ExecuteFlowResult) {
        completedJobs[result.jobId] = result
    }

    fun jobResult(jobId: String): ExecuteFlowResult? = completedJobs[jobId]

    fun requestShutdown() {
        shutdownRequested.set(true)
    }

    fun isShutdownRequested(): Boolean = shutdownRequested.get()

    fun health(): DeviceServerHealth = DeviceServerHealth(
        status = if (shutdownRequested.get()) "shutting_down" else "ok",
        registeredDevices = devices.keys.sorted(),
        workers = workers.keys.sorted(),
        ready = isReady(),
    )
}
