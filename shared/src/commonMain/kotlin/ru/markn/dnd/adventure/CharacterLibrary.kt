package ru.markn.dnd.adventure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.russhwolf.settings.Settings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Device-local profiles. Room credentials and adventure state never enter this storage. */
class CharacterLibrary(private val settings: Settings?) {
    private val serializer = ListSerializer(CharacterProfile.serializer())
    var characters by mutableStateOf<List<CharacterProfile>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var unreadable = false

    init {
        try {
            requireNotNull(settings)
            val stored = settings.getStringOrNull(KEY)
            if (stored != null) {
                val decoded = Json.decodeFromString(serializer, stored)
                require(decoded.size <= MAX_CHARACTERS && decoded.all { it.validationError() == null })
                require(decoded.map { it.name.lowercase() }.distinct().size == decoded.size)
                characters = decoded
            }
        } catch (_: Exception) { unreadable = true; error = "Не удалось прочитать сохранённых героев. Сохранённые данные не изменены; сохранение недоступно." }
    }

    fun save(profile: CharacterProfile): Boolean {
        profile.validationError()?.let { error = it; return false }
        if (characters.size >= MAX_CHARACTERS) { error = "Можно сохранить до $MAX_CHARACTERS своих героев."; return false }
        if ((characters + ReadyCharacters.all).any { it.name.equals(profile.name, true) }) {
            error = "Герой с таким именем уже есть. Выберите другое имя."; return false
        }
        return write(characters + profile)
    }

    fun remove(profile: CharacterProfile): Boolean = write(characters - profile)

    fun update(original: CharacterProfile, profile: CharacterProfile): Boolean {
        if (original !in characters) { error = "Герой больше не сохранён на устройстве."; return false }
        profile.validationError()?.let { error = it; return false }
        if ((characters.filter { it != original } + ReadyCharacters.all).any { it.name.equals(profile.name, true) }) {
            error = "Герой с таким именем уже есть. Выберите другое имя."; return false
        }
        return write(characters.map { if (it == original) profile else it })
    }

    private fun write(next: List<CharacterProfile>): Boolean = try {
        check(!unreadable)
        requireNotNull(settings).putString(KEY, Json.encodeToString(serializer, next))
        characters = next
        error = null
        true
    } catch (_: Exception) { error = "Не удалось сохранить героев на устройстве. Попробуйте ещё раз."; false }

    companion object {
        const val MAX_CHARACTERS = 8
        private const val KEY = "dnd.characters.v1"
    }
}
