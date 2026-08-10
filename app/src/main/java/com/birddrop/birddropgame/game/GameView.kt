package com.birddrop.birddropgame.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.birddrop.birddropgame.BuildConfig
import com.birddrop.birddropgame.game.model.BirdType
import com.birddrop.birddropgame.game.model.DeviceType
import com.birddrop.birddropgame.game.model.GamePhase
import com.birddrop.birddropgame.game.model.LevelDefinition
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.random.Random

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    lateinit var world: GameWorld
        private set

    var onPlanChanged: (() -> Unit)? = null

    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false

    private val bitmaps = mutableMapOf<String, Bitmap?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(190, 255, 255, 255)
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(70, 255, 255, 255)
    }

    private val dst = RectF()
    private val trailPath = Path()
    private var previewPath = emptyList<Vec2>()
    private var previewVersion = -1
    private var dashPhase = 0f
    private var clock = 0f
    private var statFrames = 0
    private var statTotal = 0L
    private var statWorst = 0L

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    fun setup(level: LevelDefinition) {
        world = GameWorld(level)
        world.aspectProvider = { name ->
            val bmp = bitmap(name)
            if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 1f
        }
        if (width > 0 && height > 0) world.resize(width.toFloat(), height.toFloat())
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread = Thread(this, "BirdDropLoop").also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (::world.isInitialized) world.resize(w.toFloat(), h.toFloat())
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try {
            thread?.join(600)
        } catch (_: InterruptedException) {
        }
        thread = null
    }

    /** Decodes everything this level needs up front so no frame pays for a cold sprite. */
    private fun warmUp() {
        if (!::world.isInitialized) return
        val level = world.level
        bitmap(level.background, BACKDROP_FRAC)
        bitmap("cannon", CANNON_FRAC)
        level.buildings.forEach { bitmap(it.drawableName, BUILDING_FRAC) }
        level.foes.forEach { bitmap(it.type.drawableName, SPRITE_FRAC) }
        level.birds.forEach { bitmap(it.drawableName, SPRITE_FRAC) }
        level.devices.forEach { bitmap(it.drawableName, SPRITE_FRAC) }
    }

    override fun run() {
        warmUp()
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
            last = now
            clock += dt
            dashPhase -= dt * 90f
            if (::world.isInitialized && world.width > 1f) {
                synchronized(world.lock) {
                    if (!paused) world.update(dt)
                    // The hardware canvas keeps the heavy scaled bitmap blits on the GPU;
                    // the software path is only a safety net if the surface refuses it.
                    val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull()
                        ?: holder.lockCanvas()
                    if (canvas != null) {
                        try {
                            render(canvas)
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    }
                }
            }
            val frame = (System.nanoTime() - now) / 1_000_000L
            trackFrame(frame)
            val sleep = 16L - frame
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun trackFrame(millis: Long) {
        if (!BuildConfig.DEBUG) return
        statFrames++
        statTotal += millis
        if (millis > statWorst) statWorst = millis
        if (statFrames >= 60) {
            Log.i("BirdDropFps", "work=${statTotal / statFrames}ms worst=${statWorst}ms")
            statFrames = 0
            statTotal = 0
            statWorst = 0
        }
    }

    private fun render(canvas: Canvas) {
        val w = world.width
        val h = world.height

        canvas.save()
        if (world.shake > 0.01f) {
            val amp = world.shake * h * 0.02f
            canvas.translate(
                (Random.nextFloat() - 0.5f) * amp,
                (Random.nextFloat() - 0.5f) * amp
            )
        }

        drawBackground(canvas, w, h)

        if (world.phase == GamePhase.PLANNING) {
            drawPlacementZone(canvas, w, h)
            drawPreview(canvas, h)
        }

        val buildings = world.buildings
        for (i in buildings.indices) {
            val b = buildings[i]
            if (!b.destroyed) drawBuilding(canvas, b, h)
        }
        val foes = world.foes
        for (i in foes.indices) {
            val foe = foes[i]
            if (foe.alive) drawFoe(canvas, foe, h)
        }
        val devices = world.placedDevices
        for (i in devices.indices) drawDevice(canvas, devices[i], h)
        drawCannon(canvas, h)
        drawBird(canvas, h)
        drawParticles(canvas)

        canvas.restore()
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        val bmp = bitmap(world.level.background, BACKDROP_FRAC)
        if (bmp == null) {
            canvas.drawColor(Color.rgb(86, 170, 240))
            return
        }
        // Pin the painted horizon to the physics ground, then scale up until the
        // art covers the whole field. Leftover width becomes parallax slack.
        val horizon = world.level.horizon
        val scale = maxOf(w / bmp.width, (world.groundY / horizon) / bmp.height)
        val drawnW = bmp.width * scale
        val drawnH = bmp.height * scale
        val slack = drawnW - w
        val progress = ((world.activeBird?.x ?: 0f) / w).coerceIn(0f, 1f)
        val left = -slack * (0.25f + 0.5f * progress)
        val top = world.groundY - drawnH * horizon
        dst.set(left, top, left + drawnW, top + drawnH)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun drawPlacementZone(canvas: Canvas, w: Float, h: Float) {
        val pulse = 0.5f + 0.5f * sin(clock * 2f)
        val left = w * 0.16f
        val right = w * 0.62f
        val top = h * 0.10f
        val bottom = world.groundY - h * 0.02f
        val corner = h * 0.03f

        solid.color = Color.WHITE
        solid.alpha = (16 + 10 * pulse).toInt()
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, solid)
        solid.alpha = 255

        zonePaint.strokeWidth = h * 0.005f
        zonePaint.alpha = (90 + 60 * pulse).toInt()
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, zonePaint)
    }

    /** Beads running along the simulated flight path; cheaper and clearer than a dashed stroke. */
    private fun drawPreview(canvas: Canvas, h: Float) {
        if (world.planVersion != previewVersion) {
            previewPath = world.simulatePath()
            previewVersion = world.planVersion
        }
        val points = previewPath
        if (points.size < 2) return
        val step = 8
        val offset = ((-dashPhase * 0.06f).toInt() % step + step) % step
        val radius = h * 0.011f
        var i = offset
        while (i < points.size) {
            val p = points[i]
            val fade = 1f - i.toFloat() / points.size * 0.4f
            solid.color = Color.BLACK
            solid.alpha = (70 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, radius * fade * 1.35f, solid)
            solid.color = Color.WHITE
            solid.alpha = (235 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, radius * fade, solid)
            i += step
        }
        val end = points[points.size - 1]
        solid.color = Color.argb(90, 0, 0, 0)
        canvas.drawCircle(end.x, end.y, h * 0.022f, solid)
        solid.color = Color.argb(220, 255, 226, 138)
        canvas.drawCircle(end.x, end.y, h * 0.017f, solid)
        solid.alpha = 255
    }

    private fun drawBuilding(canvas: Canvas, b: BuildingEntity, h: Float) {
        val bmp = bitmap(b.drawableName, BUILDING_FRAC) ?: return
        canvas.save()
        if (b.wobble > 0.01f) {
            val angle = sin(clock * 40f) * b.wobble * 2.5f
            canvas.rotate(angle, b.x + b.width / 2f, b.y + b.height)
        }
        dst.set(b.x, b.y, b.x + b.width, b.y + b.height)
        canvas.drawBitmap(bmp, null, dst, paint)
        if (b.flash > 0.01f) {
            flashPaint.alpha = (b.flash * 170).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bmp, null, dst, flashPaint)
        }
        canvas.restore()

        if (b.hp < b.maxHp) {
            val ratio = (b.hp / b.maxHp).coerceIn(0f, 1f)
            val barH = h * 0.012f
            val top = b.y - barH * 1.6f
            solid.color = Color.argb(170, 0, 0, 0)
            canvas.drawRoundRect(b.x, top, b.x + b.width, top + barH, barH, barH, solid)
            solid.color = if (ratio > 0.45f) Color.rgb(92, 208, 104) else Color.rgb(235, 92, 78)
            canvas.drawRoundRect(b.x, top, b.x + b.width * ratio, top + barH, barH, barH, solid)
        }
    }

    private fun drawFoe(canvas: Canvas, foe: FoeEntity, h: Float) {
        val bmp = bitmap(foe.type.drawableName, SPRITE_FRAC) ?: return
        val bob = sin(clock * 2.4f + foe.bobPhase) * h * 0.006f
        val squash = 1f + foe.squash * 0.25f
        val halfW = foe.size * 0.5f * squash * bmp.width / bmp.height
        val halfH = foe.size * 0.5f / squash
        dst.set(foe.x - halfW, foe.y - halfH + bob, foe.x + halfW, foe.y + halfH + bob)
        canvas.drawBitmap(bmp, null, dst, paint)
        if (foe.flash > 0.01f) {
            flashPaint.alpha = (foe.flash * 190).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bmp, null, dst, flashPaint)
        }
    }

    private fun drawDevice(canvas: Canvas, device: PlacedDevice, h: Float) {
        val bmp = bitmap(device.type.drawableName, SPRITE_FRAC) ?: return
        val size = h * 0.13f
        val halfW = size * 0.5f * bmp.width / bmp.height
        if (device.glow > 0.01f) {
            solid.color = device.type.tint
            solid.alpha = (device.glow * 120).toInt().coerceIn(0, 255)
            canvas.drawCircle(device.x, device.y, size * (0.6f + device.glow * 0.35f), solid)
            solid.alpha = 255
        }
        dst.set(device.x - halfW, device.y - size / 2f, device.x + halfW, device.y + size / 2f)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun drawCannon(canvas: Canvas, h: Float) {
        val bmp = bitmap("cannon", CANNON_FRAC) ?: return
        val size = h * 0.30f
        val halfW = size * 0.5f * bmp.width / bmp.height
        val angle = -world.level.launchAngleDeg + CANNON_ART_ANGLE
        val kick = world.recoil * h * 0.03f
        canvas.save()
        canvas.rotate(angle, world.cannonX, world.cannonY)
        dst.set(
            world.cannonX - halfW - kick,
            world.cannonY - size / 2f,
            world.cannonX + halfW - kick,
            world.cannonY + size / 2f
        )
        canvas.drawBitmap(bmp, null, dst, paint)
        canvas.restore()

        if (world.phase == GamePhase.PLANNING) {
            val bird = bitmap(world.selectedBird.drawableName, SPRITE_FRAC)
            if (bird != null) {
                val bs = h * 0.14f
                val bw = bs * bird.width / bird.height
                val hover = sin(clock * 3f) * h * 0.008f
                val top = world.cannonY - h * 0.26f + hover
                solid.color = Color.argb(60, 0, 0, 0)
                canvas.drawOval(
                    world.cannonX - bw * 0.42f,
                    top + bs * 0.92f,
                    world.cannonX + bw * 0.42f,
                    top + bs * 1.06f,
                    solid
                )
                solid.alpha = 255
                dst.set(world.cannonX - bw / 2f, top, world.cannonX + bw / 2f, top + bs)
                canvas.drawBitmap(bird, null, dst, paint)
            }
        }
    }

    private fun drawBird(canvas: Canvas, h: Float) {
        val bird = world.activeBird ?: return
        if (bird.trail.size > 1) {
            trailPath.rewind()
            val first = bird.trail.first()
            trailPath.moveTo(first.x, first.y)
            bird.trail.forEach { trailPath.lineTo(it.x, it.y) }
            trailPaint.strokeWidth = h * 0.012f
            trailPaint.color = Color.argb(110, 255, 214, 130)
            canvas.drawPath(trailPath, trailPaint)
        }
        val bmp = bitmap(bird.type.drawableName, SPRITE_FRAC) ?: return
        val angle = Math.toDegrees(atan2(bird.vy.toDouble(), bird.vx.toDouble())).toFloat() * 0.6f
        val size = bird.radius * 2.6f
        val halfW = size * 0.5f * bmp.width / bmp.height
        canvas.save()
        canvas.rotate(angle, bird.x, bird.y)
        dst.set(bird.x - halfW, bird.y - size / 2f, bird.x + halfW, bird.y + size / 2f)
        canvas.drawBitmap(bmp, null, dst, paint)
        canvas.restore()
    }

    private fun drawParticles(canvas: Canvas) {
        val particles = world.particles
        for (i in particles.indices) {
            val p = particles[i]
            val alpha = ((p.life / p.maxLife) * 255f).toInt().coerceIn(0, 255)
            solid.color = p.color
            solid.alpha = alpha
            when (p.kind) {
                ParticleKind.DEBRIS -> {
                    canvas.save()
                    canvas.rotate(Math.toDegrees(p.rotation.toDouble()).toFloat(), p.x, p.y)
                    canvas.drawRect(
                        p.x - p.size, p.y - p.size * 0.6f,
                        p.x + p.size, p.y + p.size * 0.6f, solid
                    )
                    canvas.restore()
                }

                ParticleKind.FEATHER -> {
                    canvas.save()
                    canvas.rotate(Math.toDegrees(p.rotation.toDouble()).toFloat(), p.x, p.y)
                    canvas.drawOval(
                        p.x - p.size * 1.4f, p.y - p.size * 0.5f,
                        p.x + p.size * 1.4f, p.y + p.size * 0.5f, solid
                    )
                    canvas.restore()
                }

                else -> canvas.drawCircle(p.x, p.y, p.size, solid)
            }
        }
        solid.alpha = 255
    }

    /**
     * Decodes a sprite no larger than it will ever be drawn. [maxFrac] is the tallest
     * share of the viewport the sprite can occupy, so a tiny icon never costs a
     * full-resolution texture.
     */
    private fun bitmap(name: String, maxFrac: Float = 0.5f): Bitmap? {
        if (bitmaps.containsKey(name)) return bitmaps[name]
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        val bmp = if (id == 0) null else decode(id, maxFrac)
        bitmaps[name] = bmp
        return bmp
    }

    private fun decode(id: Int, maxFrac: Float): Bitmap? {
        val viewport = if (height > 0) height else resources.displayMetrics.heightPixels
        val target = (viewport * maxFrac).coerceAtLeast(1f)
        val bounds = BitmapFactory.Options().apply {
            inScaled = false
            inJustDecodeBounds = true
        }
        runCatching { BitmapFactory.decodeResource(resources, id, bounds) }
        var sample = 1
        while (bounds.outHeight > 0 && bounds.outHeight / (sample * 2) >= target) sample *= 2
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = sample
        }
        return runCatching { BitmapFactory.decodeResource(resources, id, options) }.getOrNull()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::world.isInitialized) return false
        if (event.actionMasked != MotionEvent.ACTION_DOWN) {
            return event.actionMasked == MotionEvent.ACTION_MOVE
        }
        synchronized(world.lock) {
            when (world.phase) {
                GamePhase.PLANNING -> {
                    val removed = world.removeDeviceAt(event.x, event.y)
                    val changed = removed || world.placeDevice(event.x, event.y)
                    if (changed) onPlanChanged?.invoke()
                }

                GamePhase.FLYING -> world.useAbility()
                else -> Unit
            }
        }
        return true
    }

    fun selectBird(type: BirdType) {
        if (!::world.isInitialized) return
        synchronized(world.lock) {
            if (world.phase != GamePhase.PLANNING) return
            world.selectedBird = type
        }
        onPlanChanged?.invoke()
    }

    fun selectDevice(type: DeviceType) {
        if (!::world.isInitialized) return
        synchronized(world.lock) {
            if (world.phase != GamePhase.PLANNING) return
            world.selectedDevice = type
        }
        onPlanChanged?.invoke()
    }

    /** Freezes the simulation while a dialog is up; rendering keeps going so the scene stays live. */
    fun setPaused(value: Boolean) {
        paused = value
    }

    fun launch() {
        if (!::world.isInitialized) return
        synchronized(world.lock) { world.launch() }
    }

    fun clearDevices() {
        if (!::world.isInitialized) return
        synchronized(world.lock) { world.clearDevices() }
    }

    fun restartLevel() {
        if (!::world.isInitialized) return
        synchronized(world.lock) { world.restart() }
    }

    fun release() {
        bitmaps.values.forEach { it?.recycle() }
        bitmaps.clear()
    }

    private companion object {
        /** The cannon art already points up-right by roughly this much. */
        const val CANNON_ART_ANGLE = 26f

        /** Tallest slice of the viewport each sprite family can cover, used when decoding. */
        const val BACKDROP_FRAC = 1.8f
        const val BUILDING_FRAC = 0.5f
        const val CANNON_FRAC = 0.4f
        const val SPRITE_FRAC = 0.25f
    }
}

