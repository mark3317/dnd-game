package ru.markn.dnd.network

import kotlin.test.*
import ru.markn.dnd.adventure.*

class ProtocolTest {
    @Test fun gameStateAndCommandsRoundTripWithCyrillicAndEscapedText() {
        val engine = AdventureEngine(DiceRoller { 10 })
        val game = engine.newGame()
        val check = engine.submit(game, game.activePlayer.id, game.turnId, DemoAction.CLIMB.text)
        val room = RoomView("ABC123", game.activePlayer.id, game.players.map { RoomMember(it, true) }, check, 2)
        val snapshot: ServerMessage = ServerMessage.Snapshot(room)
        assertEquals(snapshot, GameWireJson.decodeFromString<ServerMessage>(GameWireJson.encodeToString(snapshot)))
        val command: ClientMessage = ClientMessage.Command(1, NetworkAction.SUBMIT, game.turnId, text = "Привет \"друзья\"\n")
        assertEquals(command, GameWireJson.decodeFromString<ClientMessage>(GameWireJson.encodeToString(command)))
    }

    @Test fun addressesNormalizeWithoutAcceptingCredentialsOrQuery() {
        assertEquals("ws://localhost:8080/play", serverWebSocketUrl("http://localhost:8080"))
        assertEquals("wss://example.org/play", serverWebSocketUrl("https://example.org/"))
        for (address in listOf("ftp://example.org", "ws://user:pass@localhost:8080", "ws://localhost:8080?token=secret", "ws://localhost:8080/wrong")) {
            assertFailsWith<IllegalArgumentException> { serverWebSocketUrl(address) }
        }
    }
}
