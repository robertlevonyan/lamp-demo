package com.inn.lampdemo.lamp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.inn.lampdemo.lamp.LampSim.Companion.BULB_R
import com.inn.lampdemo.lamp.LampSim.Companion.BULB_X
import com.inn.lampdemo.lamp.LampSim.Companion.BULB_Y
import com.inn.lampdemo.lamp.LampSim.Companion.LAUNCH
import com.inn.lampdemo.lamp.LampSim.Companion.MAX_PULL
import com.inn.lampdemo.lamp.LampSim.Companion.PEBBLE_G
import com.inn.lampdemo.lamp.LampSim.Companion.PEBBLE_R
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.core.graphics.withTranslation

private fun argb(a: Float, r: Int, g: Int, b: Int) =
    Color.argb((clamp(a, 0f, 1f) * 255).roundToInt(), r, g, b)

private fun mix(c1: IntArray, c2: IntArray, t: Float, a: Float = 1f) = argb(
    a,
    lerp(c1[0].toFloat(), c2[0].toFloat(), t).roundToInt(),
    lerp(c1[1].toFloat(), c2[1].toFloat(), t).roundToInt(),
    lerp(c1[2].toFloat(), c2[2].toFloat(), t).roundToInt(),
)

private fun rad2deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()

/** Draws the simulation onto a native canvas already scaled so that 1 unit = 1 dp. */
class LampRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()
    private val dstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)

    /**
     * The darkness overlay with the light cone cut out of it. Must be drawn into an offscreen layer
     * (for DST_OUT); blurring that layer gives the cone its soft edges.
     */
    fun drawLight(c: Canvas, s: LampSim) {
        val i = clamp(s.intensity, 0f, 1f)
        c.drawColor(argb(0.975f - i * 0.1f, 3, 4, 5))
        if (i <= 0.002f) return

        val apex = s.toWorld(0f, 2f)
        val rl = s.toWorld(-60f, 62f)
        val rr = s.toWorld(60f, 62f)
        val far = max(s.w, s.h) * 2.5f
        val nl = hypot(rl.x - apex.x, rl.y - apex.y)
        val nr = hypot(rr.x - apex.x, rr.y - apex.y)
        val dlx = (rl.x - apex.x) / nl
        val dly = (rl.y - apex.y) / nl
        val drx = (rr.x - apex.x) / nr
        val dry = (rr.y - apex.y) / nr
        path.reset()
        path.moveTo(rl.x, rl.y)
        path.lineTo(rr.x, rr.y)
        path.lineTo(rr.x + drx * far, rr.y + dry * far)
        path.lineTo(rl.x + dlx * far, rl.y + dly * far)
        path.close()

        val b = s.toWorld(BULB_X, BULB_Y)
        val cone = RadialGradient(
            b.x, b.y, max(s.w, s.h) * 1.05f,
            intArrayOf(
                argb(i, 255, 214, 160),
                argb(i * 0.82f, 255, 200, 140),
                argb(0f, 255, 190, 120)
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
        val halo = RadialGradient(
            b.x, b.y, 130f,
            argb(i * 0.5f, 255, 210, 150), argb(0f, 255, 210, 150),
            Shader.TileMode.CLAMP,
        )

        // Punch the light out of the darkness...
        fill.xfermode = dstOut
        fill.shader = cone
        c.drawPath(path, fill)
        fill.shader = halo
        c.drawRect(b.x - 130, b.y - 130, b.x + 130, b.y + 130, fill)

        // ...then tint what's lit with a faint warm glow.
        fill.xfermode = null
        fill.alpha = (0.13f * 255).roundToInt()
        fill.shader = cone
        c.drawPath(path, fill)
        fill.shader = halo
        c.drawRect(b.x - 130, b.y - 130, b.x + 130, b.y + 130, fill)
        fill.alpha = 255
        fill.shader = null
    }

    fun drawScene(c: Canvas, s: LampSim) {
        drawLamp(c, s)
        drawSlingshot(c, s)
        drawParticles(c, s)
        if (s.flash > 0) c.drawColor(argb(s.flash * 0.5f, 255, 236, 200))
    }

    private fun drawLamp(c: Canvas, s: LampSim) {
        val o = s.lampOrigin()
        val i = clamp(s.intensity, 0f, 1f)

        stroke.color = Color.rgb(0x2f, 0x32, 0x36)
        stroke.strokeWidth = 2f
        c.drawLine(s.ax, s.ay, o.x, o.y, stroke)

        c.withTranslation(o.x, o.y) {
            rotate(-rad2deg(s.theta))

            // cap
            fill.shader = LinearGradient(
                -8f, 0f, 8f, 0f,
                intArrayOf(0xFF16181A.toInt(), 0xFF4A4E54.toInt(), 0xFF121315.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            rect.set(-8f, -2f, 8f, 15f)
            drawRoundRect(rect, 3f, 3f, fill)

            // shade
            fill.shader = LinearGradient(
                -62f, 0f, 62f, 0f,
                intArrayOf(
                    0xFF0C0D0F.toInt(),
                    0xFF26292D.toInt(),
                    0xFF4D5157.toInt(),
                    0xFF23262A.toInt(),
                    0xFF0A0B0C.toInt(),
                ),
                floatArrayOf(0f, 0.3f, 0.42f, 0.58f, 1f),
                Shader.TileMode.CLAMP,
            )
            path.reset()
            path.moveTo(-13f, 13f)
            path.cubicTo(-28f, 15f, -56f, 34f, -62f, 62f)
            path.lineTo(62f, 62f)
            path.cubicTo(56f, 34f, 28f, 15f, 13f, 13f)
            path.close()
            drawPath(path, fill)
            stroke.color = argb(0.08f, 255, 255, 255)
            stroke.strokeWidth = 1f
            drawPath(path, stroke)

            // underside of the shade, lit from the bulb
            fill.shader = RadialGradient(
                0f, 63f, 62f,
                mix(intArrayOf(22, 23, 25), intArrayOf(255, 232, 196), i),
                mix(intArrayOf(10, 11, 12), intArrayOf(196, 128, 64), i),
                Shader.TileMode.CLAMP,
            )
            rect.set(-62f, 55f, 62f, 69f)
            drawOval(rect, fill)
            fill.shader = null

            drawBulb(this, s, i)

            stroke.color = argb(0.12f + i * 0.2f, 255, 255, 255)
            stroke.strokeWidth = 1.2f
            rect.set(-62f, 55f, 62f, 69f)
            drawArc(rect, 0f, 180f, false, stroke)
        }

        if (i > 0.01f) {
            val b = s.toWorld(BULB_X, BULB_Y)
            fill.shader = RadialGradient(
                b.x, b.y, 80f,
                argb(0.45f * i, 255, 200, 130), argb(0f, 255, 200, 130),
                Shader.TileMode.CLAMP,
            )
            fill.xfermode = add
            c.drawRect(b.x - 80, b.y - 80, b.x + 80, b.y + 80, fill)
            fill.xfermode = null
            fill.shader = null
        }
    }

    private fun drawBulb(c: Canvas, s: LampSim, i: Float) {
        if (s.broken) {
            fill.color = argb(0.08f, 200, 215, 230)
            stroke.color = argb(0.4f, 220, 230, 240)
            stroke.strokeWidth = 1f
            path.reset()
            JAGGED.forEachIndexed { k, (x, y) ->
                if (k == 0) path.moveTo(x, y) else path.lineTo(
                    x,
                    y
                )
            }
            path.close()
            c.drawPath(path, fill)
            c.drawPath(path, stroke)
            stroke.color = argb(0.8f, 120, 105, 85)
            path.reset()
            path.moveTo(-3f, 60f); path.lineTo(-5f, 70f); path.lineTo(-9f, 73f)
            path.moveTo(3f, 60f); path.lineTo(6f, 69f); path.lineTo(4f, 76f)
            c.drawPath(path, stroke)
            return
        }

        // glass
        val heatTint = clamp((s.heat - 2) / 2.5f, 0f, 1f)
        fill.shader = RadialGradient(
            BULB_X, BULB_Y + 1, 17f,
            intArrayOf(
                mix(
                    intArrayOf(255, 244, 222),
                    intArrayOf(255, 255, 245),
                    heatTint,
                    0.06f + 0.92f * i
                ),
                argb(0.04f + 0.62f * i, 255, 205, 140),
                argb(0.07f + 0.3f * i, 255, 168, 90),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawCircle(BULB_X, BULB_Y, BULB_R, fill)
        fill.shader = null
        stroke.color = argb(0.16f + 0.25f * i, 255, 255, 255)
        stroke.strokeWidth = 1f
        c.drawCircle(BULB_X, BULB_Y, BULB_R, stroke)

        // filament supports
        stroke.color = argb(0.55f, 170, 158, 140)
        c.drawLine(-3f, 60f, -6f, 76f, stroke)
        c.drawLine(3f, 60f, 6f, 76f, stroke)

        // filament
        val f = s.filament
        stroke.color = mix(intArrayOf(96, 78, 56), intArrayOf(255, 244, 214), f)
        stroke.strokeWidth = 1.2f
        if (f > 0.03f) stroke.setShadowLayer(14 * f, 0f, 0f, argb(f, 255, 170, 80))
        path.reset()
        path.moveTo(-6f, 76f)
        for (k in 1..10) {
            val x = -6 + 12f * k / 10
            val sag = sin(k / 10f * Math.PI.toFloat()) * 2.5f
            path.lineTo(x, 76 + sag + if (k % 2 == 1) -1.4f else 1.4f)
        }
        c.drawPath(path, stroke)
        stroke.clearShadowLayer()

        // highlight
        fill.color = argb(0.22f, 255, 255, 255)
        c.withTranslation(-7f, 69f) {
            rotate(rad2deg(-0.5f))
            rect.set(-2.2f, -5f, 2.2f, 5f)
            drawOval(rect, fill)
        }
    }

    private fun drawPebble(c: Canvas, x: Float, y: Float, a: Float, alpha: Float = 1f) {
        c.withTranslation(x, y) {
            rotate(rad2deg(a))
            fill.shader = RadialGradient(
                -1.5f, -1.5f, PEBBLE_R + 1.5f,
                intArrayOf(0xFFA9A49B.toInt(), 0xFF6B665F.toInt(), 0xFF34312D.toInt()),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
            fill.alpha = (clamp(alpha, 0f, 1f) * 255).roundToInt()
            rect.set(-(PEBBLE_R + 0.8f), -(PEBBLE_R - 0.8f), PEBBLE_R + 0.8f, PEBBLE_R - 0.8f)
            drawOval(rect, fill)
            fill.shader = null
            fill.alpha = 255
        }
    }

    private fun drawSlingshot(c: Canvas, s: LampSim) {
        val x = s.slingX
        val y = s.h
        val tlx = x - 28
        val trx = x + 28
        val ty = y - 140
        val px = s.pouchX
        val py = s.pouchY
        val stretch = clamp(hypot(px - s.restX, py - s.restY) / MAX_PULL, 0f, 1f)
        val bandW = 4 - stretch * 2

        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND

        // back band
        stroke.color = 0xFF7A2E22.toInt()
        stroke.strokeWidth = bandW
        c.drawLine(tlx, ty, px - 6, py, stroke)

        // wooden fork
        stroke.shader = LinearGradient(
            x - 30, 0f, x + 30, 0f,
            intArrayOf(0xFF3B2616.toInt(), 0xFF8A5A34.toInt(), 0xFF3A2515.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        stroke.strokeWidth = 11f
        path.reset()
        path.moveTo(x, y + 4)
        path.lineTo(x, y - 72)
        path.quadTo(x - 4, y - 96, tlx, ty)
        path.moveTo(x, y - 72)
        path.quadTo(x + 4, y - 96, trx, ty)
        c.drawPath(path, stroke)
        stroke.shader = null
        stroke.color = argb(0.12f, 255, 220, 180)
        stroke.strokeWidth = 2f
        c.drawLine(x - 2, y, x - 2, y - 70, stroke)
        fill.color = 0xFF2A1A0F.toInt()
        for (tx in floatArrayOf(tlx, trx)) {
            rect.set(tx - 6, ty - 3, tx + 6, ty + 3)
            c.drawOval(rect, fill)
        }

        // trajectory preview
        if (s.aiming) {
            val vx = (s.restX - px) * LAUNCH
            val vy = (s.restY - py) * LAUNCH
            for (k in 1..9) {
                val t = k * 0.035f
                fill.color = argb(0.5f * (1 - k / 10f), 0xEC, 0xE6, 0xDA)
                c.drawCircle(px + vx * t, py + vy * t + 0.5f * PEBBLE_G * t * t, 2f, fill)
            }
        }

        // pouch + pebble
        fill.color = 0xFF4A2C1C.toInt()
        c.withTranslation(px, py) {
            rotate(rad2deg(atan2(py - s.restY, px - s.restX)))
            rect.set(-11f, -7f, 11f, 7f)
            drawOval(rect, fill)
        }
        if (s.loaded) drawPebble(c, px, py, 0f)

        // front band
        stroke.color = 0xFF8E3627.toInt()
        stroke.strokeWidth = bandW
        c.drawLine(trx, ty, px + 6, py, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
    }

    private fun drawParticles(c: Canvas, s: LampSim) {
        for (p in s.pebbles) drawPebble(c, p.x, p.y, p.a, p.life)

        stroke.strokeWidth = 0.8f
        for (sh in s.shards) {
            c.withTranslation(sh.x, sh.y) {
                rotate(rad2deg(sh.a))
                path.reset()
                path.moveTo(sh.poly[0], sh.poly[1])
                for (k in 2 until sh.poly.size step 2) path.lineTo(sh.poly[k], sh.poly[k + 1])
                path.close()
                fill.color = argb(0.16f, 210, 225, 240)
                stroke.color = argb(0.55f, 230, 238, 248)
                drawPath(path, fill)
                drawPath(path, stroke)
            }
        }

        if (s.sparks.isEmpty()) return
        stroke.xfermode = add
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = 1.4f
        for (p in s.sparks) {
            val t = clamp(p.life / p.max, 0f, 1f)
            stroke.color = argb(t, 255, (150 + 90 * t).roundToInt(), (80 * t).roundToInt())
            c.drawLine(p.x, p.y, p.x - p.vx * 0.012f, p.y - p.vy * 0.012f, stroke)
        }
        stroke.xfermode = null
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private companion object {
        val JAGGED = listOf(
            -10f to 60f, -11f to 67f, -8f to 64f, -6f to 71f, -3f to 65f, 0f to 69f,
            3f to 64f, 6f to 72f, 8f to 65f, 11f to 68f, 10f to 60f,
        )
    }
}
