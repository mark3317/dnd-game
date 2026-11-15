package ru.markn.dnd.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.URLProtocol
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/** Real CIO/SSE on loopback: Ktor MockEngine does not implement SSECapability. */
internal class LocalTextProvider(val client: HttpClient, private val stop: () -> Unit) : AutoCloseable {
    override fun close() { client.close(); stop() }

    companion object {
        suspend fun start(handler: suspend (ApplicationCall) -> Unit): LocalTextProvider {
            val server = embeddedServer(io.ktor.server.cio.CIO, host = "127.0.0.1", port = 0) {
                routing {
                    post("/v1/chat/completions") { handler(call) }
                    post("/v1/messages") { handler(call) }
                }
            }.start()
            val port = server.engine.resolvedConnectors().first().port
            val redirect = createClientPlugin("LocalTextProvider") {
                onRequest { request, _ ->
                    request.url.protocol = URLProtocol.HTTP
                    request.url.host = "127.0.0.1"
                    request.url.port = port
                }
            }
            return LocalTextProvider(HttpClient(CIO) { install(redirect) }) { server.stop(100, 1000) }
        }
    }
}
