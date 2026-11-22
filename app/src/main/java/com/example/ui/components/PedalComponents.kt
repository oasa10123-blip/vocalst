package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BrushedMetalMid
import com.example.ui.theme.BrushedSilver
import com.example.ui.theme.LedAmber
import com.example.ui.theme.LedBlue
import com.example.ui.theme.LedEmerald
import com.example.ui.theme.LedOff
import com.example.ui.theme.LedPeakRed
import com.example.ui.theme.PedalCrimsonBright
import com.example.ui.theme.StagePanelBorder
import com.example.ui.theme.StagePanelElevated
import com.example.ui.theme.StageRackSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HardwareLed(
    isOn: Boolean,
    activeColor: Color,
    size: Dp = 12.dp,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val ledColor by animateColorAsState(
        targetValue = if (isOn) activeColor else LedOff,
        animationSpec = spring(),
        label = "ledColor"
    )
    Canvas(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
    ) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        if (isOn) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(activeColor.copy(alpha = 0.65f), Color.Transparent),
                    center = center,
                    radius = radius * 1.9f
                ),
                radius = radius * 1.9f,
                center = center
            )
        }
        drawCircle(
            color = Color(0xFF0A0B0E),
            radius = radius,
            center = center
        )
        drawCircle(
            color = ledColor,
            radius = radius * 0.78f,
            center = center
        )
        if (isOn) {
            drawCircle(
                color = Color.White.copy(alpha = 0.7f),
                radius = radius * 0.25f,
                center = Offset(center.x - radius * 0.22f, center.y - radius * 0.22f)
            )
        }
    }
}

