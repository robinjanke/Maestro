package maestro.cli.cloud

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

data class SessionRecord(
    val sessionId: String,
    val sessionToken: String,
    val status: SessionStatus,
    val expectedDevices: List<String>,
    val flowPlan: FlowPlan,
    val catalogYaml: String?,
    val env: Map<String, String>,
    val artifact: ArtifactRef,
    val clientProjectPath: String?,
    val gitlabPipelineId: Long?,
    val attachedWorkers: Map<String, WorkerAttachRequest>,
    val flowResults: List<FlowResultRecord>,
    val error: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

class SessionStore(private val dbPath: Path) {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        Files.createDirectories(dbPath.parent)
        DriverManager.getConnection("jdbc:sqlite:${dbPath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS sessions (
                        session_id TEXT PRIMARY KEY,
                        session_token TEXT NOT NULL,
                        status TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    fun create(record: SessionRecord) {
        upsert(record)
    }

    fun get(sessionId: String): SessionRecord? {
        DriverManager.getConnection("jdbc:sqlite:${dbPath}").use { conn ->
            conn.prepareStatement("SELECT payload FROM sessions WHERE session_id = ?").use { ps ->
                ps.setString(1, sessionId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return mapper.readValue(rs.getString("payload"))
                }
            }
        }
    }

    fun update(record: SessionRecord) {
        upsert(record)
    }

    fun listActive(): List<SessionRecord> {
        DriverManager.getConnection("jdbc:sqlite:${dbPath}").use { conn ->
            conn.prepareStatement(
                "SELECT payload FROM sessions WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val results = mutableListOf<SessionRecord>()
                    while (rs.next()) {
                        results += mapper.readValue<SessionRecord>(rs.getString("payload"))
                    }
                    return results
                }
            }
        }
    }

    private fun upsert(record: SessionRecord) {
        val payload = mapper.writeValueAsString(record)
        DriverManager.getConnection("jdbc:sqlite:${dbPath}").use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO sessions (session_id, session_token, status, payload, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    session_token = excluded.session_token,
                    status = excluded.status,
                    payload = excluded.payload,
                    updated_at = excluded.updated_at
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, record.sessionId)
                ps.setString(2, record.sessionToken)
                ps.setString(3, record.status.name)
                ps.setString(4, payload)
                ps.setString(5, record.createdAt.toString())
                ps.setString(6, record.updatedAt.toString())
                ps.executeUpdate()
            }
        }
    }
}
