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
4. Ведущий комнаты выбирает сюжет в лобби. Когда подключены 2–3 игрока, он нажимает «Начать приключение». Герои назначаются по порядку входа. Каждый отправляет действия и бросает кубик только в свой ход; состояние и результат броска одинаковы у всех. После старта сменить сюжет нельзя; перезапуск сохраняет выбранный сюжет.
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

The shared UI opens a network lobby with an option to play locally. Both modes offer **The extinguished lighthouse** and **The flooded archive** (Russian interface). Local players choose a scenario before starting; the network host selects it in the lobby. Both adventures use the same ready-made heroes, turn queue, journal, action input, d20 checks and alternative endings. The party and current turn are visible on every screen size. On wide screens the active character sheet is shown alongside the story; on narrow screens it opens in a dialog. Each hero has independent health, modifiers, inventory and location.

The local narrator is a scripted demo. Network rooms optionally use Claude when the server is configured as described below; otherwise they use the same scripted demo. Local progress lives in app memory; network progress lives on the running server. Starting over resets the episode after confirmation.

The provisional demo rules use d20 + an ability modifier against a difficulty (a tie succeeds), without automatic critical outcomes. The scenario sets the shared check budget: three before the ferry arrives, four before the archive floods. A completed action advances to the next player; actions requiring dice advance only after the roll. Inspection, conversation and safe actions advance the queue without spending checks. Cancelling before a roll, unsupported text and unavailable actions keep the current turn. The pass-turn button advances without spending checks. Finishing the adventure freezes the queue. Completing a goal on the last check takes priority over the timeout ending. These are prototype rules, not the final game specification.

Game rules live in game/src/commonMain/kotlin/ru/markn/dnd/adventure; UI and ViewModels remain in shared. The server owns state, player identity and dice. Clients send intent with turn/check IDs; stale and repeated commands are rejected. Character creation, combat, voice, images and persistent saves are future work.

## Сценарии и общий движок

| Сюжет | Места и задача | Пути к цели |
|---|---|---|
| Погасший маяк | Берег и фонарная комната; предупредить паром | Починить привод инструментами или подать сигнал фонарём; верёвка и помощь хранителя облегчают проверки |
| Затопленный архив | Зал, двор и подвал; сохранить земельные грамоты | Открыть дверь и сундук, защитить оригиналы тканью; либо прочитать опись и договориться с курьером о копиях |

Определения сюжетов находятся в `game/src/commonMain/kotlin/ru/markn/dnd/adventure/Scenarios.kt`.
`ScenarioDefinition` задаёт названия и описания мест, каталог предметов, факты и требования к ним,
переходы, начальное снаряжение, запас проверок, действия-подсказки и финалы. Это сериализуемые
Kotlin-данные внутри приложения; внешнего загрузчика сценариев, редактора и скрытой памяти NPC пока нет.
ID мест, предметов, фактов, действий и финалов — строки из выбранного определения. Инвентарь
хранит ID, UI отображает названия из каталога сценария.

`AdventureEngine` и `SceneRules` общие для обоих сюжетов: проверяют ход, владение предметом,
совместное расположение при передаче, доступность перехода, обязательные факты, минимальную
сложность, расход предмета и условия финала. Обе ветки плана проверяются атомарно до броска.
Факты могут открыть переход или действие, а существующие факты — понизить минимальную сложность.
Сюжетных ветвлений по ID маяка или архива в движке нет. Готовые действия также превращаются
в те же `SceneActionPlan`, что и свободные предложения ИИ.

Claude получает только определение выбранного сюжета и его актуальное состояние; JSON schema
содержит только его допустимые ID. Предметы, факты и цели другого сюжета отклоняются. Все
пока поддерживаемые типы последствий прежние: move, transfer, fact, damage. Сложность свободного
броска 8–18, урон только в провальной ветке и не более 3 суммарно. Это общий профиль двух прототипов.

Примеры архива: «Читаю номера грамот в описи», «Пытаюсь открыть замок двери отмычками, изучив
механизм», «Передаю ткань Мире», «Убеждаю курьера доставить копии по найденным номерам».
Дверь подвала должна быть открыта до перехода, сундук — до спасения оригиналов; копии требуют
найденных номеров и разговора во дворе. Неудача проверки расходует время и оставляет возможность
повторить попытку или сменить путь, пока общий запас не исчерпан.

Автоматические проверки включают оба финала архива, поражение и победу на последней проверке,
передачу/расход снаряжения, недопустимые ID и закрытые переходы. TCP-тест с двумя KMP-клиентами
проходит архив через свободные планы, переподключается перед броском и перезапускает тот же сюжет.
MockEngine проверяет контекст/schema архива через настоящий клиент Koog без расхода API-токенов.
14.11.2026 общий промпт проверен с реальным Claude через AI STAR после рефакторинга, с двумя
WebSocket-клиентами на каждый сюжет. Первый прогон выявил, что модель сводила интеллектуальный
способ открытия замка к готовому действию с ловкостью. Общая инструкция уточнена: совпадение цели
не позволяет подменять способ и характеристику; для другого разумного способа нужен свободный план.
После исправления повторный прогон прошёл все семь проверок:

