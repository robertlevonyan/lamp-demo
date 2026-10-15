package com.inn.lampdemo.lamp

import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

internal const val TAU = (2 * PI).toFloat()
internal fun clamp(v: Float, a: Float, b: Float) = max(a, min(b, v))
internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun rnd() = Random.nextFloat()

class Pebble(var x: Float, var y: Float, var vx: Float, var vy: Float) {
    var a = 0f
    var hitT = 0f
    var rest = 0f
    var life = 1f
}

class Shard(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var a: Float, var va: Float,
    /** Polygon points as x0, y0, x1, y1, ... */
    val poly: FloatArray,
)

class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val max: Float)

/** A pebble hit the wall switch; [id] changes for every hit so the UI can replay the knock animation. */
data class Knock(val id: Int, val nx: Float, val ny: Float, val rot: Float)

private sealed interface Grab {
    data object Pouch : Grab
    class Lamp(val offset: Float) : Grab
}

/**
 * Physics + game state for the slingshot lamp. All units are dp, time is seconds.
 * A straight port of https://kamran.fyi/lamp.
 */
class LampSim(private val sound: LampSound) {
    // world
    var w = 0f
        private set
    var h = 0f
        private set

    // lamp pendulum
    var ax = 0f
        private set
    val ay = -10f
    var len = 260f
        private set
    var theta = 0.06f
        private set
    private var omega = 0f

    // light
    var switchOn by mutableStateOf(true)
        private set
    var broken by mutableStateOf(false)
        private set
    var intensity = 1f
        private set
    var filament = 1f
        private set
    var heat = 0f
        private set
    var flash = 0f
        private set
    private var warm = 0f
    private var popT = 0f

    // slingshot
    var slingX = 0f
        private set
    var restX = 0f
        private set
    var restY = 0f
        private set
    var pouchX = 0f
        private set
    var pouchY = 0f
        private set
    var loaded = true
        private set
    private var slingVx = 0f
    private var slingVy = 0f
    private var reloadT = 0f
    private var creakAt = 0f
    private var creakCd = 0f

    // input
    private var grab: Grab? = null
    private var mouseX = -999f
    private var mouseY = -999f
    val aiming get() = grab == Grab.Pouch

    // stats / UI
    var shots by mutableIntStateOf(0)
        private set
    var bulbs by mutableIntStateOf(0)
        private set
    var knock by mutableStateOf<Knock?>(null)
        private set

    /** Bumped every frame so Compose draw scopes re-run. */
    var frame by mutableLongStateOf(0L)

    /** Wall switch bounds in dp, reported by the switch composable. */
    var switchRect: RectF? = null

    val pebbles = ArrayList<Pebble>()
    val shards = ArrayList<Shard>()
    val sparks = ArrayList<Spark>()

    private var accT = 0f

    val stateLabel: String
        get() = when {
            broken -> if (switchOn) "blown (switch on)" else "blown"
            switchOn -> "on"
            else -> "off"
        }

    // ---------- geometry ----------

    fun lampOrigin() = PointF(ax + sin(theta) * len, ay + cos(theta) * len)

    fun toWorld(lx: Float, ly: Float): PointF {
        val o = lampOrigin()
        val c = cos(theta)
        val s = sin(theta)
        return PointF(o.x + lx * c + ly * s, o.y - lx * s + ly * c)
    }

    private fun toLocal(x: Float, y: Float): PointF {
        val o = lampOrigin()
        val c = cos(theta)
        val s = sin(theta)
        val dx = x - o.x
        val dy = y - o.y
        return PointF(dx * c - dy * s, dx * s + dy * c)
    }

    private fun dirToWorld(nx: Float, ny: Float): PointF {
        val c = cos(theta)
        val s = sin(theta)
        return PointF(nx * c + ny * s, -nx * s + ny * c)
    }

