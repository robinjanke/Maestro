package maestro.cli.deviceserver

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import maestro.orchestra.yaml.DeviceCatalog
import maestro.orchestra.yaml.DevicePlan
import maestro.orchestra.yaml.DevicePlanService
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

object CatalogRunner {
    fun resolveExpectedDevices(catalog: DeviceCatalog, plan: DevicePlan, enabledEnv: Map<String, String>): Set<String> {
        return plan.orderedFlows
            .mapNotNull { plan.flowDeviceByPath[it] }
            .distinct()
            .filter { plan.devices.containsKey(it) }
            .filter { deviceName ->
                val entry = catalog.devices[deviceName] ?: return@filter true
                val enabledVar = entry.enabledVar ?: return@filter true
                enabledEnv[enabledVar] == "true"
            }
            .toSet()
    }

    fun runCatalog(
        client: DeviceServerClient,
        flowsRoot: Path,
        catalog: DeviceCatalog,
        plan: DevicePlan,
        env: Map<String, String>,
        junitReportPath: Path? = null,
    ): CatalogRunReport = runBlocking {
        val enabledDevices = resolveExpectedDevices(catalog, plan, env)
        val results = mutableListOf<FlowRunReport>()

        for (relativeFlow in plan.orderedFlows) {
            val deviceName = plan.flowDeviceByPath[relativeFlow] ?: continue
            if (deviceName !in enabledDevices) continue

            val result = runSingleFlow(
                client = client,
                flowsRoot = flowsRoot,
                catalog = catalog,
                plan = plan,
                deviceName = deviceName,
                relativeFlow = relativeFlow,
                env = env,
            )
            results += result
            if (!result.success) break
        }

        val junitPath = junitReportPath?.also { path ->
            JunitReportWriter.write(path, results)
        }

        CatalogRunReport(
            success = results.all { it.success },
            flowResults = results,
            junitPath = junitPath?.toString(),
        )
    }

    private suspend fun runSingleFlow(
        client: DeviceServerClient,
        flowsRoot: Path,
        catalog: DeviceCatalog,
        plan: DevicePlan,
        deviceName: String,
        relativeFlow: String,
        env: Map<String, String>,
    ): FlowRunReport {
        val catalogEntry = catalog.devices[deviceName]
        val platform = catalogEntry?.maestroPlatform ?: plan.devices[deviceName]?.category ?: "web"
        val flowPath = flowsRoot.resolve(relativeFlow)
        val flowContent = Files.readString(flowPath)
        val jobId = UUID.randomUUID().toString()

        client.enqueueJob(
            ExecuteFlowRequest(
                jobId = jobId,
                catalogDeviceName = deviceName,
                flowPath = relativeFlow,
                flowContent = flowContent,
                platform = platform,
                env = env,
                headless = platform == "web",
            ),
        )

        return waitForJobCompletion(client, jobId, deviceName, relativeFlow)
    }

    private suspend fun waitForJobCompletion(
        client: DeviceServerClient,
        jobId: String,
        deviceName: String,
        flowPath: String,
        timeoutMs: Long = 30 * 60 * 1000,
    ): FlowRunReport {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val result = client.jobResult(jobId)
            if (result != null) {
                return FlowRunReport(
                    deviceName = deviceName,
                    flowPath = flowPath,
                    success = result.success,
                    exitCode = result.exitCode,
                    durationMs = result.durationMs,
                    output = result.output,
                )
            }
            delay(2000)
        }
        return FlowRunReport(deviceName, flowPath, success = false, exitCode = 1, durationMs = timeoutMs)
    }

    fun loadPlanAndCatalog(flowsRoot: Path, catalogPath: Path): Pair<DevicePlan, DeviceCatalog> {
        val plan = DevicePlanService.plan(flowsRoot)
        if (!plan.isValid) {
            throw IllegalArgumentException(plan.errors.joinToString("\n"))
        }
        val catalog = DevicePlanService.loadCatalog(catalogPath)
        return plan to catalog
    }
}
