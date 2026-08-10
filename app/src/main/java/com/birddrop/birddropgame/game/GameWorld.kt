package com.birddrop.birddropgame.game

import com.birddrop.birddropgame.game.model.BirdType
import com.birddrop.birddropgame.game.model.DeviceType
import com.birddrop.birddropgame.game.model.FoeType
import com.birddrop.birddropgame.game.model.GamePhase
import com.birddrop.birddropgame.game.model.LevelDefinition
import com.birddrop.birddropgame.game.model.MaterialType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

data class Vec2(val x: Float, val y: Float)

enum class ParticleKind { SPARK, DEBRIS, DUST, FEATHER }

class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    val maxLife: Float,
    val color: Int,
    val size: Float,
    var rotation: Float,
    val spin: Float,
    val kind: ParticleKind
)

class PlacedDevice(
    val type: DeviceType,
    val x: Float,
    val y: Float
) {
    var used = false
    var glow = 0f
}

class BirdEntity(
    val type: BirdType,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val radius: Float
) {
    val trail = ArrayDeque<Vec2>()
    var abilityUsed = false
    var slamming = false
    var spin = 0f
}

class BuildingEntity(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val material: MaterialType,
    val drawableName: String,
    val maxHp: Float
) {
    var hp = maxHp
    var destroyed = false
    var flash = 0f
    var wobble = 0f
}

class FoeEntity(
    val x: Float,
    val y: Float,
    val type: FoeType,
    val size: Float
) {
    var hp = type.hp
    var alive = true
    var flash = 0f
    var squash = 0f
    val bobPhase = Random.nextFloat() * 6.28f
}

private class Mover(var x: Float, var y: Float, var vx: Float, var vy: Float) {
    var portalCooldown = 0f
}

class GameWorld(val level: LevelDefinition) {

    var width = 1f
        private set
    var height = 1f
        private set
    var groundY = 0f
        private set
    var cannonX = 0f
        private set
    var cannonY = 0f
        private set

    var phase = GamePhase.PLANNING
        private set

    /** Bumped whenever the planned route changes, so the preview can be cached. */
    var planVersion = 0
        private set

    var selectedBird = level.birds.first()
        set(value) {
            if (field != value) {
                field = value
                planVersion++
            }
        }
    var selectedDevice: DeviceType? = level.devices.firstOrNull()
    var birdsLeft = level.birdBudget
        private set
    var score = 0
        private set
    var shake = 0f
        private set
    var recoil = 0f
        private set

    val placedDevices = mutableListOf<PlacedDevice>()
    val buildings = mutableListOf<BuildingEntity>()
    val foes = mutableListOf<FoeEntity>()
    val particles = mutableListOf<Particle>()
    var activeBird: BirdEntity? = null
        private set

    /** Supplies width/height ratios of sprites so buildings keep their proportions. */
    var aspectProvider: ((String) -> Float)? = null

    /** Held by the render loop; UI-thread mutations take it before touching state. */
    val lock = Any()

    var onLaunch: (() -> Unit)? = null
    var onDeviceActivated: ((DeviceType) -> Unit)? = null
    var onImpact: (() -> Unit)? = null
    var onFoeHit: (() -> Unit)? = null
    var onBuildingDestroyed: (() -> Unit)? = null
    var onAbility: (() -> Unit)? = null
    var onPhaseChanged: ((GamePhase) -> Unit)? = null

    private var gravity = 1600f
    private var unit = 1f
    private var deviceRadius = 40f
    private var flightTime = 0f
    private var settleTime = 0f
    private var built = false

    val devicesLeft: Int get() = level.maxDevices - placedDevices.size

    fun resize(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        width = w
        height = h
        groundY = h * 0.86f
        cannonX = w * 0.10f
        cannonY = groundY - h * 0.07f
        unit = w
        gravity = w * 2.2f
        deviceRadius = h * 0.058f
        buildFort()
    }

    private fun buildFort() {
        buildings.clear()
        foes.clear()
        level.buildings.forEach { spawn ->
            val bh = spawn.height * height
            val aspect = aspectProvider?.invoke(spawn.drawableName) ?: 1f
            val bw = bh * aspect
            val bottom = groundY - spawn.base * height
            buildings += BuildingEntity(
                x = spawn.x * width - bw / 2f,
                y = bottom - bh,
                width = bw,
                height = bh,
                material = spawn.material,
                drawableName = spawn.drawableName,
                maxHp = spawn.hp
            )
        }
        level.foes.forEach { spawn ->
            val size = spawn.type.sizeFrac * height
            foes += FoeEntity(
                x = spawn.x * width,
                y = groundY - spawn.base * height - size / 2f,
                type = spawn.type,
                size = size
            )
        }
        built = true
        planVersion++
    }

