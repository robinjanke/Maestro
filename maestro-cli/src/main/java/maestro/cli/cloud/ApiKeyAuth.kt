package maestro.cli.cloud

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

const val MAESTRO_API_KEY_HEADER = "X-Maestro-Api-Key"
const val MAESTRO_SESSION_TOKEN_HEADER = "X-Session-Token"

class ApiKeyValidator(private val validKeys: Set<String>) {
    fun isValid(key: String?): Boolean {
        if (validKeys.isEmpty()) return true
        return key != null && key in validKeys
    }
}

suspend fun ApplicationCall.requireApiKey(validator: ApiKeyValidator): Boolean {
    val provided = request.headers[MAESTRO_API_KEY_HEADER]
    if (!validator.isValid(provided)) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing API key"))
        return false
    }
    return true
}

suspend fun ApplicationCall.requireSessionToken(expected: String?): Boolean {
    if (expected.isNullOrBlank()) {
        respond(HttpStatusCode.InternalServerError, mapOf("error" to "session token not configured"))
        return false
    }
    val provided = request.headers[MAESTRO_SESSION_TOKEN_HEADER]
    if (provided != expected) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing session token"))
        return false
    }
    return true
}

fun resolveApiKeysFromEnv(): Set<String> {
    val raw = System.getenv("MAESTRO_CLOUD_API_KEYS")?.trim().orEmpty()
    if (raw.isEmpty()) return emptySet()
    return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