| Сюжет и действие | Ответ ИИ | Подтверждённый сервером результат |
|---|---:|---|
| Маяк: передача инструментов | 4,7 с | У Рин 0 наборов, у Миры 2; без создания вещей |
| Маяк: закрепление верёвки | 4,6 с | Верёвка израсходована, общий факт установлен |
| Маяк: подъём снаружи силой рук | 5,5 с | Свободный план STRENGTH, сложность 8; d20=20, +1, подъём выполнен |
| Архив: чтение описи | 5,0 с | INDEX_READ установлен без расхода проверки |
| Архив: изучение замка с отмычками | 5,7 с | Свободный план INTELLIGENCE, сложность 10; d20=2, +3, дверь осталась закрытой |
| Архив: выход к курьеру | 4,4 с | Герой перемещён во двор без расхода проверки |
| Архив: переговоры о копиях | 7,9 с | Готовый план CHARISMA, сложность 12; d20=11, +0, договор не заключён |

При каждом броске применена соответствующая ветка и списана одна общая проверка. Планы и
итоговое состояние совпали у обоих клиентов. Выполнено 12 реальных запросов (5 в первом прогоне,
7 после исправления), без автоматических повторов и дополнительных запросов при броске.
Второй прогон проверяет реальные успешную и провальные ветки, но не полное прохождение обоих
сюжетов и не все варианты импровизации. Более ранние реальные прогоны маяка ниже относятся
к предыдущей версии контракта.

## Claude: первый сетевой ИИ-ведущий

