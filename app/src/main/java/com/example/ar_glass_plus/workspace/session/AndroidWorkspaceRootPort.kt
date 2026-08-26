package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.root.RootShell

class AndroidWorkspaceRootPort(
    private val shell: RootShell,
) : WorkspaceRootPort {
    override suspend fun isAvailable(): Boolean = shell.isAvailable()
}