    fun resize(width: Float, height: Float) {
        if (width == w && height == h) return
        val oldW = w
        val oldH = h
        w = width
        h = height
        if (oldW > 0f && oldH > 0f) remap(oldW, oldH)
        ax = w / 2
        len = clamp(h * (if (w < 640) 0.27f else 0.3f), if (h < 500) 90f else 130f, 320f)
        slingX = clamp(w * 0.2f, 70f, 320f)
        restX = slingX
        restY = h - 128
        if (grab != Grab.Pouch) {
            pouchX = restX
            pouchY = restY
        }
    }

    /** Moves debris into the new world size, keeping whatever lay on the old floor on the new one. */
    private fun remap(oldW: Float, oldH: Float) {
        val sx = w / oldW
        val sy = h / oldH
        for (p in pebbles) {
            p.x *= sx
            p.y = if (p.rest > 0 || p.y >= oldH - PEBBLE_R - 0.5f) h - PEBBLE_R else p.y * sy
        }
        for (s in shards) {
            s.x *= sx
            s.y = if (s.y >= oldH - 3.5f) h - 3 else s.y * sy
        }
        for (p in sparks) {
            p.x *= sx
            p.y *= sy
        }
    }

    // ---------- state saving ----------

    fun saveState(): Bundle = Bundle().apply {
        putFloat(KEY_W, w)
        putFloat(KEY_H, h)
        putFloat(KEY_THETA, theta)
        putFloat(KEY_OMEGA, omega)
        putBoolean(KEY_SWITCH_ON, switchOn)
        putBoolean(KEY_BROKEN, broken)
        putFloat(KEY_HEAT, heat)
        putFloat(KEY_POP_T, popT)
        putInt(KEY_SHOTS, shots)
        putInt(KEY_BULBS, bulbs)
        putFloatArray(KEY_PEBBLES, FloatArray(pebbles.size * 7).also { out ->
            pebbles.forEachIndexed { i, p ->
                floatArrayOf(p.x, p.y, p.vx, p.vy, p.a, p.rest, p.life).copyInto(out, i * 7)
            }
        })
        // Shards have variable-length polygons: [x, y, vx, vy, a, va, pointCount, px0, py0, ...] per shard.
        val shardData = ArrayList<Float>()
        for (s in shards) {
            shardData += listOf(s.x, s.y, s.vx, s.vy, s.a, s.va, (s.poly.size / 2).toFloat())
            shardData += s.poly.asList()
        }
        putFloatArray(KEY_SHARDS, shardData.toFloatArray())
    }

    fun restoreState(b: Bundle) {
        w = b.getFloat(KEY_W)
        h = b.getFloat(KEY_H)
        theta = b.getFloat(KEY_THETA, theta)
        omega = b.getFloat(KEY_OMEGA)
        switchOn = b.getBoolean(KEY_SWITCH_ON, true)
        broken = b.getBoolean(KEY_BROKEN)
        heat = b.getFloat(KEY_HEAT)
        popT = b.getFloat(KEY_POP_T)
        shots = b.getInt(KEY_SHOTS)
        bulbs = b.getInt(KEY_BULBS)
        // Start at the steady brightness rather than fading in again.
        intensity = if (switchOn && !broken) 1f else 0f
        filament = intensity

        pebbles.clear()
        b.getFloatArray(KEY_PEBBLES)?.let { d ->
            for (i in 0 until d.size / 7) {
                val o = i * 7
                pebbles += Pebble(d[o], d[o + 1], d[o + 2], d[o + 3]).apply {
                    a = d[o + 4]
                    rest = d[o + 5]
                    life = d[o + 6]
                }
            }
        }
        shards.clear()
        b.getFloatArray(KEY_SHARDS)?.let { d ->
            var o = 0
            while (o + 7 <= d.size) {
                val n = d[o + 6].toInt()
                shards += Shard(
                    d[o], d[o + 1], d[o + 2], d[o + 3], d[o + 4], d[o + 5],
                    d.copyOfRange(o + 7, o + 7 + n * 2),
                )
                o += 7 + n * 2
            }
        }
    }

    // ---------- actions ----------

    fun toggleSwitch() = setSwitch(!switchOn)

