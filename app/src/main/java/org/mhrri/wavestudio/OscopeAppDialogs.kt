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
internal fun ImportProgressDialog(
    visible: Boolean,
    progress: Float,
    importedAudioLabel: String?,
    onCancel: () -> Unit,
) {
    if (!visible) return

    val percent = (progress * 100f).toInt().coerceIn(0, 100)

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.import_audio_progress_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = importedAudioLabel?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.import_audio_decoding),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
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
    var showChangelog by remember { mutableStateOf(false) }

    data class AboutSection(val title: String, val bullets: List<String>)

    val appTitle = stringResource(R.string.about_app_title)
    val appVersion = try {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${pkgInfo.versionName}"
    } catch (_: Exception) {
        ""
    }
    val aboutByline = stringResource(R.string.about_byline)
    val aboutHint = stringResource(R.string.about_hint)
    val changelogTitle = stringResource(R.string.about_changelog_title)
    val aboutWebsiteLabel = stringResource(R.string.about_website_label_new)
    val aboutWebsiteUrl = stringResource(R.string.about_website_url_new)
    val aboutPresetPlaceholder = stringResource(R.string.about_preset_placeholder)

    val aboutSections = listOf(
        "0.15.3.1" to R.array.about_changelog_v01531,
        "0.15.3" to R.array.about_changelog_v0153,
        "0.15.1" to R.array.about_changelog_v0151,
        "0.15.0" to R.array.about_changelog_v0150,
        "0.14.1" to R.array.about_changelog_v0141,
        "0.14.0" to R.array.about_changelog_v0140,
        "0.13.0" to R.array.about_changelog_v0130,
    ).map { (version, arrayResId) ->
        AboutSection(
            title = stringResource(R.string.about_changelog_version_title, version),
            bullets = context.resources.getStringArray(arrayResId).toList()
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
                                text = stringResource(R.string.about_more_info),
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


