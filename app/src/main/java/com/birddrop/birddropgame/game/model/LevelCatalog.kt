package com.birddrop.birddropgame.game.model

/**
 * Positions are fractions: [x] of the field width, [height] of the field height,
 * and [base] is how high above the ground line the object stands.
 */
data class BuildingSpawn(
    val x: Float,
    val height: Float,
    val base: Float,
    val material: MaterialType,
    val drawableName: String,
    val hp: Float
)

data class FoeSpawn(
    val x: Float,
    val base: Float,
    val type: FoeType
)

data class LevelDefinition(
    val id: Int,
    val name: String,
    val chapter: String,
    val background: String,
    val birdBudget: Int,
    val birds: List<BirdType>,
    val devices: List<DeviceType>,
    val maxDevices: Int,
    val buildings: List<BuildingSpawn>,
    val foes: List<FoeSpawn>,
    /** Where the ground line sits inside the background art. */
    val horizon: Float = 0.80f,
    val launchAngleDeg: Float = 48f,
    /** Muzzle speed as a fraction of the field width per second. */
    val launchSpeed: Float = 0.95f
)

object LevelCatalog {

    const val TOTAL_LEVELS = 12

    val levels: List<LevelDefinition> by lazy { build() }

    fun get(id: Int): LevelDefinition = levels.first { it.id == id }

