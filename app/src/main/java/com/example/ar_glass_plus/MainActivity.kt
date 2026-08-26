package com.example.ar_glass_plus
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ar_glass_plus.app.AppEntry
import com.example.ar_glass_plus.app.AppRepository
import com.example.ar_glass_plus.display.ExternalDisplayController
import com.example.ar_glass_plus.display.ExternalDisplayState
import com.example.ar_glass_plus.input.touchpad.TrackpadSurface
import com.example.ar_glass_plus.render.geometry.RenderMode
import com.example.ar_glass_plus.ui.theme.ARglassplusTheme
import com.example.ar_glass_plus.workspace.ActiveApp
import com.example.ar_glass_plus.workspace.OpenAppResult
import com.example.ar_glass_plus.workspace.SpatialWindowModel
import com.example.ar_glass_plus.workspace.SpatialWindowState
import com.example.ar_glass_plus.workspace.WindowLifecycle
import com.example.ar_glass_plus.workspace.WorkspaceController
import com.example.ar_glass_plus.workspace.WorkspaceSession
import com.example.ar_glass_plus.workspace.WorkspaceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fixed dashboard: LEFT sidebar (connection, render controls, cursor tools)
 * + RIGHT full-size relative touchpad. The page MUST NOT scroll — the touchpad
 * owns all pointer changes; nothing here is a scroll container.
 */
class MainActivity : ComponentActivity() {
    private val displayController get() = (application as App).displayController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = WorkspaceSession.controllerOrNull()
        checkNotNull(controller) { "WorkspaceController not initialized by App" }
        setContent {
            ARglassplusTheme {
                val displayState by displayController.state.collectAsState()
                val workspaceState by WorkspaceSession.store.state.collectAsState()
                Dashboard(
                    displayState = displayState,
                    controller = controller,
                    workspaceState = workspaceState,
                )
            }
        }
    }
}

/**
 * Lazily decodes an app icon on IO only when the row is composed.
 */