Провайдер для прототипа — Claude через [AI STAR](https://ai.starimg.ru/help), Anthropic Messages API
`POST https://ai.starimg.ru/v1/messages`. Сервер использует клиент [Koog 1.3.0 от JetBrains](https://docs.koog.ai/prompts/llm-clients/).
Адрес и модель настраиваются; по умолчанию выбрана `claude-sonnet-5-5`, опубликованная в списке моделей сервиса.
Доступность модели для конкретного ключа и поддержка структурированных ответов требуют проверки реальным запросом.

Для локальной пробы на Windows запустите из корня репозитория:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-claude-server.ps1
```

Лаунчер создаёт `server/claude.local.json` из `server/claude.local.example.json`, если файла ещё нет.
Впишите свой ключ в `apiKey` локального файла, либо вставьте его в скрытый запрос терминала.
Если `ANTHROPIC_API_KEY` уже задан в окружении терминала, он имеет приоритет, а локальный файл не читается.
Локальный файл исключён из Git; пример с пустым ключом хранится в репозитории. Не присылайте содержимое
заполненного файла в чат. Адрес и модель уже настроены на AI STAR и Sonnet 5.5.
Скрипт находит JDK 21 через JAVA_HOME/PATH или стандартные каталоги JDK, собирает сервер при отсутствии
дистрибутива и запускает его напрямую, передавая ключ только через окружение Java-процесса.
Для пересборки используйте параметр `-Rebuild`. Остановка — Ctrl+C; постоянные переменные окружения не меняются.
Игровые клиенты подключаются к `ws://localhost:8080`. Для текущего сетевого сценария нужны два клиента:
один создаёт комнату, другой входит по её коду; «Играть локально» использует сценарного ведущего без Claude.

Перед запуском задайте переменные **на компьютере с сервером**. Пример PowerShell с вводом ключа без его записи в историю команд:

```powershell
$env:ANTHROPIC_API_KEY = [System.Net.NetworkCredential]::new('', (Read-Host 'Claude API key' -AsSecureString)).Password
$env:DND_CLAUDE_BASE_URL = 'https://ai.starimg.ru'
$env:DND_CLAUDE_MODEL = 'claude-sonnet-5-5'
.\gradlew.bat :server:run
```

На удалённом сервере задайте эти переменные в окружении службы, которая запускает серверный дистрибутив.
Base URL — только HTTPS origin, без `/v1`, query, логина и пароля; путь `/v1/messages` добавляет клиент.
Ключ передаётся сервису в заголовках `x-api-key` и `Authorization: Bearer`; перенаправления HTTP отключены.
Не сохраняйте ключ в репозитории или настройках распространяемых клиентов. Без `ANTHROPIC_API_KEY` сеть работает со сценарным ведущим.
После изменения конфигурации перезапустите сервер: текущие комнаты при этом теряются.
Протокол версии 4: сервер и клиенты необходимо обновить вместе. Добавлены выбор сценария,
его ID в комнате/состоянии, строковые ID мира и единые планы для всех проверок.

Claude разбирает свободные способы в пределах выбранного сюжета по общему контракту. Для маяка помимо осмотра, разговора,
подъёма, починки и сигнала, можно передать снаряжение герою рядом, закрепить верёвку для группы,
попросить хранителя помочь, подняться снаружи или спуститься. Например: «Передаю Мире инструменты»,
«Закрепляю верёвку у маяка», «Пробую освободить привод силой с помощью инструментов».

Для нового способа модель предлагает `SceneActionPlan`: намерение, используемый предмет,
необязательную проверку и отдельные списки последствий успеха/провала. Сервер проверяет обе ветки
целиком до броска: известные эффекты и ID, расположение героев, наличие предметов, сложность и урон.
Недопустимый план отклоняется без изменения журнала, очереди или ресурсов; автоматического запроса
исправления к модели нет. Допустимый план проверки закрепляется в `PendingCheck`, показывается
участникам и переживает переподключение. Бросок применяет только выбранную ветку один раз,
без второго вызова ИИ. Отмена до броска не применяет последствия и сохраняет ход.

Предварительная политика этой сцены: сложность свободной проверки 8–18; подъём требует минимум 10,
уже закреплённая верёвка снижает минимум до 8. Закрепление снимает одну верёвку со снаряжения
и даёт общий постоянный эффект. Помощь хранителя получается у подножия, не суммируется и снижает
минимальную сложность починки/сигнала с 12 до 10. Починка требует инструментов наверху, сигнал — фонаря.
Эти ограничения действуют и для прежних кнопок действий. Передача требует совместного расположения
героев и переносит одну вещь без создания копий. Провал может нанести активному герою суммарно до 3 урона
или только потратить время. Любой бросок расходует одну из трёх общих проверок; безопасное действие —
только передаёт ход. Успех цели на последней проверке засчитывается до поражения по времени.

Результат свободного действия описывает сервер по применённым эффектам; произвольная `narration`
модели для плана не показывается. LOOK/TALK сохраняют художественные ответы, остальные прежние
действия используют сценарный план в общем движке. Генерация неописанных в сценарии предметов, NPC, мест, магия, бой, произвольные изменения мира
и долговременная память не реализованы. Смысловую уместность способа оценивает ИИ; сервер гарантирует
формальные ограничения сцены. Качество новых способов с реальной моделью требует отдельного прогона.

Команда сразу подтверждается; участники видят «Ведущий думает…». Владелец действия может отменить запрос;
ведущий комнаты — перезапустить эпизод или пропустить ход отключённого игрока. Запрос не привязан к сокету:
после переподключения виден его текущий статус, повторной отправки в Claude нет. Ошибка, отмена и таймаут
45 секунд сохраняют ход, ресурсы и журнал. Запоздалый ответ не применяется после отмены/перезапуска.
Черновик действия в открытом экране сохраняется для повторной отправки после ошибки.

Для контроля расхода: один запрос на отправленное действие, до 1600 выходных токенов, не более четырёх
параллельных запросов на экземпляр сервера, без автоматических повторов. При занятых слотах сервер
отклоняет новое действие до вызова API; попытку можно повторить вручную. В контекст входят состояние и
последние 16 записей журнала с ограничением длины. Долговременная память ещё не реализована.
Квота посредника может учитывать коэффициенты моделей; событие `ai_usage` содержит входные/выходные
токены по ответу API, а не остаток пакета AI STAR. Общий лимит расходов по ключу этот прототип не обеспечивает.
Ключи, реплики, промпты и ответы не включаются в диагностику. Не включайте DEBUG/TRACE-логирование Koog,
которое может содержать промпты. Ошибки провайдера показываются игрокам без исходных HTTP-тел.
Событие `ai_request_failed` содержит `reason`, `request_id`, `elapsed_ms`, а при HTTP-ошибке — `http_status`
и известный тип ошибки провайдера из разрешённого списка. Исходные сообщения исключений и HTTP-тела
не сохраняются. Ошибки разбора различают отсутствие JSON, обязательных полей, неизвестное действие и
неподходящую реплику. Usage сохраняется и для полученного, но отклонённого ответа.

В реальной пробе AI STAR ответ был получен, но не прошёл проверку игрового JSON. Поэтому формат явно
закреплён также в системной инструкции; корректный JSON в единственном Markdown-блоке допускается,
но весь объект всё равно проходит проверку полей и ограничений. На один нативный параметр schema не полагаемся.

Тесты используют подставного ведущего, MockEngine для реального клиента Koog и TCP с двумя KMP-клиентами;
платные запросы и секреты для них не нужны. 13.11.2026 проверен реальный вызов AI STAR с действием осмотра:
после уточнения формата получен валидный ответ, сервер применил действие и передал ход примерно за 5,2 секунды.
Это проверка подключения и одного действия, а не оценка качества полного приключения.

13.11.2026 также проверены три свободных действия с реальным Claude через AI STAR и двумя
WebSocket-клиентами в одной комнате: передача инструментов Рин → Мира (4,3 с), закрепление
верёвки Мирой (4,7 с), подъём Рин снаружи по закреплённой верёвке (5,3 с). Инструменты перенесены
без копирования, верёвка снята со снаряжения и закреплена в состоянии сцены. Для подъёма модель
предложила свободный план с проверкой ловкости сложности 8; серверный d20 = 20, модификатор +3,
герой перемещён в фонарную комнату, запас проверок уменьшен с 3 до 2. План до броска и итоговое
состояние совпали у обоих клиентов. Выполнены ровно три обращения к модели, без повторов.
Это подтверждение трёх способов и успешной ветки реального броска; неудачная ветка проверена
автоматическими тестами, качество всей импровизации этим прогоном не оценивалось.

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
