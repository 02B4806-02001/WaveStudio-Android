package org.mhrri.wavestudio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
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
    playingId: String?
) {
    var expandedId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        items(recordings) { recording ->
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expandedId = if (expandedId == recording.id) null else recording.id
                        onItemClick(recording)
                    },
                headlineContent = {
                    Text(
                        text = recording.fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Column {
                        Text(text = "${recording.dateText} | 时长：${recording.durationText}秒")

                        // 仅对当前播放条目显示进度条
                        if (playingId == recording.id && playingDurationMs > 0) {
                            Spacer(modifier = Modifier.size(4.dp))
                            OscopeSlider(
                                value = (playingPositionMs.coerceIn(0L, playingDurationMs).toFloat()),
                                onValueChange = { onSeek(it.toLong()) },
                                valueRange = 0f..playingDurationMs.toFloat(),
                                steps = 0,
                                accentColor = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "${formatMs(playingPositionMs)} / ${formatMs(playingDurationMs)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (expandedId == recording.id) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onRenameClick(recording) }) { Text("重命名") }
                                TextButton(onClick = { onDeleteClick(recording) }) { Text("删除") }
                            }
                        }
                    }
                },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(onClick = { onShareClick(recording) }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                painter = rememberVectorPainter(ShareIconFallback),
                                contentDescription = "分享录音"
                            )
                        }
                        IconButton(onClick = { onPlayClick(recording) }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                painter = rememberVectorPainter(
                                    if (playingId == recording.id) StopIcon else PlayIcon
                                ),
                                contentDescription = if (playingId == recording.id) "停止播放" else "播放录音"
                            )
                        }
                    }
                }
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.getDefault(), "%d:%02d", m, s)
}
