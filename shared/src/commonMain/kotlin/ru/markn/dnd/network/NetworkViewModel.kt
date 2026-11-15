package ru.markn.dnd.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.russhwolf.settings.Settings
import ru.markn.dnd.adventure.CharacterLibrary

class NetworkViewModel : ViewModel() {
    val client = NetworkGameClient(viewModelScope)
    val characters = CharacterLibrary(runCatching { Settings() }.getOrNull())
    override fun onCleared() { client.close() }
}
