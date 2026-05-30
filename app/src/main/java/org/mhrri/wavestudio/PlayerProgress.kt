package org.mhrri.wavestudio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * 格式化毫秒为 "M:SS" 或 "H:MM:SS" 的时间字符串。
 */
internal fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

/**
 * 导入音频的播放进度条组件。
 *
 * 显示音频标签、当前播放位置/总时长的时间文本、可拖拽的进度条，
 * 以及播放/暂停和停止控制按钮。
 *
 * @param positionMs 当前播放位置（毫秒）
 * @param durationMs 音频总时长（毫秒）
 * @param isPaused 是否处于暂停状态
 * @param audioLabel 音频文件名称（可选，为空时不显示标签行）
 * @param onSeek 用户拖动进度条时的回调，参数为目标位置（毫秒）
 * @param onTogglePause 点击播放/暂停按钮的回调
 * @param onStop 点击停止按钮的回调
 * @param modifier 外部修饰符
 */
@Composable
fun ImportedAudioProgressBar(
    positionMs: Long,
    durationMs: Long,
    isPaused: Boolean,
    audioLabel: String?,
    onSeek: (Long) -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safePosition = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L))
    val safeDuration = durationMs.coerceAtLeast(1L)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 标签行：音频名称 + 时间
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = audioLabel?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.audio_input_controller_hint),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF1565C0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "${formatTimeMs(safePosition)} / ${formatTimeMs(safeDuration)}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 控制行：播放/暂停 + 进度条 + 停止
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onTogglePause,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (isPaused) stringResource(R.string.action_resume)
                        else stringResource(R.string.action_pause),
                )
            }

            Slider(
                value = (safePosition.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f),
                onValueChange = { frac ->
                    onSeek((frac * safeDuration.toFloat()).toLong())
                },
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = onStop,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.action_stop),
                    tint = Color(0xFFC62828),
                )
            }
        }
    }
}
