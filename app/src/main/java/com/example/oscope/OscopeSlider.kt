package com.example.oscope

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

private val OscopeSliderShape = RoundedCornerShape(999.dp)
private val OscopeThumbShape = RoundedCornerShape(999.dp)
private val OscopeThumbWidth = 34.dp
private val OscopeThumbHeight = 26.dp
private val OscopeTrackHeight = 4.dp
private val OscopeTouchHeight = 44.dp
private val OscopeSliderHorizontalPadding = 10.dp
private val OscopeSliderVerticalPadding = 6.dp
private val IOSBlue = Color(0xFF0A84FF)
private val IOSInactiveTrack = Color(0xFFD7D7DC)

object OscopeSliderDefaults {
    @Composable
    fun colors(
        accentColor: Color = IOSBlue,
    ): SliderColors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = accentColor,
        inactiveTrackColor = IOSInactiveTrack,
        activeTickColor = accentColor.copy(alpha = 0.20f),
        inactiveTickColor = IOSInactiveTrack.copy(alpha = 0.72f),
        disabledThumbColor = Color.White.copy(alpha = 0.82f),
        disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
        disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        disabledActiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        disabledInactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )

    @Composable
    fun containerColor(
        accentColor: Color = IOSBlue,
        enabled: Boolean = true,
    ): Color = if (enabled) accentColor.copy(alpha = 0.02f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f)

    @Composable
    fun borderColor(
        accentColor: Color = IOSBlue,
        enabled: Boolean = true,
    ): Color = if (enabled) accentColor.copy(alpha = 0.05f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)

    @Composable
    fun activeTrackColor(
        accentColor: Color = IOSBlue,
        enabled: Boolean = true,
    ): Color = if (enabled) accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)

    @Composable
    fun inactiveTrackColor(enabled: Boolean = true): Color =
        if (enabled) IOSInactiveTrack else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    @Composable
    fun thumbColor(enabled: Boolean = true): Color =
        if (enabled) Color.White else Color.White.copy(alpha = 0.82f)

    @Composable
    fun thumbBorderColor(
        accentColor: Color = IOSBlue,
        enabled: Boolean = true,
    ): Color {
        return if (!enabled) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        } else if (accentColor.luminance() > 0.55f) {
            Color.Black.copy(alpha = 0.08f)
        } else {
            accentColor.copy(alpha = 0.14f)
        }
    }
}

