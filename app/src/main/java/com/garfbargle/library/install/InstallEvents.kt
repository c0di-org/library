package com.garfbargle.library.install

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface InstallEvent {
    data class Success(val packageName: String) : InstallEvent
    data class Failure(val packageName: String, val message: String) : InstallEvent
}

object InstallEvents {
    private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    fun publish(event: InstallEvent) {
        _events.tryEmit(event)
    }
}
