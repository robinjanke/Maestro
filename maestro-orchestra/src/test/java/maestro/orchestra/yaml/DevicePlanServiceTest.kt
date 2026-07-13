package maestro.orchestra.yaml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DevicePlanServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `plan groups flows by device name`() {
        writeFlow(
            "web/smoke.yaml",
            """
            device:
              name: chrome-1
              type: chrome
              version: latest
              category: web
            url: https://example.com
            ---
            - launchApp
            """.trimIndent(),
        )
        writeFlow(
            "ios/smoke.yaml",
            """
            device:
              name: iphone-1
              type: iPhone 17 Pro Max
              version: iOS 26
              category: ios
            appId: com.example.app
            ---
            - launchApp
            """.trimIndent(),
        )

        val plan = DevicePlanService.plan(tempDir)

        assertTrue(plan.errors.isEmpty())
        assertEquals(2, plan.devices.size)
        assertEquals(listOf("ios/smoke.yaml"), plan.devices["iphone-1"]?.flows)
        assertEquals(listOf("web/smoke.yaml"), plan.devices["chrome-1"]?.flows)
    }

    @Test
    fun `plan reports missing device blocks`() {
        writeFlow(
            "invalid.yaml",
            """
            url: https://example.com
            ---
            - launchApp
            """.trimIndent(),
        )

        val plan = DevicePlanService.plan(tempDir)

        assertEquals(1, plan.errors.size)
        assertTrue(plan.errors.first().contains("missing device block"))
    }

    private fun writeFlow(relativePath: String, content: String) {
        val flowPath = tempDir.resolve(relativePath)
        Files.createDirectories(flowPath.parent)
        Files.writeString(flowPath, content)
    }
}
