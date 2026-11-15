package ru.markn.dnd.server

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.network.*

class CharacterRoomsTest {
    @Test fun presetsCanBeChosenRegardlessOfJoinOrderButExplicitChoicesAreProtected() = runBlocking {
        val rooms = Rooms(presentationEnabled = false)
        val a = rooms.join(ClientMessage.Join("A"))
        val b = rooms.join(ClientMessage.Join("B", a.welcome.room.code))
        rooms.join(ClientMessage.Join("C", a.welcome.room.code))
        val selected = rooms.command(b.connection, ClientMessage.Command(1, NetworkAction.SELECT_CHARACTER, character = ReadyCharacters.rin))
        assertNull(selected.error)
        assertEquals(ReadyCharacters.rin, selected.room.members[1].player.hero.profile)
        assertEquals(ReadyCharacters.mira, selected.room.members[0].player.hero.profile)
        assertTrue(selected.room.members[1].characterConfirmed)
        val rejected = rooms.command(a.connection, ClientMessage.Command(1, NetworkAction.SELECT_CHARACTER, character = ReadyCharacters.rin))
        assertNotNull(rejected.error)
        assertEquals(selected.room, rejected.room)
        val mira = rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.SELECT_CHARACTER, character = ReadyCharacters.mira))
        assertNull(mira.error)
        assertEquals(3, mira.room.members.map { it.player.hero.name }.distinct().size)
    }
    @Test fun onlyOwnValidBuildCanBeSelectedAndItSurvivesScenarioChangeReconnectAndRestart() = runBlocking {
        val rooms = Rooms(presentationEnabled = false) { AdventureEngine(DiceRoller { 1 }) }
        val a = rooms.join(ClientMessage.Join("A"))
        val b = rooms.join(ClientMessage.Join("B", a.welcome.room.code))
        val profile = ReadyCharacters.rin.copy(name = "Ника", race = CharacterRace.HALFLING, avatarId = "halfling_rogue")
        val invalid = ClientMessage.Command(1, NetworkAction.SELECT_CHARACTER,
            character = profile.copy(scores = profile.scores + (Ability.DEXTERITY to 30)))
        val original = a.connection.updates.value
        assertNotNull(rooms.command(a.connection, invalid).error)
        assertEquals(original, a.connection.updates.value)
        val chosen = rooms.command(a.connection, invalid.copy(requestId = 2, character = profile, targetPlayerId = b.welcome.playerId))
        assertNull(chosen.error)
        assertEquals(profile, chosen.room.members.first().player.hero.profile)
        assertEquals(ReadyCharacters.mira, chosen.room.members[1].player.hero.profile)
        val duplicate = rooms.command(b.connection, ClientMessage.Command(1, NetworkAction.SELECT_CHARACTER, character = profile.copy(name = "ника")))
        assertNotNull(duplicate.error)
        val selected = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.SELECT_SCENARIO, scenarioId = FloodedArchive.ID))
        assertEquals(profile, selected.room.members.first().player.hero.profile)
        assertEquals(FloodedArchive.definition.startingItems, selected.room.members.first().player.hero.inventory)
        rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.SELECT_SCENARIO, scenarioId = Lighthouse.ID))
        rooms.command(a.connection, ClientMessage.Command(5, NetworkAction.START))
        assertNotNull(rooms.command(a.connection, ClientMessage.Command(6, NetworkAction.SELECT_CHARACTER, character = ReadyCharacters.bran)).error)
        val game = a.connection.updates.value.adventure!!
        val pending = rooms.command(a.connection, ClientMessage.Command(7, NetworkAction.SUBMIT, turnId = game.turnId, text = Lighthouse.CLIMB.text)).room.adventure!!
        val roll = ClientMessage.Command(8, NetworkAction.ROLL, checkId = pending.pendingCheck!!.id, useRacialTalent = true)
        assertNotNull(rooms.command(b.connection, roll).error)
        assertNull(rooms.command(a.connection, roll).error)
        val rolled = a.connection.updates.value.adventure!!
        assertTrue(rolled.players.first().hero.racialTalentUsed)
        assertEquals(4, rolled.journal.mapNotNull { it.roll }.single().modifier)
        assertNotNull(rooms.command(a.connection, roll.copy(requestId = 9)).error)
        rooms.disconnect(a.connection)
        val resumed = rooms.join(ClientMessage.Join("ignored", a.welcome.room.code, a.welcome.resumeToken))
        assertEquals(profile, resumed.welcome.room.adventure!!.players.first().hero.profile)
        assertTrue(resumed.welcome.room.adventure!!.players.first().hero.racialTalentUsed)
        val restarted = rooms.command(b.connection, ClientMessage.Command(10, NetworkAction.RESTART, sessionId = game.sessionId)).room.adventure!!
        assertEquals(profile, restarted.players.first().hero.profile)
        assertFalse(restarted.players.first().hero.racialTalentUsed)
        assertEquals(restarted.players.first().hero.maxHealth, restarted.players.first().hero.health)
    }

    @Test fun fourRealClientsReceiveCustomCharacterAndIdenticalServerTalentRoll() = runBlocking {
        val rooms = Rooms(presentationEnabled = false) { AdventureEngine(DiceRoller { 8 }) }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { gameServer(rooms) }.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val clients = List(4) { NetworkGameClient(scope) }
        try {
            val address = "ws://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            var code = ""
            clients.forEachIndexed { index, client ->
                client.connect(address, "Игрок $index", code)
                val joined = withTimeout(15_000) { client.state.first { it.status == ConnectionStatus.CONNECTED } }
                code = joined.room!!.code
            }
            val a = clients.first()
            withTimeout(10_000) { a.state.first { it.room!!.members.size == 4 } }
            val custom = ReadyCharacters.bran.copy(name = "Собственный герой", race = CharacterRace.HALFLING,
                scores = ReadyCharacters.rin.scores, avatarId = "halfling_warrior")
            a.command(NetworkAction.SELECT_CHARACTER, character = custom)
            withTimeout(10_000) { a.state.first { !it.busy && it.room!!.members.first().player.hero.profile == custom } }
            a.command(NetworkAction.START)
            val started = withTimeout(10_000) { a.state.first { !it.busy && it.room?.adventure != null } }.room!!.adventure!!
            assertEquals(4, started.players.size)
            assertEquals(custom, started.players.first().hero.profile)
            a.command(NetworkAction.SUBMIT, turnId = started.turnId, text = Lighthouse.CLIMB.text)
            val check = withTimeout(10_000) { a.state.first { !it.busy && it.room!!.adventure!!.pendingCheck != null } }.room!!.adventure!!.pendingCheck!!
            a.reconnect()
            withTimeout(15_000) { a.state.first { it.status == ConnectionStatus.CONNECTED } }
            a.command(NetworkAction.ROLL, checkId = check.id, useRacialTalent = true)
            val result = withTimeout(10_000) { a.state.first { !it.busy && it.room!!.adventure!!.pendingCheck == null && it.room!!.adventure!!.turnOrder.turnNumber == 2L } }.room!!
            for (client in clients) {
                withTimeout(10_000) { client.state.first { it.room!!.revision == result.revision } }
                assertEquals(result, client.state.value.room)
            }
            val roll = result.adventure!!.journal.mapNotNull { it.roll }.single()
            assertEquals(CheckBonuses(2, 0, 2), roll.bonuses)
            assertEquals(12, roll.total)
            assertTrue(result.adventure!!.players.first().hero.racialTalentUsed)
        } finally {
            clients.forEach { it.close() }; scope.cancel(); server.stop(100, 1_000)
        }
    }
}
