package maestro.cli.cloud

import maestro.orchestra.yaml.DeviceCatalog
import maestro.orchestra.yaml.DevicePlan
import maestro.orchestra.yaml.DevicePlanService
import java.nio.file.Path

object CloudFlowPlanner {
    fun planForCloud(
        flowsRoot: Path,
        catalog: DeviceCatalog,
    ): DevicePlan {
        val basePlan = DevicePlanService.plan(flowsRoot)
        if (!basePlan.isValid) return basePlan

        val enabledDevices = basePlan.flowDeviceByPath.values
            .distinct()
            .filter { deviceName -> isDeviceEnabled(catalog, deviceName) }

        val plannedForEnabled = basePlan.orderedFlows.filter { flow ->
            val device = basePlan.flowDeviceByPath[flow] ?: return@filter false
            device in enabledDevices
        }

        val ordered = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun add(relativePath: String) {
            if (relativePath !in plannedForEnabled) return
            if (!seen.add(relativePath)) return
            ordered += relativePath
        }

        if (enabledDevices.contains("chrome-1")) {
            add("customer-frontend/web/lifecycle/01_register.yaml")
            add("customer-frontend/web/lifecycle/02_create_organization.yaml")

            for (flow in plannedForEnabled) {
                if (flow.startsWith("customer-frontend/web/lifecycle/")) continue
                if (flow.startsWith("e2e-tests/")) continue
                if (flow.startsWith("platform-app/")) continue
                if (!flow.startsWith("customer-frontend/web/")) continue
                add(flow)
            }

            add("e2e-tests/backend/healthz.yaml")
            add("customer-frontend/web/lifecycle/99_delete_organization.yaml")
            add("customer-frontend/web/lifecycle/99_delete_account.yaml")
        }

        for (device in enabledDevices) {
            if (device == "chrome-1") continue
            val prefix = when (device) {
                "linux-1" -> "platform-app/desktop-linux/"
                "macos-1" -> "platform-app/desktop-macos/"
                "windows-1" -> "platform-app/desktop-windows/"
                else -> null
            } ?: continue
            for (flow in plannedForEnabled) {
                if (flow.startsWith(prefix)) add(flow)
            }
        }

        for (flow in plannedForEnabled) {
            add(flow)
        }

        val flowDeviceByPath = ordered.associateWith { flow ->
            basePlan.flowDeviceByPath[flow]
                ?: error("Missing device mapping for flow $flow")
        }

        return DevicePlan(
            orderedFlows = ordered,
            flowDeviceByPath = flowDeviceByPath,
            devices = flowDeviceByPath.values.distinct().associateWith { name ->
                basePlan.devices[name] ?: error("Missing device plan entry for $name")
            },
            errors = emptyList(),
        )
    }

    private fun isDeviceEnabled(catalog: DeviceCatalog, deviceName: String): Boolean {
        val entry = catalog.devices[deviceName] ?: return true
        val enabledVar = entry.enabledVar ?: return true
        return System.getenv(enabledVar) == "true"
    }
}
