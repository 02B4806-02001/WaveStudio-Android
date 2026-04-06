package com.example.oscope

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.log10
import kotlin.math.PI

private const val SETTINGS_PREFS_NAME = "oscope_settings"
private const val KEY_HIDE_STARTUP_NOTE = "hide_startup_note"
private const val KEY_TRIGGER_MODE_NAME = "trigger_mode_name"
private const val KEY_TRIGGER_USE_AUTOCORR = "trigger_use_autocorr"
private const val KEY_TRIGGER_STRONG_LOWPASS_HZ = "trigger_strong_lowpass_hz"
private const val KEY_TRIGGER_PRE_TRIGGER_RATIO = "trigger_pre_trigger_ratio"
private const val KEY_TRIGGER_HYSTERESIS_RATIO = "trigger_hysteresis_ratio"
private const val KEY_TRIGGER_HOLDOFF_RATIO = "trigger_holdoff_ratio"
private const val KEY_TRIGGER_AUTOCORR_REFRESH_FRAMES = "trigger_autocorr_refresh_frames"
private const val KEY_TRIGGER_AUTOCORR_MAX_SAMPLES = "trigger_autocorr_max_samples"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivityHolder.activity = this

        // Enable edge-to-edge: allow content to draw under system bars
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.BLACK
            window.navigationBarColor = android.graphics.Color.BLACK

            // Allow drawing into the display cutout area (camera notch) in landscape (API 28+)
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode =
                            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        setContent {
            MaterialTheme {
                // IMPORTANT: In edge-to-edge mode, don't let Scaffold auto-apply system bar paddings,
                // otherwise you'll see a top bar (often white) around the cutout area.
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    OscopeApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (MainActivityHolder.activity === this) MainActivityHolder.activity = null
        super.onDestroy()
    }
}

private object MainActivityHolder {
    var activity: ComponentActivity? = null
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OscopeApp(
    modifier: Modifier = Modifier,
    audioViewModel: AudioEngineViewModel = viewModel()
) {
    val context = LocalContext.current

    // 系统 Toast：强制居中 + 文本居中（你说的“气泡”）
    fun showCenterToast(msg: String) {
        try {
            val t = Toast.makeText(context, msg, Toast.LENGTH_SHORT)
            try {
                val tv = t.view?.findViewById<android.widget.TextView>(android.R.id.message)
                tv?.gravity = Gravity.CENTER
                tv?.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            } catch (_: Throwable) {
            }
            t.setGravity(Gravity.CENTER, 0, 0)
            t.show()
        } catch (_: Throwable) {
        }
    }

    val activity = MainActivityHolder.activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // 真·沉浸式：横屏时隐藏系统栏；竖屏恢复
    LaunchedEffect(isLandscape, activity) {
        val a = activity ?: return@LaunchedEffect
        try {
            val controller = WindowCompat.getInsetsController(a.window, a.window.decorView)
            if (isLandscape) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())

                a.window.statusBarColor = android.graphics.Color.BLACK
                a.window.navigationBarColor = android.graphics.Color.BLACK

                // Ensure cutout area is usable in immersive landscape (API 28+)
                if (Build.VERSION.SDK_INT >= 28) {
                    try {
                        a.window.attributes = a.window.attributes.apply {
                            layoutInDisplayCutoutMode =
                                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    } catch (_: Throwable) {
                    }
                }
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (_: Throwable) {
        }
    }

    // ===== 仅给沉浸模式用的最小状态集合（避免大量无关 state 更新导致全屏重组/掉帧） =====
    val immersiveFilteredWaveform by audioViewModel.immersiveFilteredWaveform.collectAsStateWithLifecycle()
    val immersiveRawWaveform by audioViewModel.rawWaveform.collectAsStateWithLifecycle()
    val immersiveWaveformSpanMs by audioViewModel.publishedWaveformSpanMs.collectAsStateWithLifecycle()
    val immersiveWindowMs by audioViewModel.windowMs.collectAsStateWithLifecycle()
    val immersiveAmpScale by audioViewModel.ampScale.collectAsStateWithLifecycle()

    val startupNoteText = "拾音器的倍率要满足两个波形的幅值均不能超过±0dB参考线，避免削波。" +
            "另外，在波形上纵向滑动可调整幅值缩放，横向滑动可调整时间窗口。"
    val startupPrefs = remember(context) {
        context.applicationContext.getSharedPreferences(SETTINGS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    val hideStartupNoteInitially = remember(startupPrefs) {
        startupPrefs.getBoolean(KEY_HIDE_STARTUP_NOTE, false)
    }

    // 启动提示弹窗
    var showStartupNoteDialog by rememberSaveable {
        mutableStateOf(!hideStartupNoteInitially)
    }
    var doNotShowStartupNoteAgain by rememberSaveable {
        mutableStateOf(hideStartupNoteInitially)
    }
    // 关于弹窗
    var showAboutDialog by remember { mutableStateOf(false) }
    // 退出确认
    var showExitConfirmDialog by remember { mutableStateOf(false) }

    fun openStartupNoteDialog() {
        doNotShowStartupNoteAgain = startupPrefs.getBoolean(KEY_HIDE_STARTUP_NOTE, false)
        showStartupNoteDialog = true
    }

    // 权限管理：引擎只需要麦克风权限；存储权限用于“录音文件导出/旧系统”
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    )

    // ===== 播放进度（录音列表里显示） =====
    val playbackPositionMs by audioViewModel.playbackPositionMs.collectAsStateWithLifecycle()
    val playbackDurationMs by audioViewModel.playbackDurationMs.collectAsStateWithLifecycle()

    // ===== 录音列表：重命名/删除对话框状态 =====
    var renameTarget by remember { mutableStateOf<RecordedClip?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<RecordedClip?>(null) }

    fun setLandscape(landscape: Boolean) {
        try {
            activity?.requestedOrientation =
                if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } catch (_: Throwable) {
        }
    }

    // ===== 预设：导入/导出/分享（SAF + FileProvider） =====
    // 预设提示：改为系统 Toast（不再用 SnackbarHost/presetTip）

    // 分享当前预设：先弹出重命名，再写入缓存文件并分享
    var presetShareDialog by remember { mutableStateOf(false) }
    var presetShareName by remember { mutableStateOf("oscope_preset") }

    // 预设“默认”：误触保护二次确认
    var presetResetConfirmDialog by remember { mutableStateOf(false) }

    fun exportCurrentPresetToCache(nameNoExt: String): File? {
        return try {
            val safeBase = nameNoExt.trim().ifEmpty { "oscope_preset" }
                .replace(Regex("[^a-zA-Z0-9_\u4e00-\u9fa5-]+"), "_")
                .take(64)
                .ifEmpty { "oscope_preset" }

            val preset = audioViewModel.exportPreset()
            val json = preset.toJsonString(pretty = true)

            val dir = File(context.externalCacheDir, "presets").apply { mkdirs() }
            val file = File(dir, "$safeBase.json")
            file.writeText(json, Charsets.UTF_8)
            file
        } catch (_: Throwable) {
            null
        }
    }

    fun shareAnyFile(file: File, mime: String) {
        try {
            if (!file.exists()) return
            val uri: Uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享"))
        } catch (_: Throwable) {
        }
    }

    // Import preset JSON
    val importPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openInputStream(uri).use { inS: InputStream? ->
                val text = inS?.bufferedReader(Charsets.UTF_8)?.readText()
                if (text.isNullOrBlank()) {
                    showCenterToast("导入失败：文件为空")
                } else {
                    val preset = FilterPreset.fromJsonString(text)
                    audioViewModel.applyPreset(preset)
                    showCenterToast("已导入预设" + (preset.name?.let { "：$it" } ?: ""))
                }
            }
        } catch (t: Throwable) {
            showCenterToast("导入失败：${t.message}")
        }
    }

    val importAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        audioViewModel.importAudioAsInput(context, uri)
    }

    // Export preset JSON via SAF
    val exportPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val preset = audioViewModel.exportPreset()
            val json = preset.toJsonString(pretty = true)
            context.contentResolver.openOutputStream(uri).use { outS: OutputStream? ->
                outS?.write(json.toByteArray(Charsets.UTF_8))
                outS?.flush()
            }
            showCenterToast("已导出预设")

