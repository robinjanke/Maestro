package maestro.cli.command

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import maestro.cli.CliError
import maestro.orchestra.yaml.DevicePlanService
import picocli.CommandLine
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "plan-devices",
    description = [
        "Scan Maestro flows, validate device blocks, and output a device execution plan",
    ],
)
class PlanDevicesCommand : Callable<Int> {

    @CommandLine.Parameters(
        index = "0",
        description = ["Root directory containing collected Maestro flows"],
    )
    private lateinit var flowsRoot: Path

    @CommandLine.Option(
        names = ["--device"],
        description = ["Only include flows for this device name"],
    )
    private var deviceFilter: String? = null

    @CommandLine.Option(
        names = ["--catalog"],
        description = ["Optional device catalog YAML for CI metadata"],
    )
    private var catalogPath: Path? = null

    @CommandLine.Option(
        names = ["--format"],
        description = ["Output format: json, list-flows, gitlab-ci"],
        defaultValue = "json",
    )
    private lateinit var format: String

    override fun call(): Int {
        val plan = DevicePlanService.plan(flowsRoot, deviceFilter)
        if (plan.errors.isNotEmpty()) {
            plan.errors.forEach { System.err.println(it) }
            throw CliError("Device planning failed with ${plan.errors.size} error(s)")
        }

        when (format.lowercase()) {
            "json" -> {
                val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                println(mapper.writeValueAsString(plan))
            }
            "list-flows" -> {
                val deviceName = deviceFilter
                    ?: throw CliError("--device is required when --format=list-flows")
                val device = plan.devices[deviceName]
                    ?: throw CliError("No flows planned for device '$deviceName'")
                device.flows.forEach { println(it) }
            }
            "gitlab-ci" -> {
                val catalog = catalogPath
                    ?: throw CliError("--catalog is required when --format=gitlab-ci")
                println(generateGitlabCi(plan, DevicePlanService.loadCatalog(catalog)))
            }
            else -> throw CliError("Unsupported format: $format")
        }

        return 0
    }

    private fun generateGitlabCi(
        plan: maestro.orchestra.yaml.DevicePlan,
        catalog: maestro.orchestra.yaml.DeviceCatalog,
    ): String {
        val waves = if (catalog.executionWaves.isNotEmpty()) {
            catalog.executionWaves
        } else {
            plan.devices.keys.sorted().map { listOf(it) }
        }

        val plannedNames = plan.devices.keys.toSet()
        val enabledDevices = waves.flatten().filter { plannedNames.contains(it) }.distinct()
        if (enabledDevices.isEmpty()) {
            throw CliError("No planned devices match catalog execution waves")
        }

        val waveByDevice = linkedMapOf<String, Int>()
        waves.forEachIndexed { waveIndex, wave ->
            wave.forEach { deviceName ->
                if (plannedNames.contains(deviceName)) {
                    waveByDevice[deviceName] = waveIndex
                }
            }
        }

        val builder = StringBuilder()
        builder.appendLine("stages:")
        builder.appendLine("  - prepare")
        builder.appendLine("  - e2e-test")
        builder.appendLine()
        builder.appendLine("include:")
        builder.appendLine("  - project: \"public-code/pipelines/app-project-pipelines\"")
        builder.appendLine("    ref: \"${System.getenv("PIPELINE_LIB_VERSION") ?: "main"}\"")
        builder.appendLine("    file: \"templates-common/steps/test/maestro/device-job.yml\"")
        builder.appendLine()

        builder.appendLine("prepare-e2e-flows:")
        builder.appendLine("  stage: prepare")
        builder.appendLine("  image: alpine:3.21")
        builder.appendLine("  before_script:")
        builder.appendLine("    - apk add --no-cache git bash jq")
        builder.appendLine("    - |")
        builder.appendLine("      if [ ! -f \"\${CI_PROJECT_DIR}/.pipeline-lib/bin/setup-pipeline-lib.sh\" ]; then")
        builder.appendLine("        git clone --depth 1 \\")
        builder.appendLine("          \"https://gitlab-ci-token:\${CI_JOB_TOKEN}@\${CI_SERVER_HOST}/public-code/pipelines/app-project-pipelines.git\" \\")
        builder.appendLine("          \"\${CI_PROJECT_DIR}/.pipeline-lib\"")
        builder.appendLine("      fi")
        builder.appendLine("      bash \"\${CI_PROJECT_DIR}/.pipeline-lib/bin/setup-pipeline-lib.sh\"")
        builder.appendLine("  script:")
        builder.appendLine("    - chmod +x \"\${CI_PROJECT_DIR}/.pipeline-lib/bin/collect-maestro-component-flows.sh\"")
        builder.appendLine("    - bash \"\${CI_PROJECT_DIR}/.pipeline-lib/bin/collect-maestro-component-flows.sh\"")
        builder.appendLine("  artifacts:")
        builder.appendLine("    paths:")
        builder.appendLine("      - .maestro-collected/")
        builder.appendLine("    expire_in: 1 day")
        builder.appendLine()

        val previousWaveJobs = mutableListOf<String>()

        waves.forEachIndexed { waveIndex, wave ->
            val waveDevices = wave.filter { plannedNames.contains(it) }
            if (waveDevices.isEmpty()) {
                return@forEachIndexed
            }

            waveDevices.forEach { deviceName ->
                val catalogEntry = catalog.devices[deviceName]
                    ?: throw CliError("Device '$deviceName' is planned but missing from catalog")
                val jobName = "${deviceName}-tests"
                builder.appendLine("${jobName}:")
                builder.appendLine("  extends: .maestro-device-test")
                builder.appendLine("  stage: e2e-test")
                builder.appendLine("  variables:")
                builder.appendLine("    MAESTRO_DEVICE_NAME: \"${deviceName}\"")
                catalogEntry.maestroPlatform?.let {
                    builder.appendLine("    MAESTRO_PLATFORM: \"${it}\"")
                }
                catalogEntry.targetUrlVar?.let {
                    builder.appendLine("    MAESTRO_TARGET_URL_VAR: \"${it}\"")
                }
                catalogEntry.flowsSubpath?.let {
                    builder.appendLine("    MAESTRO_FLOWS_SUBPATH: \"${it}\"")
                }
                if (catalogEntry.useLifecycleRunner) {
                    builder.appendLine("    E2E_USE_LIFECYCLE_RUNNER: \"true\"")
                }
                catalogEntry.enabledVar?.let {
                    builder.appendLine("  rules:")
                    builder.appendLine("    - if: \$${it} == \"true\"")
                }
                if (catalogEntry.runnerTags.isNotEmpty()) {
                    builder.appendLine("  tags:")
                    catalogEntry.runnerTags.forEach { tag ->
                        builder.appendLine("    - ${tag}")
                    }
                }

                val needsJobs = if (waveIndex == 0) {
                    listOf("prepare-e2e-flows")
                } else {
                    previousWaveJobs
                }
                if (needsJobs.isNotEmpty()) {
                    builder.appendLine("  needs:")
                    needsJobs.forEach { previousJob ->
                        val artifacts = if (previousJob == "prepare-e2e-flows") "true" else "false"
                        builder.appendLine("    - job: ${previousJob}")
                        builder.appendLine("      artifacts: ${artifacts}")
                    }
                }
                builder.appendLine()
            }
            previousWaveJobs.clear()
            previousWaveJobs.addAll(waveDevices.map { "${it}-tests" })
        }

        return builder.toString().trimEnd() + "\n"
    }
}
