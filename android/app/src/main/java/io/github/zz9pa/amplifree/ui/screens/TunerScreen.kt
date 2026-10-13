package io.github.zz9pa.amplifree.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zz9pa.amplifree.R
import io.github.zz9pa.amplifree.data.TunerEngine
import io.github.zz9pa.amplifree.data.TunerState
import io.github.zz9pa.amplifree.ui.FriendlyStatus
import io.github.zz9pa.amplifree.ui.LinkStatus
import io.github.zz9pa.amplifree.ui.WIDE_CONTENT_MIN_WIDTH
import io.github.zz9pa.amplifree.ui.components.ConnectPrompt
import io.github.zz9pa.amplifree.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TunerScreen(
    tunerState: TunerState,
    tunerEngine: TunerEngine,
    link: LinkStatus,
    status: FriendlyStatus,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!link.isReady) {
        ConnectPrompt(
            link = link,
            body = stringResource(R.string.tuner_disconnected_body),
            detail = status.text.takeIf { it.isNotBlank() },
            onConnect = onConnect,
            modifier = modifier.background(AmpMetallicDark).testTag("tuner_disconnected")
        )
        return
    }

    val animatedCents by animateFloatAsState(targetValue = tunerState.centsOffset, label = "centsNeedle")
    val inTuneColor by animateColorAsState(
        targetValue = if (tunerState.isInTune) AmpGreenLED else AmpRedLED,
        label = "inTuneColor"
    )
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(AmpMetallicDark)) {
        val wide = maxWidth >= WIDE_CONTENT_MIN_WIDTH
        // The gauge grows a little with the font size but always fits what is on screen.
        val wanted = 260.dp * fontScale.coerceIn(1f, 1.4f)
        val gaugeSize: Dp = if (wide) {
            wanted.coerceAtMost(maxHeight - 32.dp)
        } else {
            wanted.coerceAtMost(maxWidth - 32.dp)
        }.coerceAtLeast(160.dp)

        if (wide) {
            Row(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TunerGauge(tunerState, animatedCents, inTuneColor, gaugeSize)
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TunerTitle()
                    StringSelector(tunerState, tunerEngine, inTuneColor)
                }
            }
        } else {
            // Scrolls when the font is large or the phone is short, instead of clipping.
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                TunerTitle()
                TunerGauge(tunerState, animatedCents, inTuneColor, gaugeSize)
                StringSelector(tunerState, tunerEngine, inTuneColor)
            }
        }
    }
}

@Composable
private fun TunerTitle() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.tuner_title),
            style = MaterialTheme.typography.titleMedium,
            color = AmpGold,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = stringResource(R.string.tuner_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = AmpTextSecondary
        )
    }
}

/** Circular gauge with the needle, the note name and the cents readout. */
@Composable
private fun TunerGauge(tunerState: TunerState, animatedCents: Float, inTuneColor: androidx.compose.ui.graphics.Color, size: Dp) {
    val noteLabel = "${tunerState.noteName}${tunerState.octave}"
    val description = if (tunerState.signalDetected) {
        stringResource(
            R.string.tuner_gauge_reading,
            noteLabel,
            tunerState.currentFrequency,
            if (tunerState.isInTune) stringResource(R.string.tuner_gauge_in_tune)
            else stringResource(R.string.tuner_gauge_cents, tunerState.centsOffset)
        )
    } else stringResource(R.string.tuner_gauge_none)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            // The whole gauge is one spoken item that describes the current reading.
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("tuner_gauge")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val radius = (this.size.width / 2f) - 20.dp.toPx()

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

        // Central Note & Hz Display. The big note name stops growing at 130% font scale so it
        // stays inside the dial; the smaller texts keep scaling with the system setting.
        val density = LocalDensity.current
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 40.dp)
        ) {
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f))
            ) {
                Text(
                    text = if (tunerState.signalDetected) noteLabel else "—",
                    style = MaterialTheme.typography.displayLarge,
                    color = inTuneColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 54.sp
                )
            }

            Text(
                text = if (tunerState.signalDetected) stringResource(R.string.tuner_hz, tunerState.currentFrequency)
                else stringResource(R.string.tuner_play_note),
                style = MaterialTheme.typography.titleMedium,
                color = AmpTextPrimary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (tunerState.isInTune) AmpGreenLED.copy(alpha = 0.2f) else AmpRedLED.copy(alpha = 0.2f),
                border = androidx.compose.foundation.BorderStroke(1.dp, inTuneColor),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = when {
                        !tunerState.signalDetected -> stringResource(R.string.tuner_no_signal)
                        tunerState.isInTune -> stringResource(R.string.tuner_in_tune)
                        else -> stringResource(R.string.tuner_cents, tunerState.centsOffset)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = inTuneColor,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/** The six strings: tap one to pin the tuner to it, tap again for automatic detection. */
@Composable
private fun StringSelector(
    tunerState: TunerState,
    tunerEngine: TunerEngine,
    inTuneColor: androidx.compose.ui.graphics.Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.tuner_tap_string),
            style = MaterialTheme.typography.labelMedium,
            color = AmpTextSecondary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // With a very large font six buttons no longer fit in one row: use two rows of three.
        val perRow = if (LocalDensity.current.fontScale >= 1.5f) 3 else 6
        val strings = tunerEngine.standardStrings.withIndex().toList()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            strings.chunked(perRow).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    rowItems.forEach { (index, str) ->
                        val isSelected = tunerState.activeStringIndex == index
                        val label = "${str.name}${str.octave}"
                        val description = stringResource(R.string.tuner_string_description, label, str.frequency)
                        val actionLabel = stringResource(
                            if (isSelected) R.string.tuner_string_unlock else R.string.tuner_string_lock
                        )
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) inTuneColor else AmpSurface)
                                .border(1.dp, if (isSelected) AmpGold else AmpSurfaceVariant, RoundedCornerShape(8.dp))
                                .clickable(onClickLabel = actionLabel, role = Role.Button) {
                                    // Pin the tuner to this string; tap again to
                                    // return to automatic nearest-string detection.
                                    tunerEngine.selectString(if (isSelected) -1 else index)
                                }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = description
                                    selected = isSelected
                                }
                                .testTag("string_$index")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isSelected) AmpMetallicDark else AmpTextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.tuner_string_hz, str.frequency),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) AmpMetallicDark else AmpTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
