package com.inn.lampdemo.lamp

import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.core.graphics.withScale

private val Bg = Color(0xFF050607)
private val Ink = Color(0xFFECE6DA)
private val Muted = Ink.copy(alpha = 0.34f)
private val Amber = Color(0xFFF3C98F)
private val Mono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 18.sp,
    color = Muted
)

/** Mirrors the page's CSS breakpoints. All values in dp. */
private class SceneLayout(w: Float, h: Float) {
    private val mobile = w < 640
    private val short = mobile && h < 620
    private val landscape = h < 500 && w > h

    val desktopCopy = !mobile && !landscape
    val showTime = !short
    val timeSize = if (mobile) 11f else 12f
    val timeGap = when {
        landscape -> 10f
        mobile -> 14f
        else -> 22f
    }
    val titleSize = when {
        landscape -> 34f
        short -> 32f
        mobile -> clamp(w * 0.11f, 34f, 44f)
        else -> clamp(w * 0.07f, 40f, 96f)
    }
    val titleBreak = !landscape
    val bodySize = when {
        landscape || short -> 14f
        mobile -> 15f
        else -> 17f
    }
    val bodyGap = when {
        landscape || short -> 10f
        mobile -> 14f
        else -> 22f
    }
    val bodyMaxWidth = when {
        landscape -> 380f
        mobile -> 260f
        else -> 480f
    }
    val bottomPad = when {
        landscape -> 24f
        short -> 158f
        mobile -> 168f
        else -> h * 0.13f
    }
    val switchScale = if (mobile || landscape) 0.8f else 1f
    val switchRight = if (mobile) 14f else max(24f, w * 0.08f)
    val switchCenterY = when {
        landscape -> h * 0.5f
        short -> h * 0.3f
        mobile -> h * 0.36f
        else -> h * 0.5f
    }
}

@Composable
fun LampScreen() {
    val sound = remember { LampSound() }
    DisposableEffect(sound) {
        sound.start()
        onDispose { sound.stop() }
    }
    val simSaver = remember(sound) {
        Saver<LampSim, Bundle>(
            save = { it.saveState() },
            restore = { LampSim(sound).apply { restoreState(it) } },
        )
    }
    val sim = rememberSaveable(saver = simSaver) { LampSim(sound) }
    val renderer = remember { LampRenderer() }
    var muted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(muted) { sound.muted = muted }

    LaunchedEffect(sim) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) sim.advance((now - last) / 1e9f)
                last = now
                sim.frame = now
            }
        }
    }

    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.getBottom(density) / density.density

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        val layout = remember(wDp, hDp) { SceneLayout(wDp, hDp) }
        SideEffect { sim.resize(wDp, hDp - navBottom) }

        GridBackground()
        SceneText(layout)

        // Darkness with the light cone cut out. Blurring the whole layer softens the cone's edges.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val r = 20.dp.toPx()
                        renderEffect = BlurEffect(r, r, TileMode.Clamp)
                    }
                }
        ) {
            sim.frame
            drawIntoCanvas {
                val c = it.nativeCanvas
                c.withScale(density.density, density.density) {
                    renderer.drawLight(this, sim)
                }
            }
        }

        // Lamp, slingshot and particles.
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(sim) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!sim.pointerDown(
                                down.position.x / density.density,
                                down.position.y / density.density
                            )
                        ) {
                            return@awaitEachGesture
                        }
                        down.consume()
                        try {
                            while (true) {
                                val change =
                                    awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                        ?: break
                                sim.pointerMove(
                                    change.position.x / density.density,
                                    change.position.y / density.density
                                )
                                change.consume()
                                if (!change.pressed) break
                            }
                        } finally {
                            sim.pointerUp()
                        }
                    }
                }
        ) {
            sim.frame
            drawIntoCanvas {
                val c = it.nativeCanvas
                c.withScale(density.density, density.density) {
                    renderer.drawScene(this, sim)
                }
            }
        }

        Hud(
            stateLabel = sim.stateLabel,
            shots = sim.shots,
            bulbs = sim.bulbs,
            muted = muted,
            onToggleSound = { muted = !muted },
        )

        WallSwitch(
            on = sim.switchOn,
            knock = sim.knock,
            scale = layout.switchScale,
            onToggle = sim::toggleSwitch,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(
                    x = (-layout.switchRight).dp,
                    y = (layout.switchCenterY - 60 * layout.switchScale).dp
                )
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInRoot()
                    val d = density.density
                    sim.switchRect = RectF(b.left / d, b.top / d, b.right / d, b.bottom / d)
                },
        )

        if (layout.desktopCopy) {
            Text(
                "flip the switch · pull the pebble back and let go · grab the shade to swing it",
                style = Mono.copy(fontSize = 12.sp, textAlign = TextAlign.Center),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = (22 + navBottom).dp),
            )
        }

        AnimatedVisibility(
            visible = sim.broken,
            enter = fadeIn(tween(400)) + slideInVertically(
                tween(400, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f))
            ) { it / 3 },
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (52 + navBottom).dp),
        ) {
            Text(
                "replace bulb",
                style = Mono.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF111111)
                ),
                modifier = Modifier
                    .shadow(
                        16.dp,
                        RoundedCornerShape(50),
                        ambientColor = Color.Black,
                        spotColor = Color.Black
                    )
                    .background(Ink, RoundedCornerShape(50))
                    .clickable(onClick = sim::replaceBulb)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun GridBackground() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 76.dp.toPx()
        val line = Color.White.copy(alpha = 0.05f)
        val stroke = 1.dp.toPx()
        var x = ((size.width - step) / 2).mod(step)
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), stroke)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
            y += step
        }
    }
}