@Composable
private fun AppIcon(entry: AppEntry, size: Dp = 32.dp) {
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, entry) {
        value = withContext(Dispatchers.IO) {
            entry.iconProvider()?.let { drawable ->
                val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, px, px)
                drawable.draw(canvas)
                bmp.asImageBitmap()
            }
        }
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        icon?.let {
            Image(it, contentDescription = null, modifier = Modifier.size(size))
        } ?: Box(
            Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
fun Dashboard(
    displayState: ExternalDisplayState,
    controller: WorkspaceController,
    workspaceState: WorkspaceState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val scope = rememberCoroutineScope()

    val connected = displayState as? ExternalDisplayState.Connected
    val contentDisplayId = workspaceState.contentDisplayId ?: -1
    val currentApp = workspaceState.activeApp?.packageName

    val cursorController = app.cursorController
    val engine = app.engine
    val mouseController = app.mouseController
    var sensitivity by remember { mutableStateOf(1f) }

    // App picker (P4.1): enumerate launcher apps once, filter by query.
    val appRepository = remember { AppRepository(context.packageManager) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var appQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { appRepository.loadLaunchableApps() }
    }
    val filteredApps = remember(apps, appQuery) {
        if (appQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(appQuery, ignoreCase = true) ||
                it.packageName.contains(appQuery, ignoreCase = true)
        }
    }

    LaunchedEffect(sensitivity) { cursorController.setSensitivity(sensitivity) }

    // Sync the physical display lifecycle into the workspace session. The
    // controller only transitions out of Idle; re-keying on status re-arms
    // OutputReady after a manual stop without auto-starting.
    LaunchedEffect(displayState, workspaceState.status) {
        val oldId = workspaceState.outputDisplayId
        when (displayState) {
            is ExternalDisplayState.Connected -> {
                if (oldId != null && oldId != displayState.displayId) {
                    controller.onOutputDisconnected(oldId)
                }
                controller.onOutputConnected(displayState.displayId)
            }
            ExternalDisplayState.Disconnected -> {
                if (oldId != null) controller.onOutputDisconnected(oldId)
            }
        }
    }

    // Connect the root uinput mouse service (fallback: shell backend).
    LaunchedEffect(Unit) {
        controller.setInputAvailable(app.uinputBackend.connect())
        controller.probeRootAvailability()
    }

    DisposableEffect(Unit) {
        onDispose {
            // The input stack is process-owned; Activity disposal only releases transient gesture state.
            app.engine.cancel()
            app.inputScope.launch { app.mouseController.releaseAllButtons() }
        }
    }

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
                        Text(
                            "windows=${workspaceState.windows.size}/${SpatialWindowModel.MAX_WINDOWS}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = { controller.startWorkspace() },
                    enabled = connected != null && !workspaceState.started,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (workspaceState.started) "渲染会话运行中…" else "渲染 App 到眼镜")
                }
            }

            // ── Apps ──
            item { Text("Apps", style = MaterialTheme.typography.labelLarge) }
            item {
                OutlinedTextField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    placeholder = { Text("搜索应用…", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Text(
                    if (currentApp != null) "运行中: $currentApp" else "未选择应用",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (filteredApps.isEmpty()) {
                item {
                    Text(
                        if (apps.isEmpty()) "加载应用列表…" else "无匹配应用",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(filteredApps, key = { it.packageName }) { app ->
                    val openPackages = workspaceState.windows.mapTo(mutableSetOf()) { it.app.packageName }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable(enabled = connected != null && workspaceState.started) {
                                scope.launch {
                                    when (val result = controller.openApp(
                                        ActiveApp(app.packageName, app.launcherClassName, app.label),
                                    )) {
                                        is OpenAppResult.Rejected -> {
                                            val text = when (result.reason) {
                                                OpenAppResult.Reason.NO_FREE_SLOT ->
                                                    "窗口已满 (${SpatialWindowModel.MAX_WINDOWS}/${SpatialWindowModel.MAX_WINDOWS})"
                                                OpenAppResult.Reason.NOT_RUNNING -> "会话未启动"
                                            }
                                            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (app.packageName in openPackages) "${app.label} (已开)" else app.label,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ── Windows ──
            if (workspaceState.windows.isNotEmpty()) {
                item { Text("Windows", style = MaterialTheme.typography.labelLarge) }
                items(workspaceState.windows.toList(), key = { it.id.value }) { window ->
                    WindowRow(
                        window = window,
                        focused = window.id == workspaceState.focusedWindowId,
                        onFocus = { scope.launch { controller.focusWindow(window.id) } },
                        onClose = { scope.launch { controller.closeWindow(window.id) } },
                    )
                }
            }
            item { Text("Display", style = MaterialTheme.typography.labelLarge) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { controller.setRenderMode(RenderMode.PASSTHROUGH_2D) },
                        enabled = connected != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("2D") }
                    Button(
                        onClick = { controller.setRenderMode(RenderMode.SBS_DUPLICATE) },
                        enabled = connected != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("SBS") }
                    Button(
                        onClick = { controller.setRenderMode(RenderMode.SBS_STEREO) },
                        enabled = connected != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("STEREO", fontSize = 10.sp) }
                }
            }

            // ── Gate 2 pose debug (focused window) ──
            if (workspaceState.focusedWindow != null) {
                item { Text("Pose (焦点窗口)", style = MaterialTheme.typography.labelLarge) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PoseRow("X", POSE_STEP_METERS) { controller.adjustFocusedWindow(dxMeters = it) }
                        PoseRow("Y", POSE_STEP_METERS) { controller.adjustFocusedWindow(dyMeters = it) }
                        PoseRow("Z", POSE_STEP_METERS) { controller.adjustFocusedWindow(dzMeters = it) }
                        PoseRow("Yaw", POSE_STEP_DEGREES) { controller.adjustFocusedWindow(dyawDeg = it) }
                        PoseRow("Pitch", POSE_STEP_DEGREES) { controller.adjustFocusedWindow(dpitchDeg = it) }
                        PoseRow("Roll", POSE_STEP_DEGREES) { controller.adjustFocusedWindow(drollDeg = it) }
                        PoseRow("W", POSE_STEP_SIZE) { controller.adjustFocusedWindow(dWidthMeters = it) }
                        PoseRow("H", POSE_STEP_SIZE) { controller.adjustFocusedWindow(dHeightMeters = it) }
                        val pose = workspaceState.focusedWindow!!.pose
                        Text(
                            "pos=(${pose.position.x.format()}, ${pose.position.y.format()}, ${pose.position.z.format()}) m  " +
                                "size=${pose.widthMeters.format()}×${pose.heightMeters.format()} m",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Input ──
            item { Text("Input", style = MaterialTheme.typography.labelLarge) }
            item {
                Button(
                    onClick = { mouseController.onBack() },
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
                            scope.launch { controller.stopWorkspace() }
                        },
                        enabled = contentDisplayId >= 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop", fontSize = 12.sp) }
                }
            }

            item {
                Text(
                    "Touchpad: 单指移动=光标 · 点按=左键 · 双击保持=拖拽\n双指点按=右键 · 双指滑动=滚轮",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Right touchpad (fixed, owns all pointer changes) ──
        TrackpadSurface(
            engine = engine,
            onGesture = { gestures -> gestures.forEach { mouseController.onGesture(it) } },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .onSizeChanged {
                    mouseController.setPadSize(it.width.toFloat(), it.height.toFloat())
                },
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
}

/**
 * One spatial window row: focus marker, app label, pose hint, lifecycle,
 * click to focus, ✕ to close. Pure presentation of SpatialWindowState.
 */
@Composable
private fun WindowRow(
    window: SpatialWindowState,
    focused: Boolean,
    onFocus: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onFocus)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (focused) "●" else "○",
            fontSize = 12.sp,
            color = if (focused) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        val p = window.pose.position
        Text(
            "${window.app.label} · (${p.x.format()}, ${p.y.format()}, ${p.z.format()}) · " +
                when (window.lifecycle) {
                    WindowLifecycle.CREATING -> "启动中"
                    WindowLifecycle.CONTENT_READY -> "就绪"
                    WindowLifecycle.RUNNING -> "运行中"
                },
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) { Text("✕", fontSize = 12.sp) }
    }
}

/**
 * Gate 2 pose nudge row: label + −/+ buttons applying a fixed delta
 * (meters or degrees) through [onDelta].
 */
@Composable
private fun PoseRow(label: String, step: Float, onDelta: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            modifier = Modifier.width(44.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { onDelta(-step) },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) { Text("−", fontSize = 12.sp) }
        OutlinedButton(
            onClick = { onDelta(step) },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) { Text("+", fontSize = 12.sp) }
    }
}

private fun Float.format(): String = String.format("%.2f", this)

private const val POSE_STEP_METERS = 0.05f
private const val POSE_STEP_DEGREES = 5f
private const val POSE_STEP_SIZE = 0.05f
