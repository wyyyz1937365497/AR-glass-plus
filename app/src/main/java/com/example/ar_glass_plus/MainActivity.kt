package com.example.ar_glass_plus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ar_glass_plus.display.ExternalDisplayController
import com.example.ar_glass_plus.display.ExternalDisplayState
import com.example.ar_glass_plus.root.DensityInfo
import com.example.ar_glass_plus.root.DisplayDensityController
import com.example.ar_glass_plus.root.InputController
import com.example.ar_glass_plus.root.RootResult
import com.example.ar_glass_plus.root.RootShell
import com.example.ar_glass_plus.root.RootShellImpl
import com.example.ar_glass_plus.ui.theme.ARglassplusTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val displayController by lazy { ExternalDisplayController(this) }
    private val shell: RootShell by lazy { RootShellImpl() }
    private val input by lazy { InputController(shell) }
    private val density by lazy { DisplayDensityController(shell) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ARglassplusTheme {
                val displayState by displayController.state.collectAsState()
                ControlPanel(
                    displayState = displayState,
                    displayController = displayController,
                    shell = shell,
                    input = input,
                    density = density,
                )
            }
        }
    }
}

@Composable
fun ControlPanel(
    displayState: ExternalDisplayState,
    displayController: ExternalDisplayController,
    shell: RootShell,
    input: InputController,
    density: DisplayDensityController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logLines = remember { mutableStateListOf<String>() }
    var rootStatus by remember { mutableStateOf("checking…") }

    fun runCommand(label: String, block: suspend () -> RootResult) {
        scope.launch {
            logLines.add(0, "▶ $label")
            val r = block()
            logLines.add(
                0,
                "$label → exit=${r.exitCode} out=${r.stdout.trim().take(100)} err=${r.stderr.trim().take(100)}",
            )
        }
    }

    LaunchedEffect(Unit) {
        rootStatus = if (shell.isAvailable()) "root: available" else "root: NOT available"
        logLines.add(0, rootStatus)
    }

    val connected = displayState as? ExternalDisplayState.Connected

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AR-glass-plus 控制端", style = MaterialTheme.typography.headlineSmall)

            // External display status
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (connected != null) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("外部显示（眼镜）", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    when (connected) {
                        null -> Text("未检测到外部显示", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {
                            Text("${connected.name}  displayId=${connected.displayId}")
                            Text(
                                "${connected.width}x${connected.height} @ ${"%.0f".format(connected.refreshRate)}Hz · ${connected.densityDpi}dpi",
                            )
                        }
                    }
                }
            }

            // Launch glasses UI
            Button(
                onClick = { displayController.launchExternalActivity(context) },
                enabled = connected != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (connected != null) "启动眼镜界面" else "等待眼镜连接…")
            }

            // Input injection test (display-aware)
            Text("注入测试（root, displayId=${connected?.displayId ?: "-"}）", style = MaterialTheme.typography.titleMedium)
            val extId = connected?.displayId
            val injectEnabled = extId != null && rootStatus.startsWith("root: available")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        extId?.let { id -> runCommand("tap(960,540)") { input.tap(id, 960, 540) } }
                    },
                    enabled = injectEnabled,
                ) { Text("Tap") }
                Button(
                    onClick = {
                        extId?.let { id ->
                            runCommand("swipe down") { input.swipe(id, 960, 800, 960, 300, 400) }
                        }
                    },
                    enabled = injectEnabled,
                ) { Text("Swipe") }
                Button(
                    onClick = { extId?.let { id -> runCommand("back") { input.back(id) } } },
                    enabled = injectEnabled,
                ) { Text("Back") }
            }
            OutlinedButton(
                onClick = { extId?.let { id -> runCommand("home") { input.home(id) } } },
                enabled = injectEnabled,
            ) { Text("Home") }

            // Per-display density control
            DensityCard(extId = extId, density = density, runCommand = ::runCommand)

            Text(rootStatus, fontSize = 12.sp)

            // Command log
            Text("命令日志", style = MaterialTheme.typography.titleMedium)
            for (line in logLines.take(20)) {
                Text(
                    line,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun DensityCard(
    extId: Int?,
    density: DisplayDensityController,
    runCommand: (String, suspend () -> RootResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var info by remember(extId) { mutableStateOf<DensityInfo?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(extId) {
        info = extId?.let { density.read(it) }
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("显示密度（displayId=${extId ?: "-"}）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                info?.let {
                    "物理 ${it.physicalDpi}dpi" +
                        (it.overrideDpi?.let { o -> " → 覆盖 $o dpi" } ?: "")
                } ?: "无法读取（眼镜未连接）",
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        extId?.let { id ->
                            val cur = info?.overrideDpi ?: info?.physicalDpi ?: 213
                            runCommand("density ${cur - 20}") { density.set(id, cur - 20) }
                            scope.launch { info = density.read(id) }
                        }
                    },
                    enabled = extId != null,
                ) { Text("−") }
                OutlinedButton(
                    onClick = {
                        extId?.let { id ->
                            runCommand("density reset") { density.reset(id) }
                            scope.launch { info = density.read(id) }
                        }
                    },
                    enabled = extId != null,
                ) { Text("reset") }
                Button(
                    onClick = {
                        extId?.let { id ->
                            val cur = info?.overrideDpi ?: info?.physicalDpi ?: 213
                            runCommand("density ${cur + 20}") { density.set(id, cur + 20) }
                            scope.launch { info = density.read(id) }
                        }
                    },
                    enabled = extId != null,
                ) { Text("＋") }
            }
        }
    }
}
