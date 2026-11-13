package ru.markn.dnd.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

class NetworkViewModel : ViewModel() {
    val client = NetworkGameClient(viewModelScope)
    override fun onCleared() { client.close() }
}
