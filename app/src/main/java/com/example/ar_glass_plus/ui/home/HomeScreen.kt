package com.example.ar_glass_plus.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ar_glass_plus.display.ExternalDisplayState
import com.example.ar_glass_plus.glasses.GlassesCommandSender
import com.example.ar_glass_plus.glasses.GlassesCommands
import com.example.ar_glass_plus.glasses.GlassesControlResult
import com.example.ar_glass_plus.render.geometry.RenderMode
import com.example.ar_glass_plus.ui.common.PageHeader
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import kotlinx.coroutines.launch

/**
 * 主页 tab：眼镜连接状态 + 常用硬件快捷设置。页面切换由底部导航条负责，
 * 本页不包含导航入口。
 */
@Composable
fun HomeScreen(
    connected: ExternalDisplayState.Connected?,
    renderMode: RenderMode,
    glassesControl: GlassesCommandSender,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var controlMessage by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    var audioTubeEnabled by rememberSaveable { mutableStateOf(false) }

    fun send(cmd: Int, value: Int) {
        scope.launch {
            controlMessage = when (val result = glassesControl.sendCommand(cmd, value)) {
                is GlassesControlResult.Sent -> "已发送 0x${cmd.toString(16)} = $value"
                is GlassesControlResult.Failed -> result.message
                GlassesControlResult.Disconnected -> "root 眼镜控制服务未连接"
            }
        }
    }

    fun sendColorMode(mode: Int) {
        scope.launch {
            controlMessage = when (val result = glassesControl.setColorMode(mode)) {
                is GlassesControlResult.Sent -> "图像模式已应用并保存"
                is GlassesControlResult.Failed -> result.message
                GlassesControlResult.Disconnected -> "root 眼镜控制服务未连接"
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHeader(title = "主页", subtitle = "眼镜状态与常用硬件设置") }

        item { StatusCard(connected = connected, renderMode = renderMode) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "常用设置（眼镜硬件）",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    HorizontalDivider()
                    QuickSlider(
                        label = "亮度",
                        valueLabel = { level ->
                            "${level + 1} / ${GlassesCommands.BRIGHTNESS_MAX_LEVEL + 1} 级"
                        },
                        maxValue = GlassesCommands.BRIGHTNESS_MAX_LEVEL,
                        onCommit = { level -> send(GlassesCommands.BRIGHTNESS_SET, level) },
                        trailingButton = "保存" to { send(GlassesCommands.BRIGHTNESS_SAVE, 0) },
                    )
                    QuickSlider(
                        label = "音量",
                        valueLabel = { level -> level.toString() },
                        maxValue = GlassesCommands.VOLUME_MAX_LEVEL,
                        onCommit = { level -> send(GlassesCommands.VOLUME, level) },
                    )
                    QuickSwitch(
                        label = "镜腿滚轮键 2D/3D 切换",
                        description = "关闭即锁定镜腿键，防止会话中误触切换显示模式",
                        onChecked = { enabled ->
                            send(
                                GlassesCommands.WHEEL_KEY_2D3D_SWITCH,
                                if (enabled) GlassesCommands.BOOL_ENABLED
                                else GlassesCommands.BOOL_DISABLED,
                            )
                        },
                    )
                    QuickSwitch(
                        label = "佩戴检测",
                        description = "摘下眼镜时自动关闭面板（需固件支持）",
                        onChecked = { enabled ->
                            send(
                                GlassesCommands.PSENSOR_DETECT,
                                if (enabled) GlassesCommands.BOOL_ENABLED
                                else GlassesCommands.BOOL_DISABLED,
                            )
                        },
                    )
                    controlMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("显示效果", style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider()
                    val stereoActive = renderMode != RenderMode.PASSTHROUGH_2D
                    QuickSegmented(
                        label = "刷新率",
                        options = listOf(
                            "60Hz" to GlassesCommands.FRAME_RATE_60,
                            "120Hz" to GlassesCommands.FRAME_RATE_120,
                        ),
                        enabled = !stereoActive,
                        supportingText = if (stereoActive) "3D 模式下不支持 120Hz 刷新率" else null,
                        onSelect = { cmd -> send(cmd, 0) },
                    )
                    QuickSegmented(
                        label = "图像模式",
                        options = listOf(
                            "标准" to GlassesCommands.COLOR_MODE_STANDARD,
                            "电影" to GlassesCommands.COLOR_MODE_MOVIE,
                            "护眼" to GlassesCommands.COLOR_MODE_EYE_COMFORT,
                        ),
                        supportingText = "选择后直接保存到眼镜",
                        onSelect = ::sendColorMode,
                    )
                    QuickSwitch(
                        label = "高动态（Vision 4000）",
                        description = "片上 SDR→HDR 实时增强",
                        onChecked = { enabled ->
                            send(
                                GlassesCommands.HIGH_DYNAMIC,
                                if (enabled) GlassesCommands.BOOL_ENABLED
                                else GlassesCommands.BOOL_DISABLED,
                            )
                        },
                    )
                    QuickSwitch(
                        label = "色彩增强",
                        description = "饱和度增强",
                        onChecked = { enabled ->
                            send(
                                GlassesCommands.COLOR_ENHANCE,
                                if (enabled) GlassesCommands.BOOL_ENABLED
                                else GlassesCommands.BOOL_DISABLED,
                            )
                        },
                    )
                    QuickSwitch(
                        label = "高亮度模式",
                        description = "提升峰值亮度（HDR 类模式）",
                        onChecked = { enabled ->
                            send(
                                GlassesCommands.HDR_MODE,
                                if (enabled) GlassesCommands.BOOL_ENABLED
                                else GlassesCommands.BOOL_DISABLED,
                            )
                        },
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("声音与高级", style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider()
                    QuickSegmented(
                        label = "音频模式",
                        options = listOf(
                            "标准" to GlassesCommands.AUDIO_MODE_STANDARD,
                            "轻语" to GlassesCommands.AUDIO_MODE_WHISPER,
                            "环绕" to GlassesCommands.AUDIO_MODE_SURROUND,
                        ),
                        enabled = !audioTubeEnabled,
                        supportingText = if (audioTubeEnabled) {
                            "导音鳍开启时固件锁定音频模式"
                        } else {
                            null
                        },
                        onSelect = { value -> send(GlassesCommands.AUDIO_MODE, value) },
                    )
                    QuickSwitch(
                        label = "导音鳍",
                        description = "开启对应声学补偿；固件会限制继续增大音量",
                        onChecked = { enabled ->
                            audioTubeEnabled = enabled
                            send(
                                GlassesCommands.AUDIO_TUBE_MODE,
                                if (enabled) GlassesCommands.BOOL_ENABLED
                                else GlassesCommands.BOOL_DISABLED,
                            )
                        },
                    )
                    QuickSwitch(
                        label = "音频保持",
                        description = "切换显示模式时保持眼镜音频不中断",
                        onChecked = { enabled ->
                            send(
                                GlassesCommands.AUDIO_PERSISTENCE,
                                if (enabled) GlassesCommands.BOOL_ENABLED
                                else GlassesCommands.BOOL_DISABLED,
                            )
                        },
                    )
                    QuickSwitch(
                        label = "眼镜息屏",
                        description = "关闭面板电源；重新打开请切回本开关",
                        onChecked = { enabled ->
                            send(
                                if (enabled) GlassesCommands.PANEL_POWER_OFF
                                else GlassesCommands.PANEL_POWER_ON,
                                0,
                            )
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { send(GlassesCommands.PANEL_EYE_SWAP, 0) },
                            modifier = Modifier.weight(1f),
                        ) { Text("左右眼画面交换") }
                        OutlinedButton(
                            onClick = { send(GlassesCommands.SAVE_SETTINGS, 0) },
                            modifier = Modifier.weight(1f),
                        ) { Text("保存全部设置") }
                        OutlinedButton(
                            onClick = { showResetConfirm = true },
                            modifier = Modifier.weight(1f),
                        ) { Text("恢复出厂") }
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复眼镜出厂设置？") },
            text = { Text("将清除眼镜端亮度、音频和显示参数，无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        send(GlassesCommands.RESET_SETTINGS, 0)
                    },
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StatusCard(
    connected: ExternalDisplayState.Connected?,
    renderMode: RenderMode,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIcon(
                icon = if (connected != null) Icons.Filled.CheckCircle else Icons.Outlined.Info,
                active = connected != null,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (connected != null) "Air 4 Pro 已连接" else "Air 4 Pro 未连接",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (connected != null) {
                        "output=${connected.displayId} · ${connected.width}×${connected.height}"
                    } else {
                        "USB-C 接入后自动识别并进入原生镜像"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                renderModeLabel(renderMode),
                style = MaterialTheme.typography.labelLarge,
                color = if (connected != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, active: Boolean) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(44.dp)
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickSlider(
    label: String,
    valueLabel: (Int) -> String,
    maxValue: Int,
    onCommit: (Int) -> Unit,
    trailingButton: Pair<String, () -> Unit>? = null,
) {
    var level by rememberSaveable(label) { mutableFloatStateOf(maxValue / 2f) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(
                valueLabel(level.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            trailingButton?.let { (text, action) ->
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(text, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Slider(
            value = level,
            onValueChange = { level = it },
            onValueChangeFinished = { onCommit(level.toInt()) },
            valueRange = 0f..maxValue.toFloat(),
            steps = maxValue - 1,
        )
    }
}

@Composable
private fun QuickSegmented(
    label: String,
    options: List<Pair<String, Int>>,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    var selected by rememberSaveable(label) { mutableStateOf(options.first().second) }
    Column {
        Text(label, fontWeight = FontWeight.Medium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (text, value) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = {
                        selected = value
                        onSelect(value)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    enabled = enabled,
                ) { Text(text) }
            }
        }
        supportingText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickSwitch(
    label: String,
    description: String,
    onChecked: (Boolean) -> Unit,
) {
    var enabled by rememberSaveable(label) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { value ->
                enabled = value
                onChecked(value)
            },
        )
    }
}

private fun renderModeLabel(mode: RenderMode): String = when (mode) {
    RenderMode.PASSTHROUGH_2D -> "原生 2D"
    RenderMode.SBS_DUPLICATE -> "SBS 复制"
    RenderMode.SBS_STEREO -> "空间立体"
    RenderMode.CALIBRATION -> "校准"
}
