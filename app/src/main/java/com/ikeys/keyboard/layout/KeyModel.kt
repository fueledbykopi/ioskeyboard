package com.ikeys.keyboard.layout

/**
 * Pure JVM-friendly rectangle (no android.graphics.RectF dependency in core layout engine)
 * so layout geometry and hit-testing can be rigorously unit tested on host JVM.
 */
data class KeyRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    fun distanceSquaredTo(x: Float, y: Float): Float {
        val dx = when {
            x < left -> left - x
            x > right -> x - right
            else -> 0f
        }
        val dy = when {
            y < top -> top - y
            y > bottom -> y - bottom
            else -> 0f
        }
        return dx * dx + dy * dy
    }
}

enum class KeyType {
    CHARACTER,
    MODIFIER,
    SPACE,
    ACTION
}

enum class EdgeBias {
    NONE,
    LEFT,
    RIGHT
}

enum class KeyboardMode {
    ALPHA,
    SYMBOLS,
    MORE_SYMBOLS,
    EMOJI
}

enum class InputVariant {
    NORMAL,
    EMAIL,
    URI
}

enum class ShiftState {
    OFF,
    SHIFTED,
    CAPS_LOCK;

    val isUpperCase: Boolean get() = this != OFF
}

enum class ReturnAction(val label: String, val isBlueAccent: Boolean) {
    RETURN("return", false),
    SEARCH("search", true),
    SEND("send", true),
    GO("go", true),
    NEXT("next", false),
    DONE("done", true)
}

object KeyCode {
    const val CHAR = 0
    const val SHIFT = -1
    const val BACKSPACE = -2
    const val SYMBOLS = -3
    const val MORE_SYMBOLS = -4
    const val ALPHA = -5
    const val EMOJI = -6
    const val GLOBE = -7
    const val SPACE = -8
    const val RETURN = -9
    const val DISMISS = -10
}

data class KeySpec(
    val id: String,
    val label: String,
    val code: Int = KeyCode.CHAR,
    val type: KeyType = KeyType.CHARACTER,
    val weight: Float = 1f,
    val alternates: List<String> = emptyList(),
    val edgeBias: EdgeBias = EdgeBias.NONE,
    val leadingSpacerWeight: Float = 0f,
    val trailingSpacerWeight: Float = 0f
) {
    val isPopupEligible: Boolean
        get() = type == KeyType.CHARACTER && code == KeyCode.CHAR
}

data class ComputedKey(
    val spec: KeySpec,
    val drawRect: KeyRect,
    val touchRect: KeyRect,
    val rowIndex: Int,
    val colIndex: Int
) {
    fun displayLabel(shiftState: ShiftState, returnAction: ReturnAction): String {
        return when (spec.code) {
            KeyCode.RETURN -> returnAction.label
            KeyCode.SPACE -> "space"
            KeyCode.CHAR -> if (shiftState.isUpperCase && spec.label.length == 1 && spec.label[0].isLetter()) {
                spec.label.uppercase()
            } else {
                spec.label
            }
            else -> spec.label
        }
    }

    fun alternatesFor(shiftState: ShiftState): List<String> {
        if (spec.alternates.isEmpty()) return emptyList()
        return if (shiftState.isUpperCase) {
            spec.alternates.map { alt ->
                if (alt.length == 1 && alt[0].isLetter()) alt.uppercase() else alt
            }
        } else {
            spec.alternates
        }
    }
}