    private fun setSwitch(on: Boolean) {
        if (switchOn == on) return
        switchOn = on
        sound.play(if (on) Sfx.On else Sfx.Off)
        if (broken || popT > 0) return
        if (!on) {
            heat += 0.4f
            return
        }
        heat += 1f
        warm = 0.16f
        if (heat > 4.4f) popT = 0.14f
    }

    private fun pop(vx: Float = 0f, vy: Float = 0f) {
        val b = toWorld(BULB_X, BULB_Y)
        broken = true
        popT = 0f
        flash = 1f
        bulbs++
        repeat(38) {
            val ang = rnd() * TAU
            val sp = 120 + rnd() * 480
            val n = 3 + Random.nextInt(2)
            val r = 2 + rnd() * 6
            val poly = FloatArray(n * 2)
            for (k in 0 until n) {
                val a = k.toFloat() / n * TAU + rnd() * 0.8f
                poly[k * 2] = cos(a) * r * (0.5f + rnd())
                poly[k * 2 + 1] = sin(a) * r * (0.5f + rnd())
            }
            shards += Shard(
                x = b.x + cos(ang) * 8, y = b.y + sin(ang) * 8,
                vx = cos(ang) * sp + vx * 0.35f, vy = sin(ang) * sp * 0.7f + 80 + vy * 0.35f,
                a = rnd() * TAU, va = (rnd() - 0.5f) * 24, poly = poly,
            )
        }
        repeat(40) {
            val ang = rnd() * TAU
            val sp = 200 + rnd() * 700
            sparks += Spark(b.x, b.y, cos(ang) * sp, sin(ang) * sp, 0.3f + rnd() * 0.6f, 0.9f)
        }
        sound.play(Sfx.Pop)
    }

    fun replaceBulb() {
        broken = false
        heat = 0f
        popT = 0f
        if (switchOn) {
            warm = 0.2f
            sound.play(Sfx.On)
        }
    }

    private fun shoot() {
        val vx = (restX - pouchX) * LAUNCH
        val vy = (restY - pouchY) * LAUNCH
        val pull = hypot(vx, vy) / LAUNCH
        if (pull < 18) return
        pebbles += Pebble(pouchX, pouchY, vx, vy)
        loaded = false
        reloadT = 0.45f
        slingVx = vx * 0.4f
        slingVy = vy * 0.4f
        shots++
        sound.play(Sfx.Twang)
    }

    // ---------- input (dp) ----------

    /** Returns true when the touch grabbed the pouch or the lamp shade. */
    fun pointerDown(x: Float, y: Float): Boolean {
        mouseX = x
        mouseY = y
        grab = hitTest(x, y)
        return grab != null
    }

    fun pointerMove(x: Float, y: Float) {
        mouseX = x
        mouseY = y
    }

    fun pointerUp() {
        if (grab == Grab.Pouch) {
            shoot()
            creakAt = 0f
        }
        grab = null
    }

    private fun hitTest(x: Float, y: Float): Grab? {
        // Slightly larger than the web version's 28px: fingers are fatter than cursors.
        if (loaded && hypot(x - pouchX, y - pouchY) < 36) return Grab.Pouch
        val l = toLocal(x, y)
        val halfW = 14 + 50 * clamp((l.y - 12) / 50, 0f, 1f) + 6
        if (l.y > -6 && l.y < 92 && abs(l.x) < halfW) {
            val tgt = atan2(x - ax, y - ay)
            return Grab.Lamp(tgt - theta)
        }
        return null
    }

    // ---------- simulation ----------

    fun advance(dtRaw: Float) {
        if (w <= 0f) return
        val dt = min(0.05f, dtRaw)
        accT += dt
        while (accT >= STEP) {
            step(STEP)
            accT -= STEP
        }
        updateWorld(dt)
        updateLight(dt)
    }

    private fun kickLamp(px: Float, py: Float, fx: Float, fy: Float) {
        val rx = px - ax
        val ry = py - ay
        omega += (ry * fx - rx * fy) / (len * len)
    }

    private var knockId = 0