    private fun build(): List<LevelDefinition> = listOf(
        LevelDefinition(
            id = 1,
            name = "First Flight",
            chapter = "Green Valley",
            background = "bg_green_valley",
            birdBudget = 3,
            birds = listOf(BirdType.RED),
            devices = listOf(DeviceType.BOOSTER),
            maxDevices = 2,
            buildings = listOf(
                BuildingSpawn(0.70f, 0.20f, 0f, MaterialType.WOOD, "wood_2", 1.8f)
            ),
            foes = listOf(
                FoeSpawn(0.78f, 0f, FoeType.GRUNT)
            ),
            launchSpeed = 1.05f
        ),
        LevelDefinition(
            id = 2,
            name = "Bounce House",
            chapter = "Green Valley",
            background = "bg_green_valley",
            birdBudget = 3,
            birds = listOf(BirdType.RED),
            devices = listOf(DeviceType.BOOSTER, DeviceType.SPRING),
            maxDevices = 3,
            buildings = listOf(
                BuildingSpawn(0.72f, 0.22f, 0f, MaterialType.WOOD, "wood_1", 2.0f),
                BuildingSpawn(0.88f, 0.18f, 0f, MaterialType.WOOD, "wood_3", 1.7f)
            ),
            foes = listOf(
                FoeSpawn(0.72f, 0.22f, FoeType.GRUNT),
                FoeSpawn(0.88f, 0f, FoeType.BUILDER)
            ),
            launchSpeed = 1.02f
        ),
        LevelDefinition(
            id = 3,
            name = "Watchtower",
            chapter = "Green Valley",
            background = "bg_green_valley",
            birdBudget = 3,
            birds = listOf(BirdType.RED, BirdType.BLUE),
            devices = listOf(DeviceType.BOOSTER, DeviceType.SPRING, DeviceType.REFLECTOR),
            maxDevices = 3,
            buildings = listOf(
                BuildingSpawn(0.70f, 0.30f, 0f, MaterialType.WOOD, "wood_4", 2.4f),
                BuildingSpawn(0.84f, 0.20f, 0f, MaterialType.WOOD, "wood_2", 1.8f)
            ),
            foes = listOf(
                FoeSpawn(0.70f, 0.30f, FoeType.GRUNT),
                FoeSpawn(0.84f, 0.20f, FoeType.GRUNT),
                FoeSpawn(0.92f, 0f, FoeType.BUILDER)
            ),
            launchSpeed = 1.00f
        ),
        LevelDefinition(
            id = 4,
            name = "Foundry Gate",
            chapter = "Gremlin Foundry",
            background = "bg_foundry",
            birdBudget = 4,
            birds = listOf(BirdType.RED, BirdType.BLUE),
            devices = listOf(DeviceType.BOOSTER, DeviceType.PIPE, DeviceType.SPRING),
            maxDevices = 4,
            buildings = listOf(
                BuildingSpawn(0.68f, 0.24f, 0f, MaterialType.STONE, "stone_2", 3.0f),
                BuildingSpawn(0.84f, 0.26f, 0f, MaterialType.WOOD, "wood_3", 2.0f)
            ),
            foes = listOf(
                FoeSpawn(0.68f, 0.24f, FoeType.BUILDER),
                FoeSpawn(0.84f, 0.26f, FoeType.GRUNT),
                FoeSpawn(0.93f, 0f, FoeType.ARMORED)
            ),
            launchSpeed = 0.97f
        ),
        LevelDefinition(
            id = 5,
            name = "Pipe Works",
            chapter = "Gremlin Foundry",
            background = "bg_foundry",
            birdBudget = 4,
            birds = listOf(BirdType.RED, BirdType.BLUE, BirdType.YELLOW),
            devices = listOf(
                DeviceType.BOOSTER, DeviceType.PIPE, DeviceType.REFLECTOR, DeviceType.SPRING
            ),
            maxDevices = 4,
            buildings = listOf(
                BuildingSpawn(0.66f, 0.30f, 0f, MaterialType.STONE, "stone_1", 3.4f),
                BuildingSpawn(0.80f, 0.22f, 0f, MaterialType.STONE, "stone_3", 3.0f),
                BuildingSpawn(0.91f, 0.24f, 0f, MaterialType.WOOD, "wood_1", 2.0f)
            ),
            foes = listOf(
                FoeSpawn(0.66f, 0.30f, FoeType.ARMORED),
                FoeSpawn(0.80f, 0.22f, FoeType.GRUNT),
                FoeSpawn(0.91f, 0.24f, FoeType.BUILDER)
            ),
            launchSpeed = 0.97f
        ),
        LevelDefinition(
            id = 6,
            name = "Assembly Line",
            chapter = "Gremlin Foundry",
            background = "bg_foundry",
            birdBudget = 4,
            birds = listOf(BirdType.RED, BirdType.BLUE, BirdType.YELLOW),
            devices = listOf(
                DeviceType.BOOSTER, DeviceType.PIPE, DeviceType.REFLECTOR,
                DeviceType.SPRING, DeviceType.FAN
            ),
            maxDevices = 5,
            buildings = listOf(
                BuildingSpawn(0.64f, 0.26f, 0f, MaterialType.STONE, "stone_4", 3.6f),
                BuildingSpawn(0.78f, 0.24f, 0f, MaterialType.STONE, "stone_2", 3.0f),
                BuildingSpawn(0.90f, 0.28f, 0f, MaterialType.WOOD, "wood_4", 2.2f)
            ),
            foes = listOf(
                FoeSpawn(0.64f, 0.26f, FoeType.ENGINEER),
                FoeSpawn(0.78f, 0.24f, FoeType.ARMORED),
                FoeSpawn(0.90f, 0.28f, FoeType.GRUNT),
                FoeSpawn(0.96f, 0f, FoeType.GRUNT)
            ),
            launchSpeed = 0.98f
        ),
        LevelDefinition(
            id = 7,
            name = "Cloud Bastion",
            chapter = "Sky Fortress",
            background = "bg_sky_fortress",
            horizon = 0.76f,
            birdBudget = 4,
            birds = listOf(BirdType.RED, BirdType.BLUE, BirdType.YELLOW, BirdType.GREEN),
            devices = listOf(
                DeviceType.BOOSTER, DeviceType.PIPE, DeviceType.REFLECTOR,
                DeviceType.SPRING, DeviceType.PORTAL
            ),
            maxDevices = 5,
            buildings = listOf(
                BuildingSpawn(0.66f, 0.28f, 0f, MaterialType.METAL, "metal_1", 4.0f),
                BuildingSpawn(0.80f, 0.30f, 0f, MaterialType.STONE, "stone_1", 3.4f)
            ),
            foes = listOf(
                FoeSpawn(0.66f, 0.28f, FoeType.ENGINEER),
                FoeSpawn(0.80f, 0.30f, FoeType.ARMORED),
                FoeSpawn(0.90f, 0f, FoeType.BUILDER)
            ),
            launchSpeed = 0.99f
        ),
        LevelDefinition(
            id = 8,
            name = "Sky Gate",
            chapter = "Sky Fortress",
            background = "bg_sky_fortress",
            horizon = 0.76f,
            birdBudget = 5,
            birds = BirdType.entries,
            devices = DeviceType.entries,
            maxDevices = 5,
            buildings = listOf(
                BuildingSpawn(0.62f, 0.30f, 0f, MaterialType.METAL, "metal_2", 4.4f),
                BuildingSpawn(0.78f, 0.24f, 0f, MaterialType.STONE, "stone_4", 3.6f),
                BuildingSpawn(0.90f, 0.26f, 0f, MaterialType.METAL, "metal_3", 4.0f)
            ),
            foes = listOf(
                FoeSpawn(0.62f, 0.30f, FoeType.ARMORED),
                FoeSpawn(0.78f, 0.24f, FoeType.ENGINEER),
                FoeSpawn(0.90f, 0.26f, FoeType.ARMORED)
            ),
            launchSpeed = 0.99f
        ),
        LevelDefinition(
            id = 9,
            name = "High Command",
            chapter = "Sky Fortress",
            background = "bg_sky_fortress",
            horizon = 0.76f,
            birdBudget = 5,
            birds = BirdType.entries,
            devices = DeviceType.entries,
            maxDevices = 6,
            buildings = listOf(
                BuildingSpawn(0.60f, 0.26f, 0f, MaterialType.METAL, "metal_4", 4.6f),
                BuildingSpawn(0.72f, 0.30f, 0f, MaterialType.METAL, "metal_2", 4.4f),
                BuildingSpawn(0.86f, 0.28f, 0f, MaterialType.STONE, "stone_3", 3.2f)
            ),
            foes = listOf(
                FoeSpawn(0.60f, 0.26f, FoeType.ENGINEER),
                FoeSpawn(0.72f, 0.30f, FoeType.ARMORED),
                FoeSpawn(0.86f, 0.28f, FoeType.ARMORED),
                FoeSpawn(0.94f, 0f, FoeType.GRUNT)
            ),
            launchSpeed = 1.00f
        ),
        LevelDefinition(
            id = 10,
            name = "Ash Ramparts",
            chapter = "Volcano Base",
            background = "bg_volcano",
            horizon = 0.76f,
            birdBudget = 5,
            birds = BirdType.entries,
            devices = DeviceType.entries,
            maxDevices = 6,
            buildings = listOf(
                BuildingSpawn(0.62f, 0.30f, 0f, MaterialType.METAL, "metal_1", 4.8f),
                BuildingSpawn(0.76f, 0.26f, 0f, MaterialType.STONE, "stone_2", 3.6f),
                BuildingSpawn(0.90f, 0.30f, 0f, MaterialType.METAL, "metal_4", 5.0f)
            ),
            foes = listOf(
                FoeSpawn(0.62f, 0.30f, FoeType.ARMORED),
                FoeSpawn(0.76f, 0.26f, FoeType.ENGINEER),
                FoeSpawn(0.90f, 0.30f, FoeType.ARMORED)
            ),
            launchSpeed = 1.00f
        ),
        LevelDefinition(
            id = 11,
            name = "Magma Works",
            chapter = "Volcano Base",
            background = "bg_volcano",
            horizon = 0.76f,
            birdBudget = 5,
            birds = BirdType.entries,
            devices = DeviceType.entries,
            maxDevices = 6,
            buildings = listOf(
                BuildingSpawn(0.58f, 0.28f, 0f, MaterialType.METAL, "metal_3", 4.6f),
                BuildingSpawn(0.70f, 0.32f, 0f, MaterialType.METAL, "metal_2", 5.0f),
                BuildingSpawn(0.84f, 0.24f, 0f, MaterialType.STONE, "stone_1", 3.8f),
                BuildingSpawn(0.94f, 0.22f, 0f, MaterialType.WOOD, "wood_2", 2.2f)
            ),
            foes = listOf(
                FoeSpawn(0.58f, 0.28f, FoeType.ENGINEER),
                FoeSpawn(0.70f, 0.32f, FoeType.ARMORED),
                FoeSpawn(0.84f, 0.24f, FoeType.BUILDER),
                FoeSpawn(0.94f, 0.22f, FoeType.GRUNT)
            ),
            launchSpeed = 1.01f
        ),
        LevelDefinition(
            id = 12,
            name = "Iron Crown",
            chapter = "Volcano Base",
            background = "bg_volcano",
            horizon = 0.76f,
            birdBudget = 6,
            birds = BirdType.entries,
            devices = DeviceType.entries,
            maxDevices = 7,
            buildings = listOf(
                BuildingSpawn(0.56f, 0.26f, 0f, MaterialType.METAL, "metal_1", 5.0f),
                BuildingSpawn(0.68f, 0.34f, 0f, MaterialType.METAL, "metal_2", 5.4f),
                BuildingSpawn(0.80f, 0.28f, 0f, MaterialType.METAL, "metal_4", 5.2f),
                BuildingSpawn(0.92f, 0.30f, 0f, MaterialType.STONE, "stone_4", 4.0f)
            ),
            foes = listOf(
                FoeSpawn(0.56f, 0.26f, FoeType.ARMORED),
                FoeSpawn(0.68f, 0.34f, FoeType.ENGINEER),
                FoeSpawn(0.80f, 0.28f, FoeType.ARMORED),
                FoeSpawn(0.92f, 0.30f, FoeType.ENGINEER),
                FoeSpawn(0.98f, 0f, FoeType.GRUNT)
            ),
            launchSpeed = 1.02f
        )
    )
}

