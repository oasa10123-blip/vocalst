package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MusicalKey
import com.example.model.MusicalScale
import com.example.ui.theme.LedAmber
import com.example.ui.theme.LedBlue
import com.example.ui.theme.LedEmerald
import com.example.ui.theme.LedOff
import com.example.ui.theme.StagePanelBorder
import com.example.ui.theme.StagePanelElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ChromagramVisualizer(
    chromaBins: List<Float>,
    activeKey: MusicalKey,
    activeScale: MusicalScale,
    hasLockedKey: Boolean,
    signalActive: Boolean,
    modifier: Modifier = Modifier
) {
    val scaleNoteIndices = rememberScalePitchClasses(activeKey, activeScale)

    Surface(
        color = StagePanelElevated,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, StagePanelBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE CHROMAGRAM (12 PITCH CLASSES)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = when {
                        signalActive && hasLockedKey -> "IN-KEY NOTES HIGHLIGHTED"
                        signalActive -> "ANALYZING SPECTRUM..."
                        else -> "GATE IDLE (SILENT)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        signalActive && hasLockedKey -> LedEmerald
                        signalActive -> LedAmber
                        else -> TextSecondary
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                for (i in 0 until 12) {
                    val key = MusicalKey.fromIndex(i)
                    val rawVal = chromaBins.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                    val animatedVal by animateFloatAsState(
                        targetValue = rawVal,
                        animationSpec = spring(stiffness = 500f),
                        label = "chroma_$i"
                    )
                    val isRoot = hasLockedKey && i == activeKey.semitoneOffset
                    val isInScale = hasLockedKey && i in scaleNoteIndices

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFF0C0E13)),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            val barBrush = when {
                                isRoot -> Brush.verticalGradient(listOf(LedEmerald, Color(0xFF00994D)))
                                isInScale -> Brush.verticalGradient(listOf(LedBlue, Color(0xFF154BB8)))
                                else -> Brush.verticalGradient(listOf(Color(0xFF545B6E), LedOff))
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(fraction = animatedVal.coerceAtLeast(0.06f))
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(barBrush)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = key.displayName,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = if (isRoot) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                isRoot -> LedEmerald
                                isInScale -> TextPrimary
                                else -> TextSecondary
                            },
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                        Text(
                            text = "$i",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = if (isRoot) LedEmerald else TextSecondary.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

private fun rememberScalePitchClasses(key: MusicalKey, scale: MusicalScale): Set<Int> {
    val root = key.semitoneOffset
    return scale.intervals.map { (root + it) % 12 }.toSet()
}
