# dnd-game

Kotlin Multiplatform / Compose Multiplatform: Android, iOS, Desktop (JVM), Web (Wasm).

## Project structure

- `game` — shared game state, rules, diagnostics, serializable network protocol and Ktor client, without Compose.
- `server` — standalone JVM Ktor WebSocket server depending on `game`.
- `shared` — Compose UI, resources, ViewModels and network screens. Depends on `game` and produces the `Shared` framework for iOS.
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

## Игра по сети

1. На компьютере с JDK 21 запустите сервер: `.\gradlew.bat :server:run`. Он слушает TCP-порт 8080 на всех интерфейсах. Проверка доступности: `http://localhost:8080/health`.
2. Запустите Desktop, Android, iOS или Web-клиент. Введите имя и адрес сервера: на том же ПК `ws://localhost:8080`, на других устройствах `ws://<LAN-IP компьютера>:8080`. Для Android-эмулятора адрес хоста обычно `ws://10.0.2.2:8080`.
3. Один игрок нажимает «Создать комнату» и сообщает друзьям адрес сервера и шестизначный код. Остальные вводят код и нажимают «Присоединиться».
4. Когда подключены 2–3 игрока, ведущий комнаты нажимает «Начать приключение». Герои назначаются по порядку входа. Каждый отправляет действия и бросает кубик только в свой ход; состояние и результат броска одинаковы у всех.
5. При обрыве нажмите «Подключиться снова». Место героя сохраняется, очередь ждёт его возвращения. Ведущий может пропустить ход отключившегося героя. При отключении ведущего управление комнатой переходит к первому оставшемуся игроку. В лобби ведущий может убрать отключённого участника и освободить место.

Для другого порта задайте `DND_SERVER_PORT` перед запуском. Самостоятельный дистрибутив сервера без UI:

```powershell
.\gradlew.bat :server:installDist
# Windows, JAVA_HOME указывает на JDK 21:
.\server\build\install\server\bin\server.bat
```

На Linux/macOS запускайте `server/build/install/server/bin/server`.

Друзьям из интернета нужен доступный им адрес сервера. Типичная схема — JVM-сервер за HTTPS reverse proxy, который передаёт WebSocket Upgrade на `127.0.0.1:8080`; клиенты вводят `wss://имя-сервера`. Текущий `/play` находится в корне адреса; префиксы URL не поддерживаются. Сервер здесь не развёрнут в интернете, автоматической настройки роутера/firewall или relay нет. Для LAN входящий TCP-порт должен быть доступен устройствам.

Android debug допускает `ws://` для разработки; release используйте с `wss://`. У HTTPS-версии Web-клиента также должен быть `wss://` сервер. На iOS добавлены описание доступа к локальной сети и `NSAllowsLocalNetworking`; запуск и сетевые разрешения нужно отдельно проверить на Mac/устройстве.

Ограничения прототипа:

- Комнаты и прогресс хранятся в памяти сервера. Его перезапуск сбрасывает игру. Отключённые комнаты очищаются при следующем входе после 30 минут простоя.
- Приватный токен восстановления хранится в памяти клиента. «Подключиться снова» работает в текущем экземпляре приложения; закрытие приложения, обновление вкладки или явный выход теряют токен. После начала нельзя присоединиться новым героем.
- Сервер передаёт последние 200 записей журнала. Полный журнал текущей партии остаётся в памяти сервера.
- Автоматического повтора действий нет: после обрыва клиент получает актуальный снимок. Сервер выполняет броски и проверяет владельца хода/проверки; ID игрока определяется подключением.
- Учётных записей, базы данных и постоянных сохранений пока нет. Код комнаты — приглашение, токен восстановления — приватный доступ к своему персонажу.
- Число участников и правила очереди пока относятся к демонстрационному сценарию.

Сеть использует [Ktor 3.6.1](https://ktor.io/docs/client-websockets.html), [рекомендованный набор движков KMP](https://ktor.io/docs/client-engines.html) и kotlinx.serialization. Серверные тесты проверяют очередь, владельца броска, повторные команды, возврат в комнату, отключения и реальную TCP-синхронизацию нескольких KMP-клиентов.

## Version policy

Kotlin 2.4.21, Compose Multiplatform 1.12.2, AGP 9.3.1, and Gradle 9.7.0 follow the published KMP compatibility range. AGP and Gradle are intentionally kept within that range rather than independently updated to their newest releases.

Material3 has a separate release cycle. This project uses `1.12.0-alpha03`, the version listed for Compose Multiplatform 1.12.2. Android Compose UI tooling uses Jetpack Compose 1.12.1 to match Compose Multiplatform's Android artifacts; there is no separate Android BOM overriding shared UI dependencies.

References:

- [Recommended KMP project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- [KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
- [Compose compatibility and versions](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)
- [Compose Multiplatform 1.12.2](https://kotlinlang.org/docs/multiplatform/whats-new-compose-112.html)

## Adventure prototype

The shared UI opens a network lobby with an option to play locally. Both modes use the original short adventure **The extinguished lighthouse** (Russian interface). It includes three ready-made local players (Rin, Mira and Bran), a turn queue, a journal, action input, d20 checks, a shared countdown and alternative endings. The party and current turn are visible on every screen size. On wide screens the active character sheet is shown alongside the story; on narrow screens it opens in a dialog. Each hero has independent health, modifiers and location.

The current narrator is a scripted demo, not an AI. Use the action suggestions or type their exact text; unsupported text is logged without inventing an outcome. Local progress lives in app memory; network progress lives on the running server. Starting over resets the episode after confirmation.

The provisional demo rules use d20 + an ability modifier against a difficulty (a tie succeeds), without automatic critical outcomes. Each resolved check spends one of three shared checks before the ferry arrives. A completed action advances to the next player; actions requiring dice advance only after the roll. Inspection and conversation advance the queue without spending checks. Cancelling before a roll, unsupported text and unavailable actions keep the current turn. The pass-turn button advances without spending checks. Finishing the adventure freezes the queue. Both a successful repair and a successful signal can save the ferry, including on the last turn. These are replaceable scenario rules, not the final game specification.

Game rules live in game/src/commonMain/kotlin/ru/markn/dnd/adventure; UI and ViewModels remain in shared. The server owns state, player identity and dice. Clients send intent with turn/check IDs; stale and repeated commands are rejected. Character creation, combat, AI, voice, images and persistent saves are future work.

Run shared game-logic tests:

```powershell
.\gradlew.bat :game:jvmTest :server:test
```

## Game diagnostics

Logging uses [Kermit 2.2.0](https://github.com/touchlab/Kermit/releases/tag/2.2.0) with the `DndGame` tag. JSON encoding and parsing use [kotlinx.serialization](https://kotlinlang.org/docs/serialization-json-elements.html) 1.13.0. The compiler plugin generates serializers for game state and the network protocol; diagnostic JSONL continues to use buildJsonObject. The log schema remains timestamp/event/fields, with string-valued fields. Events are structured JSONL: session start, processed/rejected commands, rejection reasons, turn/round changes, checks and their dice/modifier/difficulty/result, hero health/location changes, and adventure endings. IDs connect commands to the originating player, session and turn. Raw player text, prompts and AI responses are excluded.

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

The user checks game design and UI. Agent verification uses tests, builds and programmatic log analysis; do not use computer-use or automate visual inspection of this game. The file-output integration test exercises the real Kermit writer without launching the UI and leaves sample JSONL under `game/build/test-logs`.