    fun restart() {
        placedDevices.clear()
        particles.clear()
        activeBird = null
        birdsLeft = level.birdBudget
        score = 0
        flightTime = 0f
        settleTime = 0f
        if (built) buildFort()
        planVersion++
        setPhase(GamePhase.PLANNING)
    }

    fun clearDevices() {
        if (phase != GamePhase.PLANNING) return
        placedDevices.clear()
        planVersion++
    }

    fun placeDevice(x: Float, y: Float): Boolean {
        if (phase != GamePhase.PLANNING) return false
        val type = selectedDevice ?: return false
        if (!inPlacementZone(x, y)) return false
        if (placedDevices.size >= level.maxDevices) return false
        if (placedDevices.any { hypot(it.x - x, it.y - y) < deviceRadius * 1.2f }) return false
        if (type == DeviceType.PORTAL && placedDevices.count { it.type == DeviceType.PORTAL } >= 2) {
            return false
        }
        placedDevices += PlacedDevice(type, x, y)
        planVersion++
        return true
    }

    fun removeDeviceAt(x: Float, y: Float): Boolean {
        if (phase != GamePhase.PLANNING) return false
        val hit = placedDevices.firstOrNull { hypot(it.x - x, it.y - y) <= deviceRadius } ?: return false
        placedDevices.remove(hit)
        planVersion++
        return true
    }

    fun inPlacementZone(x: Float, y: Float): Boolean =
        x in (width * 0.16f)..(width * 0.62f) && y in (height * 0.10f)..(groundY - height * 0.02f)

    fun launch() {
        if (phase != GamePhase.PLANNING || birdsLeft <= 0) return
        birdsLeft--
        placedDevices.forEach { it.used = false }
        val angle = Math.toRadians(level.launchAngleDeg.toDouble())
        val speed = level.launchSpeed * unit * selectedBird.speedFactor
        activeBird = BirdEntity(
            type = selectedBird,
            x = cannonX + height * 0.06f,
            y = cannonY - height * 0.04f,
            vx = (cos(angle) * speed).toFloat(),
            vy = (-sin(angle) * speed).toFloat(),
            radius = selectedBird.radiusFrac * height
        )
        flightTime = 0f
        settleTime = 0f
        recoil = 1f
        shake = max(shake, 0.35f)
        spawnFeathers(cannonX + height * 0.06f, cannonY - height * 0.04f)
        setPhase(GamePhase.FLYING)
        onLaunch?.invoke()
    }

    fun useAbility() {
        val bird = activeBird ?: return
        if (phase != GamePhase.FLYING || bird.abilityUsed) return
        bird.abilityUsed = true
        when (bird.type) {
            BirdType.RED -> {
                bird.vy = abs(bird.vy) + unit * 0.9f
                bird.vx *= 1.1f
                bird.slamming = true
            }

            BirdType.BLUE -> {
                bird.vx *= 1.8f
                bird.vy *= 0.35f
            }

            BirdType.YELLOW -> {
                val speed = hypot(bird.vx, bird.vy)
                if (speed > 1f) {
                    val boost = 1.7f
                    bird.vx *= boost
                    bird.vy *= boost
                }
            }

            BirdType.GREEN -> {
                shockwave(bird.x, bird.y)
            }
        }
        burst(bird.x, bird.y, 0xFFFFF3C4.toInt(), 14, ParticleKind.SPARK)
        onAbility?.invoke()
    }

    private fun shockwave(x: Float, y: Float) {
        val radius = height * 0.22f
        foes.filter { it.alive }.forEach { foe ->
            if (hypot(foe.x - x, foe.y - y) < radius) damageFoe(foe, 1.2f)
        }
        buildings.filter { !it.destroyed }.forEach { b ->
            val cx = b.x + b.width / 2f
            val cy = b.y + b.height / 2f
            if (hypot(cx - x, cy - y) < radius) damageBuilding(b, 1.2f)
        }
        burst(x, y, 0xFF9CFF7A.toInt(), 26, ParticleKind.SPARK)
        shake = max(shake, 0.7f)
    }

