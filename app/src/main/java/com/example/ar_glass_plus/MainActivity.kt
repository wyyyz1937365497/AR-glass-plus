package com.example.ar_glass_plus
import android.graphics.Bitmap
import android.util.Log
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
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
import com.example.ar_glass_plus.display.sbs.SbsKernelState
import com.example.ar_glass_plus.display.sbs.SbsOperationResult
import com.example.ar_glass_plus.input.mouse.MouseController
import com.example.ar_glass_plus.input.touchpad.TrackpadGestureEngine
import com.example.ar_glass_plus.input.touchpad.TrackpadSurface
import com.example.ar_glass_plus.render.geometry.RenderMode
import com.example.ar_glass_plus.ui.theme.ARglassplusTheme
import com.example.ar_glass_plus.workspace.ActiveApp
import com.example.ar_glass_plus.workspace.OpenAppResult
import com.example.ar_glass_plus.workspace.CalibrationDraft
import com.example.ar_glass_plus.workspace.SpatialWindowModel
import com.example.ar_glass_plus.workspace.SpatialWindowState
import com.example.ar_glass_plus.workspace.WindowLifecycle
import com.example.ar_glass_plus.workspace.WorkspaceController
import com.example.ar_glass_plus.workspace.WorkspaceSession
import com.example.ar_glass_plus.workspace.WorkspaceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Dashboard design contract: docs/ARCHITECTURE.md, section 5.6.
private val SidebarWidth = 260.dp
private val CompactButtonHeight = 34.dp
private const val COLLAPSED_APP_COUNT = 4
private const val POSE_STEP_METERS = 0.05f
private const val POSE_STEP_DEGREES = 5f
private const val POSE_STEP_SIZE = 0.05f

/**
 * Fixed dashboard: LEFT config sidebar (app picker + panels,
 * the only scroll container) + RIGHT operation pane (status, session/render
 * controls, bounded one-hand touchpad with an input strip). The page MUST NOT
 * scroll — the touchpad owns all pointer changes.
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
private fun AppIcon(entry: AppEntry, size: Dp = 28.dp) {
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
    val sbsKernelState by app.sbsKernelController.state.collectAsState()
    val sbsTransitioning = sbsKernelState is SbsKernelState.Transitioning
    val sbsStatus = when (val state = sbsKernelState) {
        SbsKernelState.Inactive -> "DP fix: inactive"
        is SbsKernelState.Transitioning -> "DP fix: ${state.action}…"
        is SbsKernelState.Active -> "DP fix: active"
        is SbsKernelState.Error -> "DP fix: ${state.message}"
    }

    val cursorController = app.cursorController
    val engine = app.engine
    val mouseController = app.mouseController
    var sensitivity by remember { mutableStateOf(1f) }

    // App picker (P4.1): enumerate launcher apps once, filter by query.
    val appRepository = remember { AppRepository(context.packageManager) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var appQuery by remember { mutableStateOf("") }
    var showAllApps by remember { mutableStateOf(false) }

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

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        ConfigSidebar(
            workspaceState = workspaceState,
            connected = connected,
            controller = controller,
            apps = apps,
            filteredApps = filteredApps,
            appQuery = appQuery,
            onQueryChange = { appQuery = it },
            showAllApps = showAllApps,
            onToggleShowAll = { showAllApps = !showAllApps },
            onOpenApp = { entry ->
                scope.launch {
                    when (val result = controller.openApp(
                        ActiveApp(entry.packageName, entry.launcherClassName, entry.label),
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
            },
        )

        OperationPane(
            connected = connected,
            workspaceState = workspaceState,
            contentDisplayId = contentDisplayId,
            sbsKernelState = sbsKernelState,
            sbsTransitioning = sbsTransitioning,
            sbsStatus = sbsStatus,
            sensitivity = sensitivity,
            onSelectMode = { mode ->
                scope.launch {
                    when (val result = app.selectRenderMode(mode)) {
                        is SbsOperationResult.Failure ->
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        is SbsOperationResult.Success -> {
                            if (mode == RenderMode.CALIBRATION && !controller.state.started) {
                                controller.startWorkspace()
                            }
                        }
                    }
                }
            },
            onStartWorkspace = { controller.startWorkspace() },
            onStopWorkspace = { scope.launch { controller.stopWorkspace() } },
            onSensitivity = { sensitivity = it },
            onBack = { mouseController.onBack() },
            onCenterCursor = { cursorController.center() },
            engine = engine,
            mouseController = mouseController,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Sidebar / pane section header. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Compact status pill for the operation-pane top bar. */
