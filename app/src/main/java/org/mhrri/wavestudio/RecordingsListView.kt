package org.mhrri.wavestudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

// 播放图标（自定义实现，无外部依赖）
private val PlayIcon: ImageVector
    get() = ImageVector.Builder(
        name = "PlayIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f
        ) {
            moveTo(5f, 3f)
            lineTo(19f, 12f)
            lineTo(5f, 21f)
            close()
        }
    }.build()

// 停止图标（自定义实现，无外部依赖）
private val StopIcon: ImageVector
    get() = ImageVector.Builder(
        name = "StopIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.Black)
        ) {
            moveTo(6f, 6f)
            lineTo(18f, 6f)
            lineTo(18f, 18f)
            lineTo(6f, 18f)
            close()
        }
    }.build()

// 分享图标（自定义实现，无外部依赖）
// 用一个“上传/分享”箭头形状，避免额外依赖。
private val ShareIconFallback: ImageVector
    get() = ImageVector.Builder(
        name = "ShareIconFallback",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f
        ) {
            // 一个“上传/分享”箭头
            moveTo(12f, 16f)
            lineTo(12f, 6f)
            moveTo(8f, 10f)
            lineTo(12f, 6f)
            lineTo(16f, 10f)
            moveTo(6f, 18f)
            lineTo(18f, 18f)
        }
    }.build()

/**
 * 录音列表组件
 * @param recordings 录音列表数据
 * @param onItemClick 列表项点击事件
 * @param onPlayClick 播放/停止按钮点击事件
 * @param onShareClick 分享按钮点击事件
 * @param onRenameClick 重命名按钮点击事件
 * @param onDeleteClick 删除按钮点击事件
 * @param playingPositionMs 当前播放位置（仅 playingId 对应的那条显示）
 * @param playingDurationMs 当前音频总时长（毫秒）
 * @param onSeek 在进度条上拖动时回调
 * @param playingId 当前正在播放的录音ID
 * @param isEditMode 是否为编辑模式（批量选择）
 * @param selectedIds 当前选中的录音ID集合
 * @param onToggleSelect 切换录音选中状态的回调
 */
@Composable
fun RecordingsListView(
    recordings: List<RecordedClip>,
    onItemClick: (RecordedClip) -> Unit,
    onPlayClick: (RecordedClip) -> Unit,
    onShareClick: (RecordedClip) -> Unit,
    onRenameClick: (RecordedClip) -> Unit,
    onDeleteClick: (RecordedClip) -> Unit,
    playingPositionMs: Long,
    playingDurationMs: Long,
    onSeek: (Long) -> Unit,
    playingId: String?,
    modifier: Modifier = Modifier,
    isEditMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelect: (String) -> Unit = {},
) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    val sortedRecordings = recordings.sortedByDescending { it.date }
    val totalDurationSec = sortedRecordings.sumOf { it.duration }

    Column(modifier = modifier.fillMaxSize()) {
        if (sortedRecordings.isEmpty()) {
            EmptyRecordingsState(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sortedRecordings, key = { it.id }) { recording ->
                    val isPlaying = playingId == recording.id
                    RecordingCard(
                        recording = recording,
                        expanded = expandedId == recording.id,
                        isPlaying = isPlaying,
                        playingPositionMs = if (isPlaying) playingPositionMs else 0L,
                        playingDurationMs = if (isPlaying) playingDurationMs else 0L,
                        onToggleExpanded = {
                            expandedId = if (expandedId == recording.id) null else recording.id
                            onItemClick(recording)
                        },
                        onPlayClick = onPlayClick,
                        onShareClick = onShareClick,
                        onRenameClick = onRenameClick,
                        onDeleteClick = onDeleteClick,
                        onSeek = onSeek,
                        isEditMode = isEditMode,
                        selectedIds = selectedIds,
                        onToggleSelect = onToggleSelect,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingSummaryCard(
    count: Int,
    totalDurationSec: Double,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val countText = stringResource(R.string.recordings_count_format, count)
    val durationText = stringResource(R.string.recordings_total_duration_format, formatTotalDuration(totalDurationSec))
    val statusText = when {
        isPlaying -> stringResource(R.string.recordings_status_playing)
        count > 0 -> stringResource(R.string.recordings_status_saved)
        else -> stringResource(R.string.recordings_status_waiting)
    }

    Card(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = countText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                )
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun EmptyRecordingsState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.recordings_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.recordings_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun RecordingCard(
    recording: RecordedClip,
    expanded: Boolean,
    isPlaying: Boolean,
    playingPositionMs: Long,
    playingDurationMs: Long,
    onToggleExpanded: () -> Unit,
    onPlayClick: (RecordedClip) -> Unit,
    onShareClick: (RecordedClip) -> Unit,
    onRenameClick: (RecordedClip) -> Unit,
    onDeleteClick: (RecordedClip) -> Unit,
    onSeek: (Long) -> Unit,
    isEditMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelect: (String) -> Unit = {},
) {
    val headlineColor = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isEditMode) {
                    onToggleSelect(recording.id)
                } else {
                    onToggleExpanded()
                }
            },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isEditMode) {
                    Checkbox(
                        checked = recording.id in selectedIds,
                        onCheckedChange = { onToggleSelect(recording.id) },
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = recording.fileName,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleSmall,
                            color = headlineColor,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Text(
                        text = "${recording.dateText} · ${recording.durationText}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = headlineColor.copy(alpha = 0.78f),
                    )
                }

                if (!isEditMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(onClick = { onShareClick(recording) }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                painter = rememberVectorPainter(ShareIconFallback),
                                contentDescription = stringResource(R.string.share_title_recording),
                            )
                        }
                        IconButton(onClick = { onPlayClick(recording) }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                painter = rememberVectorPainter(if (isPlaying) StopIcon else PlayIcon),
                                contentDescription = if (isPlaying) stringResource(R.string.action_stop) else stringResource(R.string.action_record),
                            )
                        }
                        IconButton(onClick = onToggleExpanded, modifier = Modifier.size(34.dp)) {
                            Icon(
                                painter = painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                                contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                            )
                        }
                    }
                }
            }

            if (isPlaying && playingDurationMs > 0) {
                OscopeSlider(
                    value = playingPositionMs.coerceIn(0L, playingDurationMs).toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..playingDurationMs.toFloat(),
                    steps = 0,
                    accentColor = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${formatMs(playingPositionMs)} / ${formatMs(playingDurationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = headlineColor.copy(alpha = 0.78f),
                )
            }

            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onRenameClick(recording) }) { Text(stringResource(R.string.rename_title)) }
                    TextButton(onClick = { onDeleteClick(recording) }) { Text(stringResource(R.string.action_delete)) }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.getDefault(), "%d:%02d", m, s)
}

private fun formatTotalDuration(totalSeconds: Double): String {
    val totalSec = totalSeconds.coerceAtLeast(0.0).toLong()
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