    fun update(dt: Float) {
        updateParticles(dt)
        shake = max(0f, shake - dt * 2.2f)
        recoil = max(0f, recoil - dt * 3.5f)
        buildings.forEach {
            it.flash = max(0f, it.flash - dt * 3f)
            it.wobble = max(0f, it.wobble - dt * 2.5f)
        }
        foes.forEach {
            it.flash = max(0f, it.flash - dt * 3f)
            it.squash = max(0f, it.squash - dt * 4f)
        }
        placedDevices.forEach { it.glow = max(0f, it.glow - dt * 2f) }

        if (phase != GamePhase.FLYING) return
        val bird = activeBird ?: return

        flightTime += dt
        val mover = Mover(bird.x, bird.y, bird.vx, bird.vy)
        applyDevices(mover, dt, live = true)
        bird.x = mover.x
        bird.y = mover.y
        bird.vx = mover.vx
        bird.vy = mover.vy

        if (bird.type == BirdType.YELLOW) bird.vx *= 1f + 0.22f * dt
        bird.vy += gravity * dt
        bird.vx *= 1f - 0.04f * dt
        bird.x += bird.vx * dt
        bird.y += bird.vy * dt
        bird.spin += dt * 4f

        bird.trail.addLast(Vec2(bird.x, bird.y))
        if (bird.trail.size > 26) bird.trail.removeFirst()

        resolveCollisions(bird)

        if (bird.y >= groundY - bird.radius) {
            if (bird.vy > unit * 0.22f) {
                burst(bird.x, groundY, 0xFFCBB994.toInt(), 8, ParticleKind.DUST)
                shake = max(shake, 0.2f)
            }
            bird.y = groundY - bird.radius
            bird.vy *= -0.32f
            bird.vx *= 0.62f
            if (abs(bird.vy) < unit * 0.04f) bird.vy = 0f
        }

        if (foes.none { it.alive }) {
            score += 400 + birdsLeft * 150
            finish(GamePhase.VICTORY)
            return
        }

        val gone = bird.x < -bird.radius * 2f || bird.x > width + bird.radius * 2f || bird.y > height * 1.3f
        val resting = bird.y >= groundY - bird.radius - 1f &&
            abs(bird.vx) < unit * 0.03f && abs(bird.vy) < unit * 0.03f
        if (gone || resting || flightTime > 12f) {
            settleTime += dt
            if (settleTime > 0.5f) endFlight()
        } else {
            settleTime = 0f
        }
    }

    private fun applyDevices(m: Mover, dt: Float, live: Boolean, used: BooleanArray? = null) {
        m.portalCooldown = max(0f, m.portalCooldown - dt)
        placedDevices.forEachIndexed { index, device ->
            val consumed = if (used != null) used[index] else device.used
            if (hypot(device.x - m.x, device.y - m.y) > deviceRadius) return@forEachIndexed

            fun consume() {
                if (used != null) used[index] = true else device.used = true
                if (live) {
                    device.glow = 1f
                    onDeviceActivated?.invoke(device.type)
                }
            }

            when (device.type) {
                DeviceType.BOOSTER -> {
                    if (consumed) return@forEachIndexed
                    consume()
                    val speed = hypot(m.vx, m.vy).coerceAtLeast(unit * 0.45f)
                    val dirX = if (m.vx == 0f) 1f else m.vx / abs(m.vx)
                    m.vx = speed * 1.35f * dirX
                    m.vy = min(m.vy, 0f) * 0.7f - unit * 0.10f
                    if (live) burst(device.x, device.y, device.type.tint, 12, ParticleKind.SPARK)
                }

                DeviceType.PIPE -> {
                    if (consumed) return@forEachIndexed
                    consume()
                    val speed = hypot(m.vx, m.vy).coerceAtLeast(unit * 0.62f)
                    m.vx = speed * 0.94f
                    m.vy = -speed * 0.34f
                    if (live) burst(device.x, device.y, device.type.tint, 12, ParticleKind.SPARK)
                }

                DeviceType.REFLECTOR -> {
                    if (consumed) return@forEachIndexed
                    consume()
                    val speed = hypot(m.vx, m.vy).coerceAtLeast(unit * 0.58f)
                    m.vx = speed * 0.72f
                    m.vy = -speed * 0.69f
                    if (live) burst(device.x, device.y, device.type.tint, 12, ParticleKind.SPARK)
                }

                DeviceType.SPRING -> {
                    if (consumed || m.vy <= 0f) return@forEachIndexed
                    consume()
                    m.vy = -abs(m.vy) * 1.05f - unit * 0.34f
                    m.vx *= 1.08f
                    if (live) burst(device.x, device.y, device.type.tint, 12, ParticleKind.SPARK)
                }

                DeviceType.PORTAL -> {
                    if (m.portalCooldown > 0f) return@forEachIndexed
                    val target = placedDevices.firstOrNull {
                        it !== device && it.type == DeviceType.PORTAL
                    } ?: return@forEachIndexed
                    m.x = target.x
                    m.y = target.y
                    m.portalCooldown = 0.6f
                    if (used != null) {
                        used[index] = true
                    } else {
                        device.used = true
                        device.glow = 1f
                        onDeviceActivated?.invoke(device.type)
                    }
                    if (live) burst(target.x, target.y, device.type.tint, 16, ParticleKind.SPARK)
                }

                DeviceType.FAN -> {
                    m.vy -= unit * 1.7f * dt
                    m.vx += unit * 0.18f * dt
                    if (live && device.glow <= 0.2f) {
                        device.glow = 1f
                        onDeviceActivated?.invoke(device.type)
                    }
                }
            }
        }
    }

