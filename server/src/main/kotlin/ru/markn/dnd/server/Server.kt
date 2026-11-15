package ru.markn.dnd.server

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.serialization.kotlinx.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import ru.markn.dnd.network.*
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    if ("--export-speech" in args) { exportSpeech(args.getOrNull(args.indexOf("--export-speech") + 1) ?: error("Output path required")); return }
    if ("--probe-ai" in args) { probeGameMaster(); return }
    if ("--probe-ai-region" in args) { probeGameMasterCases(alternativeRegion = true); return }
    if ("--probe-ai-current" in args) { probeGameMasterCases(); return }
    val port = System.getenv("DND_SERVER_PORT")?.toIntOrNull() ?: 8080
    println("dnd-game: WebSocket /play, port=$port; rooms live in memory")
    embeddedServer(CIO, host = "0.0.0.0", port = port) {
        val narrator = ClaudeSettings.fromEnvironment()?.let { ClaudeGameMaster(it) }
        monitor.subscribe(ApplicationStopped) { narrator?.close() }
        gameServer(Rooms(gameMaster = narrator, aiScope = this))
    }.start(wait = true)
}

fun Application.gameServer(rooms: Rooms = Rooms()) {
    val speechHttp = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
    monitor.subscribe(ApplicationStopped) { speechHttp.close() }
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(GameWireJson)
        pingPeriod = 20.seconds
        timeout = 30.seconds
        maxFrameSize = 16 * 1024
    }
    routing {
        speechRoutes(speechHttp)
        get("/health") { call.respondText("dnd-game protocol=$PROTOCOL_VERSION") }
        webSocket("/play") {
            var connection: Rooms.Connection? = null
            try {
                val join = withTimeout(15_000) { receiveDeserialized<ClientMessage>() } as? ClientMessage.Join
                    ?: error("Сначала подключитесь к комнате.")
                val admission = rooms.join(join)
                connection = admission.connection
                sendSerialized<ServerMessage>(admission.welcome)
                val updates = launch {
                    admission.connection.updates.collect { sendSerialized<ServerMessage>(ServerMessage.Snapshot(it)) }
                }
                val replacement = launch {
                    admission.connection.replaced.await()
                    close(CloseReason(CloseReason.Codes.NORMAL, "Connection replaced"))
                }
                try {
                    while (true) {
                        val command = receiveDeserialized<ClientMessage>() as? ClientMessage.Command
                            ?: error("Повторное подключение недоступно.")
                        sendSerialized<ServerMessage>(rooms.command(admission.connection, command))
                    }
                } finally { updates.cancel(); replacement.cancel() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                sendSerialized<ServerMessage>(ServerMessage.Error(e.message ?: "Неверные данные."))
            } catch (e: IllegalStateException) {
                sendSerialized<ServerMessage>(ServerMessage.Error(e.message ?: "Команда недоступна."))
            } catch (_: Exception) {
                // Transport errors and malformed frames must not leak tokens or payloads.
            } finally {
                withContext(NonCancellable) {
                    connection?.let { rooms.disconnect(it) }
                    close(CloseReason(CloseReason.Codes.NORMAL, "Session closed"))
                }
            }
        }
    }
}