@Composable
private fun StatusPill(text: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = tint,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * LEFT configuration sidebar — the ONLY scroll container on the page.
 * App picker with a collapsed-by-default list, window rows, and the
 * calibration / pose panels (conditional).
 */
@Composable
private fun ConfigSidebar(
    workspaceState: WorkspaceState,
    connected: ExternalDisplayState.Connected?,
    controller: WorkspaceController,
    apps: List<AppEntry>,
    filteredApps: List<AppEntry>,
    appQuery: String,
    onQueryChange: (String) -> Unit,
    showAllApps: Boolean,
    onToggleShowAll: () -> Unit,
    onOpenApp: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentApp = workspaceState.activeApp?.packageName

    LazyColumn(
        modifier = modifier
            .width(SidebarWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── Apps (collapsed by default) ──
        item { SectionLabel("应用") }
        item {
            OutlinedTextField(
                value = appQuery,
                onValueChange = onQueryChange,
                placeholder = { Text("搜索应用…", fontSize = 11.sp) },
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
            val expanded = appQuery.isNotBlank() || showAllApps
            val shown = if (expanded) filteredApps else filteredApps.take(COLLAPSED_APP_COUNT)
            val openPackages = workspaceState.windows.mapTo(mutableSetOf()) { it.app.packageName }
            items(shown, key = { it.packageName }) { app ->
                AppRow(
                    entry = app,
                    isRunning = app.packageName in openPackages,
                    enabled = connected != null && workspaceState.started,
                    onClick = { onOpenApp(app) },
                )
            }
            if (appQuery.isBlank() && filteredApps.size > COLLAPSED_APP_COUNT) {
                item {
                    TextButton(
                        onClick = onToggleShowAll,
                        modifier = Modifier.fillMaxWidth().height(30.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            if (showAllApps) "收起" else "显示全部 (${filteredApps.size})",
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        // ── Windows ──
        if (workspaceState.windows.isNotEmpty()) {
            item { SectionLabel("窗口") }
            items(workspaceState.windows.toList(), key = { it.id.value }) { window ->
                WindowRow(
                    window = window,
                    focused = window.id == workspaceState.focusedWindowId,
                    onFocus = { scope.launch { controller.focusWindow(window.id) } },
                    onClose = { scope.launch { controller.closeWindow(window.id) } },
                )
            }
        }

        // ── Gate 3R Calibration Live Panel ──
        if (workspaceState.renderMode == RenderMode.CALIBRATION) {
            item { SectionLabel("Calibration") }
            item {
                val cal = workspaceState.calibration
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Eye order", fontSize = 11.sp, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                controller.updateCalibration(
                                    cal.copy(eyeOrderLeftFirst = !cal.eyeOrderLeftFirst),
                                )
                            },
                            modifier = Modifier.weight(1.5f).height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text(if (cal.eyeOrderLeftFirst) "L|R" else "R|L", fontSize = 11.sp) }
                    }
                    CalRow("IPD", "%.1fmm".format(cal.ipdMeters * 1000)) {
                        controller.updateCalibration(cal.nudgeIpd(it))
                    }
                    CalRow("L-X", "%.0f".format(cal.leftCenterX)) {
                        controller.updateCalibration(cal.nudgeLeft(it, 0f))
                    }
                    CalRow("L-Y", "%.0f".format(cal.leftCenterY)) {
                        controller.updateCalibration(cal.nudgeLeft(0f, it))
                    }
                    CalRow("R-X", "%.0f".format(cal.rightCenterX)) {
                        controller.updateCalibration(cal.nudgeRight(it, 0f))
                    }
                    CalRow("R-Y", "%.0f".format(cal.rightCenterY)) {
                        controller.updateCalibration(cal.nudgeRight(0f, it))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { controller.updateCalibration(CalibrationDraft.default()) },
                            modifier = Modifier.weight(1f).height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("Reset", fontSize = 11.sp) }
                        OutlinedButton(
                            onClick = {
                                Log.i("CalibrationProfile", cal.dump("RayNeo", 1920, 1080))
                                Toast.makeText(context, "Profile dumped to logcat", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f).height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("Save", fontSize = 11.sp) }
                    }
                }
            }
        }

        // ── Gate 2 pose debug (focused window) ──
        if (workspaceState.focusedWindow != null) {
            item { SectionLabel("Pose (焦点窗口)") }
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
    }
}

/**
 * RIGHT operation pane: status top bar, session + render-mode + cursor-speed
 * controls, and the bounded one-hand touchpad cluster anchored bottom-end.
 * This pane never scrolls.
 */
@Composable
private fun OperationPane(
    connected: ExternalDisplayState.Connected?,
    workspaceState: WorkspaceState,
    contentDisplayId: Int,
    sbsKernelState: SbsKernelState,
    sbsTransitioning: Boolean,
    sbsStatus: String,
    sensitivity: Float,
    onSelectMode: (RenderMode) -> Unit,
    onStartWorkspace: () -> Unit,
    onStopWorkspace: () -> Unit,
    onSensitivity: (Float) -> Unit,
    onBack: () -> Unit,
    onCenterCursor: () -> Unit,
    engine: TrackpadGestureEngine,
    mouseController: MouseController,
    modifier: Modifier = Modifier,
) {
    val sbsTint = when {
        sbsKernelState is SbsKernelState.Active -> MaterialTheme.colorScheme.primary
        sbsKernelState is SbsKernelState.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val connectionTint =
        if (connected != null) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    val contentLabel = contentDisplayId.takeIf { it >= 0 }?.toString() ?: "-"
    val detail = if (connected != null) {
        "output=${connected.displayId} · ${connected.width}x${connected.height} · content=$contentLabel"
    } else {
        "content=$contentLabel"
    }
    val modes = listOf(
        "2D" to RenderMode.PASSTHROUGH_2D,
        "SBS" to RenderMode.SBS_DUPLICATE,
        "立体" to RenderMode.SBS_STEREO,
        "校准" to RenderMode.CALIBRATION,
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Top bar: title + status pills ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "AR-glass-plus",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            StatusPill(
                if (connected != null) "RayNeo ● ${connected.displayId}" else "RayNeo ○ 未连接",
                tint = connectionTint,
            )
            StatusPill("窗口 ${workspaceState.windows.size}/${SpatialWindowModel.MAX_WINDOWS}")
            StatusPill(sbsStatus, tint = sbsTint)
        }
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Session actions ──
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStartWorkspace,
                enabled = connected != null && !workspaceState.started,
                modifier = Modifier.weight(2f).height(40.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(
                    if (workspaceState.started) "渲染会话运行中…" else "渲染 App 到眼镜",
                    maxLines = 1,
                )
            }
            OutlinedButton(
                onClick = onStopWorkspace,
                enabled = contentDisplayId >= 0,
                modifier = Modifier.weight(1f).height(40.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) { Text("停止", fontSize = 12.sp) }
        }

        // ── Render mode ──
        SectionLabel("渲染模式")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(36.dp)) {
            modes.forEachIndexed { index, (label, mode) ->
                SegmentedButton(
                    selected = workspaceState.renderMode == mode,
                    onClick = { onSelectMode(mode) },
                    enabled = connected != null && !sbsTransitioning,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                ) {
                    Text(label, fontSize = 12.sp, maxLines = 1)
                }
            }
        }

        // ── Cursor speed ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "光标速度",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { preset ->
                FilterChip(
                    selected = sensitivity == preset,
                    onClick = { onSensitivity(preset) },
                    enabled = contentDisplayId >= 0,
                    label = { Text("${preset}x", fontSize = 11.sp) },
                    modifier = Modifier.height(30.dp),
                )
            }
        }

        // ── One-hand touchpad cluster (bottom-end) ──
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val padWidth = minOf(maxWidth * 0.5f, 420.dp)
            val padHeight = minOf(maxHeight * 0.62f, 360.dp)
            Column(
                modifier = Modifier.align(Alignment.BottomEnd),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TrackpadSurface(
                    engine = engine,
                    onGesture = { gestures -> gestures.forEach { mouseController.onGesture(it) } },
                    modifier = Modifier
                        .width(padWidth)
                        .height(padHeight)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                        .onSizeChanged {
                            mouseController.setPadSize(it.width.toFloat(), it.height.toFloat())
                        },
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "触控板",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                        Text(
                            "●",
                            fontSize = 28.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.width(padWidth),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        enabled = contentDisplayId >= 0,
                        modifier = Modifier.weight(1f).height(CompactButtonHeight),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Back", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = onCenterCursor,
                        enabled = contentDisplayId >= 0,
                        modifier = Modifier.weight(1f).height(CompactButtonHeight),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("居中光标", fontSize = 12.sp) }
                }
                Text(
                    "单指移动=光标 · 点按=左键 · 双击保持=拖拽 · 双指点按=右键 · 双指滑动=滚轮",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(padWidth),
                )
            }
        }
    }
}