    private fun collideSwitch(p: Pebble) {
        val rect = switchRect ?: return
        if (p.hitT > 0) return
        val r = PEBBLE_R
        val cx = clamp(p.x, rect.left, rect.right)
        val cy = clamp(p.y, rect.top, rect.bottom)
        var nx = p.x - cx
        var ny = p.y - cy
        val d = hypot(nx, ny)
        if (d > r) return
        if (d == 0f) {
            val sp = hypot(p.vx, p.vy).takeIf { it > 0f } ?: 1f
            nx = -p.vx / sp
            ny = -p.vy / sp
        } else {
            nx /= d
            ny /= d
        }
        val vn = p.vx * nx + p.vy * ny
        if (vn >= 0) return
        p.vx -= 1.4f * vn * nx
        p.vy -= 1.4f * vn * ny
        p.x = cx + nx * (r + 1)
        p.y = cy + ny * (r + 1)
        p.hitT = 0.1f
        sound.play(Sfx.Tap, -vn / 2000)
        knock = Knock(++knockId, nx, ny, (rnd() - 0.5f) * 6)
        setSwitch(!switchOn)
    }

    private fun collidePebble(p: Pebble) {
        collideSwitch(p)
        if (p.hitT > 0) return
        val l = toLocal(p.x, p.y)
        val r = PEBBLE_R

        if (!broken && hypot(l.x - BULB_X, l.y - BULB_Y) < BULB_R + r) {
            pop(p.vx, p.vy)
            kickLamp(p.x, p.y, p.vx * 0.08f, p.vy * 0.08f)
            p.vx *= 0.75f
            p.vy *= 0.75f
            p.hitT = 0.08f
            return
        }

        if (l.y < 10 - r || l.y > 62 + r) return
        val halfW = 14 + 48 * clamp((l.y - 13) / 49, 0f, 1f).pow(0.7f)
        if (abs(l.x) > halfW + r) return
        var nx = l.x
        var ny = l.y - 30
        val nl = hypot(nx, ny).takeIf { it > 0f } ?: 1f
        nx /= nl
        ny /= nl
        val n = dirToWorld(nx, ny)
        val vn = p.vx * n.x + p.vy * n.y
        if (vn >= 0) return
        val e = 0.45f
        p.vx -= (1 + e) * vn * n.x
        p.vy -= (1 + e) * vn * n.y
        p.x += n.x * 6
        p.y += n.y * 6
        val j = -(1 + e) * vn * 0.22f
        kickLamp(p.x, p.y, -n.x * j, -n.y * j)
        p.hitT = 0.06f
        sound.play(Sfx.Clank, -vn / 1200)
    }

    private fun step(dt: Float) {
        val g = grab
        var acc = -(G / len) * sin(theta) - omega * 0.3f
        if (g is Grab.Lamp) {
            val tgt = clamp(atan2(mouseX - ax, mouseY - ay) - g.offset, -1.25f, 1.25f)
            acc += (tgt - theta) * 170 - omega * 16
        }
        omega += acc * dt
        val prevTheta = theta
        theta = clamp(theta + omega * dt, -1.45f, 1.45f)
        if (sign(prevTheta) != sign(theta) && abs(omega) > 0.35f) {
            sound.play(Sfx.Squeak, (abs(omega) - 0.35f) / 2)
        }

        if (g == Grab.Pouch) {
            var dx = mouseX - restX
            var dy = min(mouseY, h - 10) - restY
            val d = hypot(dx, dy)
            if (d > MAX_PULL) {
                dx *= MAX_PULL / d
                dy *= MAX_PULL / d
            }
            pouchX = restX + dx
            pouchY = restY + dy
            slingVx = 0f
            slingVy = 0f
            val pull = hypot(dx, dy)
            creakCd -= dt
            if (abs(pull - creakAt) > 7 && creakCd <= 0) {
                sound.play(Sfx.Stretch, pull / MAX_PULL)
                creakAt = pull
                creakCd = 0.03f
            }
        } else {
            slingVx += ((restX - pouchX) * 900 - slingVx * 12) * dt
            slingVy += ((restY - pouchY) * 900 - slingVy * 12) * dt
            pouchX += slingVx * dt
            pouchY += slingVy * dt
        }

        for (p in pebbles) {
            p.hitT = max(0f, p.hitT - dt)
            if (p.rest > 0) continue
            p.vy += PEBBLE_G * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.a += p.vx * dt * 0.05f
            collidePebble(p)
            if (p.y > h - PEBBLE_R) {
                p.y = h - PEBBLE_R
                if (p.vy > 120) sound.play(Sfx.Tap, p.vy / 3000)
                p.vy *= -0.35f
                p.vx *= 0.75f
                if (abs(p.vy) < 40 && abs(p.vx) < 20) p.rest = 0.0001f
            }
            if (p.x < PEBBLE_R || p.x > w - PEBBLE_R) {
                p.x = clamp(p.x, PEBBLE_R, w - PEBBLE_R)
                p.vx *= -0.5f
            }
        }
    }

