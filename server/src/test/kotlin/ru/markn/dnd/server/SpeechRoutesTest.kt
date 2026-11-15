package ru.markn.dnd.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SpeechRoutesTest {
    @Test fun browserPreflightAndRequestsUseOnlyTheLoopbackWorker() = runBlocking {
        val forwarded = mutableListOf<String>()
        val worker = HttpClient(MockEngine { request ->
            forwarded += request.url.toString()
            respond("{\"status\":\"ready\"}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { routing { speechRoutes(worker) } }.start()
        val client = HttpClient(ClientCIO)
        try {
            val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            val preflight = client.options("$base/speech/prepare") {
                header(HttpHeaders.Origin, "http://localhost:8081")
                header(HttpHeaders.AccessControlRequestMethod, "POST")
                header(HttpHeaders.AccessControlRequestHeaders, "content-type")
            }
            assertEquals("http://localhost:8081", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
            val result = client.post("$base/speech/prepare") {
                contentType(ContentType.Application.Json)
                setBody("{\"text\":\"Вода прибывает.\",\"voice\":\"narrator\"}")
            }
            assertEquals(HttpStatusCode.OK, result.status)
            assertEquals(listOf("http://127.0.0.1:8091/speech/prepare"), forwarded)
            assertEquals(HttpStatusCode.BadRequest, client.post("$base/speech/prepare") {
                contentType(ContentType.Application.Json); setBody("{\"text\":\"Текст\",\"voice\":\"unknown\"}")
            }.status)
            assertEquals(1, forwarded.size)
            val denied = client.options("$base/speech/prepare") {
                header(HttpHeaders.Origin, "https://unrelated.example")
                header(HttpHeaders.AccessControlRequestMethod, "POST")
            }
            assertNull(denied.headers[HttpHeaders.AccessControlAllowOrigin])
        } finally { client.close(); server.stop(0, 1000); worker.close() }
    }
}
