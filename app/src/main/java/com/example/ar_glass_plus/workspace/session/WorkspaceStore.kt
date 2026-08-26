package com.example.ar_glass_plus.workspace

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class WorkspaceStore {
    private val mutableState = MutableStateFlow(WorkspaceState())

    val state: StateFlow<WorkspaceState> = mutableState.asStateFlow()

    internal fun update(transform: (WorkspaceState) -> WorkspaceState) {
        mutableState.update(transform)
    }
}
