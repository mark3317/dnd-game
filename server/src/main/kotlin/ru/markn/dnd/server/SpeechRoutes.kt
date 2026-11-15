package ru.markn.dnd.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.cors.routing.CORS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import ru.markn.dnd.adventure.SpeechRequest

/** The GPU worker is loopback-only. Clients use their existing game server origin. */
fun Route.speechRoutes(http: HttpClient, worker: String = "http://127.0.0.1:8091", workerTimeoutMillis: Long = 8_000) {
    require(workerTimeoutMillis > 0)
    val json = Json { ignoreUnknownKeys = false }
    val localAddresses = java.net.NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }.map { it.hostAddress }.toSet() + setOf("localhost", "127.0.0.1", "::1")
    val configuredOrigins = System.getenv("DND_WEB_ORIGINS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    route("/speech") {
    install(CORS) {
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
        allowOrigins { origin -> origin in configuredOrigins || runCatching { Url(origin).host in localAddresses }.getOrDefault(false) }
    }
    suspend fun proxy(call: io.ktor.server.application.ApplicationCall, path: String, body: String? = null) {
        try {
            val result = speechWorkerResponse(http, worker, path, body, workerTimeoutMillis)
            if (result == null) call.respondText("{\"status\":\"unavailable\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            else call.respondBytes(result.bytes, result.contentType ?: ContentType.Application.Json, result.status)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { call.respondText("{\"status\":\"unavailable\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable) }
    }
    get("/health") { proxy(call, "health") }
    post("/prepare") {
        val body = call.receiveText()
        val request = if (body.length <= 5000) runCatching { json.decodeFromString<SpeechRequest>(body) }.getOrNull() else null
        if (request == null || request.text.isBlank() || request.text.length > 600 ||
            request.voice !in setOf("narrator", "bran", "rin", "mira", "lada", "archivist", "courier")) {
            call.respond(HttpStatusCode.BadRequest); return@post
        }
        proxy(call, "prepare", body)
    }
    get("/status/{id}") {
        val id = call.parameters["id"].orEmpty()
        if (!id.matches(Regex("[a-f0-9]{64}"))) { call.respond(HttpStatusCode.NotFound); return@get }
        proxy(call, "status/$id")
    }
    get("/audio/{file}") {
        val file = call.parameters["file"].orEmpty()
        if (!file.matches(Regex("[a-f0-9]{64}\\.wav"))) { call.respond(HttpStatusCode.NotFound); return@get }
        proxy(call, "audio/$file")
    }
    }
}

internal data class SpeechProxyResponse(val bytes: ByteArray, val contentType: ContentType?, val status: HttpStatusCode)

/** Only this operation's timeout becomes unavailable; parent cancellation must propagate. */
internal suspend fun speechWorkerResponse(http: HttpClient, worker: String, path: String,
    body: String?, timeoutMillis: Long): SpeechProxyResponse? = withTimeoutOrNull(timeoutMillis) {
    val response = if (body == null) http.get("$worker/speech/$path")
        else http.post("$worker/speech/$path") { contentType(ContentType.Application.Json); setBody(body) }
    // Include body transfer in the deadline: a stalled WAV must not hold a request indefinitely.
    SpeechProxyResponse(response.readRawBytes(), response.contentType(), response.status)
}
