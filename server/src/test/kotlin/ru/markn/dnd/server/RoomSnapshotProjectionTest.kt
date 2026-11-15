package ru.markn.dnd.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import ru.markn.dnd.adventure.*
import ru.markn.dnd.combat.*
import ru.markn.dnd.network.*

class RoomSnapshotProjectionTest {
    @Test fun publicationKeepsCanonicalBattleHistoriesAndFullLogWhileJournalCarriesNewEvents() = runBlocking<Unit> {
        var diceCalls = 0
        val rooms = Rooms(presentationEnabled = false) { AdventureEngine(DiceRoller { diceCalls++; 1 }) }
        val a = rooms.joinReady(ClientMessage.Join("A"))
        rooms.joinReady(ClientMessage.Join("B", a.welcome.room.code))
        rooms.command(a.connection, ClientMessage.Command(2, NetworkAction.SELECT_SCENARIO, scenarioId = ArchiveDemo.ID))
        val started = rooms.command(a.connection, ClientMessage.Command(3, NetworkAction.START)).room.adventure!!
        val reply = rooms.command(a.connection, ClientMessage.Command(4, NetworkAction.SUBMIT,
            turnId = started.turnId, text = "Всем занять оборону"))
        assertNull(reply.error)
        val canonical = a.connection.room.adventure!!
        val authorityBattle = canonical.combat!!
        val publicBattle = reply.room.adventure!!.combat!!
        assertTrue(authorityBattle.events.isNotEmpty())
        assertTrue(authorityBattle.diceEvents.isNotEmpty())
        assertTrue(publicBattle.events.isEmpty())
        assertTrue(publicBattle.diceEvents.isEmpty())
        assertEquals(authorityBattle.log, publicBattle.log)
        assertEquals(canonical.journal.takeLast(200), reply.room.adventure!!.journal)
        assertTrue(reply.room.adventure!!.journal.any { it.combatEvents.isNotEmpty() })
        assertTrue(reply.room.adventure!!.journal.any { it.diceEvents.isNotEmpty() })

        val guarded = rooms.command(a.connection, ClientMessage.Command(5, NetworkAction.COMBAT,
            turnId = canonical.turnId, combatAction = DemoAction.GUARD))
        assertNull(guarded.error)
        val after = a.connection.room.adventure!!
        assertTrue(after.combat!!.events.size > authorityBattle.events.size)
        assertEquals(authorityBattle.diceEvents, after.combat!!.diceEvents)
        val newJournal = guarded.room.adventure!!.journal.filter { it.id > canonical.journal.last().id }
        assertEquals(1, newJournal.flatMap { it.combatEvents }.count { it.kind == CombatEventKind.GUARD })
        assertEquals(after.combat!!.log, guarded.room.adventure!!.combat!!.log)
        assertTrue(guarded.room.adventure!!.combat!!.events.isEmpty())
        val diceBeforeResume = diceCalls
        rooms.disconnect(a.connection)
        val resumed = rooms.join(ClientMessage.Join("ignored", a.welcome.room.code, a.welcome.resumeToken))
        assertSame(after, a.connection.room.adventure)
        assertEquals(after.publicRoomSnapshot(), resumed.welcome.room.adventure)
        assertEquals(diceBeforeResume, diceCalls)
        assertTrue(resumed.welcome.room.adventure!!.combat!!.events.isEmpty())
        assertTrue(resumed.welcome.room.adventure!!.combat!!.diceEvents.isEmpty())
    }

    @Test fun realClientsReceiveJournalEventsAndReconnectWithoutBattleHistoryDuplicates() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val rooms = Rooms(presentationEnabled = false) { AdventureEngine(DiceRoller { 1 }) }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { gameServer(rooms) }.start()
        val clients = List(2) { NetworkGameClient(scope) }
        suspend fun await(client: NetworkGameClient, condition: (RoomView) -> Boolean): NetworkState =
            withTimeout(10_000) { client.state.first { !it.busy && it.room?.let(condition) == true } }
        fun ids(state: AdventureState) = state.journal.flatMap { entry ->
            entry.combatEvents.mapIndexed { index, _ -> "${entry.id}:combat:$index" }
        }
        try {
            val address = "ws://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            val a = clients[0]; val b = clients[1]
            a.connect(address, "A")
            val joined = await(a) { it.members.size == 1 }
            b.connect(address, "B", joined.room!!.code)
            await(a) { it.members.size == 2 }; await(b) { it.members.size == 2 }
            a.command(NetworkAction.SELECT_SCENARIO, scenarioId = ArchiveDemo.ID)
            await(a) { it.scenarioId == ArchiveDemo.ID }
            confirmNetworkCharacters(*clients.toTypedArray())
            a.command(NetworkAction.START)
            val started = await(a) { it.adventure != null }.room!!.adventure!!
            a.command(NetworkAction.SUBMIT, turnId = started.turnId, text = "Всем занять оборону")
            val before = await(a) { it.adventure?.combat != null }.room!!.adventure!!
            await(b) { it.adventure == before }
            assertTrue(before.combat!!.events.isEmpty())
            assertTrue(before.combat!!.diceEvents.isEmpty())
            assertEquals(1, before.journal.flatMap { it.combatEvents }.count { it.kind == CombatEventKind.INITIATIVE })
            val baseline = ids(before)
            a.command(NetworkAction.COMBAT, turnId = before.turnId, combatAction = DemoAction.GUARD)
            val after = await(a) { it.adventure?.turnId != before.turnId }.room!!.adventure!!
            await(b) { it.adventure == after }
            assertTrue(after.combat!!.events.isEmpty())
            assertTrue(after.combat!!.diceEvents.isEmpty())
            assertTrue(ids(after).containsAll(baseline))
            assertEquals(1, ids(after).count { it !in baseline })
            assertEquals(1, after.journal.flatMap { it.combatEvents }.count { it.kind == CombatEventKind.GUARD })
            assertTrue(after.combat!!.log.size > before.combat!!.log.size)
            val generation = a.state.value.connectionGeneration
            a.reconnect()
            val resumed = withTimeout(10_000) { a.state.first {
                it.status == ConnectionStatus.CONNECTED && it.connectionGeneration > generation
            } }
            assertEquals(after, resumed.room!!.adventure)
            assertEquals(ids(after), ids(resumed.room!!.adventure!!))
            val wire = GameWireJson.encodeToString<ServerMessage>(ServerMessage.Snapshot(resumed.room!!))
            val decoded = (GameWireJson.decodeFromString<ServerMessage>(wire) as ServerMessage.Snapshot).room
            assertEquals(resumed.room, decoded)
            assertEquals(after.combat!!.log, decoded.adventure!!.combat!!.log)
        } finally { clients.forEach { it.close() }; scope.cancel(); server.stop(100, 1_000) }
    }
}
