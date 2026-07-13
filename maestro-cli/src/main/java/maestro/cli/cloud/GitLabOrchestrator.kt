package maestro.cli.cloud

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class GitLabOrchestrator(
    private val gitlabHost: String,
    private val projectId: String,
    private val triggerToken: String,
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
        val form = FormBody.Builder()
            .add("token", triggerToken)
            .add("ref", "main")
            .add("variables[MAESTRO_SESSION_ID]", sessionId)
            .add("variables[MAESTRO_SESSION_TOKEN]", sessionToken)
            .add("variables[MAESTRO_DEVICE_SERVER_URL]", deviceServerUrl)
            .add("variables[MAESTRO_DEVICES]", devices.joinToString(","))
            .add("variables[MAESTRO_WORKER_GROUPS]", workerGroups.joinToString(","))
            .add("variables[MAESTRO_PARENT_PROJECT_ID]", artifact.projectId)
            .add("variables[MAESTRO_PARENT_PIPELINE_ID]", artifact.pipelineId)
            .add("variables[MAESTRO_PARENT_JOB_NAME]", artifact.jobName)
            .build()

        val request = Request.Builder()
            .url("https://${gitlabHost}/api/v4/projects/${projectId}/trigger/pipeline")
            .post(form)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }
            val body = response.body?.string() ?: return null
            val node = mapper.readTree(body)
            return node.get("id")?.asLong()
        }
    }

    fun cancelPipeline(pipelineId: Long) {
        val request = Request.Builder()
            .url("https://${gitlabHost}/api/v4/projects/${projectId}/pipelines/${pipelineId}/cancel")
            .post(FormBody.Builder().build())
            .header("PRIVATE-TOKEN", System.getenv("MAESTRO_GITLAB_API_TOKEN") ?: return)
            .build()
        client.newCall(request).execute().close()
    }

    companion object {
        fun fromEnv(deviceServerUrl: String): GitLabOrchestrator? {
            val host = System.getenv("CI_SERVER_HOST")
                ?: System.getenv("GITLAB_HOST")
                ?: "gitlab.doppelt-digital.com"
            val projectId = System.getenv("MAESTRO_DEVICES_GITLAB_PROJECT_ID") ?: return null
            val triggerToken = System.getenv("MAESTRO_DEVICES_GITLAB_TRIGGER_TOKEN") ?: return null
            return GitLabOrchestrator(host, projectId, triggerToken, deviceServerUrl)
        }
    }
}