@Composable
private fun SceneText(layout: SceneLayout) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = layout.bottomPad.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (layout.showTime) {
                Text(
                    "03:14 AM",
                    style = Mono.copy(
                        fontSize = layout.timeSize.sp,
                        letterSpacing = 0.2.em,
                        color = Ink.copy(alpha = 0.55f),
                    ),
                )
                Spacer(Modifier.height(layout.timeGap.dp))
            }
            Text(
                buildAnnotatedString {
                    append(if (layout.titleBreak) "Nobody touch\nthe " else "Nobody touch the ")
                    withStyle(SpanStyle(color = Amber)) { append("lamp") }
                    append(".")
                },
                color = Ink,
                fontSize = layout.titleSize.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.035).em,
                lineHeight = (layout.titleSize * 0.98f).sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(layout.bodyGap.dp))
            Text(
                if (layout.desktopCopy) {
                    "Use the switch to turn the bulb on and off.\nUse the slingshot to hit the shade, the bulb or the switch."
                } else {
                    "Flip the switch. Pull back the slingshot to hit the shade, bulb or switch. Drag the shade to swing it."
                },
                color = Ink.copy(alpha = 0.6f),
                fontSize = layout.bodySize.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = (layout.bodySize * 1.5f).sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = layout.bodyMaxWidth.dp),
            )
        }
    }
}

