# dnd-game

Kotlin Multiplatform / Compose Multiplatform: Android, iOS, Desktop (JVM), Web (Wasm).

## Project structure

- `shared` вЂ” shared Compose UI, resources, logic, and platform implementations (`androidMain`, `iosMain`, `jvmMain`, `wasmJsMain`). Produces the `Shared` framework for iOS.
- `androidApp` вЂ” Android application, manifest, launcher resources, and `MainActivity`. Uses AGP's built-in Kotlin support.
- `desktopApp` вЂ” JVM application entry point, Compose Hot Reload, and native distribution packaging.
- `webApp` вЂ” browser entry point and HTML shell for Kotlin/Wasm.
- `iosApp` вЂ” Xcode application and SwiftUI host for the shared UI.
- `gradle/libs.versions.toml` вЂ” dependency and plugin versions.

Application modules depend on `shared`. Only application modules define executable entry points for Android, Desktop, and Web. The iOS UIKit bridge remains in `shared` so Swift can call it from the generated framework.

## Requirements

Use JDK 21 for Gradle and Desktop development (including Compose Hot Reload). Install Android SDK 37 to build Android. Android minimum SDK is 24; target SDK is 36. Compose UI 1.12.1 and Lifecycle 2.11.0 require compile SDK 37.

For iOS, use macOS and Xcode 26.4. Set the signing team in the Xcode project before running on a device.

The web application requires a browser with WasmGC support. This project targets Kotlin/Wasm only, without a JavaScript fallback.

## Build and run

Commands below use the Windows wrapper. On macOS/Linux, replace `.\gradlew.bat` with `./gradlew`.

```powershell
# Android debug APK
.\gradlew.bat :androidApp:assembleDebug

# Desktop
.\gradlew.bat :desktopApp:run

# Desktop with Compose Hot Reload
.\gradlew.bat :desktopApp:hotRun

# Web development server
.\gradlew.bat :webApp:wasmJsBrowserDevelopmentRun

# Web production distribution
.\gradlew.bat :webApp:wasmJsBrowserDistribution

# Check the three platforms available on Windows
.\gradlew.bat :desktopApp:classes :webApp:wasmJsBrowserDistribution :androidApp:assembleDebug
```

The production web files are in `webApp/build/dist/wasmJs/productionExecutable`. Serve this directory over HTTP; configure the server to serve `.wasm` files as `application/wasm`.

Open `iosApp/iosApp.xcodeproj` in Xcode to build and run iOS. Its build phase invokes `:shared:embedAndSignAppleFrameworkForXcode`.

## Version policy

Kotlin 2.4.21, Compose Multiplatform 1.12.2, AGP 9.3.1, and Gradle 9.7.0 follow the published KMP compatibility range. AGP and Gradle are intentionally kept within that range rather than independently updated to their newest releases.

Material3 has a separate release cycle. This project uses `1.12.0-alpha03`, the version listed for Compose Multiplatform 1.12.2. Android Compose UI tooling uses Jetpack Compose 1.12.1 to match Compose Multiplatform's Android artifacts; there is no separate Android BOM overriding shared UI dependencies.

References:

- [Recommended KMP project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- [KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
- [Compose compatibility and versions](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)
- [Compose Multiplatform 1.12.2](https://kotlinlang.org/docs/multiplatform/whats-new-compose-112.html)

## Adventure prototype

The shared UI now opens the original short adventure **The extinguished lighthouse** (Russian interface). It includes three ready-made local players (Rin, Mira and Bran), a turn queue, a journal, action input, d20 checks, a shared countdown and alternative endings. The party and current turn are visible on every screen size. On wide screens the active character sheet is shown alongside the story; on narrow screens it opens in a dialog. Each hero has independent health, modifiers and location.

The current narrator is a local scripted demo, not an AI. Use the action suggestions or type their exact text; unsupported text is logged without inventing an outcome. Progress lives in memory until the app is closed. Starting over resets the episode after confirmation.

The provisional demo rules use d20 + an ability modifier against a difficulty (a tie succeeds), without automatic critical outcomes. Each resolved check spends one of three shared checks before the ferry arrives. A completed action advances to the next player; actions requiring dice advance only after the roll. Inspection and conversation advance the queue without spending checks. Cancelling before a roll, unsupported text and unavailable actions keep the current turn. The pass-turn button advances without spending checks. Finishing the adventure freezes the queue. Both a successful repair and a successful signal can save the ferry, including on the last turn. These are replaceable scenario rules, not the final game specification.

Code lives in `shared/src/commonMain/kotlin/ru/markn/dnd/adventure`: immutable state, an engine with injectable dice, a ViewModel, and Compose UI. Commands carry a player ID and session/turn token so the engine rejects inactive players, repeated commands and stale requests; this is local validation, not network authentication. The default fixed queue is provisional, not the final multiplayer protocol. Character creation, networking, combat, AI, voice, images and persistent saves are future work.

Run shared game-logic tests:

```powershell
.\gradlew.bat :shared:jvmTest
```

## Game diagnostics

Logging uses [Kermit 2.2.0](https://github.com/touchlab/Kermit/releases/tag/2.2.0) with the `DndGame` tag. JSON encoding and parsing use [kotlinx.serialization](https://kotlinlang.org/docs/serialization-json-elements.html) 1.13.0 without a compiler plugin. The log schema remains timestamp/event/fields, with string-valued fields. Events are structured JSONL: session start, processed/rejected commands, rejection reasons, turn/round changes, checks and their dice/modifier/difficulty/result, hero health/location changes, and adventure endings. IDs connect commands to the originating player, session and turn. Raw player text, prompts and AI responses are excluded.

On the next Desktop launch, logs appear in `%LOCALAPPDATA%/dnd-game/logs/game.log` (or `user.home/dnd-game/logs` when LOCALAPPDATA is unavailable). Kermit's asynchronous rolling writer keeps `game.log` and `game-1.log`, approximately 5 MiB each. Override the directory with `DND_GAME_LOG_DIR` or the JVM property `dnd.game.logDir`. Logging is best-effort; output failures must not change a game action. The application also writes to its console.

```powershell
# Follow Desktop game events after launching the app
Get-Content -LiteralPath (Join-Path $env:LOCALAPPDATA 'dnd-game/logs/game.log') -Tail 100 -Wait

# Optional: choose the Desktop log directory before launching
$env:DND_GAME_LOG_DIR = Join-Path $env:TEMP 'dnd-game-logs'
.\gradlew.bat :desktopApp:run

# Android diagnostics with a connected device
adb logcat -s DndGame:I '*:S'
```

On iOS use Xcode/system logs; on Wasm use the browser console. No remote logging service is configured.

The user checks game design and UI. Agent verification uses tests, builds and programmatic log analysis; do not use computer-use or automate visual inspection of this game. The file-output integration test exercises the real Kermit writer without launching the UI and leaves sample JSONL under `shared/build/test-logs`.
