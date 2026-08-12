package com.example.ar_glass_plus

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ar_glass_plus.display.ExternalDisplayController
import com.example.ar_glass_plus.display.ExternalDisplayState
import com.example.ar_glass_plus.input.CursorController
import com.example.ar_glass_plus.input.RelativeTouchpadController
import com.example.ar_glass_plus.input.ShellInputInjector
import com.example.ar_glass_plus.render.api.RenderDisplaySession
import com.example.ar_glass_plus.render.geometry.AspectMode
import com.example.ar_glass_plus.render.geometry.ContentRotation
import com.example.ar_glass_plus.render.geometry.RenderMode
import com.example.ar_glass_plus.root.RootShellImpl
import com.example.ar_glass_plus.ui.theme.ARglassplusTheme
import kotlinx.coroutines.launch

/**
 * Fixed dashboard: LEFT sidebar (connection, render controls, cursor tools)
 * + RIGHT full-size relative touchpad. The page MUST NOT scroll — the touchpad
 * owns all pointer changes; nothing here is a scroll container.
 */
class MainActivity : ComponentActivity() {
    private val displayController by lazy { ExternalDisplayController(this) }
    private val shell by lazy { RootShellImpl() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ARglassplusTheme {
                val displayState by displayController.state.collectAsState()
                Dashboard(displayState = displayState, displayController = displayController)
            }
        }
    }
}

@Composable
fun Dashboard(
    displayState: ExternalDisplayState,
    displayController: ExternalDisplayController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shell = remember { RootShellImpl() }
    val scope = rememberCoroutineScope()

    val connected = displayState as? ExternalDisplayState.Connected
    val contentDisplayId by RenderDisplaySession.contentDisplayId.collectAsState()

    // Cursor + relative touchpad
    val cursorController = remember {
        CursorController(
            onContentSize = {
                RenderDisplaySession.contentSize.value
            },
        )
    }
    val touchpad = remember {
        RelativeTouchpadController(
            cursor = cursorController,
            injector = ShellInputInjector(shell),
            onContentId = { RenderDisplaySession.contentDisplayId.value },
            scope = scope,
        )
    }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var sensitivity by remember { mutableStateOf(1f) }

    LaunchedEffect(sensitivity) { cursorController.setSensitivity(sensitivity) }

    Row(modifier = modifier.fillMaxSize()) {
        // ── Left sidebar (independent scroll container) ──
        LazyColumn(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("AR-glass-plus", style = MaterialTheme.typography.titleMedium) }

            // ── Session ──
            item { Text("Session", style = MaterialTheme.typography.labelLarge) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            if (connected != null) "RayNeo ● Connected" else "RayNeo ○ Disconnected",
                            color = if (connected != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                        if (connected != null) {
                            Text(
                                "output=${connected.displayId} · ${connected.width}x${connected.height}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "content=${if (contentDisplayId >= 0) contentDisplayId else "-"}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = { displayController.launchRenderDisplay(context) },
                    enabled = connected != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("渲染 App 到眼镜") }
            }

            // ── Display ──
            item { Text("Display", style = MaterialTheme.typography.labelLarge) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { RenderDisplaySession.setMode(RenderMode.PASSTHROUGH_2D) },
                        enabled = connected != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("2D") }
                    Button(
                        onClick = { RenderDisplaySession.setMode(RenderMode.SBS_DUPLICATE) },
                        enabled = connected != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("SBS") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (mode in AspectMode.entries) {
                        OutlinedButton(
                            onClick = { RenderDisplaySession.setAspect(mode) },
                            enabled = connected != null,
                            modifier = Modifier.weight(1f),
                        ) { Text(mode.name.first().toString(), fontSize = 12.sp) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (rotation in ContentRotation.entries) {
                        OutlinedButton(
                            onClick = { RenderDisplaySession.setRotation(rotation) },
                            enabled = connected != null,
                            modifier = Modifier.weight(1f),
                        ) { Text(rotation.name.removePrefix("DEG_") + "°", fontSize = 12.sp) }
                    }
                }
            }

            // ── Input ──
            item { Text("Input", style = MaterialTheme.typography.labelLarge) }
            item {
                Button(
                    onClick = { touchpad.onBack() },
                    enabled = contentDisplayId >= 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back") }
            }
            item {
                Text("Cursor speed", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (preset in listOf(0.5f, 1.0f, 1.5f, 2.0f)) {
                        OutlinedButton(
                            onClick = { sensitivity = preset },
                            enabled = contentDisplayId >= 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("${preset}x", fontSize = 12.sp) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { cursorController.center() },
                        enabled = contentDisplayId >= 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Center", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            RenderDisplaySession.requestStop()
                            RenderDisplaySession.reset()
                        },
                        enabled = contentDisplayId >= 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop", fontSize = 12.sp) }
                }
            }

            item {
                Text(
                    "Touchpad: 单指移动 = 光标 · 点按 = 点击 · 长按拖动 = 拖拽",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Right touchpad (fixed, owns all pointer changes) ──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .onSizeChanged { padSize = it }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            when (event.type) {
                                PointerEventType.Press -> touchpad.onTouch(
                                    MotionEvent.ACTION_DOWN,
                                    change.position.x,
                                    change.position.y,
                                )
                                PointerEventType.Move -> touchpad.onTouch(
                                    MotionEvent.ACTION_MOVE,
                                    change.position.x,
                                    change.position.y,
                                )
                                PointerEventType.Release -> touchpad.onTouch(
                                    MotionEvent.ACTION_UP,
                                    change.position.x,
                                    change.position.y,
                                )
                            }
                            // Touchpad owns this gesture — never let it scroll UI.
                            change.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Touchpad",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "●",
                    fontSize = 48.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                )
            }
        }
    }

    LaunchedEffect(padSize) {
        touchpad.padWidth = padSize.width.toFloat()
        touchpad.padHeight = padSize.height.toFloat()
    }
}
