package ru.markn.dnd.network

internal data class SessionLifecyclePresentation(
    val paused: Boolean,
    val commandsAvailable: Boolean,
    val waitingNames: List<String>,
    val closedByName: String?,
)

/** Local transport loss holds the current presentation before the server PAUSED snapshot arrives. */
internal fun NetworkState.sessionPresentation(): SessionLifecyclePresentation {
    val value = room
    val connected = status == ConnectionStatus.CONNECTED
    return SessionLifecyclePresentation(
        paused = value?.lifecycle == RoomLifecycle.PAUSED ||
            (!connected && value?.lifecycle == RoomLifecycle.ACTIVE),
        commandsAvailable = connected && !busy && !leavePending &&
            value?.lifecycle in listOf(RoomLifecycle.LOBBY, RoomLifecycle.ACTIVE),
        waitingNames = value?.waitingPlayerIds.orEmpty().mapNotNull { id ->
            value?.members?.firstOrNull { it.player.id == id }?.player?.name
        }.distinct(),
        closedByName = value?.members?.firstOrNull { it.player.id == value.closedByPlayerId }?.player?.name,
    )
}

internal data class SessionLeavePresentation(val requiresAcknowledgement: Boolean, val explanation: String)

/** A remembered ticket alone cannot tell whether the server party has already started. */
internal fun NetworkState.leavePresentation(): SessionLeavePresentation = when (room?.lifecycle) {
    RoomLifecycle.LOBBY -> SessionLeavePresentation(true,
        "Вы покинете подготовку и освободите своё место. Остальные игроки смогут продолжить собирать группу.")
    RoomLifecycle.ACTIVE, RoomLifecycle.PAUSED -> SessionLeavePresentation(true,
        "Выход завершит партию для всех игроков. Продолжить её после подтверждённого выхода будет нельзя.")
    RoomLifecycle.CLOSED, RoomLifecycle.COMPLETED -> SessionLeavePresentation(false, "Выйти из игры на рабочий стол?")
    null -> if (canResumeSession) SessionLeavePresentation(true,
        "Сначала восстановите связь с прежней комнатой. Если партия уже началась, подтверждённый выход завершит её для всех игроков.")
        else SessionLeavePresentation(false, "Выйти из игры на рабочий стол?")
}
