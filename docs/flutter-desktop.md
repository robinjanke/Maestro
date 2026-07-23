# Flutter Desktop (macOS / Windows / Linux)

This fork extends [Maestro](https://github.com/mobile-dev-inc/Maestro) with a **Desktop** platform for Flutter desktop apps.

## Status

| OS | Driver | Status |
|----|--------|--------|
| macOS | `MacOSDesktopDriver` (AXUIElement / Accessibility API) | Implemented |
| Windows | `WindowsDesktopDriver` (UI Automation via PowerShell + AWT Robot) | Implemented |
| Linux | `LinuxDesktopDriver` (AT-SPI via Python + AWT Robot) | Implemented |

## Requirements

- Java 17+
- macOS: **Accessibility permission** for the terminal/Java process  
  (System Settings → Privacy & Security → Accessibility)
- Windows: PowerShell + .NET UIAutomation assemblies (built into Windows)
- Linux: graphical session with AT-SPI (`python3` + `gi.repository.Atspi` / `gir1.2-atspi-2.0`), `DISPLAY` set
- Flutter app: semantics enabled and stable identifiers

```dart
import 'package:flutter/semantics.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SemanticsBinding.instance.ensureSemantics();
  runApp(const MyApp());
}
```

Use `Semantics(identifier: 'my-button', child: ...)` — Maestro maps `identifier` to `resource-id` in the hierarchy (same as Flutter mobile/web).

## Usage

Build the CLI from this fork:

```bash
export JAVA_HOME="$HOME/.asdf/installs/java/openjdk-17"  # or any JDK 17+
./installLocally.sh
```

Run a flow against a desktop app:

```bash
maestro test maestro/flows/desktop/launch_textedit.yaml --platform desktop
```

Flow example:

```yaml
appId: com.apple.TextEdit
---
- launchApp
- assertVisible: "TextEdit"
```

For Flutter desktop apps use the bundle id (macOS) or path to the binary:

```yaml
appId: com.doppelt-digital.setup-app
---
- launchApp
- tapOn:
    id: "clone-project-button"
```

## Architecture

- Module: `maestro-desktop`
- Factory: `DesktopDriverFactory` selects OS-specific driver
- CLI: `--platform desktop` or auto-detect via `desktop-*` device id
- Platform enum: `Platform.DESKTOP`

## Local development

```bash
export JAVA_HOME="$HOME/.asdf/installs/java/openjdk-17"
./gradlew :maestro-cli:shadowJar
java -jar maestro-cli/build/libs/maestro-cli-*-all.jar test <flow> --platform desktop
```

## Upstream

Fork: https://github.com/robinjanke/Maestro  
Upstream: https://github.com/mobile-dev-inc/Maestro

Maintainer: Robin Janke <mail@robin-janke.de>
