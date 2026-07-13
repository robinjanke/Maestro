package maestro.cli.cloud

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GitLabOrchestrator(
    private val apiBaseUrl: String,
    private val projectId: String,
    private val triggerToken: String?,
    private val apiToken: String?,
    private val deviceServerUrl: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val mapper: ObjectMapper = jacksonObjectMapper()

    fun triggerDevicesPipeline(
        sessionId: String,
        sessionToken: String,
        devices: List<String>,
        workerGroups: List<String>,
        artifact: ArtifactRef,
    ): Long? {
        val variables = linkedMapOf(
            "MAESTRO_SESSION_ID" to sessionId,
            "MAESTRO_SESSION_TOKEN" to sessionToken,
            "MAESTRO_DEVICE_SERVER_URL" to deviceServerUrl,
            "MAESTRO_DEVICES" to devices.joinToString(","),
            "MAESTRO_WORKER_GROUPS" to workerGroups.joinToString(","),
            "MAESTRO_PARENT_PROJECT_ID" to artifact.projectId,
            "MAESTRO_PARENT_PIPELINE_ID" to artifact.pipelineId,
            "MAESTRO_PARENT_JOB_NAME" to artifact.jobName,
        )

        triggerViaToken(variables)?.let { return it }
        triggerViaApiToken(variables)?.let { return it }

        System.out.println("maestro-devices trigger failed: no successful trigger method")
        return null
    }

    private fun triggerViaToken(variables: Map<String, String>): Long? {
        val token = triggerToken?.trim().orEmpty()
        if (token.isEmpty()) return null

        val form = FormBody.Builder()
            .add("token", token)
            .add("ref", "main")
        variables.forEach { (key, value) -> form.add("variables[$key]", value) }

        val request = Request.Builder()
            .url("$apiBaseUrl/api/v4/projects/$projectId/trigger/pipeline")
            .post(form.build())
            .build()

        return executePipelineRequest(request, "trigger-token")
    }

    private fun triggerViaApiToken(variables: Map<String, String>): Long? {
        val token = apiToken?.trim().orEmpty()
        if (token.isEmpty()) return null

        val payload = mapper.createObjectNode().apply {
            put("ref", "main")
            putArray("variables").apply {
                variables.forEach { (key, value) ->
                    add(mapper.createObjectNode().apply {
                        put("key", key)
                        put("value", value)
                    })
                }
            }
        }

        val request = Request.Builder()
            .url("$apiBaseUrl/api/v4/projects/$projectId/pipeline")
            .header("PRIVATE-TOKEN", token)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return executePipelineRequest(request, "api-token")
    }

    private fun executePipelineRequest(request: Request, method: String): Long? {
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    System.out.println(
                        "maestro-devices trigger failed via $method: ${response.code} ${response.message}${if (body.isNotBlank()) " — $body" else ""}",
                    )
                    return null
                }
                val node = mapper.readTree(body)
                node.get("id")?.asLong()
            }
        } catch (error: Exception) {
            System.out.println("maestro-devices trigger failed via $method: ${error.message}")
            error.printStackTrace(System.out)
            null
        }
    }

    fun cancelPipeline(pipelineId: Long) {
        val token = apiToken?.trim().orEmpty()
        if (token.isEmpty()) return

        val request = Request.Builder()
            .url("$apiBaseUrl/api/v4/projects/$projectId/pipelines/$pipelineId/cancel")
            .header("PRIVATE-TOKEN", token)
            .post(FormBody.Builder().build())
            .build()
        runCatching { client.newCall(request).execute().close() }
    }

    companion object {
        fun fromEnv(deviceServerUrl: String): GitLabOrchestrator? {
            val apiBaseUrl = resolveApiBaseUrl()
            val projectId = System.getenv("MAESTRO_DEVICES_GITLAB_PROJECT_ID")?.trim().orEmpty()
            if (projectId.isEmpty()) return null

            val triggerToken = System.getenv("MAESTRO_DEVICES_GITLAB_TRIGGER_TOKEN")
            val apiToken = System.getenv("MAESTRO_GITLAB_API_TOKEN")
            if (triggerToken.isNullOrBlank() && apiToken.isNullOrBlank()) return null

            return GitLabOrchestrator(apiBaseUrl, projectId, triggerToken, apiToken, deviceServerUrl)
        }

        private fun resolveApiBaseUrl(): String {
            val configured = System.getenv("GITLAB_API_BASE_URL")?.trim().orEmpty()
            if (configured.isNotEmpty()) {
                return configured.trimEnd('/')
            }
            val host = System.getenv("GITLAB_HOST")
                ?: System.getenv("CI_SERVER_HOST")
                ?: "gitlab.doppelt-digital.com"
            val normalizedHost = host
                .removePrefix("https://")
                .removePrefix("http://")
                .removeSuffix("/")
            return "https://$normalizedHost"
        }
    }
}