@Composable
fun HarmonyLedArc(
    ledMask: Int,
    harmonyActive: Boolean,
    fullBypass: Boolean,
    modifier: Modifier = Modifier
) {
    val labels = listOf("OCT-", "-5", "-3", "UNI", "+3", "+5", "OCT+")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            val bit = 1 shl (6 - index)
            val isLit = !fullBypass && harmonyActive && (ledMask and bit) != 0
            val ledColor = if (index == 3) LedAmber else PedalCrimsonBright
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HardwareLed(
                    isOn = isLit,
                    activeColor = ledColor,
                    size = 13.dp,
                    contentDescription = "$label harmony LED"
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLit) TextPrimary else TextSecondary.copy(alpha = 0.6f),
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
fun RotaryPedalKnob(
    label: String,
    valueText: String,
    normalizedValue: Float,
    onValueChange: (Float) -> Unit,
    onTapStep: () -> Unit,
    accentColor: Color = PedalCrimsonBright,
    knobSize: Dp = 84.dp,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val clamped = normalizedValue.coerceIn(0f, 1f)
    val animatedVal by animateFloatAsState(
        targetValue = clamped,
        animationSpec = spring(stiffness = 700f),
        label = "knobValue"
    )
    var dragAccumulator by remember { mutableFloatStateOf(clamped) }
    val latestValue by rememberUpdatedState(clamped)
    val latestOnChange by rememberUpdatedState(onValueChange)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .testTag(testTag)
            .semantics { contentDescription = "$label knob: $valueText" }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(knobSize)
                .minimumInteractiveComponentSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTapStep() })
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragAccumulator = latestValue },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val delta = (-dragAmount.y + dragAmount.x) / 220f
                            dragAccumulator = (dragAccumulator + delta).coerceIn(0f, 1f)
                            latestOnChange(dragAccumulator)
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension / 2f - 4.dp.toPx()

                // 11 dial tick marks from 135 deg to 405 deg (270 deg sweep)
                for (i in 0..10) {
                    val frac = i / 10f
                    val angleRad = (135.0 + frac * 270.0) * PI / 180.0
                    val startR = outerRadius - 2.dp.toPx()
                    val endR = outerRadius + 2.dp.toPx()
                    val p1 = Offset(
                        center.x + (startR * cos(angleRad)).toFloat(),
                        center.y + (startR * sin(angleRad)).toFloat()
                    )
                    val p2 = Offset(
                        center.x + (endR * cos(angleRad)).toFloat(),
                        center.y + (endR * sin(angleRad)).toFloat()
                    )
                    drawLine(
                        color = if (frac <= animatedVal + 0.02f) accentColor else StagePanelBorder,
                        start = p1,
                        end = p2,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Active arc
                val arcRadius = outerRadius - 6.dp.toPx()
                drawArc(
                    color = StagePanelBorder.copy(alpha = 0.55f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2, arcRadius * 2),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = accentColor,
                    startAngle = 135f,
                    sweepAngle = 270f * animatedVal,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2, arcRadius * 2),
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                )

                // Dark skirt & brushed aluminum cap
                val capRadius = outerRadius - 12.dp.toPx()
                drawCircle(
                    color = Color(0xFF0E1015),
                    radius = capRadius + 3.dp.toPx(),
                    center = center
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(BrushedSilver, BrushedMetalMid, Color(0xFF474E5D)),
                        center = Offset(center.x - capRadius * 0.25f, center.y - capRadius * 0.25f),
                        radius = capRadius * 1.4f
                    ),
                    radius = capRadius,
                    center = center
                )

                // Indicator notch line
                val pointerAngle = (135.0 + animatedVal * 270.0) * PI / 180.0
                val pStart = Offset(
                    center.x + (capRadius * 0.22f * cos(pointerAngle)).toFloat(),
                    center.y + (capRadius * 0.22f * sin(pointerAngle)).toFloat()
                )
                val pEnd = Offset(
                    center.x + (capRadius * 0.88f * cos(pointerAngle)).toFloat(),
                    center.y + (capRadius * 0.88f * sin(pointerAngle)).toFloat()
                )
                drawLine(
                    color = Color(0xFF101217),
                    start = pStart,
                    end = pEnd,
                    strokeWidth = 3.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            color = Color(0xFF0C0E13),
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, StagePanelBorder)
        ) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun PedalPushButton(
    label: String,
    isActive: Boolean,
    ledColor: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = if (isActive) StagePanelElevated else StageRackSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) ledColor.copy(alpha = 0.7f) else StagePanelBorder
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            HardwareLed(isOn = isActive, activeColor = ledColor, size = 11.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive) TextPrimary else TextSecondary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun StageFootswitch(
    harmonyActive: Boolean,
    fullBypass: Boolean,
    onTapToggleHarmony: () -> Unit,
    onHoldToggleBypass: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var holdTriggered by remember { mutableStateOf(false) }

    val latestTap by rememberUpdatedState(onTapToggleHarmony)
    val latestHold by rememberUpdatedState(onHoldToggleBypass)

    LaunchedEffect(isPressed) {
        if (isPressed) {
            holdProgress = 0f
            holdTriggered = false
            val steps = 20
            val totalMs = 950L
            for (i in 1..steps) {
                delay(totalMs / steps)
                if (!isPressed) break
                holdProgress = i / steps.toFloat()
            }
            if (isPressed && holdProgress >= 1f) {
                holdTriggered = true
                latestHold()
            }
        } else {
            holdProgress = 0f
        }
    }

    val statusText = when {
        fullBypass -> "BYPASSED (CLEAN DRY)"
        harmonyActive -> "HARMONIST ACTIVE"
        else -> "HARMONY MUTED (ECHO ON)"
    }
    val statusLedColor = when {
        fullBypass -> LedOff
        harmonyActive -> LedBlue
        else -> LedAmber
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HardwareLed(isOn = !fullBypass, activeColor = statusLedColor, size = 12.dp)
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = if (fullBypass) TextSecondary else TextPrimary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF282C36), Color(0xFF14161C), Color(0xFF0E1014))
                    )
                )
                .border(
                    width = 1.5.dp,
                    color = if (isPressed) PedalCrimsonBright else StagePanelBorder,
                    shape = RoundedCornerShape(16.dp)
                )
                .testTag("stage_footswitch")
                .semantics { contentDescription = "Stage Footswitch Pedal: $statusText" }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            val wasTriggered = holdTriggered
                            isPressed = false
                            if (!wasTriggered) {
                                latestTap()
                            }
                        }
                    )
                }
        ) {
            // Subtle pedal tread texture & hold progress bar
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lines = 5
                val padX = 24.dp.toPx()
                val stepY = size.height / (lines + 1)
                for (i in 1..lines) {
                    val y = stepY * i
                    drawLine(
                        color = Color(0xFF090A0D),
                        start = Offset(padX, y),
                        end = Offset(size.width - padX, y),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                if (holdProgress > 0f) {
                    drawRoundRect(
                        color = PedalCrimsonBright.copy(alpha = 0.25f),
                        topLeft = Offset.Zero,
                        size = Size(size.width * holdProgress, size.height),
                        cornerRadius = CornerRadius(16.dp.toPx())
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (fullBypass) "TAP TO ENGAGE" else "PEDAL SWITCH",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap: Harmony On/Off · Hold 1s: Full Bypass",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun SignalVuMeter(
    label: String,
    rmsDb: Float,
    peakDb: Float,
    accentColor: Color = LedEmerald,
    modifier: Modifier = Modifier
) {
    val rmsNorm = ((rmsDb + 60f) / 60f).coerceIn(0f, 1f)
    val peakNorm = ((peakDb + 60f) / 60f).coerceIn(0f, 1f)
    val animRms by animateFloatAsState(
        targetValue = rmsNorm,
        animationSpec = spring(stiffness = 900f),
        label = "vuRms"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = String.format("RMS %5.1f dB | PK %5.1f dB", rmsDb, peakDb),
                style = MaterialTheme.typography.labelMedium,
                color = if (peakDb > -1.5f) LedPeakRed else TextPrimary
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0A0C10))
                .border(1.dp, StagePanelBorder, RoundedCornerShape(6.dp))
        ) {
            val segments = 32
            val gap = 2.dp.toPx()
            val segW = (size.width - gap * (segments + 1)) / segments
            for (i in 0 until segments) {
                val frac = (i + 1) / segments.toFloat()
                val segColor = when {
                    frac > 0.90f -> LedPeakRed
                    frac > 0.72f -> LedAmber
                    else -> accentColor
                }
                val isLit = frac <= animRms
                val x = gap + i * (segW + gap)
                drawRoundRect(
                    color = if (isLit) segColor else segColor.copy(alpha = 0.14f),
                    topLeft = Offset(x, 3.dp.toPx()),
                    size = Size(segW, size.height - 6.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
            if (peakNorm > 0.02f) {
                val px = (peakNorm * (size.width - 4.dp.toPx())).coerceIn(2.dp.toPx(), size.width - 4.dp.toPx())
                drawLine(
                    color = Color.White,
                    start = Offset(px, 1.dp.toPx()),
                    end = Offset(px, size.height - 1.dp.toPx()),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}

@Composable
fun WaveformOscilloscope(
    inputSamples: List<Float>,
    outputSamples: List<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF080A0E))
            .border(1.dp, StagePanelBorder, RoundedCornerShape(12.dp))
    ) {
        val midY = size.height / 2f
        // Center grid & 50% grid
        drawLine(
            color = StagePanelBorder.copy(alpha = 0.6f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = StagePanelBorder.copy(alpha = 0.25f),
            start = Offset(0f, midY * 0.5f),
            end = Offset(size.width, midY * 0.5f),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = StagePanelBorder.copy(alpha = 0.25f),
            start = Offset(0f, midY * 1.5f),
            end = Offset(size.width, midY * 1.5f),
            strokeWidth = 1.dp.toPx()
        )

        fun drawTrace(samples: List<Float>, color: Color, strokeWidth: Float) {
            if (samples.size < 2) return
            val path = Path()
            val stepX = size.width / (samples.size - 1).toFloat()
            samples.forEachIndexed { idx, s ->
                val x = idx * stepX
                val y = midY - s.coerceIn(-1f, 1f) * (midY * 0.85f)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        drawTrace(inputSamples, Color(0xFF60A5FA).copy(alpha = 0.75f), 2.dp.toPx())
        drawTrace(outputSamples, PedalCrimsonBright, 2.5.dp.toPx())
    }
}
