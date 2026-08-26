package com.example.ar_glass_plus.workspace

import android.content.Context
import com.example.ar_glass_plus.display.ExternalDisplayController

/**
 * Android host adapter for the workspace session: launches the rendered host
 * (RenderDisplayActivity) onto the connected output display using the
 * application context. stop() is a no-op by design — the controller's own
 * state change makes the host activity observe the display mismatch and
 * finish itself.
 */
class AndroidWorkspaceHostPort(
    context: Context,
    private val displayController: ExternalDisplayController,
) : WorkspaceHostPort {

    private val applicationContext = context.applicationContext

    override fun start(outputDisplayId: Int): Boolean =
        displayController.launchRenderDisplay(applicationContext, outputDisplayId)

    override fun stop() = Unit
}
