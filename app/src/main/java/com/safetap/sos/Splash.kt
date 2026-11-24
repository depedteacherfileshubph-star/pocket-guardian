package com.safetap.sos

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TITLE = "Pocket Guardian"
private const val TAGLINE = "— Help is one tap away."

/**
 * Animated opening screen: the shield pops in with a pulsing ring, then the tagline
 * "Pocket Guardian — Help is one tap away." types itself out. Tap anywhere to skip.
 * Shown only when the app opens (never on the emergency SOS screen, which must not be delayed).
 * Honours the system "remove animations" setting.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val reduce = remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val shield = remember { Animatable(if (reduce) 1f else 0.3f) }
    val shieldAlpha = remember { Animatable(if (reduce) 1f else 0f) }
    val ring = remember { Animatable(0f) }
    var typed by remember { mutableIntStateOf(if (reduce) TITLE.length + TAGLINE.length else 0) }
    var finished by remember { mutableStateOf(false) }
    fun finish() { if (!finished) { finished = true; onDone() } }

    LaunchedEffect(Unit) {
        if (reduce) {
            delay(900)
        } else {
            launch { shieldAlpha.animateTo(1f, tween(350)) }
            launch { shield.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
            launch { ring.animateTo(1f, tween(1100)) }
            delay(500)
            val total = TITLE.length + TAGLINE.length
            for (i in 1..total) { typed = i; delay(34) }
            delay(800)
        }
        finish()
    }

    val bg = Color(0xFF8E0000)
    Box(
        Modifier.fillMaxSize().background(bg).clickable(onClickLabel = "Skip the opening") { finish() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.padding(32.dp).clearAndSetSemantics {
                contentDescription = "Pocket Guardian. Help is one tap away."
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(200.dp)) {
                    val r = ring.value
                    if (r in 0.01f..0.99f) {
                        drawCircle(
                            Color.White.copy(alpha = (1f - r) * 0.7f),
                            radius = size.minDimension / 2f * (0.55f + 0.45f * r),
                            style = Stroke(width = 6.dp.toPx()),
                        )
                    }
                }
                Canvas(Modifier.size(120.dp).scale(shield.value).alpha(shieldAlpha.value)) {
                    val w = size.width; val h = size.height
                    val p = Path().apply {
                        moveTo(w * 0.5f, 0f)
                        lineTo(w * 0.95f, h * 0.16f)
                        lineTo(w * 0.95f, h * 0.5f)
                        cubicTo(w * 0.95f, h * 0.78f, w * 0.75f, h * 0.93f, w * 0.5f, h)
                        cubicTo(w * 0.25f, h * 0.93f, w * 0.05f, h * 0.78f, w * 0.05f, h * 0.5f)
                        lineTo(w * 0.05f, h * 0.16f)
                        close()
                    }
                    drawPath(p, Color.White)
                    val bar = w * 0.14f
                    drawRect(bg, Offset(w * 0.5f - bar / 2, h * 0.28f), Size(bar, h * 0.46f))
                    drawRect(bg, Offset(w * 0.27f, h * 0.44f), Size(w * 0.46f, bar))
                }
            }
            val t1 = TITLE.take(typed)
            val t2 = TAGLINE.take((typed - TITLE.length).coerceAtLeast(0))
            Text(t1, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(t2, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
        }
    }
}
