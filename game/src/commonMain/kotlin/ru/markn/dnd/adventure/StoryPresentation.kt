package ru.markn.dnd.adventure

/** Only these entries are candidates for subtitles and future speech synthesis. */
val JournalEntry.isSpoken: Boolean get() = speechEligible && (kind == EntryKind.NARRATOR || kind == EntryKind.NPC || kind == EntryKind.PLAYER)

val AdventureState.lastSpokenEntry: JournalEntry? get() = journal.lastOrNull { it.isSpoken }

fun AdventureState.scriptedConversation(site: Site): String =
    site.conversationOverrides.entries.firstOrNull { it.key in scene.facts }?.value ?: site.conversation

fun AdventureState.speakerName(entry: JournalEntry): String = if (entry.kind == EntryKind.NPC)
    scenario.sites.values.firstOrNull { it.npcId == entry.speakerNpcId }?.let { it.npcName ?: it.title } ?: "Собеседник"
else if (entry.kind == EntryKind.PLAYER) entry.actorName ?: players.firstOrNull { it.id == entry.playerId }?.hero?.name.orEmpty()
else "Рассказчик"
