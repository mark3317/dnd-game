package ru.markn.dnd.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.russhwolf.settings.Settings
import ru.markn.dnd.adventure.CharacterLibrary

class NetworkViewModel : ViewModel() {
    private val settings = runCatching { Settings() }.getOrNull()
    val client = NetworkGameClient(viewModelScope, settings?.let(::SettingsResumeSessionStore) ?: UnavailableResumeSessionStore())
    val characters = CharacterLibrary(settings)
    override fun onCleared() { client.close() }
}