    /** Dotted preview of where the currently selected bird would travel. */
    fun simulatePath(): List<Vec2> {
        if (phase != GamePhase.PLANNING) return emptyList()
        val angle = Math.toRadians(level.launchAngleDeg.toDouble())
        val speed = level.launchSpeed * unit * selectedBird.speedFactor
        val radius = selectedBird.radiusFrac * height
        val m = Mover(
            cannonX + height * 0.06f,
            cannonY - height * 0.04f,
            (cos(angle) * speed).toFloat(),
            (-sin(angle) * speed).toFloat()
        )
        val used = BooleanArray(placedDevices.size)
        val path = ArrayList<Vec2>(200)
        val dt = 1f / 120f
        var t = 0f
        while (t < 6f) {
            t += dt
            applyDevices(m, dt, live = false, used = used)
            if (selectedBird == BirdType.YELLOW) m.vx *= 1f + 0.22f * dt
            m.vy += gravity * dt
            m.vx *= 1f - 0.04f * dt
            m.x += m.vx * dt
            m.y += m.vy * dt
            path += Vec2(m.x, m.y)
            if (m.y >= groundY - radius) break
            if (m.x < 0f || m.x > width || m.y < -height) break
            if (buildings.any {
                    !it.destroyed && circleHitsRect(m.x, m.y, radius, it.x, it.y, it.width, it.height)
                }
            ) break
            if (foes.any { it.alive && hypot(it.x - m.x, it.y - m.y) < radius + it.size * 0.32f }) break
        }
        return path
    }

    private fun resolveCollisions(bird: BirdEntity) {
        buildings.filter { !it.destroyed }.forEach { b ->
            if (!circleHitsRect(bird.x, bird.y, bird.radius, b.x, b.y, b.width, b.height)) return@forEach
            val speed = hypot(bird.vx, bird.vy)
            val impact = speed / (unit * 0.85f)
            var damage = impact * bird.type.power * b.material.toughness
            if (bird.slamming) damage *= 1.6f
            damageBuilding(b, damage)
            onImpact?.invoke()
            shake = max(shake, min(0.8f, impact * 0.5f))
            burst(bird.x, bird.y, 0xFFE8D5A8.toInt(), 10, ParticleKind.DUST)

            val left = bird.x < b.x
            val right = bird.x > b.x + b.width
            if (left) bird.vx = -abs(bird.vx) * 0.42f
            else if (right) bird.vx = abs(bird.vx) * 0.42f
            else bird.vy = -abs(bird.vy) * 0.42f
            bird.vx *= 0.8f
            bird.vy *= 0.8f
            bird.slamming = false
        }

        foes.filter { it.alive }.forEach { foe ->
            if (hypot(foe.x - bird.x, foe.y - bird.y) > bird.radius + foe.size * 0.32f) return@forEach
            val impact = hypot(bird.vx, bird.vy) / (unit * 0.8f)
            damageFoe(foe, impact * bird.type.power * 1.4f)
            bird.vx *= 0.55f
            bird.vy = -abs(bird.vy) * 0.35f
            shake = max(shake, 0.35f)
        }
    }