            // 同时在 externalCacheDir 保存一份，方便一键分享
            try {
                val dir = File(context.externalCacheDir, "presets").apply { mkdirs() }
                val file = File(dir, "oscope_preset_${System.currentTimeMillis()}.json")
                file.writeText(json, Charsets.UTF_8)
            } catch (_: Throwable) {
                // ignore cache write
            }
        } catch (t: Throwable) {
            showCenterToast("导出失败：${t.message}")
        }
    }

    fun formatHz(hz: Float): String {
        val v = hz.coerceAtLeast(0f)
        return if (v < 10f) String.format(Locale.US, "%.1f", v) else v.toInt().toString()
    }

    fun cutoffStepLowPass(hz: Float): Float {
        val v = hz.coerceAtLeast(0f)
        return when {
            v < 5000f -> 100f
            v < 10000f -> 200f
            else -> 500f
        }
    }

    fun cutoffStepHighPass(hz: Float): Float {
        val v = hz.coerceAtLeast(0f)
        return when {
            v < 100f -> 5f
            v < 500f -> 10f
            v < 1000f -> 20f
            v < 2000f -> 50f
            else -> 100f
        }
    }

    fun snapLowPassHz(hz: Float): Float {
        val v = hz.coerceIn(800f, 30001f)
        val step = cutoffStepLowPass(v)
        return (round(v / step) * step).coerceIn(800f, 30001f)
    }

    fun snapHighPassHz(hz: Float): Float {
        val v = hz.coerceIn(30f, 8001f)
        val step = cutoffStepHighPass(v)
        return (round(v / step) * step).coerceIn(30f, 8001f)
    }

    fun formatLowPassHz(hz: Float): String {
        return snapLowPassHz(hz).toInt().toString()
    }

    fun formatHighPassHz(hz: Float): String {
        val v = snapHighPassHz(hz)
        return if (v < 100f) String.format(Locale.US, "%.0f", v) else v.toInt().toString()
    }

    // ===== 0dB 参考线显示开关（竖屏默认隐藏） =====
    var showRefRaw by remember { mutableStateOf(false) }
    var showRefFiltered by remember { mutableStateOf(false) }

    // ===== Recording settings UI state =====
    val recordingFormat by audioViewModel.recordingFormat.collectAsStateWithLifecycle()
    val recordingSampleRate by audioViewModel.recordingSampleRate.collectAsStateWithLifecycle()
    val publishRateOption by audioViewModel.publishRateOption.collectAsStateWithLifecycle()

    fun mimeForRecording(path: String): String {
        val lower = path.lowercase(Locale.getDefault())
        return when {
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".mp4") -> "audio/mp4"
            lower.endsWith(".aac") -> "audio/aac"
            else -> "application/octet-stream"
        }
    }

    fun shareRecording(clip: RecordedClip) {
        try {
            val file = File(clip.fileURL)
            if (!file.exists()) return

            val uri: Uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeForRecording(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享录音"))
        } catch (_: Throwable) {
            // ignore
        }
    }

    // 收集ViewModel状态
    val isRunning by audioViewModel.isRunning.collectAsStateWithLifecycle()
    val useTestSignal by audioViewModel.useTestSignal.collectAsStateWithLifecycle()
    val testSignalPreset by audioViewModel.testSignalPreset.collectAsStateWithLifecycle()
    val useImportedSignal by audioViewModel.useImportedSignal.collectAsStateWithLifecycle()
    val importedAudioLabel by audioViewModel.importedAudioLabel.collectAsStateWithLifecycle()
    val isImportingAudio by audioViewModel.isImportingAudio.collectAsStateWithLifecycle()
    val isRecording by audioViewModel.isRecording.collectAsStateWithLifecycle()
    val isMonitoring by audioViewModel.isMonitoring.collectAsStateWithLifecycle()
    val recordings by audioViewModel.recordings.collectAsStateWithLifecycle()

    val lowPassEnabled by audioViewModel.lowPassEnabled.collectAsStateWithLifecycle()
    val lowPassCutoff by audioViewModel.lowPassCutoff.collectAsStateWithLifecycle()
    val highPassEnabled by audioViewModel.highPassEnabled.collectAsStateWithLifecycle()
    val highPassCutoff by audioViewModel.highPassCutoff.collectAsStateWithLifecycle()

    // 低通/高通阶数（1..8）
    val lowPassOrder by audioViewModel.lowPassOrder.collectAsStateWithLifecycle()
    val highPassOrder by audioViewModel.highPassOrder.collectAsStateWithLifecycle()

    val windowMs by audioViewModel.windowMs.collectAsStateWithLifecycle()
    val ampScale by audioViewModel.ampScale.collectAsStateWithLifecycle()

    // ===== 显示幅度（倍数）范围：0.5..30（手势/显示用） =====
    val ampMin = 0.5f
    val ampMax = 30f

    // ===== 波形幅度缩放步进（x 倍数）=====
    // 需求：按住拖动时也保持步进显示/更新。
    // 分段步进（仅用于标题显示吸附，不影响实际手感/缩放）
    fun ampStepFor(v: Float): Float {
        val x = v.coerceAtLeast(0f)
        return when {
            x < 1f -> 0.05f
            x < 2f -> 0.1f
            x < 5f -> 0.2f
            x < 10f -> 0.5f
            else -> 1f
        }
    }
    fun snapAmp(v: Float): Float {
        val clamped = v.coerceIn(ampMin, ampMax)
        val step = ampStepFor(clamped)
        val snapped = (round(clamped / step) * step)
        return snapped.coerceIn(ampMin, ampMax)
    }

    // 竖屏：两条波形各自的“显示缩放”，互不影响
    var rawDisplayScale by remember { mutableStateOf(ampScale) }
    var filteredDisplayScale by remember { mutableStateOf(ampScale) }

    // ===== 竖屏：两条波形高度可调（标题行控件） =====
    val waveHeightMin = 60
    val waveHeightMax = 150
    val waveHeightStep = 10
    var rawWaveHeightDp by remember { mutableIntStateOf(90) }
    var filteredWaveHeightDp by remember { mutableIntStateOf(110) }

    fun clampWaveHeight(v: Int): Int {
        val clamped = v.coerceIn(waveHeightMin, waveHeightMax)
        // Snap to step grid from min
        val snapped = (((clamped - waveHeightMin) + waveHeightStep / 2) / waveHeightStep) * waveHeightStep + waveHeightMin
        return snapped.coerceIn(waveHeightMin, waveHeightMax)
    }

    fun incWaveHeight(current: Int): Int = clampWaveHeight(current + waveHeightStep)
    fun decWaveHeight(current: Int): Int = clampWaveHeight(current - waveHeightStep)

    // ViewModel 的 ampScale 变化时（例如重置/加载），如果用户还没改过各自缩放，则跟随
    LaunchedEffect(ampScale) {
        if (abs(rawDisplayScale - ampScale) < 1e-4f) rawDisplayScale = ampScale.coerceIn(ampMin, ampMax)
        if (abs(filteredDisplayScale - ampScale) < 1e-4f) filteredDisplayScale = ampScale.coerceIn(ampMin, ampMax)
    }

    // 竖屏标题用：仅显示内容做轻微低通，交互/波形绘制保持连续
    val rawScaleDisplay = rememberDisplayLowPass(rawDisplayScale, resetKey = "rawScale", alpha = 0.22f, snapThreshold = 0.01f)
    val filteredScaleDisplay = rememberDisplayLowPass(filteredDisplayScale, resetKey = "filteredScale", alpha = 0.22f, snapThreshold = 0.01f)
    val rawScaleText = String.format(Locale.US, "%.2f", snapAmp(rawScaleDisplay))
    val filteredScaleText = String.format(Locale.US, "%.2f", snapAmp(filteredScaleDisplay))

    // ===== 手势用：滤波后增益 dB <-> linear =====
    fun gainToDb(gain: Float): Float {
        val g = gain.coerceAtLeast(1e-6f)
        return (20f * (ln(g) / ln(10f)))
    }

    fun dbToGain(db: Float): Float {
        // gain = 10^(db/20)
        return exp((db / 20f) * ln(10f))
    }

    // ===== 频率滑块：拖动时用本地 0..1 状态避免“映射回写”造成手指/滑块不贴合 =====
    val lowPassMin = 800f
    val lowPassMax = 30001f
    val highPassMin = 30f
    val highPassMax = 8001f
    val lowPassLinearW = 0.5f

    // 明确类型，避免 Kotlin 推断失败
    var lpFreq01 by remember { mutableStateOf<Float>(hzToSliderBlend(lowPassCutoff, lowPassMin, lowPassMax, linearWeight = lowPassLinearW)) }
    var hpFreq01 by remember { mutableStateOf<Float>(hzToSlider(highPassCutoff, highPassMin, highPassMax)) }
    var lpDragging by remember { mutableStateOf(false) }
    var hpDragging by remember { mutableStateOf(false) }

    // 拖动时标题显示值：做轻微低通平滑；实际参数仍实时生效
    val lowPassDisplayHzTarget = if (lpDragging)
        sliderToHzBlend(lpFreq01, lowPassMin, lowPassMax, linearWeight = lowPassLinearW)
    else lowPassCutoff
    val lowPassDisplayHz = rememberDisplayLowPass(
        target = lowPassDisplayHzTarget,
        resetKey = "lowPassHz",
        alpha = 0.24f,
        snapThreshold = 1f,
    )

    val highPassDisplayHzTarget = if (hpDragging)
        sliderToHz(hpFreq01, highPassMin, highPassMax)
    else highPassCutoff
    val highPassDisplayHz = rememberDisplayLowPass(
        target = highPassDisplayHzTarget,
        resetKey = "highPassHz",
        alpha = 0.24f,
        snapThreshold = 0.25f,
    )

    // 拖动时也要实时影响滤波：节流（约 60Hz），避免每次 onValueChange 都回写引发抖动/性能问题
    LaunchedEffect(lpDragging, lpFreq01) {
        if (!lpDragging) return@LaunchedEffect
        kotlinx.coroutines.delay(8)
        audioViewModel.updateLowPassSlider(
            snapLowPassHz(sliderToHzBlend(lpFreq01, lowPassMin, lowPassMax, linearWeight = lowPassLinearW))
        )
    }
    LaunchedEffect(hpDragging, hpFreq01) {
        if (!hpDragging) return@LaunchedEffect
        kotlinx.coroutines.delay(8)
        audioViewModel.updateHighPassSlider(snapHighPassHz(sliderToHz(hpFreq01, highPassMin, highPassMax)))
    }

    // 当 ViewModel 的截止频率被外部改变（比如重置/加载/其它），且当前没有在拖动时，同步 UI 位置
    LaunchedEffect(lowPassCutoff) {
        if (!lpDragging) {
            lpFreq01 = hzToSliderBlend(lowPassCutoff, lowPassMin, lowPassMax, linearWeight = lowPassLinearW)
        }
    }
    LaunchedEffect(highPassCutoff) {
        if (!hpDragging) {
            hpFreq01 = hzToSlider(highPassCutoff, highPassMin, highPassMax)
        }
    }

    val engineError by audioViewModel.engineError.collectAsStateWithLifecycle()

    // ===== 滤波增益状态（纯手动） =====
    val filterGain by audioViewModel.filterGain.collectAsStateWithLifecycle()

    // ===== EQ state =====
    val eqEnabled by audioViewModel.eqEnabled.collectAsStateWithLifecycle()
    val eqBands by audioViewModel.eqBands.collectAsStateWithLifecycle()

    val playingId by audioViewModel.playingId.collectAsStateWithLifecycle()

    // 录音列表弹窗状态
    var showRecordList by remember { mutableStateOf(false) }

    // 阶数选项：1..8（任意整数）
    val orderOptions = (1..8).toList()

    val settingsScroll = rememberScrollState()
    // EQ 图拖动时，禁用外层滚动，避免“拖动节点同时界面滚动”
    val eqGraphDragging by audioViewModel.eqGraphDragging.collectAsStateWithLifecycle()

    // 对数映射：0..1 <-> [min,max]
    fun logToSlider(v: Float, min: Float, max: Float): Float = hzToSlider(v, min, max)
    fun sliderToLog(v01: Float, min: Float, max: Float): Float = sliderToHz(v01, min, max)

    // 时间窗（ms）范围：5..300
    val windowMinMs = 5f
    val windowMaxMs = 300f

    // 滤波后增益（线性）范围：-20dB..40dB (~0.1..100)
    val gainMin = 0.1f
    val gainMax = 100f


    // ===== 横屏手势：锁/解锁 + 实时显示（必须在 isLandscape 分支前定义） =====
    var landscapeLocked by remember { mutableStateOf(false) }
    var gestureOverlayVisible by remember { mutableStateOf(false) }
    var gestureAmp by remember { mutableStateOf(ampScale) }
    var gestureWindow by remember { mutableStateOf(windowMs) }
    // 0=none, 1=amp, 2=window
    var gestureMode by remember { mutableIntStateOf(0) }

    LaunchedEffect(ampScale) { if (!gestureOverlayVisible) gestureAmp = ampScale }
    LaunchedEffect(windowMs) { if (!gestureOverlayVisible) gestureWindow = windowMs }
    val currentWindowMs by rememberUpdatedState(windowMs)
    val currentRawDisplayScale by rememberUpdatedState(rawDisplayScale)
    val currentFilteredDisplayScale by rememberUpdatedState(filteredDisplayScale)

    // ===== 竖屏波形手势：两条波形分别控制纵向，横向共同控制时间窗 =====
    // （悬浮文本已移除，保留手势 lock 状态）

    // DELETE these if still present:
    // var portraitOverlayVisible by remember { mutableStateOf(false) }
    // var portraitOverlayText by remember { mutableStateOf("") }
    // var portraitGestureMode by remember { mutableIntStateOf(0) }

    // 拖动手势：锁定状态（避免同时影响两条波形）
    var rawDragMode by remember { mutableIntStateOf(0) }      // 0 none, 1 vertical, 2 horizontal
    var filteredDragMode by remember { mutableIntStateOf(0) } // 0 none, 1 vertical, 2 horizontal

    // ===== 仅横屏：全屏波形 + 手势控制 + 锁/解锁 + 切回竖屏 =====
    if (isLandscape) {
        key(isLandscape) {
            ImmersiveScreen(
                modifier = modifier,
                setLandscape = ::setLandscape,
                landscapeLocked = landscapeLocked,
                onToggleLock = { landscapeLocked = !landscapeLocked },
                filteredWaveform = immersiveFilteredWaveform,
                rawWaveform = immersiveRawWaveform,
                useTestSignal = useTestSignal,
                filteredDisplayScale = filteredDisplayScale,
                showRefFiltered = showRefFiltered,
                onToggleShowRef = { showRefFiltered = !showRefFiltered },
                ampMin = ampMin,
                ampMax = ampMax,
                windowMinMs = windowMinMs,
                windowMaxMs = windowMaxMs,
                gestureOverlayVisible = gestureOverlayVisible,
                onGestureOverlayVisible = { gestureOverlayVisible = it },
                gestureAmp = gestureAmp,
                onGestureAmp = { gestureAmp = it },
                gestureWindow = gestureWindow,
                onGestureWindow = { gestureWindow = it },
                gestureMode = gestureMode,
                onGestureMode = { gestureMode = it },
                windowMs = immersiveWindowMs,
                waveformSpanMs = immersiveWaveformSpanMs,
                ampScale = immersiveAmpScale,
                onAmpScale = { v ->
                    filteredDisplayScale = v
                    audioViewModel.updateAmpSlider(v)
                },
                onWindowMs = { v -> audioViewModel.updateTimeSlider(v) },
                onTriggerEnabled = { v -> audioViewModel.setTriggerEnabled(v) },
            )
        }
        return
    }

    // ===== 竖屏：原界面保持不变（仅加了横屏切换按钮） =====

    Column(modifier = modifier.fillMaxSize()) {
        // ===== 固定波形区：两行 =====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            // 顶部：左侧“进入沉浸模式”，右侧“预设/关于/退出”
            var presetMenuExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { setLandscape(true) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("沉浸模式") }

                Spacer(modifier = Modifier.weight(1f))

                // 预设：放到“关于/开始”左边，下拉四个选项 + 提示语（文案保持不变）
                Box(
                    modifier = Modifier.widthIn(max = 72.dp) // 让一级“预设”入口整体更窄一点
                ) {
                    OutlinedButton(
                        onClick = { presetMenuExpanded = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("预设") }

                    DropdownMenu(
                        expanded = presetMenuExpanded,
                        onDismissRequest = { presetMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("导入") },
                            onClick = {
                                presetMenuExpanded = false
                                importPresetLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("导出") },
                            onClick = {
                                presetMenuExpanded = false
                                exportPresetLauncher.launch("oscope_preset.json")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("分享") },
                            onClick = {
                                presetMenuExpanded = false
                                presetShareName = "oscope_preset"
                                presetShareDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("默认") },
                            onClick = {
                                presetMenuExpanded = false
                                presetResetConfirmDialog = true
                            }
                        )

                        HorizontalDivider()

                        // 提示语：保持原文案不变
                        Text(
                            text = "预设是 .json 文件。先点“导出”保存到手机；如果要发给别人，也可以不导出直接点“分享”。对方收到后点“导入”选择该文件即可。", //不要改
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier
                                .widthIn(max = 320.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = { showAboutDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("关于") }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        // 避免误触：退出前确认
                        showExitConfirmDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        // 不要太亮的红色
                        containerColor = Color(0xFFB71C1C),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("退出") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // 原始波形标题：把实时值显示在括号里（替代悬浮文本）
                    text = "输入波形 ${rawScaleText}x",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                // Height control (70..130, step 10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { rawWaveHeightDp = decWaveHeight(rawWaveHeightDp) },
                        modifier = Modifier.size(28.dp)
                    ) { Text("-", style = MaterialTheme.typography.bodyMedium) }

                    Text(
                        text = "${rawWaveHeightDp}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    IconButton(
                        onClick = { rawWaveHeightDp = incWaveHeight(rawWaveHeightDp) },
                        modifier = Modifier.size(28.dp)
                    ) { Text("+", style = MaterialTheme.typography.bodyMedium) }
                }

            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rawWaveHeightDp.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(3.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                rawDragMode = 0
                            },
                            onDragEnd = { rawDragMode = 0 },
                            onDragCancel = { rawDragMode = 0 },
                            onDrag = { change, dragAmount ->
                                change.consume()

                                val dx = dragAmount.x
                                val dy = dragAmount.y

                                val invH = (1.0 / (size.height.toDouble().coerceAtLeast(1.0))).toFloat()
                                val invW = (1.0 / (size.width.toDouble().coerceAtLeast(1.0))).toFloat()

                                // 竖屏：纵向调幅度，横向调时间窗
                                if (rawDragMode == 0) {
                                    val ax = abs(dx)
                                    val ay = abs(dy)
                                    val thresholdPx = 4f
                                    if (ax + ay > thresholdPx) {
                                        rawDragMode = if (ay > ax * 1.2f) 1 else if (ax > ay * 1.2f) 2 else 0
                                    }
                                }

                                when (rawDragMode) {
                                    1 -> {
                                        val dy01 = (-(dy) * invH).coerceIn(-1f, 1f)
                                        // 对数手感：在 log 域里加法 => 线性域里乘法
                                        // dy01>0 表示向上滑 => 放大；dy01<0 => 缩小
                                        val k = 0.8f // tweak: lower => less sensitive
                                        val factor = exp(dy01 * k)
                                        rawDisplayScale = (currentRawDisplayScale * factor).coerceIn(ampMin, ampMax)
                                    }
                                    2 -> {
                                        val dx01 = (dx * invW).coerceIn(-1f, 1f)
                                        val baseStepMs = 40f
                                        val accel = (currentWindowMs / 40f).coerceIn(0.5f, 14f)
                                        val deltaMs = (-dx01) * baseStepMs * accel
                                        val nextWindow = (currentWindowMs + deltaMs).coerceIn(windowMinMs, windowMaxMs)
                                        audioViewModel.updateTimeSlider(nextWindow)
                                    }
                                }
                            }
                        )
                    }
             ) {
                 LiveWaveformView(
                     samplesFlow = audioViewModel.rawWaveform,
                     ampScale = rawDisplayScale,
                     lineColor = Color.Blue,
                     showReference = showRefRaw,
                     referenceAmpNormalized = rawDisplayScale.coerceAtLeast(1e-4f),
                     referenceColor = Color(0x22000000),
                     modifier = Modifier.fillMaxSize()
                 )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // 处理后波形标题：仅显示缩放（与示例一致）
                    text = "处理后波形 ${rawScaleText}x",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                // Height control (70..130, step 10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { filteredWaveHeightDp = decWaveHeight(filteredWaveHeightDp) },
                        modifier = Modifier.size(28.dp)
                    ) { Text("-", style = MaterialTheme.typography.bodyMedium) }

                    Text(
                        text = "${filteredWaveHeightDp}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    IconButton(
                        onClick = { filteredWaveHeightDp = incWaveHeight(filteredWaveHeightDp) },
                        modifier = Modifier.size(28.dp)
                    ) { Text("+", style = MaterialTheme.typography.bodyMedium) }
                }

            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(filteredWaveHeightDp.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(3.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                filteredDragMode = 0
                            },
                            onDragEnd = { filteredDragMode = 0 },
                            onDragCancel = { filteredDragMode = 0 },
                            onDrag = { change, dragAmount ->
                                change.consume()

                                val dx = dragAmount.x
                                val dy = dragAmount.y

                                val invH = (1.0 / (size.height.toDouble().coerceAtLeast(1.0))).toFloat()
                                val invW = (1.0 / (size.width.toDouble().coerceAtLeast(1.0))).toFloat()

                                // 竖屏：纵向调幅度，横向调时间窗
                                if (filteredDragMode == 0) {
                                    val ax = abs(dx)
                                    val ay = abs(dy)
                                    val thresholdPx = 4f
                                    if (ax + ay > thresholdPx) {
                                        filteredDragMode = if (ay > ax * 1.2f) 1 else if (ax > ay * 1.2f) 2 else 0
                                    }
                                }

                                when (filteredDragMode) {
                                    1 -> {
                                        val dy01 = (-(dy) * invH).coerceIn(-1f, 1f)
                                        val k = 0.8f // tweak: lower => less sensitive
                                        val factor = exp(dy01 * k)
                                        filteredDisplayScale = (currentFilteredDisplayScale * factor).coerceIn(ampMin, ampMax)
                                    }
                                    2 -> {
                                        val dx01 = (dx * invW).coerceIn(-1f, 1f)
                                        val baseStepMs = 40f
                                        val accel = (currentWindowMs / 40f).coerceIn(0.5f, 14f)
                                        val deltaMs = (-dx01) * baseStepMs * accel
                                        val nextWindow = (currentWindowMs + deltaMs).coerceIn(windowMinMs, windowMaxMs)
                                        audioViewModel.updateTimeSlider(nextWindow)
                                    }
                                }
                            }
                        )
                    }
             ) {
                 LiveWaveformView(
                     samplesFlow = audioViewModel.filteredWaveform,
                     ampScale = filteredDisplayScale,
                     lineColor = Color.Red,
                     showReference = showRefFiltered,
                     referenceAmpNormalized = filteredDisplayScale.coerceAtLeast(1e-4f),
                     referenceColor = Color(0x22FF0000),
                     modifier = Modifier.fillMaxSize()
                 )
            }

            CaptureDiagnosticsLine(audioViewModel = audioViewModel)

            // 竖屏波形手势浮层：已移除（改为显示在标题括号里）
            // if (portraitOverlayVisible && portraitOverlayText.isNotBlank()) {
            //     Box(
            //         modifier = Modifier
            //             .fillMaxWidth()
            //             .padding(top = 4.dp),
            //         contentAlignment = Alignment.Center
            //     ) {
            //         Box(
            //             modifier = Modifier
            //                 .clip(RoundedCornerShape(10.dp))
            //                 .background(Color(0xAA000000))
            //                 .padding(horizontal = 12.dp, vertical = 8.dp)
            //         ) {
            //             Text(
            //                 text = portraitOverlayText,
            //                 color = Color.White,
            //                 style = MaterialTheme.typography.bodyMedium,
            //                 textAlign = TextAlign.Center
            //             )
            //         }
            //     }
            // }
        }

        HorizontalDivider()

        // ===== 设置区：滚动，间距更密 =====
        // 说明：为了避免内容显示到系统导航栏/返回键区域，底部安全区放在滚动容器“外面”。
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(settingsScroll, enabled = !eqGraphDragging)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 控制按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val startStopAction = {
                        if (useTestSignal) {
                            // 测试模式下，“开始/停止”用于退出测试
                            audioViewModel.toggleVvvfTestSignal()
                        } else if (useImportedSignal) {
                            audioViewModel.stopImportedSignalInput()
                        } else if (isRunning) {
                            audioViewModel.stopEngine()
                        } else {
                            val recordAudioGranted = permissionsState.permissions
                                .firstOrNull { it.permission == Manifest.permission.RECORD_AUDIO }
                                ?.status is PermissionStatus.Granted
                            if (recordAudioGranted) audioViewModel.startEngine(context)
                            else permissionsState.launchMultiplePermissionRequest()
                        }
                    }

                    Button(
                        onClick = startStopAction,
                        colors = ButtonDefaults.buttonColors(
                            // 用更柔和的颜色，避免按钮过亮
                            containerColor = if (isRunning || useImportedSignal || useTestSignal) Color(0xFFC62828) else Color(0xFF2E7D32)
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (useTestSignal || useImportedSignal || isRunning) "停止" else "开始", fontSize = 15.sp) }

                    Button(
                        onClick = {
                            if (isRecording) {
                                audioViewModel.stopRecording()
                            } else {
                                val recordAudioGranted = permissionsState.permissions
                                    .firstOrNull { it.permission == Manifest.permission.RECORD_AUDIO }
                                    ?.status is PermissionStatus.Granted
                                if (!useImportedSignal && !recordAudioGranted) {
                                    permissionsState.launchMultiplePermissionRequest()
                                    return@Button
                                }
                                audioViewModel.startRecording(context)
                            }
                        },
                        enabled = isRunning || useImportedSignal,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (isRecording) "结束" else "录音", fontSize = 15.sp) }

                    Button(
                        onClick = { audioViewModel.toggleMonitoring() },
                        enabled = isRunning || useImportedSignal,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (isMonitoring) "关监听" else "监听", fontSize = 15.sp) }


                    Button(
                        onClick = { importAudioLauncher.launch(arrayOf("audio/*")) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (useImportedSignal) Color(0xFF1565C0) else MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.weight(1f),
                        enabled = !isImportingAudio
                    ) {
                        Text(
                            when {
                                isImportingAudio -> "导入中"
                                useImportedSignal -> "音频输入"
                                else -> "导入音频"
                            },
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    TextButton(
                        onClick = { showRecordList = true },
                        enabled = recordings.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) { Text("列表") }
                }


                if (engineError != null) {
                    Text(
                        text = "错误: ${engineError}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFC62828),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (useImportedSignal) {
                    Text(
                        text = "输入源: 导入音频${importedAudioLabel?.let { " ($it)" } ?: ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF1565C0),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 低通：标题行放“频率标题 + 开关”在左侧、阶数在右侧；下一行只放整行滑块
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ClickToEditNumberText(
                            text = "低通 ${formatLowPassHz(lowPassDisplayHz)}Hz",
                            initialText = formatLowPassHz(lowPassCutoff),
                            title = "设置低通截止频率",
                            unit = "Hz",
                            parseAndClamp = { s ->
                                s.trim().replace(",", ".").toFloatOrNull()?.let { snapLowPassHz(it) }
                            },
                            onValue = { hz ->
                                // 直接更新 ViewModel + 同步滑块位置
                                lpDragging = false
                                val snapped = snapLowPassHz(hz)
                                audioViewModel.updateLowPassSlider(snapped)
                                lpFreq01 = hzToSliderBlend(snapped, lowPassMin, lowPassMax, linearWeight = lowPassLinearW)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f),
                            trailingIcon = { InfoIconButton("低通滤波截止频率", "低通滤波可以阻挡高频，使低频通过，截止频率越低效果越明显。") }
                        )
                        Switch(
                            checked = lowPassEnabled,
                            onCheckedChange = { audioViewModel.toggleLowPass(it) }
                        )
                    }
                    FilterOrderSelector(
                        order = lowPassOrder,
                        orderOptions = orderOptions,
                        onOrderChange = { audioViewModel.setLowPassOrder(it) }
                    )
                }
                LowHighPassRow(
                    // 低通不要完全对数：混合一点线性，让中高频段更“好调”
                    freq01 = lpFreq01,
                    onFreq01Change = {
                        lpDragging = true
                        lpFreq01 = it
                    },
                    onFreq01ChangeFinished = {
                        lpDragging = false
                        // 最终再提交一次，保证松手瞬间也精确
                        audioViewModel.updateLowPassSlider(snapLowPassHz(sliderToHzBlend(lpFreq01, lowPassMin, lowPassMax, linearWeight = lowPassLinearW)))
                    },
                )

                // 高通：标题行放“频率标题 + 开关”在左侧、阶数在右侧；下一行只放整行滑块
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ClickToEditNumberText(
                            text = "高通 ${formatHighPassHz(highPassDisplayHz)}Hz",
                            initialText = formatHighPassHz(highPassCutoff),
                            title = "设置高通截止频率",
                            unit = "Hz",
                            parseAndClamp = { s ->
                                s.trim().replace(",", ".").toFloatOrNull()?.let { snapHighPassHz(it) }
                            },
                            onValue = { hz ->
                                hpDragging = false
                                val snapped = snapHighPassHz(hz)
                                audioViewModel.updateHighPassSlider(snapped)
                                hpFreq01 = hzToSlider(snapped, highPassMin, highPassMax)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f),
                            trailingIcon = { InfoIconButton("高通滤波截止频率", "高通滤波可以阻挡低频，使高频通过，截止频率越高效果越明显。") }
                        )
                        Switch(
                            checked = highPassEnabled,
                            onCheckedChange = { audioViewModel.toggleHighPass(it) }
                        )
                    }
                    FilterOrderSelector(
                        order = highPassOrder,
                        orderOptions = orderOptions,
                        onOrderChange = { audioViewModel.setHighPassOrder(it) }
                    )
                }
                LowHighPassRow(
                    freq01 = hpFreq01,
                    onFreq01Change = {
                        hpDragging = true
                        hpFreq01 = it
                    },
                    onFreq01ChangeFinished = {
                        hpDragging = false
                        audioViewModel.updateHighPassSlider(snapHighPassHz(sliderToHz(hpFreq01, highPassMin, highPassMax)))
                    },
                )

                // ===== EQ：三段参数均衡器（紧凑） =====
                EqPanel(
                    enabled = eqEnabled,
                    onEnabledChange = { audioViewModel.setEqEnabled(it) },
                    bands = eqBands,
                    onReset = { audioViewModel.resetEq() },
                    onBandEnabled = { id, en -> audioViewModel.setEqBandEnabled(id, en) },
                    onBandType = { id, type -> audioViewModel.setEqBandType(id, type) },
                    onBandFreq = { id, hz -> audioViewModel.setEqBandFreq(id, hz) },
                    onBandGainDb = { id, db -> audioViewModel.setEqBandGainDb(id, db) },
                    onBandQ = { id, q -> audioViewModel.setEqBandQ(id, q) },
                    logToSlider = ::logToSlider,
                    sliderToLog = ::sliderToHz,
                    onGraphDragging = { audioViewModel.setEqGraphDragging(it) },
                    // pass current global/filter state so the graph matches actual processing
                    filterGain = filterGain,
                    lowPassEnabled = lowPassEnabled,
                    lowPassCutoff = lowPassCutoff,
                    lowPassOrder = lowPassOrder,
                    highPassEnabled = highPassEnabled,
                    highPassCutoff = highPassCutoff,
                    highPassOrder = highPassOrder,
                    sampleRate = 44100,
                )

                // ===== 处理后增益（仅滑块，手势不再控制） =====
                run {
                    val gainDb = gainToDb(filterGain)
                    val displayGainDb = rememberDisplayLowPass(gainDb, resetKey = "filterGainDb", alpha = 0.24f, snapThreshold = 0.03f)
                    val displayGainX = dbToGain(displayGainDb)
                    val gainDbText = String.format(Locale.US, "%+.1f", displayGainDb)
                    val gainXText = String.format(Locale.US, "%.1f", displayGainX)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ClickToEditNumberText(
                            text = "处理后增益 ${gainDbText}dB (${gainXText}x)",
                            initialText = String.format(Locale.US, "%.2f", gainDb),
                            title = "设置处理后增益",
                            unit = "dB",
                            parseAndClamp = { s ->
                                s.trim().replace(",", ".").toFloatOrNull()?.coerceIn(-20f, 40f)
                            },
                            onValue = { db ->
                                audioViewModel.updateFilterGain(dbToGain(db).coerceIn(gainMin, gainMax))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f),
                            trailingIcon = { InfoIconButton("处理后增益", "它会整体调节处理后波形的音量大小") }
                        )
                        TextButton(
                            // 0 dB = 1.0x
                            onClick = { audioViewModel.updateFilterGain(dbToGain(0f).coerceIn(gainMin, gainMax)) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("重置") }
                    }

                    // range: -20dB .. 40dB
                    val gainDbMin = -20f
                    val gainDbMax = 40f
                    val powerCurve = 1.5f // Higher = more resolution at low end

                    // Non-linear mapping: p = ((db - min) / range)^(1/k)
                    // db = min + range * p^k
                    fun dbToSlider(db: Float): Float {
                        val d = db.coerceIn(gainDbMin, gainDbMax)
                        val norm = (d - gainDbMin) / (gainDbMax - gainDbMin)
                        return norm.pow(1f / powerCurve)
                    }

                    fun sliderToDb(p01: Float): Float {
                        val p = p01.coerceIn(0f, 1f)
                        val norm = p.pow(powerCurve)
                        return gainDbMin + norm * (gainDbMax - gainDbMin)
                    }

                    val v01 = dbToSlider(gainDb)
                    OscopeSlider(
                        value = v01,
                        onValueChange = { p ->
                            val db = sliderToDb(p)
                            audioViewModel.updateFilterGain(dbToGain(db).coerceIn(gainMin, gainMax))
                        },
                        valueRange = 0f..1f,
                        steps = 0,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ===== 时间窗口（滑块 + 重置） =====
                run {
                    val displayWindowMs = rememberDisplayLowPass(windowMs, resetKey = "windowMs", alpha = 0.24f, snapThreshold = 0.05f)
                    val winText = if (displayWindowMs < 10f) String.format(Locale.US, "%.1f", displayWindowMs) else String.format(Locale.US, "%.0f", displayWindowMs)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ClickToEditNumberText(
                            text = "时间窗口 ${winText}ms",
                            initialText = winText,
                            title = "设置时间窗口",
                            unit = "ms",
                            parseAndClamp = { s -> s.trim().replace(",", ".").toFloatOrNull()?.coerceIn(windowMinMs, windowMaxMs) },
                            onValue = { v -> audioViewModel.updateTimeSlider(v) },
                            style = MaterialTheme.typography.bodyMedium,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f),
                            trailingIcon = { InfoIconButton("时间窗口", "窗口越长显示的周期越多，但画面更新会更慢。") }
                        )
                        TextButton(
                            // 重置为 20ms
                            onClick = { audioViewModel.updateTimeSlider(20f) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("重置") }
                    }

                    // log-ish slider mapping
                    val p01 = hzToSlider(windowMs, windowMinMs, windowMaxMs)
                    OscopeSlider(
                        value = p01,
                        onValueChange = { p -> audioViewModel.updateTimeSlider(sliderToHz(p, windowMinMs, windowMaxMs)) },
                        valueRange = 0f..1f,
                        steps = 180,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ===== 预设：导入/导出/直接分享/默认（放到最下方） =====
                // （已移动到顶部“预设”下拉菜单里，这里移除以避免重复）

                // 分享预设：重命名弹窗
                if (presetShareDialog) {
                    AlertDialog(
                        onDismissRequest = { presetShareDialog = false },
                        title = { Text("分享预设") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = presetShareName,
                                    onValueChange = { presetShareName = it },
                                    singleLine = true,
                                    label = { Text("文件名（不含 .json）") },
                                    supportingText = { Text("会生成并分享一个 .json 预设文件") }
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val file = exportCurrentPresetToCache(presetShareName)
                                    if (file != null) {
                                        showCenterToast("已生成并分享预设：${file.name}")
                                        shareAnyFile(file, "application/json")
                                    } else {
                                        showCenterToast("分享失败：无法生成预设文件")
                                    }
                                    presetShareDialog = false
                                }
                            ) { Text("直接分享") }
                        },
                        dismissButton = {
                            TextButton(onClick = { presetShareDialog = false }) { Text("取消") }
                        }
                    )
                }

                // 恢复默认：确认弹窗（避免误触）
                if (presetResetConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { presetResetConfirmDialog = false },
                        title = { Text("恢复默认") },
                        text = { Text("确定要把当前滤波器参数恢复为默认值吗？") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    audioViewModel.resetFilterPresetToDefault()
                                    showCenterToast("已恢复默认")
                                    presetResetConfirmDialog = false
                                }
                            ) { Text("确定") }
                        },
                        dismissButton = {
                            TextButton(onClick = { presetResetConfirmDialog = false }) { Text("取消") }
                        }
                    )
                }

                // ===== 录音列表弹窗 =====
                if (showRecordList) {
                    AlertDialog(
                        onDismissRequest = { showRecordList = false },
                        title = { Text("录音列表") },
                        text = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 220.dp, max = 520.dp)
                            ) {
                                RecordingsListView(
                                    recordings = recordings.sortedByDescending { it.date },
                                    onItemClick = { /* 仅用于展开，不做额外动作 */ },
                                    onPlayClick = { /* playback disabled */ },
                                    onShareClick = { clip ->
                                        // 复用现有分享录音逻辑
                                        shareRecording(clip)
                                    },
                                    onRenameClick = { clip ->
                                        renameTarget = clip
                                        renameText = clip.fileName.substringBeforeLast('.')
                                    },
                                    onDeleteClick = { clip ->
                                        deleteTarget = clip
                                    },
                                    playingPositionMs = playbackPositionMs,
                                    playingDurationMs = playbackDurationMs,
                                    onSeek = { /* playback disabled */ },
                                    playingId = playingId
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showRecordList = false }) { Text("关闭") }
                        }
                    )
                }

                // ===== 重命名弹窗 =====
                if (renameTarget != null) {
                    val clip = renameTarget!!
                    AlertDialog(
                        onDismissRequest = { renameTarget = null },
                        title = { Text("重命名") },
                        text = {
                            OutlinedTextField(
                                value = renameText,
                                onValueChange = { renameText = it },
                                singleLine = true,
                                label = { Text("文件名") },
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val name = renameText.trim()
                                    if (name.isNotEmpty()) {
                                        audioViewModel.renameRecording(clip.id, name)
                                    }
                                    renameTarget = null
                                }
                            ) { Text("确定") }
                        },
                        dismissButton = {
                            TextButton(onClick = { renameTarget = null }) { Text("取消") }
                        }
                    )
                }

                // ===== 删除确认 =====
                if (deleteTarget != null) {
                    val clip = deleteTarget!!
                    AlertDialog(
                        onDismissRequest = { deleteTarget = null },
                        title = { Text("删除录音") },
                        text = { Text("确定要删除“${clip.fileName}”吗？此操作不可恢复。") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    audioViewModel.deleteRecording(clip.id)
                                    deleteTarget = null
                                }
                            ) { Text("删除") }
                        },
                        dismissButton = {
                            TextButton(onClick = { deleteTarget = null }) { Text("取消") }
                        }
                    )
                }

                // ===== Recording format & sample rate (bottom of main page) =====
                run {
                    var fmtMenu by remember { mutableStateOf(false) }
                    val srOptions = listOf(16000, 22050, 32000, 44100, 48000)
                    var srMenu by remember { mutableStateOf(false) }
                    val publishRateOptions = AudioEngineViewModel.PublishRateOption.entries
                    var publishRateMenu by remember { mutableStateOf(false) }
                    val testSignalOptions = AudioEngineViewModel.TestSignalPreset.entries
                    var testSignalMenu by remember { mutableStateOf(false) }

                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "录音格式",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.widthIn(min = 64.dp)
                        )
                        Box {
                            OutlinedButton(
                                onClick = { fmtMenu = true },
                                enabled = !isRecording,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(recordingFormat.label)
                            }
                            DropdownMenu(expanded = fmtMenu, onDismissRequest = { fmtMenu = false }) {
                                listOf(
                                    AudioEngineViewModel.RecordingFormat.WAV,
                                    AudioEngineViewModel.RecordingFormat.M4A_AAC,
                                ).forEach { fmt ->
                                    DropdownMenuItem(
                                        text = { Text(fmt.label) },
                                        onClick = {
                                            fmtMenu = false
                                            audioViewModel.setRecordingFormat(fmt)
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        Text(
                            text = "采样率",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Box {
                            OutlinedButton(
                                onClick = { srMenu = true },
                                enabled = !isRecording,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("${recordingSampleRate}Hz")
                            }
                            DropdownMenu(expanded = srMenu, onDismissRequest = { srMenu = false }) {
                                srOptions.forEach { sr ->
                                    DropdownMenuItem(
                                        text = { Text("${sr}Hz") },
                                        onClick = {
                                            srMenu = false
                                            audioViewModel.setRecordingSampleRate(sr)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (isRecording) {
                        Text(
                            text = "录音中无法修改格式/采样率",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "波形刷新率",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.widthIn(min = 64.dp)
                        )
                        Box {
                            OutlinedButton(
                                onClick = { publishRateMenu = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("${publishRateOption.hz}Hz")
                            }
                            DropdownMenu(
                                expanded = publishRateMenu,
                                onDismissRequest = { publishRateMenu = false }
                            ) {
                                publishRateOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text("${option.hz}Hz") },
                                        onClick = {
                                            publishRateMenu = false
                                            audioViewModel.setPublishRateOption(option)
                                        }
                                    )
                                }
                            }
                        }

                        Text(
                            text = "越高越流畅，但更耗性能",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "测试波形",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.widthIn(min = 64.dp)
                        )
                        Box {
                            OutlinedButton(
                                onClick = { testSignalMenu = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(testSignalPreset.label)
                            }
                            DropdownMenu(
                                expanded = testSignalMenu,
                                onDismissRequest = { testSignalMenu = false }
                            ) {
                                testSignalOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            testSignalMenu = false
                                            audioViewModel.setTestSignalPreset(option)
                                        }
                                    )
                                }
                            }
                        }

                        Text(
                            text = "可切换SPWM异步调制或同步1分频",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "测试模式",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.widthIn(min = 64.dp)
                        )
                        Box {
                            OutlinedButton(
                                onClick = { audioViewModel.toggleVvvfTestSignal() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(if (useTestSignal) "测试中" else "测试")
                            }
                        }

                        Text(
                            text = "点击切换测试信号（SPWM）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
             }

             // 底部安全区：直接占住系统导航栏/手势返回区域（以及少量额外余量），确保该区域不绘制任何内容。
             Spacer(
                 modifier = Modifier
                     .fillMaxWidth()
                     .navigationBarsPadding()
                     .height(4.dp)
             )
        }
    }

    if (showStartupNoteDialog) {
        AlertDialog(
            onDismissRequest = { showStartupNoteDialog = false },
            title = { Text("提示") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(startupNoteText)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .toggleable(
                                value = doNotShowStartupNoteAgain,
                                role = Role.Checkbox,
                                onValueChange = { doNotShowStartupNoteAgain = it }
                            )
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Checkbox(
                            checked = doNotShowStartupNoteAgain,
                            onCheckedChange = null
                        )
                        Text(
                            text = "不再提示",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        startupPrefs.edit { putBoolean(KEY_HIDE_STARTUP_NOTE, doNotShowStartupNoteAgain) }
                        showStartupNoteDialog = false
                    }
                ) { Text("知道了") }
            }
        )
    }

    val aboutDialogMaxHeight = minOf(configuration.screenHeightDp.dp * 0.55f, 360.dp)

    if (showAboutDialog) {
        val aboutDialogScrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = aboutDialogMaxHeight)
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(aboutDialogScrollState),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text("Wave Studio v0.11.2 by 磁拾音器研究所")
                        Text("提示：使用前请授予麦克风权限。")
                        Text("")
                        Text("0.11.2版本主要更新内容如下：")
                        Text("- 修复了监听模式卡顿的问题")
                        Text("")
                        Text("0.11.1版本主要更新内容如下：")
                        Text("- 导入音频可直接替换，无需停止")
                        Text("- 去掉导入音频 5 分钟截断限制")
                        Text("- 优化了导入后卡顿/断续问题")
                        Text("- Trigger 模式帧率提高")
                        Text("- 修复了均衡器Q值太大时的异常")
                        Text("")
                        Text("参与开发人员（B站名）：02B4806長-02001、某地铁迷_、莓喵の小风扇、TEG-28WG01等")
                        Text("")
                        Text("磁拾音器QQ交流群：762852552")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("磁拾音器研究所官网：")
                            val url = "https://www.mhrri.org/"
                            val linkText = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline
                                    )
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
                                    }
                            )
                        }
                        Text("")
                        Text("预设配置下载：（暂时预留）")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAboutDialog = false
                        openStartupNoteDialog()
                    }
                ) {
                    Text("显示提示弹窗")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("确定") }
            }
        )
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("退出") },
            text = { Text("确定要退出吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirmDialog = false
                        // 尽量优雅退出：先停引擎/录音/监听
                        try { audioViewModel.stopEngine() } catch (_: Throwable) {}
                        try { activity?.finish() } catch (_: Throwable) {}
                    }
                ) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ImmersiveScreen(
    modifier: Modifier,
    setLandscape: (Boolean) -> Unit,
    landscapeLocked: Boolean,
    onToggleLock: () -> Unit,
    filteredWaveform: FloatArray,
    rawWaveform: FloatArray,
    useTestSignal: Boolean,
    filteredDisplayScale: Float,
    showRefFiltered: Boolean,
    onToggleShowRef: () -> Unit,
    ampMin: Float,
    ampMax: Float,
    windowMinMs: Float,
    windowMaxMs: Float,
    gestureOverlayVisible: Boolean,
    onGestureOverlayVisible: (Boolean) -> Unit,
    gestureAmp: Float,
    onGestureAmp: (Float) -> Unit,
    gestureWindow: Float,
    onGestureWindow: (Float) -> Unit,
    gestureMode: Int,
    onGestureMode: (Int) -> Unit,
    windowMs: Float,
    waveformSpanMs: Float,
    ampScale: Float,
    onAmpScale: (Float) -> Unit,
    onWindowMs: (Float) -> Unit,
    onTriggerEnabled: (Boolean) -> Unit,
) {
    // Display-only snapping (keeps gesture feel continuous).
    fun snapForDisplay(v: Float): Float {
        val clamped = v.coerceIn(ampMin, ampMax)
        val step = when {
            clamped < 1f -> 0.05f
            clamped < 2f -> 0.1f
            clamped < 5f -> 0.2f
            clamped < 10f -> 0.5f
            else -> 1f
        }
        return (round(clamped / step) * step).coerceIn(ampMin, ampMax)
    }

    val currentFilteredDisplayScale by rememberUpdatedState(filteredDisplayScale)
    val currentGestureWindow by rememberUpdatedState(gestureWindow)
    val currentGestureMode by rememberUpdatedState(gestureMode)
    val currentAmpScale by rememberUpdatedState(ampScale)
    val currentWindowMs by rememberUpdatedState(windowMs)
    val context = LocalContext.current
    val triggerPrefs = remember(context) {
        context.applicationContext.getSharedPreferences(SETTINGS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    // ===== 触发开关：开/关（开启时使用升沿 + 自相关辅助） =====
    fun parseTriggerMode(name: String): NewTriggerEngine.Mode = when (name) {
        NewTriggerEngine.Mode.OFF.name -> NewTriggerEngine.Mode.OFF
        else -> NewTriggerEngine.Mode.RISING
    }

    val triggerModeNameInitial = remember(triggerPrefs) {
        triggerPrefs.getString(KEY_TRIGGER_MODE_NAME, NewTriggerEngine.Mode.OFF.name)
            ?: NewTriggerEngine.Mode.OFF.name
    }
    val triggerUseAutocorrInitial = remember(triggerPrefs) {
        triggerPrefs.getBoolean(KEY_TRIGGER_USE_AUTOCORR, true)
    }
    val triggerStrongLowPassHzInitial = remember(triggerPrefs) {
        triggerPrefs.getFloat(KEY_TRIGGER_STRONG_LOWPASS_HZ, 240f)
    }
    val triggerPreTriggerRatioInitial = remember(triggerPrefs) {
        triggerPrefs.getFloat(KEY_TRIGGER_PRE_TRIGGER_RATIO, 0.16f)
    }
    val triggerHysteresisRatioInitial = remember(triggerPrefs) {
        triggerPrefs.getFloat(KEY_TRIGGER_HYSTERESIS_RATIO, 0.16f)
    }
    val triggerHoldoffRatioInitial = remember(triggerPrefs) {
        triggerPrefs.getFloat(KEY_TRIGGER_HOLDOFF_RATIO, 0.60f)
    }
    val triggerAutocorrRefreshFramesInitial = remember(triggerPrefs) {
        triggerPrefs.getInt(KEY_TRIGGER_AUTOCORR_REFRESH_FRAMES, 8)
    }
    val triggerAutocorrMaxSamplesInitial = remember(triggerPrefs) {
        triggerPrefs.getInt(KEY_TRIGGER_AUTOCORR_MAX_SAMPLES, 512)
    }

    var triggerModeName by rememberSaveable { mutableStateOf(triggerModeNameInitial) }
    var showTriggerAdvancedDialog by rememberSaveable { mutableStateOf(false) }
    var triggerUseAutocorr by rememberSaveable { mutableStateOf(triggerUseAutocorrInitial) }
    var triggerStrongLowPassHz by rememberSaveable { mutableStateOf(triggerStrongLowPassHzInitial) }
    var triggerPreTriggerRatio by rememberSaveable { mutableStateOf(triggerPreTriggerRatioInitial) }
    var triggerHysteresisRatio by rememberSaveable { mutableStateOf(triggerHysteresisRatioInitial) }
    var triggerHoldoffRatio by rememberSaveable { mutableStateOf(triggerHoldoffRatioInitial) }
    var triggerAutocorrRefreshFrames by rememberSaveable { mutableIntStateOf(triggerAutocorrRefreshFramesInitial) }
    var triggerAutocorrMaxSamples by rememberSaveable { mutableIntStateOf(triggerAutocorrMaxSamplesInitial) }

    LaunchedEffect(
        triggerModeName,
        triggerUseAutocorr,
        triggerStrongLowPassHz,
        triggerPreTriggerRatio,
        triggerHysteresisRatio,
        triggerHoldoffRatio,
        triggerAutocorrRefreshFrames,
        triggerAutocorrMaxSamples,
    ) {
        triggerPrefs.edit {
            putString(KEY_TRIGGER_MODE_NAME, triggerModeName)
            putBoolean(KEY_TRIGGER_USE_AUTOCORR, triggerUseAutocorr)
            putFloat(KEY_TRIGGER_STRONG_LOWPASS_HZ, triggerStrongLowPassHz)
            putFloat(KEY_TRIGGER_PRE_TRIGGER_RATIO, triggerPreTriggerRatio)
            putFloat(KEY_TRIGGER_HYSTERESIS_RATIO, triggerHysteresisRatio)
            putFloat(KEY_TRIGGER_HOLDOFF_RATIO, triggerHoldoffRatio)
            putInt(KEY_TRIGGER_AUTOCORR_REFRESH_FRAMES, triggerAutocorrRefreshFrames)
            putInt(KEY_TRIGGER_AUTOCORR_MAX_SAMPLES, triggerAutocorrMaxSamples)
        }
    }
    val triggerMode = parseTriggerMode(triggerModeName)
    val triggerEngine = remember(triggerMode) { NewTriggerEngine(nominalWindowSize = 512) }

    LaunchedEffect(triggerMode) {
        onTriggerEnabled(triggerMode != NewTriggerEngine.Mode.OFF)
    }

    fun nextTriggerModeName(name: String): String = when (name) {
        NewTriggerEngine.Mode.OFF.name -> "ON"
        else -> NewTriggerEngine.Mode.OFF.name
    }

    fun buildTriggeredWindow(
        source: FloatArray,
        mode: NewTriggerEngine.Mode,
        waveformSpanMs: Float,
    ): Pair<FloatArray, NewTriggerEngine.Result?> {
        val nominalWindowSize = 512
        if (source.isEmpty()) return source to null
        if (source.size <= nominalWindowSize) return source to null

        val preferAutocorrelation = triggerUseAutocorr && mode != NewTriggerEngine.Mode.OFF

        val cfg = NewTriggerEngine.Config(
            mode = if (mode == NewTriggerEngine.Mode.OFF) NewTriggerEngine.Mode.OFF else NewTriggerEngine.Mode.RISING,
            sampleRateHz = (source.size.toFloat() / (waveformSpanMs / 1000f).coerceAtLeast(1e-4f)).coerceAtLeast(1000f),
            strongLowPassHz = if (preferAutocorrelation) triggerStrongLowPassHz else 160f,
            fMaxHz = if (preferAutocorrelation) (triggerStrongLowPassHz + 40f).coerceAtLeast(260f) else 2000f,
            useAutocorrelation = preferAutocorrelation,
            autocorrRefreshFrames = triggerAutocorrRefreshFrames.coerceIn(1, 32),
            autocorrMaxSamples = triggerAutocorrMaxSamples.coerceIn(128, 2048),
            preTriggerRatio = triggerPreTriggerRatio.coerceIn(0.08f, 0.35f),
            hysteresisRatio = triggerHysteresisRatio.coerceIn(0.05f, 0.40f),
            holdoffRatio = triggerHoldoffRatio.coerceIn(0.20f, 0.85f),
        )
        val res = triggerEngine.process(source, cfg)
        val window = if (mode == NewTriggerEngine.Mode.OFF) {
            val maxStart = (source.size - nominalWindowSize).coerceAtLeast(0)
            source.copyOfRange(maxStart, maxStart + nominalWindowSize)
        } else {
            triggerEngine.extractTriggeredWindow(source, res)
        }
        return window to if (mode == NewTriggerEngine.Mode.OFF) null else res
     }


     Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(landscapeLocked, ampMin, ampMax, windowMinMs, windowMaxMs) {
                if (landscapeLocked) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        onGestureOverlayVisible(true)
                        onGestureAmp(currentAmpScale)
                        onGestureWindow(currentWindowMs)
                        onGestureMode(0)
                    },
                    onDragEnd = {
                        onGestureOverlayVisible(false)
                        onGestureMode(0)
                    },
                    onDragCancel = {
                        onGestureOverlayVisible(false)
                        onGestureMode(0)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()

                        val dx = dragAmount.x
                        val dy = dragAmount.y

                        val invHF = (1.0 / ((size.height.toFloat()).let { if (it > 1f) it else 1f }).toDouble()).toFloat()
                        val invWF = (1.0 / ((size.width.toFloat()).let { if (it > 1f) it else 1f }).toDouble()).toFloat()

                        var mode = currentGestureMode
                        if (mode == 0) {
                            val ax = abs(dx)
                            val ay = abs(dy)
                            val thresholdPx = 6f
                            if (ax + ay > thresholdPx) {
                                mode = if (ay > ax * 1.2f) 1 else if (ax > ay * 1.2f) 2 else 0
                                onGestureMode(mode)
                            }
                        }

                        when (mode) {
                            1 -> {
                                val dy01 = (-(dy) * invHF).coerceIn(-1f, 1f)
                                val k = 0.8f
                                val factor = exp(dy01 * k)
                                val nextAmp = (currentFilteredDisplayScale * factor).coerceIn(ampMin, ampMax)
                                onGestureAmp(snapForDisplay(nextAmp))
                                onAmpScale(nextAmp)
                            }
                            2 -> {
                                val dx01 = (dx * invWF).coerceIn(-1f, 1f)
                                val baseStepMs = 40f
                                val accel = (currentGestureWindow / 40f).coerceIn(0.5f, 14f)
                                val deltaMs = (-dx01) * baseStepMs * accel
                                val nextWindow = (currentGestureWindow + deltaMs).coerceIn(windowMinMs, windowMaxMs)
                                onGestureWindow(nextWindow)
                                onWindowMs(nextWindow)
                            }
                        }
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 12.dp, start = 12.dp, end = 12.dp)
        ) {
            val immersiveRef = filteredDisplayScale.coerceAtLeast(1e-4f)
            val immersiveSamples = filteredWaveform
            val triggeredWindowAndResult = buildTriggeredWindow(immersiveSamples, triggerMode, waveformSpanMs)
            val displaySamples = triggeredWindowAndResult.first
            val triggerResult = triggeredWindowAndResult.second

            WaveformView(
                samples = displaySamples,
                ampScale = filteredDisplayScale,
                lineColor = Color.White,
                modifier = Modifier.fillMaxSize(),
                showReferenceWhenBelow1x = showRefFiltered,
                referenceAmpNormalized = immersiveRef,
                referenceColor = Color(0x44FFFFFF),
                referenceDashed = true,
                lineWidthDp = 2f,
         )

            if (triggerMode != NewTriggerEngine.Mode.OFF && triggerResult != null) {
                val conf = String.format(Locale.US, "%.2f", triggerResult.confidence)
                val hz = String.format(Locale.US, "%.1f", triggerResult.freqHz)
                Text(
                    text = "TRG ON  f=${hz}Hz  per=${triggerResult.periodSamples}  s=${triggerResult.startIndex}  a=${triggerResult.anchorIndex}  lock=${triggerResult.locked}  c=$conf",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x66000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (!landscapeLocked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { setLandscape(false) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) { Text("普通模式", color = Color.White) }

                Spacer(Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x44000000))
                ) {
                    TextButton(
                        onClick = { triggerModeName = nextTriggerModeName(triggerModeName) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val label = when (triggerMode) {
                                NewTriggerEngine.Mode.OFF -> "Trigger 关"
                                NewTriggerEngine.Mode.RISING,
                                NewTriggerEngine.Mode.FALLING -> "Trigger 开"
                            }
                            Text(label, color = Color.White)
                            InfoIconButton("Trigger 开关", "开启后会使用升沿触发，并结合自相关与历史相位来稳定触发点。")
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                OutlinedButton(
                    onClick = { showTriggerAdvancedDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(if (triggerUseAutocorr) "增强·开" else "增强·关", color = Color.White)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {}
        }

        if (showTriggerAdvancedDialog) {
            AlertDialog(
                onDismissRequest = { showTriggerAdvancedDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Trigger 增强")
                        InfoIconButton("Trigger 增强", "这里调的是触发稳定性相关参数。自相关辅助会帮助识别基波周期；滞回、保留间隔和刷新频率会影响稳定性与响应速度。")
                    }
                },
                text = {
                    val dialogWidth = LocalConfiguration.current.screenWidthDp.dp
                    val columns = when {
                        dialogWidth < 420.dp -> 1
                        dialogWidth < 700.dp -> 2
                        else -> 3
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 760.dp)
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("自相关辅助")
                                InfoIconButton("自相关辅助", "开启后，Trigger 会先用自相关估计当前基频周期，再辅助升沿触发，更适合基频缓慢变化的 VVVF 波形。")
                            }
                            Switch(
                                checked = triggerUseAutocorr,
                                onCheckedChange = { triggerUseAutocorr = it }
                            )
                        }

                        when (columns) {
                                1 -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("预触发比例: ${String.format(Locale.US, "%.2f", triggerPreTriggerRatio)}")
                                                InfoIconButton("预触发比例", "决定触发点出现在窗口前面留多少余量。越大，触发点越靠后，前面的波形越多。")
                                            }
                                            Slider(
                                                value = triggerPreTriggerRatio,
                                                onValueChange = { triggerPreTriggerRatio = it },
                                                valueRange = 0.08f..0.35f,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("辅助低通: ${String.format(Locale.US, "%.0f", triggerStrongLowPassHz)}Hz")
                                                InfoIconButton("辅助低通", "这是给 Trigger 用的低通截止频率，用来压制高频开关噪声，让基波更容易被识别。")
                                            }
                                            Slider(
                                                value = triggerStrongLowPassHz,
                                                onValueChange = { triggerStrongLowPassHz = it },
                                                valueRange = 120f..600f,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("自相关刷新: ${triggerAutocorrRefreshFrames} 帧")
                                                InfoIconButton("自相关刷新", "控制多久重新计算一次自相关。越小越跟手，但越耗性能；越大越省电，但响应更慢。")
                                            }
                                            Slider(
                                                value = triggerAutocorrRefreshFrames.toFloat(),
                                                onValueChange = { triggerAutocorrRefreshFrames = it.roundToInt().coerceIn(1, 32) },
                                                valueRange = 1f..32f,
                                                steps = 30,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("自相关长度: ${triggerAutocorrMaxSamples} 点")
                                                InfoIconButton("自相关长度", "用多长的一段波形做自相关。越长越稳，但越耗性能；越短越快，但可能更抖。")
                                            }
                                            Slider(
                                                value = triggerAutocorrMaxSamples.toFloat(),
                                                onValueChange = { triggerAutocorrMaxSamples = it.roundToInt().coerceIn(128, 2048) },
                                                valueRange = 128f..2048f,
                                                steps = 14,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("滞回: ${String.format(Locale.US, "%.2f", triggerHysteresisRatio)}")
                                                InfoIconButton("滞回", "防止阈值附近来回抖动造成误触。调大更稳，调小更灵敏。")
                                            }
                                            Slider(
                                                value = triggerHysteresisRatio,
                                                onValueChange = { triggerHysteresisRatio = it },
                                                valueRange = 0.05f..0.40f,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("保留间隔: ${String.format(Locale.US, "%.2f", triggerHoldoffRatio)}")
                                                InfoIconButton("保留间隔", "限制连续触发之间的最小间隔，避免同一周期里重复抓到多个边沿。")
                                            }
                                            Slider(
                                                value = triggerHoldoffRatio,
                                                onValueChange = { triggerHoldoffRatio = it },
                                                valueRange = 0.20f..0.85f,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }

                                2 -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("预触发比例: ${String.format(Locale.US, "%.2f", triggerPreTriggerRatio)}")
                                                    InfoIconButton("预触发比例", "决定触发点出现在窗口前面留多少余量。越大，触发点越靠后，前面的波形越多。")
                                                }
                                                Slider(
                                                    value = triggerPreTriggerRatio,
                                                    onValueChange = { triggerPreTriggerRatio = it },
                                                    valueRange = 0.08f..0.35f,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }

                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("辅助低通: ${String.format(Locale.US, "%.0f", triggerStrongLowPassHz)}Hz")
                                                    InfoIconButton("辅助低通", "这是给 Trigger 用的低通截止频率，用来压制高频开关噪声，让基波更容易被识别。")
                                                }
                                                Slider(
                                                    value = triggerStrongLowPassHz,
                                                    onValueChange = { triggerStrongLowPassHz = it },
                                                    valueRange = 120f..600f,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }

                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("自相关刷新: ${triggerAutocorrRefreshFrames} 帧")
                                                    InfoIconButton("自相关刷新", "控制多久重新计算一次自相关。越小越跟手，但越耗性能；越大越省电，但响应更慢。")
                                                }
                                                Slider(
                                                    value = triggerAutocorrRefreshFrames.toFloat(),
                                                    onValueChange = { triggerAutocorrRefreshFrames = it.roundToInt().coerceIn(1, 32) },
                                                    valueRange = 1f..32f,
                                                    steps = 30,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("自相关长度: ${triggerAutocorrMaxSamples} 点")
                                                    InfoIconButton("自相关长度", "用多长的一段波形做自相关。越长越稳，但越耗性能；越短越快，但可能更抖。")
                                                }
                                                Slider(
                                                    value = triggerAutocorrMaxSamples.toFloat(),
                                                    onValueChange = { triggerAutocorrMaxSamples = it.roundToInt().coerceIn(128, 2048) },
                                                    valueRange = 128f..2048f,
                                                    steps = 14,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }

                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("滞回: ${String.format(Locale.US, "%.2f", triggerHysteresisRatio)}")
                                                    InfoIconButton("滞回", "防止阈值附近来回抖动造成误触。调大更稳，调小更灵敏。")
                                                }
                                                Slider(
                                                    value = triggerHysteresisRatio,
                                                    onValueChange = { triggerHysteresisRatio = it },
                                                    valueRange = 0.05f..0.40f,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }

                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("保留间隔: ${String.format(Locale.US, "%.2f", triggerHoldoffRatio)}")
                                                    InfoIconButton("保留间隔", "限制连续触发之间的最小间隔，避免同一周期里重复抓到多个边沿。")
                                                }
                                                Slider(
                                                    value = triggerHoldoffRatio,
                                                    onValueChange = { triggerHoldoffRatio = it },
                                                    valueRange = 0.20f..0.85f,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }

                                else -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("预触发比例: ${String.format(Locale.US, "%.2f", triggerPreTriggerRatio)}")
                                                    InfoIconButton("预触发比例", "决定触发点出现在窗口前面留多少余量。越大，触发点越靠后，前面的波形越多。")
                                                }
                                                Slider(
                                                    value = triggerPreTriggerRatio,
                                                    onValueChange = { triggerPreTriggerRatio = it },
                                                    valueRange = 0.08f..0.35f,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }

                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("辅助低通: ${String.format(Locale.US, "%.0f", triggerStrongLowPassHz)}Hz")
                                                    InfoIconButton("辅助低通", "这是给 Trigger 用的低通截止频率，用来压制高频开关噪声，让基波更容易被识别。")
                                                }
                                                Slider(
                                                    value = triggerStrongLowPassHz,
                                                    onValueChange = { triggerStrongLowPassHz = it },
                                                    valueRange = 120f..600f,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("自相关刷新: ${triggerAutocorrRefreshFrames} 帧")
                                                    InfoIconButton("自相关刷新", "控制多久重新计算一次自相关。越小越跟手，但越耗性能；越大越省电，但响应更慢。")
                                                }
                                                Slider(
                                                    value = triggerAutocorrRefreshFrames.toFloat(),
                                                    onValueChange = { triggerAutocorrRefreshFrames = it.roundToInt().coerceIn(1, 32) },
                                                    valueRange = 1f..32f,
                                                    steps = 30,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }

                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("自相关长度: ${triggerAutocorrMaxSamples} 点")
                                                    InfoIconButton("自相关长度", "用多长的一段波形做自相关。越长越稳，但越耗性能；越短越快，但可能更抖。")
                                                }
                                                Slider(
                                                    value = triggerAutocorrMaxSamples.toFloat(),
                                                    onValueChange = { triggerAutocorrMaxSamples = it.roundToInt().coerceIn(128, 2048) },
                                                    valueRange = 128f..2048f,
                                                    steps = 14,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("滞回: ${String.format(Locale.US, "%.2f", triggerHysteresisRatio)}")
                                                    InfoIconButton("滞回", "防止阈值附近来回抖动造成误触。调大更稳，调小更灵敏。")
                                                }
                                                Slider(
                                                    value = triggerHysteresisRatio,
                                                    onValueChange = { triggerHysteresisRatio = it },
                                                    valueRange = 0.05f..0.40f,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }

                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("保留间隔: ${String.format(Locale.US, "%.2f", triggerHoldoffRatio)}")
                                                    InfoIconButton("保留间隔", "限制连续触发之间的最小间隔，避免同一周期里重复抓到多个边沿。")
                                                }
                                                Slider(
                                                    value = triggerHoldoffRatio,
                                                    onValueChange = { triggerHoldoffRatio = it },
                                                    valueRange = 0.20f..0.85f,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                triggerUseAutocorr = true
                                triggerStrongLowPassHz = 240f
                                triggerPreTriggerRatio = 0.16f
                                triggerHysteresisRatio = 0.16f
                                triggerHoldoffRatio = 0.60f
                                triggerAutocorrRefreshFrames = 8
                                triggerAutocorrMaxSamples = 512
                            }
                        ) {
                            Text("恢复默认")
                        }

                        TextButton(onClick = { showTriggerAdvancedDialog = false }) {
                            Text("确定")
                        }
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            OutlinedButton(
                onClick = onToggleLock,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(if (landscapeLocked) "解锁" else "锁定", color = Color.White)
            }
        }

        if (gestureOverlayVisible && !landscapeLocked) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xAA000000))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (gestureMode) {
                    1 -> Text(
                        text = "幅度 x${String.format(Locale.US, "%.2f", gestureAmp)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    2 -> Text(
                        text = "时间窗 ${if (gestureWindow < 10f) String.format(Locale.US, "%.1f", gestureWindow) else String.format(Locale.US, "%.0f", gestureWindow)}ms",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveWaveformView(
    samplesFlow: StateFlow<FloatArray>,
    ampScale: Float,
    lineColor: Color,
    showReference: Boolean,
    referenceAmpNormalized: Float,
    referenceColor: Color,
    modifier: Modifier = Modifier,
) {
    val samples by samplesFlow.collectAsStateWithLifecycle()
    WaveformView(
        samples = samples,
        ampScale = ampScale,
        lineColor = lineColor,
        showReferenceWhenBelow1x = showReference,
        referenceAmpNormalized = referenceAmpNormalized,
        referenceColor = referenceColor,
        referenceDashed = true,
        modifier = modifier,
    )
}

@Composable
private fun CaptureDiagnosticsLine(
    audioViewModel: AudioEngineViewModel,
    modifier: Modifier = Modifier,
) {
    val audioInputAlive by audioViewModel.audioInputAlive.collectAsStateWithLifecycle()
    val lastReadSamples by audioViewModel.lastReadSamples.collectAsStateWithLifecycle()
    val lastMaxAbsPcm by audioViewModel.lastMaxAbsPcm.collectAsStateWithLifecycle()

    Text(
        text = "alive=$audioInputAlive read=$lastReadSamples maxAbs=$lastMaxAbsPcm ",
        style = MaterialTheme.typography.bodySmall,
        color = if (audioInputAlive) Color(0xFF2E7D32) else Color(0xFFC62828),
        modifier = modifier.fillMaxWidth()
    )
}

// ===== Slider mapping helpers (top-level) =====
fun sliderToHz(v01: Float, minHz: Float, maxHz: Float): Float {
    val v = v01.coerceIn(0f, 1f)
    val logMin = ln(minHz)
    val logMax = ln(maxHz)
    val logHz = logMin + (logMax - logMin) * v
    return exp(logHz)
}

fun hzToSlider(hz: Float, minHz: Float, maxHz: Float): Float {
    val h = hz.coerceIn(minHz, maxHz)
    val logMin = ln(minHz)
    val logMax = ln(maxHz)
    return ((ln(h) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
}

/**
 * 混合映射：linearWeight 越大越线性。
 * - 0 -> 纯对数
 * - 1 -> 纯线性
 */
fun sliderToHzBlend(v01: Float, minHz: Float, maxHz: Float, linearWeight: Float): Float {
    val w = linearWeight.coerceIn(0f, 1f)
    val v = v01.coerceIn(0f, 1f)
    val hzLog = sliderToHz(v, minHz, maxHz)
    val hzLin = minHz + (maxHz - minHz) * v
    return (hzLog * (1f - w) + hzLin * w).coerceIn(minHz, maxHz)
}

fun hzToSliderBlend(hz: Float, minHz: Float, maxHz: Float, linearWeight: Float): Float {
    val w = linearWeight.coerceIn(0f, 1f)
    val h = hz.coerceIn(minHz, maxHz)
    val vLog = hzToSlider(h, minHz, maxHz)
    val vLin = ((h - minHz) / (maxHz - minHz)).coerceIn(0f, 1f)
    return (vLog * (1f - w) + vLin * w).coerceIn(0f, 1f)
}

@Composable
private fun rememberDisplayLowPass(
    target: Float,
    resetKey: Any? = Unit,
    alpha: Float = 0.18f,
    snapThreshold: Float = 0.01f,
): Float {
    val targetState = rememberUpdatedState(target)
    val smoothed by produceState(initialValue = target, resetKey, alpha, snapThreshold) {
        value = targetState.value
        snapshotFlow { targetState.value }.collectLatest { newTarget ->
            if (!newTarget.isFinite()) {
                value = newTarget
                return@collectLatest
            }

            var current = value.takeIf { it.isFinite() } ?: newTarget
            val safeAlpha = alpha.coerceIn(0.01f, 0.9f)
            val safeThreshold = snapThreshold.coerceAtLeast(1e-6f)

            while (true) {
                val delta = newTarget - current
                if (abs(delta) <= safeThreshold) {
                    current = newTarget
                    value = current
                    break
                }
                current += delta * safeAlpha
                value = current
                withFrameNanos { }
            }
        }
    }
    return smoothed
}

@Composable
private fun ClickToEditNumberText(
    text: String,
    initialText: String,
    title: String,
    unit: String,
    parseAndClamp: (String) -> Float?,
    onValue: (Float) -> Unit,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    var showDialog by remember { mutableStateOf(false) }
    var editText by remember(showDialog) { mutableStateOf(initialText) }

    Row(
        modifier = modifier
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = style,
            modifier = Modifier.clickable { showDialog = true }
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(4.dp))
            trailingIcon.invoke()
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    singleLine = true,
                    label = { Text(unit) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val v = parseAndClamp(editText)
                        if (v != null) onValue(v)
                        showDialog = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun InfoIconButton(title: String, message: String) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            text = "i",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFF6A5ACD))
                .clickable { expanded = true }
                .padding(horizontal = 7.dp, vertical = 2.dp)
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, (-4).dp)
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 180.dp, max = 280.dp)
                    .background(Color(0xFFF7F3FF), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Color.Black)
                Text(message, style = MaterialTheme.typography.bodySmall, color = Color.Black)
            }
        }
    }
}

