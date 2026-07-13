package maestro.cli.deviceserver

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

const val DEVICE_SERVER_TOKEN_HEADER = "X-Device-Server-Token"

fun resolveDeviceServerToken(cliToken: String?): String? {
    val fromCli = cliToken?.trim()?.takeIf { it.isNotEmpty() }
    if (fromCli != null) return fromCli
    return System.getenv("DEVICE_SERVER_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
}

suspend fun ApplicationCall.requireDeviceServerToken(expectedToken: String?): Boolean {
    if (expectedToken.isNullOrBlank()) return true
    val provided = request.headers[DEVICE_SERVER_TOKEN_HEADER]
    if (provided != expectedToken) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing device-server token"))
        return false
    }
    return true
}