    private fun damageBuilding(b: BuildingEntity, amount: Float) {
        if (b.destroyed || amount <= 0f) return
        b.hp -= amount
        b.flash = 1f
        b.wobble = 1f
        if (b.hp > 0f) return
        b.destroyed = true
        score += 120
        onBuildingDestroyed?.invoke()
        shake = max(shake, 0.6f)
        val color = when (b.material) {
            MaterialType.WOOD -> 0xFFB57A44.toInt()
            MaterialType.STONE -> 0xFF9AA3AC.toInt()
            MaterialType.METAL -> 0xFF7F8C99.toInt()
        }
        burst(b.x + b.width / 2f, b.y + b.height / 2f, color, 26, ParticleKind.DEBRIS)
        // Anything standing on the wreck takes the fall.
        foes.filter { it.alive }.forEach { foe ->
            val onTop = foe.x > b.x - foe.size * 0.4f && foe.x < b.x + b.width + foe.size * 0.4f &&
                abs((foe.y + foe.size / 2f) - b.y) < foe.size * 0.9f
            val inside = foe.x > b.x && foe.x < b.x + b.width &&
                foe.y > b.y && foe.y < b.y + b.height
            if (onTop || inside) damageFoe(foe, 1.6f)
        }
    }

    private fun damageFoe(foe: FoeEntity, amount: Float) {
        if (!foe.alive || amount <= 0f) return
        foe.hp -= amount
        foe.flash = 1f
        foe.squash = 1f
        onFoeHit?.invoke()
        if (foe.hp > 0f) return
        foe.alive = false
        score += 250
        burst(foe.x, foe.y, 0xFF7BC950.toInt(), 22, ParticleKind.SPARK)
    }

    private fun endFlight() {
        activeBird = null
        when {
            foes.none { it.alive } -> {
                score += 400 + birdsLeft * 150
                finish(GamePhase.VICTORY)
            }

            birdsLeft <= 0 -> finish(GamePhase.DEFEAT)
            else -> setPhase(GamePhase.PLANNING)
        }
    }

    private fun finish(result: GamePhase) {
        activeBird = null
        setPhase(result)
    }

    private fun setPhase(next: GamePhase) {
        if (phase == next) return
        phase = next
        onPhaseChanged?.invoke(next)
    }

    fun starsEarned(): Int {
        if (phase != GamePhase.VICTORY) return 0
        val ratio = birdsLeft.toFloat() / level.birdBudget.toFloat()
        return when {
            ratio >= 0.5f -> 3
            ratio >= 0.2f -> 2
            else -> 1
        }
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) {
                it.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rotation += p.spin * dt
            p.vy += when (p.kind) {
                ParticleKind.FEATHER -> gravity * 0.08f * dt
                ParticleKind.DUST -> -gravity * 0.05f * dt
                else -> gravity * 0.45f * dt
            }
            p.vx *= 1f - 1.1f * dt
        }
    }

    private fun burst(x: Float, y: Float, color: Int, count: Int, kind: ParticleKind) {
        val base = unit * 0.32f
        repeat(count) {
            val life = 0.35f + Random.nextFloat() * 0.5f
            particles += Particle(
                x = x,
                y = y,
                vx = (Random.nextFloat() - 0.5f) * base,
                vy = (Random.nextFloat() - 0.75f) * base,
                life = life,
                maxLife = life,
                color = color,
                size = height * (0.006f + Random.nextFloat() * 0.012f),
                rotation = Random.nextFloat() * 6.28f,
                spin = (Random.nextFloat() - 0.5f) * 12f,
                kind = kind
            )
        }
    }

    private fun spawnFeathers(x: Float, y: Float) {
        repeat(10) {
            val life = 0.6f + Random.nextFloat() * 0.6f
            particles += Particle(
                x = x,
                y = y,
                vx = Random.nextFloat() * unit * 0.16f,
                vy = (Random.nextFloat() - 0.5f) * unit * 0.2f,
                life = life,
                maxLife = life,
                color = 0xFFFFFFFF.toInt(),
                size = height * 0.012f,
                rotation = Random.nextFloat() * 6.28f,
                spin = (Random.nextFloat() - 0.5f) * 6f,
                kind = ParticleKind.FEATHER
            )
        }
    }

    private fun circleHitsRect(
        cx: Float,
        cy: Float,
        r: Float,
        rx: Float,
        ry: Float,
        rw: Float,
        rh: Float
    ): Boolean {
        val nx = min(max(cx, rx), rx + rw)
        val ny = min(max(cy, ry), ry + rh)
        val dx = cx - nx
        val dy = cy - ny
        return dx * dx + dy * dy < r * r
    }
}




