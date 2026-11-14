package ru.markn.dnd.network

import kotlin.test.*
import ru.markn.dnd.adventure.*

class ProtocolTest {
    @Test fun outcomeNarrationStatusRoundTripsForReactionAndEpilogue() {
        for (kind in listOf(NarrationKind.REACTION, NarrationKind.EPILOGUE)) {
            val pending = PendingNarration("narration-id", "actor", TurnId(1, 2), kind)
            assertEquals(pending, GameWireJson.decodeFromString<PendingNarration>(GameWireJson.encodeToString(pending)))
        }
    }
    @Test fun selectedActorAndPendingHandoffRoundTrip() {
        val engine = AdventureEngine(DiceRoller { 10 })
        val start = engine.newGame()
        val actor = start.players[2].id
        val selected = start.copy(turnOrder = start.turnOrder.next(actor))
        val pending = engine.submit(selected, actor, selected.turnId, "Поднимаюсь",
            GameMasterDecision(Lighthouse.CLIMB, "Попытка", nextPlayerId = start.players[1].id))
        val restored = GameWireJson.decodeFromString<AdventureState>(GameWireJson.encodeToString(pending))
        assertEquals(pending, restored)
        assertEquals(actor, restored.activePlayer.id)
        val check = assertNotNull(restored.pendingCheck)
        assertEquals(start.players[1].id, check.nextPlayerId)
        assertEquals(start.players[1].id, engine.roll(restored, actor, check.id).activePlayer.id)
    }

    @Test fun gameStateAndCommandsRoundTripWithCyrillicAndEscapedText() {
        val engine = AdventureEngine(DiceRoller { 10 })
        val game = engine.newGame()
        val check = engine.submit(game, game.activePlayer.id, game.turnId, Lighthouse.CLIMB.text)
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
