package maestro.web.selenium

import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeDriverService
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.chromium.ChromiumDriverLogLevel
import java.util.logging.Level
import java.util.logging.Logger

class ChromeSeleniumFactory(
    private val isHeadless: Boolean,
    private val screenSize: String?
) : SeleniumFactory {

    override fun create(): WebDriver {
        System.setProperty("webdriver.chrome.silentOutput", "true")
        System.setProperty(ChromeDriverService.CHROME_DRIVER_SILENT_OUTPUT_PROPERTY, "true")
        Logger.getLogger("org.openqa.selenium").level = Level.OFF
        Logger.getLogger("org.openqa.selenium.devtools.CdpVersionFinder").level = Level.OFF

        val driverService = ChromeDriverService.Builder()
            .withLogLevel(ChromiumDriverLogLevel.OFF)
            .build()

        return ChromeDriver(
            driverService,
            ChromeOptions().apply {
                addArguments("--remote-allow-origins=*")
                addArguments("--disable-search-engine-choice-screen")
                // E2E flows assert German UI copy (e.g. "Organisationen"). Allow override via MAESTRO_CHROME_LANG.
                val chromeLang = System.getenv("MAESTRO_CHROME_LANG")?.trim().takeUnless { it.isNullOrEmpty() } ?: "de-DE"
                addArguments("--lang=$chromeLang")
                // Expose Flutter web semantics (flt-semantics) to the DOM a11y tree
                // so Maestro can match labels like "Organisationen" in headless Chrome.
                addArguments("--force-renderer-accessibility")

                // Disable password management
                addArguments("--password-store=basic")
                val chromePrefs = hashMapOf<String, Any>(
                    "credentials_enable_service" to false,
                    "profile.password_manager_enabled" to false,
                    "profile.password_manager_leak_detection" to false,   // important one
                    "intl.accept_languages" to chromeLang,
                )
                setExperimentalOption("prefs", chromePrefs)

                if (isHeadless) {
                    addArguments("--headless=new")

                    // Customer-frontend shell uses layoutWide at width >= 1180.
                    // Default 1024x768 hid navbar actions like "Tool Download".
                    val resolvedSize = screenSize
                        ?: System.getenv("MAESTRO_CHROME_WINDOW_SIZE")?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: "1440x900"
                    addArguments("--window-size=" + resolvedSize.replace('x', ','))

                    setExperimentalOption("detach", true)
                }
            }
        )
    }

}