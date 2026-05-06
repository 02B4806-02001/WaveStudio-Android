package org.mhrri.wavestudio

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 波形展示组件
 */
@Composable
fun WaveformView(
    samples: FloatArray,
    ampScale: Float,
    lineColor: Color,
    modifier: Modifier = Modifier,
    showReferenceWhenBelow1x: Boolean = false,
    referenceAmpNormalized: Float = 1f,
    referenceColor: Color = Color(0x55000000),
    referenceDashed: Boolean = false,
    referenceDashOnPx: Float = 10f,
    referenceDashOffPx: Float = 8f,
    lineWidthDp: Float = 1f,
    triggerMarkerIndex: Int? = null,
    triggerMarkerColor: Color = Color(0x66FFFF00),
) {
    // 用 drawWithCache 缓存 Path/Stroke，避免每帧分配对象导致的“周期性小卡顿”
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .drawWithCache {
                val width = size.width
                val height = size.height
                val midY = height / 2f

                val hasEnough = samples.size >= 2 && width > 0f && height > 0f

                val strokeWidthPx = lineWidthDp.dp.toPx()
                val waveStroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                val markerStroke = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)

                val refStroke = if (referenceDashed) {
                    Stroke(
                        width = 1.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(referenceDashOnPx, referenceDashOffPx),
                            0f
                        )
                    )
                } else {
                    Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
                }

                // 仅当 samples/size/ampScale 变化时重建 Path
                val path = Path()
                if (hasEnough) {
                    val stepX = width / (samples.size - 1)
                    // Build path but avoid connecting across large discontinuities or across trigger marker
                    val firstY = midY - samples[0] * ampScale * midY
                    path.moveTo(0f, firstY)
                    // Threshold in normalized sample units (before scaling by midY)
                    val jumpThresholdNorm = 0.5f
                    val marker = triggerMarkerIndex?.coerceIn(0, samples.lastIndex)
                    for (i in 1 until samples.size) {
                        val x = i * stepX
                        val curSample = samples[i]
                        val y = midY - curSample * ampScale * midY

                        // break at marker boundary (allow a small neighborhood in case marker is off-by-one)
                        if (marker != null && i >= (marker - 1).coerceAtLeast(0) && i <= (marker + 1).coerceAtMost(samples.lastIndex)) {
                            path.moveTo(x, y)
                        } else if (kotlin.math.abs(curSample - samples[i - 1]) > jumpThresholdNorm) {
                            // detect large jump in the raw sample domain and break the path
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, y)
                        }
                    }
                }

                onDrawBehind {
                    if (!hasEnough) return@onDrawBehind

                    // Reference guide lines (behind waveform)
                    if (showReferenceWhenBelow1x && referenceAmpNormalized > 0f) {
                        val ref = referenceAmpNormalized.coerceAtLeast(0f).coerceAtMost(1f)
                        val yTop = midY - (ref * midY)
                        val yBottom = midY + (ref * midY)

                        drawLine(
                            color = referenceColor,
                            start = Offset(0f, yTop),
                            end = Offset(size.width, yTop),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = refStroke.pathEffect
                        )
                        drawLine(
                            color = referenceColor,
                            start = Offset(0f, yBottom),
                            end = Offset(size.width, yBottom),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = refStroke.pathEffect
                        )
                    }

                    // Waveform
                    drawPath(
                        path = path,
                        color = lineColor,
                        style = waveStroke
                    )

                    val marker = triggerMarkerIndex
                    if (marker != null && samples.isNotEmpty()) {
                        val idx = marker.coerceIn(0, samples.lastIndex)
                        val x = if (samples.size <= 1) 0f else (idx.toFloat() / (samples.size - 1).toFloat()) * size.width
                        drawLine(
                            color = triggerMarkerColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
            }
    ) {}
}