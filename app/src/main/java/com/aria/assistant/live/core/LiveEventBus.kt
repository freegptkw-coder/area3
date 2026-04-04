package com.aria.assistant.live.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

object LiveEventBus {
    val events = MutableSharedFlow<VoiceSessionEvent>(extraBufferCapacity = 64)
    val state = MutableStateFlow(VoiceSessionState.IDLE)
    
    // Commands from UI to Service
    val commands = MutableSharedFlow<String>(extraBufferCapacity = 16)
}