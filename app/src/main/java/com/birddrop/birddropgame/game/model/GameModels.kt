package com.birddrop.birddropgame.game.model

/**
 * Sizes and speeds are stored as fractions of the play field height so the game
 * plays identically on every screen.
 */
enum class BirdType(
    val displayName: String,
    val drawableName: String,
    val power: Float,
    val speedFactor: Float,
    val radiusFrac: Float,
    val ability: String
) {
    RED("Ember", "bird_red", 1.55f, 1.00f, 0.045f, "Tap to slam"),
    BLUE("Swift", "bird_blue", 0.95f, 1.28f, 0.034f, "Tap to dash"),
    YELLOW("Dart", "bird_yellow", 1.15f, 1.42f, 0.036f, "Tap to burst"),
    GREEN("Gale", "bird_green", 1.05f, 1.10f, 0.040f, "Tap for shockwave")
}

enum class DeviceType(
    val displayName: String,
    val drawableName: String,
    val tint: Int
) {
    BOOSTER("Booster", "device_booster", 0xFF57D46A.toInt()),
    PIPE("Air Pipe", "device_pipe", 0xFF4FB3FF.toInt()),
    REFLECTOR("Reflector", "device_reflector", 0xFFB388FF.toInt()),
    SPRING("Spring", "device_spring", 0xFFFF8C2A.toInt()),
    PORTAL("Portal", "device_portal_a", 0xFF7C4DFF.toInt()),
    FAN("Updraft", "device_fan", 0xFF7CE7FF.toInt())
}

enum class MaterialType(val toughness: Float) {
    WOOD(1.0f),
    STONE(0.68f),
    METAL(0.45f)
}

enum class FoeType(
    val displayName: String,
    val drawableName: String,
    val hp: Float,
    val sizeFrac: Float
) {
    GRUNT("Grunt", "foe_grunt", 1.0f, 0.11f),
    BUILDER("Tinker", "foe_builder", 1.4f, 0.11f),
    ARMORED("Bruiser", "foe_armored", 2.2f, 0.12f),
    ENGINEER("Engineer", "foe_engineer", 1.8f, 0.12f)
}

enum class GamePhase { PLANNING, FLYING, VICTORY, DEFEAT }
