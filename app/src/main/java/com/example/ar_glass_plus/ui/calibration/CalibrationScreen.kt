package com.example.ar_glass_plus.ui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ar_glass_plus.display.ExternalDisplayState
import com.example.ar_glass_plus.workspace.CalibrationDraft
import java.util.Locale

/**
 * Tablet-side controller for the live calibration pattern rendered on the
 * glasses. Every edit updates WorkspaceState immediately; persistence remains
 * an explicit user action.
 */
@Composable
fun CalibrationScreen(
    connected: ExternalDisplayState.Connected?,
    draft: CalibrationDraft,
    outputReady: Boolean,
    operationMessage: String,
    busy: Boolean,
    onDraftChange: (CalibrationDraft) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onSaveAndOpenWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text("← 取消") }
            Column(Modifier.weight(1f)) {
                Text(
                    "Air 4 Pro 双眼校准",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "眼镜显示校准图，平板实时调节；保存前不会覆盖原配置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onReset, enabled = !busy) { Text("恢复默认") }
            Button(onClick = onSave, enabled = outputReady && !busy) { Text("保存并返回") }
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                val statusColor = if (outputReady) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
                Card(colors = CardDefaults.cardColors(containerColor = statusColor)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            if (outputReady) "● 实时校准画面已输出" else "○ 正在准备真实 SBS 输出",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(operationMessage, style = MaterialTheme.typography.bodySmall)
                        connected?.let {
                            Text(
                                "当前物理输出：${it.width}×${it.height}@${format(it.refreshRate, 0)} Hz · output ${it.displayId}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            item {
                CalibrationCard("开始前：保持真实佩戴姿势") {
                    Instruction(1, "戴好眼镜和日常使用的近视镜片/镜片框，坐姿与鼻托位置保持自然。")
                    Instruction(2, "先看左右眼标识：左眼应只看到青色向左箭头，右眼应只看到洋红色向右箭头。")
                    Instruction(3, "分别闭上一只眼，以白色固定十字和安全框检查裁切，再小幅调对应眼 X/Y，使琥珀色目标落在该眼最清晰、畸变最小的舒适视区。")
                    Instruction(4, "双眼睁开，先用左右眼 Y 消除垂直重影，再微调 IPD，直到琥珀色中距离目标容易融合且没有明显拉扯；近处轮廓应比远处轮廓有更大视差。")
                    Instruction(5, "最后调垂直 FOV，使整体角尺寸和空间尺度自然。圆形应保持圆、方形应保持方；若比例已失真，单一 FOV 无法修正，请保留默认值并记录现象。")
                    Text(
                        "注意：这里校正的是双眼渲染几何、佩戴偏心和投影关系，不能消除近视、散光或镜片本身造成的模糊/畸变。若出现复视、头晕或眼胀，请立即停止并恢复默认值。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                CalibrationCard("眼序与图案说明") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.eyeOrderLeftFirst,
                            onClick = { onDraftChange(draft.copy(eyeOrderLeftFirst = true)) },
                            enabled = !busy,
                            label = { Text("左半屏=左眼 · L | R") },
                        )
                        FilterChip(
                            selected = !draft.eyeOrderLeftFirst,
                            onClick = { onDraftChange(draft.copy(eyeOrderLeftFirst = false)) },
                            enabled = !busy,
                            label = { Text("左半屏=右眼 · R | L") },
                        )
                    }
                    Text("青色/洋红箭头用于确认眼序；白色十字是固定的屏幕几何中心，琥珀色小框是世界空间融合目标，两者的水平间距包含正常立体视差，不应在每只眼中强行对齐。白色网格和圆/方用于检查比例；青、洋红、绿色轮廓分别表示近、中、远深度。")
                }
            }

            item {
                NumericCalibrationControl(
                    title = "瞳距 IPD",
                    description = "控制左右眼相机间距。先输入验光或实测值，再以舒适融合为准做小幅微调。",
                    value = draft.ipdMeters * 1000f,
                    valueRange = 55f..75f,
                    fineStep = 0.1f,
                    decimals = 1,
                    unit = "mm",
                    enabled = !busy,
                    onValueChange = {
                        onDraftChange(
                            draft.copy(ipdMeters = (it / 1000f)).normalized(),
                        )
                    },
                )
            }

            item {
                CalibrationCard("左眼投影中心（单眼 1920×1080 像素）") {
                    NumericCalibrationControlContent(
                        title = "左眼 X",
                        description = "正值向右移动左眼的投影主点。",
                        value = draft.leftCenterX,
                        valueRange = 0f..CalibrationDraft.REFERENCE_EYE_WIDTH_PX,
                        fineStep = 1f,
                        decimals = 0,
                        unit = "px",
                        enabled = !busy,
                        onValueChange = { onDraftChange(draft.copy(leftCenterX = it).normalized()) },
                    )
                    NumericCalibrationControlContent(
                        title = "左眼 Y",
                        description = "正值按画面像素坐标向下移动投影主点。",
                        value = draft.leftCenterY,
                        valueRange = 0f..CalibrationDraft.REFERENCE_EYE_HEIGHT_PX,
                        fineStep = 1f,
                        decimals = 0,
                        unit = "px",
                        enabled = !busy,
                        onValueChange = { onDraftChange(draft.copy(leftCenterY = it).normalized()) },
                    )
                }
            }

            item {
                CalibrationCard("右眼投影中心（单眼 1920×1080 像素）") {
                    NumericCalibrationControlContent(
                        title = "右眼 X",
                        description = "它是右眼视口内部坐标，不是 3840 宽整屏坐标。",
                        value = draft.rightCenterX,
                        valueRange = 0f..CalibrationDraft.REFERENCE_EYE_WIDTH_PX,
                        fineStep = 1f,
                        decimals = 0,
                        unit = "px",
                        enabled = !busy,
                        onValueChange = { onDraftChange(draft.copy(rightCenterX = it).normalized()) },
                    )
                    NumericCalibrationControlContent(
                        title = "右眼 Y",
                        description = "正值按画面像素坐标向下移动投影主点。",
                        value = draft.rightCenterY,
                        valueRange = 0f..CalibrationDraft.REFERENCE_EYE_HEIGHT_PX,
                        fineStep = 1f,
                        decimals = 0,
                        unit = "px",
                        enabled = !busy,
                        onValueChange = { onDraftChange(draft.copy(rightCenterY = it).normalized()) },
                    )
                }
            }

            item {
                NumericCalibrationControl(
                    title = "垂直视场角 FOV",
                    description = "同时改变横纵方向的整体角尺寸和透视尺度，不改变宽高比。不要只追求“更大”，以空间尺度自然、融合轻松为准。",
                    value = draft.fovYDegrees,
                    valueRange = CalibrationDraft.MIN_FOV_Y_DEGREES..CalibrationDraft.MAX_FOV_Y_DEGREES,
                    fineStep = 0.1f,
                    decimals = 1,
                    unit = "°",
                    enabled = !busy,
                    onValueChange = { onDraftChange(draft.copy(fovYDegrees = it).normalized()) },
                )
            }

            item {
                CalibrationCard("保存前检查") {
                    Text("• 快速眨眼或交替闭眼时，中心目标不应出现明显上下跳动。")
                    Text("• 双眼放松注视琥珀色中距离目标时，应能自然融合，不需要主动斗眼；不要以消除所有水平视差为目标。")
                    Text("• 转动头部或重新佩戴后若偏差明显，应为该佩戴者重新校准；当前版本尚未接入头部跟踪。")
                    Text("• 配置按本机用户保存；恢复默认只修改当前草稿，仍需点击保存。")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onCancel,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("取消并恢复原配置") }
                        Button(
                            onClick = onSave,
                            enabled = outputReady && !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("保存并返回设置") }
                        Button(
                            onClick = onSaveAndOpenWorkspace,
                            enabled = outputReady && !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("保存并进入立体工作区") }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun CalibrationCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Instruction(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$number", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NumericCalibrationControl(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    fineStep: Float,
    decimals: Int,
    unit: String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    CalibrationCard(title) {
        NumericCalibrationControlContent(
            title = title,
            description = description,
            value = value,
            valueRange = valueRange,
            fineStep = fineStep,
            decimals = decimals,
            unit = unit,
            enabled = enabled,
            onValueChange = onValueChange,
            showTitle = false,
        )
    }
}

@Composable
private fun NumericCalibrationControlContent(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    fineStep: Float,
    decimals: Int,
    unit: String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    showTitle: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(format(value, decimals)) }

    LaunchedEffect(value, editing) {
        if (!editing) text = format(value, decimals)
    }

    fun applyValue(candidate: Float) {
        val next = candidate.coerceIn(valueRange.start, valueRange.endInclusive)
        onValueChange(next)
        text = format(next, decimals)
    }

    if (showTitle) Text(title, fontWeight = FontWeight.Medium)
    Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = ::applyValue,
        valueRange = valueRange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { next ->
                if (next.matches(DECIMAL_INPUT)) {
                    text = next
                    next.toFloatOrNull()?.let { candidate ->
                        onValueChange(candidate.coerceIn(valueRange.start, valueRange.endInclusive))
                    }
                }
            },
            label = { Text("$title ($unit)") },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    text.toFloatOrNull()?.let(::applyValue)
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { state ->
                    if (editing && !state.isFocused) {
                        text.toFloatOrNull()?.let(::applyValue)
                    }
                    editing = state.isFocused
                },
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { applyValue(value + fineStep) },
                enabled = enabled && value < valueRange.endInclusive,
                modifier = Modifier.width(58.dp).height(36.dp),
                contentPadding = PaddingValues(0.dp),
            ) { Text("▲") }
            OutlinedButton(
                onClick = { applyValue(value - fineStep) },
                enabled = enabled && value > valueRange.start,
                modifier = Modifier.width(58.dp).height(36.dp),
                contentPadding = PaddingValues(0.dp),
            ) { Text("▼") }
        }
        Text(
            "$unit / 每次 ${format(fineStep, decimals)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
    }
}

private val DECIMAL_INPUT = Regex("^[0-9]{0,4}([.][0-9]{0,3})?$")

private fun format(value: Float, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)
