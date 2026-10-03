package com.gridhelper.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide status of the assistant service, observed by the settings screen. */
object AssistantState {

    enum class Mode { STOPPED, RUNNING, PAUSED }

    private val modeState = MutableStateFlow(Mode.STOPPED)
    val mode: StateFlow<Mode> = modeState.asStateFlow()

    private val messageState = MutableStateFlow<String?>(null)
    /** Last noteworthy event (e.g. "capture stopped by the system"). */
    val message: StateFlow<String?> = messageState.asStateFlow()

    internal fun setMode(mode: Mode) {
        modeState.value = mode
    }

    fun post(message: String?) {
        messageState.value = message
    }
}
