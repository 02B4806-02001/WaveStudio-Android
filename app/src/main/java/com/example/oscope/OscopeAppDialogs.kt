package org.mhrri.wavestudio

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestoreFromTrash

@Composable
internal fun PresetShareDialog(
    visible: Boolean,
    presetShareName: String,
    onPresetShareNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = presetShareName,
                    onValueChange = onPresetShareNameChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.preset_file_name_label)) },
                    supportingText = { Text(stringResource(R.string.preset_file_name_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onShare) {
                Text(stringResource(R.string.action_share_direct))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun PresetResetConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_restore_default_title)) },
        text = { Text(stringResource(R.string.preset_restore_default_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun ImportedAudioControllerDialog(
    visible: Boolean,
    importedAudioLabel: String?,
    progress: Float,
    isPaused: Boolean,
    onDismiss: () -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
) {
    if (!visible) return

    val clampedProgress = progress.coerceIn(0f, 1f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_input_controller_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = importedAudioLabel?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.audio_input_controller_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { clampedProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = if (isPaused) stringResource(R.string.audio_input_controller_paused) else stringResource(R.string.audio_input_controller_playing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        IconButton(onClick = onTogglePause) {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = if (isPaused) stringResource(R.string.action_resume) else stringResource(R.string.action_pause),
                            )
                        }
                        Text(
                            text = if (isPaused) stringResource(R.string.action_resume) else stringResource(R.string.action_pause),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        IconButton(onClick = onStop) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = stringResource(R.string.action_stop),
                            )
                        }
                        Text(
                            text = stringResource(R.string.action_stop),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
internal fun RecordingsListDialog(
    visible: Boolean,
    recordings: List<RecordedClip>,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    playingId: String?,
    onSeek: (Long) -> Unit = {},
    onDismiss: () -> Unit,
    onPlayClick: (RecordedClip) -> Unit,
    onShareClick: (RecordedClip) -> Unit,
    onRenameClick: (RecordedClip) -> Unit,
    onDeleteClick: (RecordedClip) -> Unit,
    onBatchShare: (List<RecordedClip>) -> Unit = { list -> list.forEach(onShareClick) },
    onBatchDelete: (List<RecordedClip>) -> Unit = { list -> list.forEach(onDeleteClick) },
    recentlyDeletedClips: List<RecordedClip> = emptyList(),
    onRestore: (RecordedClip) -> Unit = {},
    onPermanentDelete: (RecordedClip) -> Unit = {},
    onEmptyTrash: () -> Unit = {},
) {
    if (!visible) return

    var isEditMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentTab by remember { mutableStateOf(0) } // 0 = Recordings, 1 = Recently Deleted

    var showPermanentDeleteConfirm by remember { mutableStateOf(false) }
    var pendingPermanentDeletes by remember { mutableStateOf<List<RecordedClip>>(emptyList()) }
    var isClearingAllTrash by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.40f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .align(Alignment.BottomCenter),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 顶部把手 + 标题 + 编辑/关闭按钮
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.recordings_list_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 编辑按钮（两个标签共用）
                            TextButton(onClick = {
                                if (isEditMode) {
                                    selectedIds = emptySet()
                                }
                                isEditMode = !isEditMode
                            }) {
                                Text(
                                    text = stringResource(
                                        if (isEditMode) R.string.common_done else R.string.action_edit
                                    ),
                                )
                            }

                            // 最近删除 / 返回录音列表 切换按钮
                            if (currentTab == 0 && recentlyDeletedClips.isNotEmpty()) {
                                TextButton(onClick = {
                                    isEditMode = false
                                    selectedIds = emptySet()
                                    currentTab = 1
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.RestoreFromTrash,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.recordings_recently_deleted_button, recentlyDeletedClips.size),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }

                            if (currentTab == 1) {
                                TextButton(onClick = {
                                    isEditMode = false
                                    selectedIds = emptySet()
                                    currentTab = 0
                                }) {
                                    Text(text = stringResource(R.string.recordings_list_title))
                                }
                            }

                            IconButton(onClick = onDismiss) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_expand_less),
                                    contentDescription = stringResource(R.string.common_close),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (currentTab == 0) {
                        // ===== 录音列表标签 =====
                        // 编辑模式下的操作栏
                        if (isEditMode) {
                            val sortedRecordings = recordings.sortedByDescending { it.date }
                            val allSelected = sortedRecordings.isNotEmpty() &&
                                    sortedRecordings.all { it.id in selectedIds }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = {
                                    selectedIds = if (allSelected) emptySet()
                                    else sortedRecordings.map { it.id }.toSet()
                                }) {
                                    Text(
                                        text = stringResource(
                                            if (allSelected) R.string.action_deselect_all
                                            else R.string.action_select_all
                                        ),
                                    )
                                }

                                Spacer(Modifier.weight(1f))

                                TextButton(
                                    enabled = selectedIds.isNotEmpty(),
                                    onClick = {
                                        val selected = sortedRecordings.filter { it.id in selectedIds }
                                        onBatchShare(selected)
                                    },
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_share),
                                        color = if (selectedIds.isNotEmpty())
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    )
                                }

                                TextButton(
                                    enabled = selectedIds.isNotEmpty(),
                                    onClick = {
                                        val selected = sortedRecordings.filter { it.id in selectedIds }
                                        onBatchDelete(selected)
                                    },
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_delete),
                                        color = if (selectedIds.isNotEmpty())
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    )
                                }
                            }
                        }

                        // 录音列表内容
                        RecordingsListView(
                            recordings = recordings.sortedByDescending { it.date },
                            onItemClick = { },
                            onPlayClick = onPlayClick,
                            onShareClick = onShareClick,
                            onRenameClick = onRenameClick,
                            onDeleteClick = onDeleteClick,
                            playingPositionMs = playbackPositionMs,
                            playingDurationMs = playbackDurationMs,
                            onSeek = onSeek,
                            playingId = playingId,
                            modifier = Modifier.padding(horizontal = 8.dp),
                            isEditMode = isEditMode,
                            selectedIds = selectedIds,
                            onToggleSelect = { id ->
                                selectedIds = if (id in selectedIds)
                                    selectedIds - id
                                else
                                    selectedIds + id
                            },
                        )
                    } else {
                        // ===== 最近删除标签 =====
                        // 编辑模式下的操作栏
                        if (isEditMode) {
                            val sortedDeleted = recentlyDeletedClips.sortedByDescending { it.date }
                            val allSelected = sortedDeleted.isNotEmpty() &&
                                    sortedDeleted.all { it.id in selectedIds }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = {
                                    selectedIds = if (allSelected) emptySet()
                                    else sortedDeleted.map { it.id }.toSet()
                                }) {
                                    Text(
                                        text = stringResource(
                                            if (allSelected) R.string.action_deselect_all
                                            else R.string.action_select_all
                                        ),
                                    )
                                }

                                Spacer(Modifier.weight(1f))

                                TextButton(
                                    enabled = selectedIds.isNotEmpty(),
                                    onClick = {
                                        val selected = sortedDeleted.filter { it.id in selectedIds }
                                        selected.forEach(onRestore)
                                        selectedIds = emptySet()
                                    },
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_restore),
                                        color = if (selectedIds.isNotEmpty())
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    )
                                }

                                TextButton(
                                    enabled = selectedIds.isNotEmpty(),
                                    onClick = {
                                        val selected = sortedDeleted.filter { it.id in selectedIds }
                                        pendingPermanentDeletes = selected
                                        isClearingAllTrash = false
                                        showPermanentDeleteConfirm = true
                                    },
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_permanently_delete),
                                        color = if (selectedIds.isNotEmpty())
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    )
                                }
                            }
                        }

                        if (recentlyDeletedClips.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.recently_deleted_empty_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 16.dp),
                            ) {
                                items(recentlyDeletedClips, key = { it.id }) { clip ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                            .clickable(enabled = isEditMode) {
                                                selectedIds = if (clip.id in selectedIds)
                                                    selectedIds - clip.id
                                                else
                                                    selectedIds + clip.id
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (isEditMode) {
                                            Checkbox(
                                                checked = clip.id in selectedIds,
                                                onCheckedChange = {
                                                    selectedIds = if (clip.id in selectedIds)
                                                        selectedIds - clip.id
                                                    else
                                                        selectedIds + clip.id
                                                },
                                                modifier = Modifier.padding(end = 8.dp),
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = clip.fileName,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Text(
                                                text = clip.dateText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (!isEditMode) {
                                            IconButton(onClick = { onRestore(clip) }) {
                                                Icon(
                                                    imageVector = Icons.Default.RestoreFromTrash,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                            IconButton(onClick = {
                                                pendingPermanentDeletes = listOf(clip)
                                                isClearingAllTrash = false
                                                showPermanentDeleteConfirm = true
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (!isEditMode) {
                                TextButton(
                                    onClick = {
                                        isClearingAllTrash = true
                                        showPermanentDeleteConfirm = true
                                    },
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .padding(end = 8.dp, bottom = 8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.permanently_delete_all),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    if (showPermanentDeleteConfirm) {
        AlertDialog(
            onDismissRequest = {
                showPermanentDeleteConfirm = false
                pendingPermanentDeletes = emptyList()
                isClearingAllTrash = false
            },
            title = {
                Text(
                    text = if (isClearingAllTrash)
                        stringResource(R.string.permanently_delete_all)
                    else
                        stringResource(R.string.action_permanently_delete)
                )
            },
            text = {
                Text(
                    text = if (isClearingAllTrash)
                        stringResource(R.string.permanently_delete_all_confirm)
                    else if (pendingPermanentDeletes.size == 1)
                        stringResource(R.string.permanently_delete_confirm, pendingPermanentDeletes.first().fileName)
                    else
                        stringResource(R.string.permanently_delete_batch_confirm, pendingPermanentDeletes.size)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isClearingAllTrash) {
                            onEmptyTrash()
                        } else {
                            pendingPermanentDeletes.forEach(onPermanentDelete)
                        }
                        showPermanentDeleteConfirm = false
                        pendingPermanentDeletes = emptyList()
                        isClearingAllTrash = false
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPermanentDeleteConfirm = false
                        pendingPermanentDeletes = emptyList()
                        isClearingAllTrash = false
                    },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    }
}

@Composable
internal fun RenameRecordingDialog(
    clip: RecordedClip?,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (RecordedClip, String) -> Unit,
) {
    val target = clip ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = renameText,
                onValueChange = onRenameTextChange,
                singleLine = true,
                label = { Text(stringResource(R.string.file_name_label)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(target, renameText) },
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun DeleteRecordingDialog(
    clip: RecordedClip?,
    onDismiss: () -> Unit,
    onConfirm: (RecordedClip) -> Unit,
) {
    val target = clip ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_recording_title)) },
        text = { Text(stringResource(R.string.delete_recording_confirm, target.fileName)) },
        confirmButton = {
            TextButton(onClick = { onConfirm(target) }) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun StartupNoteDialog(
    visible: Boolean,
    startupNoteText: String,
    doNotShowStartupNoteAgain: Boolean,
    onDoNotShowChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.startup_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(startupNoteText)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .toggleable(
                            value = doNotShowStartupNoteAgain,
                            role = Role.Checkbox,
                            onValueChange = onDoNotShowChanged,
                        )
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Checkbox(
                        checked = doNotShowStartupNoteAgain,
                        onCheckedChange = null,
                    )
                    Text(
                        text = stringResource(R.string.startup_note_do_not_show),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.startup_note_ack)) }
        },
    )
}

@Composable
internal fun AboutDialog(
    visible: Boolean,
    selectedLanguage: String,
    onDismiss: () -> Unit,
    onShowStartupNote: () -> Unit,
) {
    if (!visible) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val aboutDialogScrollState = rememberScrollState()
    val windowHeightDp = with(density) { windowInfo.containerSize.height.toDp() }
    val aboutDialogMaxHeight = if (windowHeightDp > 0.dp) minOf(windowHeightDp * 0.68f, 520.dp) else 360.dp
    val isZhAbout = selectedLanguage == LANG_ZH
    var showChangelog by remember { mutableStateOf(false) }

    data class AboutSection(val title: String, val bullets: List<String>)

    val appTitle = "Wave Studio"
    val appVersion = "v0.16.0"
    val aboutByline = if (isZhAbout) "by 磁拾音器研究所" else "by MoHa-Radio Institute"
    val aboutHint = if (isZhAbout) "提示：使用前请授予麦克风权限。" else "Please grant microphone permission before use."
    val changelogTitle = if (isZhAbout) "更新日志" else "Changelog"
    val aboutWebsiteLabel = if (isZhAbout) "磁拾音器研究所官网" else "Official website"
    val aboutWebsiteUrl = "https://www.mhrri.org/"
    val aboutPresetPlaceholder = if (isZhAbout) "预设配置下载：（暂时预留）" else "Preset download: (reserved)"

    val aboutSections = if (isZhAbout) {
        listOf(
            AboutSection(
                title = "0.16.0 版本主要更新内容",
                bullets = listOf(
                    "更换 UI 字体",
                    "EQ 均衡器部分可读性增强",
                    "增强了 Trigger 功能",
                    "修复了一些问题",
                ),
            ),
            AboutSection(
                title = "0.15.1 版本主要更新内容",
                bullets = listOf(
                    "修复了配置文件“分享”和“默认”按钮无法使用的问题",
                ),
            ),
            AboutSection(
                title = "0.15.0 版本主要更新内容",
                bullets = listOf(
                    "重构了关于界面和录音列表界面",
                    "优化了自定义存储功能",
                ),
            ),
            AboutSection(
                title = "0.14.1 版本主要更新内容",
                bullets = listOf(
                    "修复了自定义路径的 bug",
                    "优化了 Trigger 功能",
                ),
            ),
            AboutSection(
                title = "0.14.0 版本主要更新内容",
                bullets = listOf(
                    "可使用自定义录音存储路径",
                    "设置中新增全局 1Hz 高通开关",
                    "优化了 Trigger 功能",
                    "修复了测试信号的 bug",
                ),
            ),
            AboutSection(
                title = "0.13.0 版本主要更新内容",
                bullets = listOf(
                    "均衡器 EQ 频响图支持拖拽调节",
                    "新增导入音频控制器",
                    "竖屏模式下的最大波形高度提升至 200dp",
                ),
            ),
        )
    } else {
        listOf(
            AboutSection(
                title = "Key updates in version 0.16.0",
                bullets = listOf(
                    "Changed UI font",
                    "Improved readability of EQ section",
                    "Enhanced Trigger functionality",
                    "Fixed some issues",
                ),
            ),
            AboutSection(
                title = "Key updates in version 0.15.1",
                bullets = listOf(
                    "Fixed: 'Share' and 'Default' buttons were not working."
                ),
            ),
            AboutSection(
                title = "Key updates in version 0.15.0",
                bullets = listOf(
                    "Refactored the About screen and the recording list screen",
                    "Optimized the custom storage feature",
                ),
            ),
            AboutSection(
                title = "Key updates in version 0.14.1",
                bullets = listOf(
                    "Fixed a bug with the custom path",
                    "Optimized the Trigger function",
                ),
            ),
            AboutSection(
                title = "Key updates in version 0.14.0",
                bullets = listOf(
                    "Custom recording storage path is now available",
                    "Added a global 1Hz high-pass filter toggle in settings",
                    "Optimized the Trigger function",
                    "Fixed a bug with the test signal",
                ),
            ),
            AboutSection(
                title = "Key updates in version 0.13.0",
                bullets = listOf(
                    "EQ frequency response graph now supports drag adjustment",
                    "Added an imported audio controller",
                    "Increased the maximum waveform height to 200dp",
                ),
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = aboutDialogMaxHeight),
            ) {
                Column(
                    modifier = Modifier.verticalScroll(aboutDialogScrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "$appTitle $appVersion",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = aboutByline,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f),
                            )
                            Text(
                                text = aboutHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                            )
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showChangelog = !showChangelog },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = changelogTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    painter = painterResource(
                                        id = if (showChangelog) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                                    ),
                                    contentDescription = if (showChangelog) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val visibleSections = if (showChangelog) aboutSections else aboutSections.take(1)
                                visibleSections.forEach { section ->
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = section.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        section.bullets.forEach { bullet ->
                                            AboutBullet(bullet)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = if (isZhAbout) "更多信息" else "More information",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val annotatedLabel = buildAnnotatedString {
                                    pushStringAnnotation(tag = "URL", annotation = aboutWebsiteUrl)
                                    withStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline,
                                        )
                                    ) {
                                        append(aboutWebsiteLabel)
                                    }
                                    pop()
                                }
                                Text(
                                    text = annotatedLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.clickable {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, aboutWebsiteUrl.toUri())
                                            context.startActivity(intent)
                                        } catch (_: Throwable) {
                                        }
                                    },
                                )
                            }

                            Text(
                                text = aboutPresetPlaceholder,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onShowStartupNote()
                },
            ) {
                Text(stringResource(R.string.startup_note_show_button))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun AboutBullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ExitConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirmExit: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exit_title)) },
        text = { Text(stringResource(R.string.exit_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirmExit) { Text(stringResource(R.string.exit_title)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun RecentlyDeletedDialog(
    visible: Boolean,
    recentlyDeletedClips: List<RecordedClip>,
    onDismiss: () -> Unit,
    onRestore: (RecordedClip) -> Unit,
    onPermanentDelete: (RecordedClip) -> Unit,
    onEmptyTrash: () -> Unit,
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.40f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .align(Alignment.BottomCenter),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 顶部标题行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.recordings_recently_deleted_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_expand_more),
                                contentDescription = stringResource(R.string.common_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (recentlyDeletedClips.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.recently_deleted_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp),
                        ) {
                            items(recentlyDeletedClips, key = { it.id }) { clip ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = clip.fileName,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = clip.dateText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { onRestore(clip) }) {
                                        Icon(
                                            imageVector = Icons.Default.RestoreFromTrash,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    IconButton(onClick = { onPermanentDelete(clip) }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }

                        TextButton(
                            onClick = onEmptyTrash,
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(end = 8.dp, bottom = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.permanently_delete_all),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}