@Composable
private fun LowHighPassRow(
    freq01: Float,
    onFreq01Change: (Float) -> Unit,
    onFreq01ChangeFinished: () -> Unit,
) {
    OscopeSlider(
        value = freq01.coerceIn(0f, 1f),
        onValueChange = onFreq01Change,
        onValueChangeFinished = onFreq01ChangeFinished,
        valueRange = 0f..1f,
        steps = 220,
        accentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun FilterOrderSelector(
    order: Int,
    orderOptions: List<Int>,
    onOrderChange: (Int) -> Unit,
) {
    var orderMenu by remember { mutableStateOf(false) }
    val minO = orderOptions.minOrNull() ?: 1
    val maxO = orderOptions.maxOrNull() ?: 8

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            IconButton(
                onClick = { onOrderChange((order - 1).coerceAtLeast(minO)) },
                modifier = Modifier.size(24.dp)
            ) { Text("-", style = MaterialTheme.typography.bodySmall) }

            TextButton(
                onClick = { orderMenu = true },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
            ) { Text("${order}阶", style = MaterialTheme.typography.bodySmall) }

            IconButton(
                onClick = { onOrderChange((order + 1).coerceAtMost(maxO)) },
                modifier = Modifier.size(24.dp)
            ) { Text("+", style = MaterialTheme.typography.bodySmall) }
        }

        DropdownMenu(expanded = orderMenu, onDismissRequest = { orderMenu = false }) {
            for (o in orderOptions) {
                DropdownMenuItem(
                    text = { Text("${o}阶", style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        orderMenu = false
                        onOrderChange(o)
                    }
                )
            }
        }
    }
}

@Composable
private fun EqPanel(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    bands: List<AudioEngineViewModel.EqBand>,
    onReset: () -> Unit,
    onBandEnabled: (id: Int, enabled: Boolean) -> Unit,
    onBandType: (id: Int, type: AudioEngineViewModel.EqBandType) -> Unit,
    onBandFreq: (id: Int, hz: Float) -> Unit,
    onBandGainDb: (id: Int, db: Float) -> Unit,
    onBandQ: (id: Int, q: Float) -> Unit,
    logToSlider: (v: Float, min: Float, max: Float) -> Float,
    sliderToLog: (v01: Float, min: Float, max: Float) -> Float,
    onGraphDragging: (Boolean) -> Unit,
    filterGain: Float,
    lowPassEnabled: Boolean,
    lowPassCutoff: Float,
    lowPassOrder: Int,
    highPassEnabled: Boolean,
    highPassCutoff: Float,
    highPassOrder: Int,
    sampleRate: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var expanded by remember { mutableStateOf(true) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("图形均衡器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = { expanded = !expanded },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text(if (expanded) "折叠" else "展开") }
            }

            TextButton(
                onClick = onReset,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("重置均衡器") }
        }

        if (!enabled || !expanded) return

        val freqMin = 5f
        val freqMax = 20000f
        val gainMin = -24f
        val gainMax = 24f
        val qMin = 0.2f
        val qMax = 6f

        var selectedId by remember { mutableStateOf(bands.firstOrNull()?.id ?: 0) }
        LaunchedEffect(bands) {
            if (bands.none { it.id == selectedId } && bands.isNotEmpty()) selectedId = bands.first().id
        }
        val sel = bands.firstOrNull { it.id == selectedId } ?: return
        val displaySelFreq = rememberDisplayLowPass(sel.freqHz, resetKey = "eq-freq-${sel.id}", alpha = 0.24f, snapThreshold = 0.2f)
        val displaySelGainDb = rememberDisplayLowPass(sel.gainDb, resetKey = "eq-gain-${sel.id}", alpha = 0.24f, snapThreshold = 0.03f)
        val displaySelQ = rememberDisplayLowPass(sel.q, resetKey = "eq-q-${sel.id}", alpha = 0.24f, snapThreshold = 0.01f)

        EqResponseGraph(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
            bands = bands,
            freqMin = freqMin,
            freqMax = freqMax,
            gainMin = gainMin,
            gainMax = gainMax,
            selectedId = selectedId,
            lowPassEnabled = lowPassEnabled,
            lowPassCutoff = lowPassCutoff,
            highPassEnabled = highPassEnabled,
            highPassCutoff = highPassCutoff,
            filterGain = filterGain,
            sampleRate = sampleRate,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (b in bands) {
                val isSel = b.id == selectedId
                TextButton(
                    onClick = { selectedId = b.id },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                ) { Text(b.label, style = MaterialTheme.typography.bodyMedium) }
            }

            Spacer(Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("启用", style = MaterialTheme.typography.bodySmall)
                Switch(checked = sel.enabled, onCheckedChange = { onBandEnabled(sel.id, it) })
            }
        }

        // ===== NEW: band mode selector (keeps original 4-band config, allows choosing PEAK/LOW/HIGH shelf) =====
        run {
            var modeMenu by remember(sel.id) { mutableStateOf(false) }
            val modeLabel = when (sel.type) {
                AudioEngineViewModel.EqBandType.PEAK -> "Peak"
                AudioEngineViewModel.EqBandType.LOW_SHELF -> "Low Shelf"
                AudioEngineViewModel.EqBandType.HIGH_SHELF -> "High Shelf"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ClickToEditNumberText(
                    text = "${sel.label}段 频率 ${String.format(Locale.US, "%.0f", displaySelFreq)}Hz",
                    initialText = String.format(Locale.US, "%.0f", sel.freqHz),
                    title = "设置第${sel.label}段频率",
                    unit = "Hz",
                    parseAndClamp = { s -> s.trim().replace(",", ".").toFloatOrNull()?.coerceIn(freqMin, freqMax) },
                    onValue = { v -> onBandFreq(sel.id, v) },
                    style = MaterialTheme.typography.bodyMedium,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f)
                )

                // Compact mode selector inline
                Box {
                    OutlinedButton(
                        onClick = { modeMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(modeLabel, style = MaterialTheme.typography.bodySmall)
                    }

                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Peak") },
                            onClick = {
                                modeMenu = false
                                onBandType(sel.id, AudioEngineViewModel.EqBandType.PEAK)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Low Shelf") },
                            onClick = {
                                modeMenu = false
                                onBandType(sel.id, AudioEngineViewModel.EqBandType.LOW_SHELF)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("High Shelf") },
                            onClick = {
                                modeMenu = false
                                onBandType(sel.id, AudioEngineViewModel.EqBandType.HIGH_SHELF)
                            }
                        )
                    }
                }

                TextButton(
                    onClick = {
                        val defaultFreq = when (sel.id) {
                            0 -> 50f
                            1 -> 300f
                            2 -> 1200f
                            3 -> 6000f
                            else -> 1000f
                        }
                        onBandFreq(sel.id, defaultFreq)
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) { Text("重置", style = MaterialTheme.typography.bodySmall) }
            }
        }

        OscopeSlider(
            value = logToSlider(sel.freqHz, freqMin, freqMax),
            onValueChange = { onBandFreq(sel.id, sliderToLog(it, freqMin, freqMax)) },
            valueRange = 0f..1f,
            steps = 240,
            accentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(3f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ClickToEditNumberText(
                        text = "增益 ${String.format(Locale.US, "%+.1f", displaySelGainDb)}dB",
                        initialText = String.format(Locale.US, "%.1f", sel.gainDb),
                        title = "设置第${sel.label}段增益",
                        unit = "dB",
                        parseAndClamp = { s -> s.trim().replace(",", ".").toFloatOrNull()?.coerceIn(gainMin, gainMax) },
                        onValue = { v -> onBandGainDb(sel.id, v) },
                        style = MaterialTheme.typography.bodyMedium,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { onBandGainDb(sel.id, 0f) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text("重置") }
                }
                OscopeSlider(
                    value = ((sel.gainDb - gainMin) / (gainMax - gainMin)).coerceIn(0f, 1f),
                    onValueChange = {
                        val db = (gainMin + (gainMax - gainMin) * it).coerceIn(gainMin, gainMax)
                        onBandGainDb(sel.id, db)
                    },
                    valueRange = 0f..1f,
                    steps = 120,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(modifier = Modifier.weight(2f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ClickToEditNumberText(
                        text = "Q ${String.format(Locale.US, "%.2f", displaySelQ)}",
                        initialText = String.format(Locale.US, "%.2f", sel.q),
                        title = "设置第${sel.label}段 Q",
                        unit = "Q",
                        parseAndClamp = { s -> s.trim().replace(",", ".").toFloatOrNull()?.coerceIn(qMin, qMax) },
                        onValue = { v -> onBandQ(sel.id, v) },
                        style = MaterialTheme.typography.bodyMedium,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { onBandQ(sel.id, AudioEngineViewModel.DEFAULT_EQ_Q) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text("重置") }
                }
                OscopeSlider(
                    value = logToSlider(sel.q, qMin, qMax),
                    onValueChange = { onBandQ(sel.id, sliderToLog(it, qMin, qMax)) },
                    valueRange = 0f..1f,
                    steps = 120,
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        LaunchedEffect(Unit) { onGraphDragging(false) }
    }
}

@Composable
private fun EqResponseGraph(
    modifier: Modifier,
    bands: List<AudioEngineViewModel.EqBand>,
    freqMin: Float,
    freqMax: Float,
    gainMin: Float,
    gainMax: Float,
    selectedId: Int,
    lowPassEnabled: Boolean,
    lowPassCutoff: Float,
    highPassEnabled: Boolean,
    highPassCutoff: Float,
    filterGain: Float,
    sampleRate: Int,
) {
    // Theme-aware colors
    val curveColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    val dotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val selectedColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        fun xForFreq(f: Float): Float {
            val lf = ln(f.coerceIn(freqMin, freqMax))
            val lmin = ln(freqMin)
            val lmax = ln(freqMax)
            return ((lf - lmin) / (lmax - lmin)).toFloat() * w
        }

        fun yForDb(db: Float): Float {
            val t = ((db - gainMin) / (gainMax - gainMin)).coerceIn(0f, 1f)
            return (h * (1f - t))
        }

        val sorted = bands.sortedBy { it.freqHz }

        // 0dB reference line (still useful)
        val y0 = yForDb(0f)
        drawLine(
            color = gridColor,
            start = Offset(0f, y0),
            end = Offset(w, y0),
            strokeWidth = 2f
        )

        // Build frequency axis (log-spaced)
        val nPoints = 240
        val freqs = FloatArray(nPoints + 1) { i ->
            val frac = i / nPoints.toFloat()
            exp(ln(freqMin) + (ln(freqMax) - ln(freqMin)) * frac).toFloat()
        }

        // Compute combined EQ response (dB) across freqs using RBJ peaking formula for each enabled band
        val respDb = computeEqResponse(sorted, freqs, lowPassEnabled, lowPassCutoff, highPassEnabled, highPassCutoff, filterGain, sampleRate)

        // Draw smooth curve using the computed dB values
        val curvePath = Path()
        for (i in respDb.indices) {
            val x = xForFreq(freqs[i])
            val y = yForDb(respDb[i])
            if (i == 0) curvePath.moveTo(x, y) else curvePath.lineTo(x, y)
        }
        drawPath(path = curvePath, color = curveColor.copy(alpha = 0.90f), style = Stroke(width = 3f))

        // Draw dots at band centers for visual anchors
        for (b in sorted) {
            val pt = Offset(xForFreq(b.freqHz), yForDb(b.gainDb))
            val isSel = b.id == selectedId
            drawCircle(
                color = if (isSel) selectedColor else dotColor,
                radius = if (isSel) 8f else 6f,
                center = pt
            )
        }
    }
}

// Compute the EQ combined response (in dB) for a list of bands at given freqs.
// Uses RBJ Peaking EQ design (same math as in the audio engine) so the UI graph matches actual filter effect.
private fun computeEqResponse(bands: List<AudioEngineViewModel.EqBand>, freqs: FloatArray, lowPassEnabled: Boolean, lowPassCutoff: Float, highPassEnabled: Boolean, highPassCutoff: Float, filterGain: Float, sampleRate: Int): FloatArray {
    val out = FloatArray(freqs.size) { 0f }

    // Precompute enabled biquad coefficients for each band (PEAK/LOW_SHELF/HIGH_SHELF)
    data class Coef(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)
    val coefs = bands.filter { it.enabled }
        .map { b ->
            val centerHz = b.freqHz.coerceAtLeast(1f)
            val qOrSlope = b.q.coerceIn(0.01f, 50f)
            val gainDb = b.gainDb.coerceIn(-60f, 60f) // clamp wide just for computation

            val w0 = (2f * PI.toFloat() * centerHz) / sampleRate.toFloat()
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val a = 10f.pow(gainDb / 40f)

            // RBJ cookbook formulas
            val coeffs: FloatArray = when (b.type) {
                AudioEngineViewModel.EqBandType.PEAK -> {
                    val alpha = sinW0 / (2f * qOrSlope)
                    val b0 = 1f + alpha * a
                    val b1 = -2f * cosW0
                    val b2 = 1f - alpha * a
                    val a0 = 1f + alpha / a
                    val a1 = -2f * cosW0
                    val a2 = 1f - alpha / a
                    floatArrayOf(b0, b1, b2, a0, a1, a2)
                }

                AudioEngineViewModel.EqBandType.LOW_SHELF -> {
                    val s = qOrSlope.coerceAtLeast(0.1f)
                    val alpha = (sinW0 / 2f) * sqrt((a + 1f / a) * (1f / s - 1f) + 2f)
                    val twoSqrtAAlpha = 2f * sqrt(a) * alpha

                    val b0 = a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha)
                    val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
                    val b2 = a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha)
                    val a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
                    val a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
                    val a2 = (a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha
                    floatArrayOf(b0, b1, b2, a0, a1, a2)
                }

                AudioEngineViewModel.EqBandType.HIGH_SHELF -> {
                    val s = qOrSlope.coerceAtLeast(0.1f)
                    val alpha = (sinW0 / 2f) * sqrt((a + 1f / a) * (1f / s - 1f) + 2f)
                    val twoSqrtAAlpha = 2f * sqrt(a) * alpha

                    val b0 = a * ((a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha)
                    val b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW0)
                    val b2 = a * ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha)
                    val a0 = (a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha
                    val a1 = 2f * ((a - 1f) - (a + 1f) * cosW0)
                    val a2 = (a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha
                    floatArrayOf(b0, b1, b2, a0, a1, a2)
                }
            }

            val b0 = coeffs[0]
            val b1 = coeffs[1]
            val b2 = coeffs[2]
            val a0 = coeffs[3]
            val a1 = coeffs[4]
            val a2 = coeffs[5]

            Coef(b0 = b0 / a0, b1 = b1 / a0, b2 = b2 / a0, a1 = a1 / a0, a2 = a2 / a0)
        }

    for (i in freqs.indices) {
        val f = freqs[i]
        val w = 2f * PI.toFloat() * f / sampleRate.toFloat()
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2f * w)
        val s2 = sin(2f * w)

        var magSquared = 1f
        for (coef in coefs) {
            // Numerator: b0 + b1 e^{-jω} + b2 e^{-j2ω}
            val realNum = coef.b0 + coef.b1 * c1 + coef.b2 * c2
            val imagNum = -coef.b1 * s1 - coef.b2 * s2
            // Denominator: 1 + a1 e^{-jω} + a2 e^{-j2ω}
            val realDen = 1f + coef.a1 * c1 + coef.a2 * c2
            val imagDen = -coef.a1 * s1 - coef.a2 * s2

            val numSq = realNum * realNum + imagNum * imagNum
            val denSq = realDen * realDen + imagDen * imagDen
            val hSq = if (denSq <= 0f) numSq else numSq / denSq
            magSquared *= hSq
        }

        val mag = sqrt(magSquared.toDouble()).toFloat().coerceAtLeast(1e-12f)
        val db = 20f * log10(mag)
        out[i] = db.coerceIn(-120f, 120f)
    }

    return out
}