@Composable
private fun Hud(
    stateLabel: String,
    shots: Int,
    bulbs: Int,
    muted: Boolean,
    onToggleSound: () -> Unit,
) {
    val strong = SpanStyle(color = Ink.copy(alpha = 0.7f))
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    ) {
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
        ) {
            Text(
                buildAnnotatedString {
                    append("slingshot-lamp · ")
                    withStyle(strong) { append(stateLabel) }
                },
                style = Mono.copy(letterSpacing = 0.04.em),
            )
            Text(
                buildAnnotatedString {
                    append("shots ")
                    withStyle(strong) { append(shots.toString()) }
                    append(" · bulbs ")
                    withStyle(strong) { append(bulbs.toString()) }
                },
                style = Mono.copy(letterSpacing = 0.04.em),
            )
        }
        Text(
            if (muted) "sound: off" else "sound: on",
            style = Mono,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
                .clickable(onClick = onToggleSound)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun Modifier.scaled(s: Float) = layout { measurable, _ ->
    val p = measurable.measure(Constraints())
    layout((p.width * s).roundToInt(), (p.height * s).roundToInt()) {
        p.placeWithLayer(0, 0) {
            scaleX = s
            scaleY = s
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

@Composable
private fun WallSwitch(
    on: Boolean,
    knock: Knock?,
    scale: Float,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val leverRot by animateFloatAsState(
        if (on) -26f else 26f,
        tween(140, easing = CubicBezierEasing(0.3f, 1.6f, 0.5f, 1f)),
        label = "lever",
    )
    val pilot by animateColorAsState(
        if (on) Color(0xFFFFB45A) else Color(0xFF3A2A1A),
        tween(200),
        label = "pilot"
    )
    val pilotGlow by animateFloatAsState(if (on) 1f else 0f, tween(200), label = "glow")
    val knockT = remember { Animatable(0f) }
    LaunchedEffect(knock?.id) {
        if (knock == null) return@LaunchedEffect
        knockT.snapTo(1f)
        knockT.animateTo(0f, tween(320, easing = CubicBezierEasing(0.3f, 0.7f, 0.4f, 1f)))
    }
    val plate = RoundedCornerShape(11.dp)
    val engrave = Mono.copy(
        fontSize = 8.sp,
        lineHeight = 8.sp,
        letterSpacing = 0.18.em,
        color = Color.White.copy(alpha = 0.22f)
    )

    Box(
        modifier
            .scaled(scale)
            .graphicsLayer {
                val k = knockT.value
                translationX = -(knock?.nx ?: 0f) * 6.dp.toPx() * k
                translationY = -(knock?.ny ?: 0f) * 6.dp.toPx() * k
                rotationZ = (knock?.rot ?: 0f) * k
            }
            .size(74.dp, 120.dp)
            .shadow(18.dp, plate, ambientColor = Color.Black, spotColor = Color.Black)
            .background(
                Brush.linearGradient(
                    0f to Color(0xFF26282B),
                    0.6f to Color(0xFF151618),
                    1f to Color(0xFF101112),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
                plate,
            )
            .drawBehind {
                val r = CornerRadius(11.dp.toPx())
                // inset highlight on top, inset shade at the bottom
                drawRoundRect(
                    Color.White.copy(alpha = 0.09f),
                    size = Size(size.width, 1.dp.toPx() * 2),
                    cornerRadius = r
                )
                drawRoundRect(
                    Color.Black.copy(alpha = 0.6f),
                    topLeft = Offset(0f, size.height - 2.dp.toPx()),
                    size = Size(size.width, 2.dp.toPx()),
                    cornerRadius = r,
                )
                // screws
                val sr = 3.5.dp.toPx()
                for (cy in listOf(9.dp.toPx() + sr, size.height - 9.dp.toPx() - sr)) {
                    val c = Offset(size.width / 2, cy)
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color(0xFF6B6D70), 0.7f to Color(0xFF2A2B2D),
                            center = c + Offset(-sr * 0.3f, -sr * 0.3f), radius = sr * 1.4f,
                        ),
                        sr, c,
                    )
                    val dx = sr * 0.8f * 0.82f
                    val dy = sr * 0.8f * 0.57f
                    drawLine(
                        Color.Black.copy(alpha = 0.7f),
                        c - Offset(dx, dy),
                        c + Offset(dx, dy),
                        1.dp.toPx()
                    )
                }
                // pilot light
                val p = Offset(size.width - 9.dp.toPx() - 2.dp.toPx(), size.height / 2)
                if (pilotGlow > 0f) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFFFFAA50).copy(alpha = 0.8f * pilotGlow),
                                Color.Transparent
                            ),
                            center = p, radius = 7.dp.toPx(),
                        ),
                        7.dp.toPx(), p,
                    )
                }
                drawCircle(pilot, 2.dp.toPx(), p)
            }
            .toggleable(
                value = on,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = { onToggle() },
            ),
    ) {
        Text(
            "ON", style = engrave, modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp)
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .size(26.dp, 50.dp)
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF060607), Color(0xFF0D0E0F))),
                    RoundedCornerShape(5.dp),
                )
                .padding(3.dp)
        ) {
            val leverBrush = if (on) {
                Brush.verticalGradient(
                    0f to Color(0xFF9D9B97),
                    0.38f to Color(0xFFD8D4CC),
                    0.52f to Color(0xFF8C8984),
                    1f to Color(0xFF54524F),
                )
            } else {
                Brush.verticalGradient(
                    0f to Color(0xFF4D4B48),
                    0.48f to Color(0xFF85827D),
                    0.64f to Color(0xFFD0CCC4),
                    1f to Color(0xFF97948F),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        // drop shadow cast down (on) or up (off)
                        val dy = (if (on) 5 else -5).dp.toPx()
                        drawRoundRect(
                            Color.Black.copy(alpha = 0.35f),
                            topLeft = Offset(0f, dy * 0.6f),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                    }
                    .graphicsLayer {
                        rotationX = leverRot
                        cameraDistance = 3f * density
                    }
                    .background(leverBrush, RoundedCornerShape(3.dp))
            )
        }
        Text(
            "OFF", style = engrave, modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        )
    }
}
