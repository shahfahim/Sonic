package com.sonic.share.feature_discovery.wifidirect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class P2pState {
    data object Disconnected : P2pState()
    data object CreatingGroup : P2pState()
    data class Connecting(val targetSsid: String = "") : P2pState()
    data class Connected(
        val isOwner: Boolean,
        val ownerIp: String,
        val ssid: String = "",
        val passphrase: String = ""
    ) : P2pState()
    data class Failed(val reason: String) : P2pState()
}

typealias P2pConnectionState = P2pState

sealed class P2pEvent {
    data object CreatingGroup : P2pEvent()
    data class Connecting(val ssid: String = "") : P2pEvent()
    data class GroupFormed(
        val isOwner: Boolean,
        val ownerIp: String,
        val ssid: String = "",
        val passphrase: String = ""
    ) : P2pEvent()
    data class Connected(
        val isOwner: Boolean,
        val ownerIp: String,
        val ssid: String = "",
        val passphrase: String = ""
    ) : P2pEvent()
    data object Disconnected : P2pEvent()
    data class Failure(val reason: String) : P2pEvent()
}

class P2pStateMachine(initialState: P2pState = P2pState.Disconnected) {
    private val _stateFlow = MutableStateFlow(initialState)
    val stateFlow: StateFlow<P2pState> = _stateFlow.asStateFlow()

    var currentState: P2pState
        get() = _stateFlow.value
        private set(value) {
            _stateFlow.value = value
        }

    var onStateChanged: ((P2pState) -> Unit)? = null

    fun transition(event: P2pEvent): P2pState {
        val nextState = when (event) {
            is P2pEvent.CreatingGroup -> P2pState.CreatingGroup
            is P2pEvent.Connecting -> P2pState.Connecting(event.ssid)
            is P2pEvent.GroupFormed -> P2pState.Connected(
                isOwner = event.isOwner,
                ownerIp = event.ownerIp,
                ssid = event.ssid,
                passphrase = event.passphrase
            )
            is P2pEvent.Connected -> P2pState.Connected(
                isOwner = event.isOwner,
                ownerIp = event.ownerIp,
                ssid = event.ssid,
                passphrase = event.passphrase
            )
            is P2pEvent.Disconnected -> P2pState.Disconnected
            is P2pEvent.Failure -> P2pState.Failed(event.reason)
        }
        currentState = nextState
        onStateChanged?.invoke(nextState)
        return nextState
    }

    fun reset() {
        currentState = P2pState.Disconnected
        onStateChanged?.invoke(P2pState.Disconnected)
    }
}
