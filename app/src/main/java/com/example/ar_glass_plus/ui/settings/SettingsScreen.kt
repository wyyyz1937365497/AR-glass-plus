package com.example.ar_glass_plus.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ar_glass_plus.ui.common.PageHeader
import com.example.ar_glass_plus.display.ExternalDisplayState
import com.example.ar_glass_plus.workspace.CalibrationDraft
import java.util.Locale

/** Tablet-side settings hub. Calibration is a dedicated workflow from here. */
@Composable
fun SettingsScreen(
    connected: ExternalDisplayState.Connected?,
    calibration: CalibrationDraft,
    pointerSensitivity: Float,
    autoConfirmProjection: Boolean,
    onPointerSensitivityChange: (Float) -> Unit,
    onAutoConfirmProjectionChange: (Boolean) -> Unit,
    onOpenCalibration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "设置",
            subtitle = "软件偏好、佩戴者校准与项目信息",
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsCard(title = "软件设置") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("触控板光标速度", fontWeight = FontWeight.Medium)
                            Text(
                                "当前 ${format(pointerSensitivity, 2)}×；该值会立即生效并自动保存。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${format(pointerSensitivity, 2)}×",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Slider(
                        value = pointerSensitivity,
                        onValueChange = onPointerSensitivityChange,
                        valueRange = 0.5f..2f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text("0.50×", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.weight(1f))
                        Text("2.00×", style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("自动确认 ColorOS 投屏", fontWeight = FontWeight.Medium)
                            Text(
                                "App 运行期间，仅当 Air 4 Pro 的 USB VID/PID 已连接，且 SystemUI 的标题、正文与“开始”按钮全部精确匹配时自动确认。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autoConfirmProjection,
                            onCheckedChange = onAutoConfirmProjectionChange,
                        )
                    }
                }
            }

            item {
                SettingsCard(title = "双眼 SBS 校准") {
                    val displayText = if (connected == null) {
                        "Air 4 Pro 未连接"
                    } else {
                        "已连接：${connected.width}×${connected.height}@${format(connected.refreshRate, 0)} Hz · output ${connected.displayId}"
                    }
                    Text(displayText, color = if (connected == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text(
                        "当前配置：${format(calibration.ipdMeters * 1000f, 1)} mm · " +
                            "L(${format(calibration.leftCenterX, 0)}, ${format(calibration.leftCenterY, 0)}) · " +
                            "R(${format(calibration.rightCenterX, 0)}, ${format(calibration.rightCenterY, 0)}) · " +
                            "FOV ${format(calibration.fovYDegrees, 1)}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "进入后，眼镜会按需切换到 3840×1080 Full-SBS，并持续显示双眼校准图。平板只承担说明与参数控制。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onOpenCalibration,
                        enabled = connected != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (connected == null) "连接 Air 4 Pro 后可校准" else "进入双眼校准")
                    }
                }
            }

            item {
                SettingsCard(title = "运行边界") {
                    Text(
                        "App 退到后台、眼镜拔出或选择 2D 时，会停止工作区并释放 SBS 租约。" +
                            "系统原生镜像不经过本软件的渲染引擎。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                SettingsCard(title = "开发者信息") {
                    Text(
                        "GitHub：github.com/wyyyz1937365497",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            uriHandler.openUri(DEVELOPER_GITHUB_URL)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun format(value: Float, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

private const val DEVELOPER_GITHUB_URL = "https://github.com/wyyyz1937365497"
