package org.mhrri.wavestudio

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

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
internal fun RecordingsListDialog(
    visible: Boolean,
    recordings: List<RecordedClip>,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    playingId: String?,
    onDismiss: () -> Unit,
    onShareClick: (RecordedClip) -> Unit,
    onRenameClick: (RecordedClip) -> Unit,
    onDeleteClick: (RecordedClip) -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recordings_list_title)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 520.dp),
            ) {
                RecordingsListView(
                    recordings = recordings.sortedByDescending { it.date },
                    onItemClick = { },
                    onPlayClick = { },
                    onShareClick = onShareClick,
                    onRenameClick = onRenameClick,
                    onDeleteClick = onDeleteClick,
                    playingPositionMs = playbackPositionMs,
                    playingDurationMs = playbackDurationMs,
                    onSeek = { },
                    playingId = playingId,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
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
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val aboutDialogScrollState = rememberScrollState()
    val aboutDialogMaxHeight = minOf(configuration.screenHeightDp.dp * 0.55f, 360.dp)
    val isZhAbout = selectedLanguage == LANG_ZH

    val aboutMainLines = if (isZhAbout) {
        listOf(
            "Wave Studio v0.12.1 by 磁拾音器研究所",
            "提示：使用前请授予麦克风权限。",
            "",
            "0.12.1版本主要更新内容如下：",
            "- 均衡器折叠后持久化保存",
            "- 构建与稳定性修复",
            "- 修复了一些其他 bug",
            "",
            "0.12.0版本主要更新内容如下：",
            "- 全局资源化，适配中英双语",
            "- 处理后增益滑块显示逻辑更改",
            "- 处理后增益和 EQ 增益滑块手感调整",
            "- 滑块交互更跟手",
            "- 顶部 UI 部分按钮改成图标",
            "- 波形高度值持久化",
            "- 优化了 Trigger 功能",
            "- 构建与稳定性修复",
            "- 修复了一些其他 bug",
            "",
            "0.11.4版本主要更新内容如下：",
            "- 滤波器改为默认关闭",
            "- 修复了监听模式卡顿的问题",
            "- 修复了一些其他 bug",
            "",
            "参与开发人员（B站名）：02B4806長-02001、某地铁迷_、莓喵の小风扇、TEP-28WG01等",
            "",
            "磁拾音器QQ交流群：762852552",
        )
    } else {
        listOf(
            "Wave Studio v0.12.1 by MoHa-Radio Institute",
            "Note: Please grant microphone permission before use.",
            "",
            "Key updates in version 0.12.1:",
            "- Persist equalizer collapsed state",
            "- Fixed several other bugs",
            "",
            "Key updates in version 0.12.0:",
            "- Support for both Chinese and English",
            "- Changed display logic for Processing Gain slider",
            "- Adjusted slider feel for Processing gain and EQ gain",
            "- Smoother and more responsive slider interaction",
            "- Changed some buttons in the top UI to icons",
            "- Persisted waveform height value",
            "- Optimized the Trigger function",
            "- Build and stability fixes",
            "- Fixed several other bugs",
            "",
            "Key updates in version 0.11.4:",
            "- Filter is now disabled by default",
            "- Fixed lag issue in monitoring mode",
            "- Fixed several other bugs",
            "",
            "Contributing developers (Bilibili usernames): 02B4806長-02001, 某地铁迷_, 莓喵の小风扇, TEP-28WG01, etc.",
            "",
            "MoHa-Radio QQ group: 762852552",
        )
    }

    val aboutWebsiteLabel = if (isZhAbout) "磁拾音器研究所官网：" else "Official website:"
    val aboutPresetPlaceholder = if (isZhAbout) "预设配置下载：（暂时预留）" else "Preset download: (reserved)"

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
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    aboutMainLines.forEach { line -> Text(line) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(aboutWebsiteLabel)
                        val url = "https://www.mhrri.org/"
                        val linkText = buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ) {
                                append(url)
                            }
                        }
                        Text(
                            text = linkText,
                            modifier = Modifier
                                .padding(start = 2.dp)
                                .clickable {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        context.startActivity(intent)
                                    } catch (_: Throwable) {
                                    }
                                },
                        )
                    }
                    Text("")
                    Text(aboutPresetPlaceholder)
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_confirm)) }
        },
    )
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


