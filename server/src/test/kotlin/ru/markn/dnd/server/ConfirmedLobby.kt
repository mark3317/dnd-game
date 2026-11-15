package ru.markn.dnd.server

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.assertNull
import ru.markn.dnd.network.*

/** Fixtures explicitly select the suggested profile, just like the lobby UI. */
internal suspend fun Rooms.joinReady(message: ClientMessage.Join): Rooms.Admission {
    val admission = join(message)
    if (message.resumeToken == null) {
        val profile = admission.welcome.room.members.single { it.player.id == admission.welcome.playerId }.player.hero.profile!!
        assertNull(command(admission.connection, ClientMessage.Command(1, NetworkAction.SELECT_CHARACTER, character = profile)).error)
    }
    return admission
}

internal suspend fun confirmNetworkCharacters(vararg clients: NetworkGameClient) {
    for (client in clients) {
        val state = withTimeout(10_000) { client.state.first { it.status == ConnectionStatus.CONNECTED && !it.busy } }
        val room = requireNotNull(state.room)
        if (room.members.single { it.player.id == state.playerId }.characterConfirmed) continue
        val profile = room.members.single { it.player.id == state.playerId }.player.hero.profile!!
        client.command(NetworkAction.SELECT_CHARACTER, character = profile)
        withTimeout(10_000) {
            client.state.first { !it.busy && it.room!!.members.single { member -> member.player.id == it.playerId }.characterConfirmed }
        }
    }
    for (client in clients) withTimeout(10_000) {
        client.state.first { it.room!!.members.all { member -> member.characterConfirmed } }
    }
}