/** Compact launcher-app row for the sidebar picker. */
@Composable
private fun AppRow(
    entry: AppEntry,
    isRunning: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(entry)
        Spacer(Modifier.width(8.dp))
        Text(
            if (isRunning) "${entry.label} (已开)" else entry.label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
            color = MaterialTheme.colorScheme.onSurface,
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
            modifier = Modifier.weight(1f).height(26.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) { Text("−", fontSize = 12.sp) }
        OutlinedButton(
            onClick = { onDelta(step) },
            modifier = Modifier.weight(1f).height(26.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) { Text("+", fontSize = 12.sp) }
    }
}

/** Calibration live-panel row: label, value readout, −/+ nudge buttons. */
@Composable
private fun CalRow(label: String, value: String, onDelta: (Float) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            modifier = Modifier.width(40.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(value, fontSize = 11.sp, modifier = Modifier.width(64.dp), color = MaterialTheme.colorScheme.primary)
        OutlinedButton(
            onClick = { onDelta(-1f) },
            modifier = Modifier.weight(1f).height(26.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) { Text("−", fontSize = 12.sp) }
        OutlinedButton(
            onClick = { onDelta(1f) },
            modifier = Modifier.weight(1f).height(26.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) { Text("+", fontSize = 12.sp) }
    }
}

private fun Float.format(): String = String.format("%.2f", this)
