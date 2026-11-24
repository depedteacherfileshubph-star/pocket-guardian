package com.safetap.sos

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One volume key drawn like the phone's own rocker: a rounded key with an arrow and a +/- mark.
 * Meaning is carried by shape and symbol, not colour alone. Decorative: callers describe the group.
 */
@Composable
fun VolumeKeyIcon(up: Boolean, active: Boolean = true, size: Dp = 46.dp) {
    val cs = MaterialTheme.colorScheme
    val bg = if (active) cs.primary else cs.surfaceVariant
    val fg = if (active) cs.onPrimary else cs.onSurfaceVariant
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(size).background(bg, shape).border(2.dp, cs.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(size * 0.38f)) {
                val p = Path()
                if (up) {
                    p.moveTo(this.size.width / 2, 0f); p.lineTo(this.size.width, this.size.height); p.lineTo(0f, this.size.height)
                } else {
                    p.moveTo(0f, 0f); p.lineTo(this.size.width, 0f); p.lineTo(this.size.width / 2, this.size.height)
                }
                p.close()
                drawPath(p, fg)
            }
            Text(if (up) "+" else "−", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The volume rocker as it looks on the phone's edge, with the key(s) the shortcut uses lit up. */
@Composable
fun VolumeRocker(pattern: PatternType) {
    val cs = MaterialTheme.colorScheme
    val upOn = pattern != PatternType.DOWN_ONLY
    val downOn = pattern != PatternType.UP_ONLY
    Column(
        Modifier.background(cs.surfaceVariant, RoundedCornerShape(28.dp)).border(2.dp, cs.outline, RoundedCornerShape(28.dp))
            .padding(8.dp).clearAndSetSemantics {
                contentDescription = "Phone volume buttons. Used: ${pattern.label}"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VolumeKeyIcon(up = true, active = upOn, size = 56.dp)
        VolumeKeyIcon(up = false, active = downOn, size = 56.dp)
    }
}

/** A row of keys showing exactly what to press for [count] presses. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PressSequence(pattern: PatternType, count: Int) {
    val keys = pattern.sequence(count)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "$count presses: " + if (pattern == PatternType.ALTERNATING) "alternating Volume Up and Down" else pattern.label
        },
    ) {
        keys.forEach { VolumeKeyIcon(up = it == KeyEvent.KEYCODE_VOLUME_UP, size = 40.dp) }
    }
}
