package com.example.ar_glass_plus.display

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.view.Display
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ar_glass_plus.ui.theme.ARglassplusTheme

/**
 * Independent Compose UI rendered on the glasses display. Launched via
 * ActivityOptions.setLaunchDisplayId — this activity runs on the external
 * display, not on the tablet. Finishes itself when that display is removed
 * (glasses unplugged) instead of being stranded on the built-in display.
 */
class ExternalDisplayActivity : ComponentActivity() {
    private val displayManager by lazy {
        getSystemService(DisplayManager::class.java)
    }
    private var hostedDisplayId: Int = Display.INVALID_DISPLAY

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == hostedDisplayId) {
                Log.i(TAG, "host display $displayId removed, finishing")
                finish()
            }
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hostedDisplayId = display?.displayId ?: Display.INVALID_DISPLAY
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        setContent {
            ARglassplusTheme {
                val display = display
                GlassesScreen(
                    displayId = display?.displayId ?: hostedDisplayId,
                    width = display?.width ?: 0,
                    height = display?.height ?: 0,
                    refreshRate = display?.refreshRate ?: 0f,
                )
            }
        }
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ExtDisplayAct"
    }
}

@Composable
fun GlassesScreen(
    displayId: Int,
    width: Int,
    height: Int,
    refreshRate: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101418)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "AR-glass-plus",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Text(
                text = "External Display Active",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF8AB4F8),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "displayId = $displayId",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFB0BEC5),
            )
            Text(
                text = "$width x $height @ ${"%.1f".format(refreshRate)}Hz",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFB0BEC5),
            )
        }
    }
}
