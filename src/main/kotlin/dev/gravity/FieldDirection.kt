package dev.gravity

import org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_KEY_UP

/** Direction of a uniform gravity field toggled by an arrow key; +y is up. */
enum class FieldDirection(val label: String, val x: Int, val y: Int, private val key: Int) {
    UP("up", 0, 1, GLFW_KEY_UP),
    DOWN("down", 0, -1, GLFW_KEY_DOWN),
    LEFT("left", -1, 0, GLFW_KEY_LEFT),
    RIGHT("right", 1, 0, GLFW_KEY_RIGHT),
    ;

    companion object {
        fun forKey(key: Int): FieldDirection? = entries.firstOrNull { it.key == key }
    }
}