    private fun updateWorld(dt: Float) {
        if (!loaded) {
            reloadT -= dt
            if (reloadT <= 0) loaded = true
        }
        for (p in pebbles) {
            if (p.rest > 0) {
                p.rest += dt
                if (p.rest > 4) p.life -= dt * 1.5f
            }
        }
        pebbles.removeAll { it.life <= 0 || it.y >= h + 200 }
        while (pebbles.size > 10) pebbles.removeAt(0)

        for (s in shards) {
            s.vy += G * 0.75f * dt
            s.x += s.vx * dt
            s.y += s.vy * dt
            s.a += s.va * dt
            if (s.y > h - 3) {
                s.y = h - 3
                s.vy *= -0.28f
                s.vx *= 0.6f
                s.va *= 0.5f
                if (abs(s.vy) < 30) s.vy = 0f
            }
            if (s.x < 0 || s.x > w) {
                s.vx *= -0.5f
                s.x = clamp(s.x, 0f, w)
            }
        }
        while (shards.size > 240) shards.removeAt(0)

        for (p in sparks) {
            p.vy += G * 0.4f * dt
            p.vx *= 0.985f
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt
        }
        sparks.removeAll { it.life <= 0 }
    }

    private fun updateLight(dt: Float) {
        heat = max(0f, heat - dt * 0.35f)
        var target = if (switchOn && !broken) 1f else 0f
        if (warm > 0) {
            warm -= dt
            if (rnd() < 0.45f) target *= 0.12f
        }
        if (switchOn && heat > 2.6f && rnd() < (heat - 2.6f) * 0.06f) target *= 0.3f
        if (popT > 0) {
            popT -= dt
            target = 1.6f
            if (popT <= 0) {
                pop()
                target = 0f
            }
        }
        val upI = if (target > intensity) 34f else 16f
        intensity = lerp(intensity, target, 1 - exp(-upI * dt))
        val upF = if (target > filament) 14f else 3.2f
        filament = lerp(filament, clamp(target, 0f, 1f), 1 - exp(-upF * dt))
        if (broken) filament = 0f
        flash = max(0f, flash - dt * 3.5f)
    }

    companion object {
        const val G = 2600f
        const val PEBBLE_G = 1500f
        const val STEP = 1f / 120
        const val BULB_X = 0f
        const val BULB_Y = 74f
        const val BULB_R = 16f
        const val PEBBLE_R = 7f
        const val MAX_PULL = 140f
        const val LAUNCH = 12.5f

        private const val KEY_W = "w"
        private const val KEY_H = "h"
        private const val KEY_THETA = "theta"
        private const val KEY_OMEGA = "omega"
        private const val KEY_SWITCH_ON = "switchOn"
        private const val KEY_BROKEN = "broken"
        private const val KEY_HEAT = "heat"
        private const val KEY_POP_T = "popT"
        private const val KEY_SHOTS = "shots"
        private const val KEY_BULBS = "bulbs"
        private const val KEY_PEBBLES = "pebbles"
        private const val KEY_SHARDS = "shards"
    }
}
