package io.github.zz9pa.amplifree.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zz9pa.amplifree.data.TunerEngine
import io.github.zz9pa.amplifree.data.TunerState
import io.github.zz9pa.amplifree.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TunerScreen(
    tunerState: TunerState,
    tunerEngine: TunerEngine,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()


    val animatedCents by animateFloatAsState(targetValue = tunerState.centsOffset, label = "centsNeedle")
    val inTuneColor by animateColorAsState(
        targetValue = if (tunerState.isInTune) AmpGreenLED else AmpRedLED,
        label = "inTuneColor"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmpMetallicDark)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "CHROMATIC GUITAR TUNER",
                style = MaterialTheme.typography.titleMedium,
                color = AmpGold,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.2.sp
            )
            Text(
                text = "AMPLIFi Hardware & Microphone Signal Sync",
                style = MaterialTheme.typography.bodySmall,
                color = AmpTextSecondary,
                fontSize = 11.sp
            )
        }

        // Circular Gauge Meter
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(260.dp)
                .testTag("tuner_gauge")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = (size.width / 2f) - 20.dp.toPx()

                // Gauge Arc Track
                drawArc(
                    color = AmpSurfaceVariant,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                )

                // Scale Ticks (-50 to +50 cents)
                for (cents in -50..50 step 10) {
                    val angle = 270f + (cents / 50f * 80f)
                    val rad = Math.toRadians(angle.toDouble())
                    val innerR = radius - (if (cents == 0) 16.dp.toPx() else 10.dp.toPx())
                    val outerR = radius + 4.dp.toPx()

                    val startX = center.x + innerR * cos(rad).toFloat()
                    val startY = center.y + innerR * sin(rad).toFloat()
                    val endX = center.x + outerR * cos(rad).toFloat()
                    val endY = center.y + outerR * sin(rad).toFloat()

                    drawLine(
                        color = if (cents == 0) AmpGreenLED else AmpTextSecondary.copy(alpha = 0.6f),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = if (cents == 0) 4.dp.toPx() else 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Dynamic Needle
                val needleAngle = 270f + (animatedCents / 50f * 80f)
                val needleRad = Math.toRadians(needleAngle.toDouble())
                val needleLength = radius * 0.88f

                val needleX = center.x + needleLength * cos(needleRad).toFloat()
                val needleY = center.y + needleLength * sin(needleRad).toFloat()

                drawLine(
                    color = inTuneColor,
                    start = center,
                    end = Offset(needleX, needleY),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Pivot Center
                drawCircle(
                    color = inTuneColor,
                    radius = 12.dp.toPx(),
                    center = center
                )
            }

            // Central Note & Hz Display
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 40.dp)
            ) {
                Text(
                    text = "${tunerState.noteName}${tunerState.octave}",
                    style = MaterialTheme.typography.displayLarge,
                    color = inTuneColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 54.sp
                )

                Text(
                    text = String.format("%.1f Hz", tunerState.currentFrequency),
                    style = MaterialTheme.typography.titleMedium,
                    color = AmpTextPrimary,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (tunerState.isInTune) AmpGreenLED.copy(alpha = 0.2f) else AmpRedLED.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, inTuneColor),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        text = if (tunerState.isInTune) "IN TUNE ✓" else String.format("%+.1f CENTS", tunerState.centsOffset),
                        style = MaterialTheme.typography.labelMedium,
                        color = inTuneColor,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Guitar String Selector Bar
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "TAP STRING TO SIMULATE PLUCK",
                style = MaterialTheme.typography.labelSmall,
                color = AmpTextSecondary,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                tunerEngine.standardStrings.forEachIndexed { index, str ->
                    val isSelected = tunerState.activeStringIndex == index
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) inTuneColor else AmpSurface)
                            .border(1.dp, if (isSelected) AmpGold else AmpSurfaceVariant, RoundedCornerShape(8.dp))
                            .clickable {
                                // Pin the tuner to this string; tap again to
                                // return to automatic nearest-string detection.
                                tunerEngine.selectString(if (isSelected) -1 else index)
                            }
                            .testTag("string_$index")
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${str.name}${str.octave}",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) AmpMetallicDark else AmpTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = String.format("%.0fHz", str.frequency),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) AmpMetallicDark else AmpTextSecondary,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
