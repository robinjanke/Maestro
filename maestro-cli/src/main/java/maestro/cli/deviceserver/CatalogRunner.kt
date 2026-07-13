package maestro.cli.deviceserver

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import maestro.orchestra.yaml.DeviceCatalog
import maestro.orchestra.yaml.DevicePlan
import maestro.orchestra.yaml.DevicePlanService
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

object CatalogRunner {
    fun resolveExpectedDevices(catalog: DeviceCatalog, plan: DevicePlan, enabledEnv: Map<String, String>): Set<String> {
        val waves = if (catalog.executionWaves.isNotEmpty()) {
            catalog.executionWaves
        } else {
            plan.devices.keys.sorted().map { listOf(it) }
        }
        return waves.flatten()
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
        val waves = if (catalog.executionWaves.isNotEmpty()) {
            catalog.executionWaves
        } else {
            plan.devices.keys.sorted().map { listOf(it) }
        }

        val results = mutableListOf<FlowRunReport>()

        for (wave in waves) {
            val waveDevices = wave.filter { plan.devices.containsKey(it) }
            if (waveDevices.isEmpty()) continue

            val waveResults = coroutineScope {
                waveDevices.map { deviceName ->
                    async {
                        runDeviceFlows(
                            client = client,
                            flowsRoot = flowsRoot,
                            catalog = catalog,
                            plan = plan,
                            deviceName = deviceName,
                            env = env,
                        )
                    }
                }.awaitAll().flatten()
            }
            results += waveResults

            if (waveResults.any { !it.success }) {
                break
            }
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

    private suspend fun runDeviceFlows(
        client: DeviceServerClient,
        flowsRoot: Path,
        catalog: DeviceCatalog,
        plan: DevicePlan,
        deviceName: String,
        env: Map<String, String>,
    ): List<FlowRunReport> {
        val catalogEntry = catalog.devices[deviceName]
        val platform = catalogEntry?.maestroPlatform ?: plan.devices[deviceName]?.category ?: "web"
        val devicePlan = plan.devices[deviceName] ?: return emptyList()

        val orderedFlows = orderFlows(devicePlan.flows, catalogEntry?.useLifecycleRunner == true, platform)

        return orderedFlows.map { relativeFlow ->
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

            waitForJobCompletion(client, jobId, deviceName, relativeFlow)
        }
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
            kotlinx.coroutines.delay(2000)
        }
        return FlowRunReport(deviceName, flowPath, success = false, exitCode = 1, durationMs = timeoutMs)
    }

    fun orderFlows(flows: List<String>, useLifecycle: Boolean, platform: String): List<String> {
        if (!useLifecycle || platform != "web") return flows.sorted()

        val ordered = mutableListOf<String>()
        fun addIfPresent(path: String) {
            if (path in flows) ordered += path
        }

        addIfPresent("customer-frontend/web/lifecycle/01_register.yaml")
        addIfPresent("customer-frontend/web/lifecycle/02_create_organization.yaml")

        flows.filter { flow ->
            !flow.startsWith("customer-frontend/web/lifecycle/") &&
                !flow.startsWith("e2e-tests/backend/") &&
                !flow.startsWith("e2e-tests/smoke/")
        }.sorted().forEach { ordered += it }

        addIfPresent("e2e-tests/backend/healthz.yaml")
        addIfPresent("e2e-tests/smoke/backend_healthz.yaml")
        addIfPresent("customer-frontend/web/lifecycle/99_delete_organization.yaml")
        addIfPresent("customer-frontend/web/lifecycle/99_delete_account.yaml")

        return ordered.distinct()
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