@Composable
fun OscopeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    accentColor: Color = IOSBlue,
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    val rangeStart = valueRange.start
    val rangeEnd = valueRange.endInclusive
    val rangeSpan = (rangeEnd - rangeStart).takeIf { it > 0f } ?: 1f
    val clampedValue = value.coerceIn(rangeStart, rangeEnd)
    val normalizedValue = ((clampedValue - rangeStart) / rangeSpan).coerceIn(0f, 1f)

    var layoutWidthPx by remember { mutableFloatStateOf(0f) }
    var dragFraction by remember { mutableFloatStateOf(Float.NaN) }
    var pendingFraction by remember { mutableFloatStateOf(Float.NaN) }
    var isDragging by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val baseThumbWidthPx = with(density) { OscopeThumbWidth.toPx() }
    val baseThumbHalfWidthPx = baseThumbWidthPx / 2f
    val baseThumbHalfWidthDp = OscopeThumbWidth / 2
    val horizontalContentPaddingPx = with(density) { OscopeSliderHorizontalPadding.toPx() }

    fun contentWidthPx(): Float = (layoutWidthPx - horizontalContentPaddingPx * 2f).coerceAtLeast(baseThumbWidthPx + 1f)

    fun dragWidthPx(): Float = (contentWidthPx() - baseThumbWidthPx).coerceAtLeast(1f)

    fun snapFraction(raw: Float): Float {
        val clamped = raw.coerceIn(0f, 1f)
        if (steps <= 0) return clamped
        val segments = steps + 1
        return (clamped * segments).roundToInt() / segments.toFloat()
    }

    fun fractionToValue(fraction: Float): Float {
        val snapped = snapFraction(fraction)
        return (rangeStart + snapped * rangeSpan).coerceIn(rangeStart, rangeEnd)
    }

    fun positionToFraction(x: Float): Float {
        val contentX = x - horizontalContentPaddingPx
        val width = dragWidthPx()
        return ((contentX - baseThumbHalfWidthPx) / width).coerceIn(0f, 1f)
    }

    fun emitFraction(fraction: Float) {
        val snapped = snapFraction(fraction)
        pendingFraction = snapped
        currentOnValueChange(fractionToValue(snapped))
    }

    LaunchedEffect(normalizedValue, pendingFraction, isDragging, isPressed, steps) {
        if (isDragging || isPressed || pendingFraction.isNaN()) return@LaunchedEffect
        val tolerance = if (steps > 0) 0.5f / (steps + 1).toFloat() else 0.003f
        if (abs(normalizedValue - pendingFraction) <= tolerance) {
            pendingFraction = Float.NaN
        }
    }

    val localFraction = when {
        !dragFraction.isNaN() -> dragFraction
        !pendingFraction.isNaN() -> pendingFraction
        else -> normalizedValue
    }
    val targetFraction = localFraction.coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "oscope-slider-fraction"
    )
    val renderedFraction = if (isDragging) targetFraction else animatedFraction

    val thumbScale by animateFloatAsState(
        targetValue = when {
            isDragging -> 1.22f
            isPressed -> 1.12f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = 420f,
        ),
        label = "oscope-slider-thumb-scale"
    )

    val glassOverlayAlpha by animateFloatAsState(
        targetValue = when {
            isDragging -> 0.90f
            isPressed -> 0.82f
            else -> 0.70f
        },
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 500f),
        label = "oscope-slider-thumb-glass"
    )

    val specularAlpha by animateFloatAsState(
        targetValue = when {
            isDragging -> 0.62f
            isPressed -> 0.56f
            else -> 0.44f
        },
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 540f),
        label = "oscope-slider-thumb-specular"
    )

    val thumbElevation by animateDpAsState(
        targetValue = if (!enabled) 1.dp else if (isDragging) 12.dp else if (isPressed) 8.dp else 6.dp,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = 520f),
        label = "oscope-slider-thumb-elevation"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val dragState = rememberDraggableState { delta ->
        if (!enabled) return@rememberDraggableState
        val base = when {
            !dragFraction.isNaN() -> dragFraction
            !pendingFraction.isNaN() -> pendingFraction
            else -> normalizedValue
        }
        val next = (base + delta / dragWidthPx()).coerceIn(0f, 1f)
        dragFraction = next
        emitFraction(next)
    }

    val activeTrackColor = OscopeSliderDefaults.activeTrackColor(accentColor, enabled)
    val inactiveTrackColor = OscopeSliderDefaults.inactiveTrackColor(enabled)
    val thumbColor = OscopeSliderDefaults.thumbColor(enabled)
    val thumbBorderColor = OscopeSliderDefaults.thumbBorderColor(accentColor, enabled)

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = OscopeTouchHeight)
            .clip(OscopeSliderShape)
            .background(OscopeSliderDefaults.containerColor(accentColor, enabled))
            .border(1.dp, OscopeSliderDefaults.borderColor(accentColor, enabled), OscopeSliderShape)
            .onSizeChanged { layoutWidthPx = it.width.toFloat() }
            .progressSemantics(clampedValue, valueRange, steps)
            .pointerInput(enabled, layoutWidthPx, steps, rangeStart, rangeEnd) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { offset ->
                        if (!enabled) return@detectTapGestures
                        val next = positionToFraction(offset.x)
                        emitFraction(next)
                        currentOnValueChangeFinished?.invoke()
                    }
                )
            }
            .draggable(
                enabled = enabled,
                orientation = Orientation.Horizontal,
                state = dragState,
                interactionSource = interactionSource,
                startDragImmediately = false,
                onDragStarted = { startOffset ->
                    isPressed = true
                    isDragging = true
                    val next = positionToFraction(startOffset.x)
                    dragFraction = next
                    emitFraction(next)
                },
                onDragStopped = {
                    isDragging = false
                    isPressed = false
                    dragFraction = Float.NaN
                    currentOnValueChangeFinished?.invoke()
                }
            )
            .padding(horizontal = OscopeSliderHorizontalPadding, vertical = OscopeSliderVerticalPadding)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = baseThumbHalfWidthDp)
                .height(OscopeTrackHeight)
                .clip(OscopeSliderShape)
                .background(inactiveTrackColor)
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = baseThumbHalfWidthDp)
                .fillMaxWidth(renderedFraction)
                .height(OscopeTrackHeight)
                .clip(OscopeSliderShape)
                .background(activeTrackColor)
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset {
                    val centerX = renderedFraction * dragWidthPx() + baseThumbHalfWidthPx
                    IntOffset((centerX - baseThumbHalfWidthPx).roundToInt(), 0)
                }
                .size(width = OscopeThumbWidth, height = OscopeThumbHeight)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .shadow(
                    elevation = thumbElevation,
                    shape = OscopeThumbShape,
                    ambientColor = Color.Black.copy(alpha = if (enabled) 0.18f else 0.06f),
                    spotColor = Color.Black.copy(alpha = if (enabled) 0.14f else 0.05f),
                )
                .background(thumbColor, OscopeThumbShape)
                .border(0.8.dp, thumbBorderColor, OscopeThumbShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(OscopeThumbShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = glassOverlayAlpha),
                                Color.White.copy(alpha = glassOverlayAlpha * 0.55f),
                                accentColor.copy(alpha = if (enabled) 0.08f else 0.03f),
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp, start = 4.dp, end = 4.dp)
                    .fillMaxWidth(0.88f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = specularAlpha))
            )
        }
    }
}
