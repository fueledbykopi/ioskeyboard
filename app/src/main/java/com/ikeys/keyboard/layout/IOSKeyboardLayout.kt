package com.ikeys.keyboard.layout

/**
 * Authentic latest iOS QWERTY, Symbols (123), and Extended Symbols (#+=) layout definitions
 * plus zero-dead-zone spatial geometry computation tuned for effortless two-thumb typing.
 */
object IOSKeyboardLayout {

    private val diacritics: Map<String, List<String>> = mapOf(
        "a" to listOf("a", "à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "e" to listOf("e", "è", "é", "ê", "ë", "ē", "ė", "ę"),
        "i" to listOf("i", "ì", "į", "ī", "í", "ï", "î"),
        "o" to listOf("o", "õ", "ō", "ø", "œ", "ó", "ò", "ö", "ô"),
        "u" to listOf("u", "ū", "ú", "ù", "ü", "û"),
        "c" to listOf("c", "ç", "ć", "č"),
        "n" to listOf("n", "ñ", "ń"),
        "s" to listOf("s", "ß", "ś", "š"),
        "y" to listOf("y", "ÿ"),
        "z" to listOf("z", "ž", "ź", "ż"),
        "l" to listOf("l", "ł"),
        "0" to listOf("0", "°"),
        "-" to listOf("-", "–", "—", "•"),
        "/" to listOf("/", "\\"),
        "$" to listOf("$", "Rp", "€", "£", "¥", "₩", "¢"),
        "&" to listOf("&", "§"),
        "\"" to listOf("\"", "“", "”", "«", "»", "„"),
        "." to listOf(".", "…", ",", "?", "!"),
        "?" to listOf("?", "¿"),
        "!" to listOf("!", "¡"),
        "'" to listOf("'", "‘", "’", "`"),
        "%" to listOf("%", "‰"),
        "=" to listOf("=", "≈", "≠")
    )

    private fun charKey(
        ch: String,
        weight: Float = 1f,
        edgeBias: EdgeBias = EdgeBias.NONE,
        leadingSpacer: Float = 0f,
        trailingSpacer: Float = 0f
    ): KeySpec = KeySpec(
        id = "char_$ch",
        label = ch,
        code = KeyCode.CHAR,
        type = KeyType.CHARACTER,
        weight = weight,
        alternates = diacritics[ch].orEmpty(),
        edgeBias = edgeBias,
        leadingSpacerWeight = leadingSpacer,
        trailingSpacerWeight = trailingSpacer
    )

    fun rowsFor(mode: KeyboardMode, variant: InputVariant = InputVariant.NORMAL): List<List<KeySpec>> {
        return when (mode) {
            KeyboardMode.ALPHA -> buildAlphaRows(variant)
            KeyboardMode.SYMBOLS -> buildSymbolRows()
            KeyboardMode.MORE_SYMBOLS -> buildMoreSymbolRows()
            KeyboardMode.EMOJI -> emptyList()
        }
    }

    private fun buildAlphaRows(variant: InputVariant): List<List<KeySpec>> {
        val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").mapIndexed { idx, ch ->
            charKey(
                ch = ch,
                edgeBias = when (idx) {
                    0 -> EdgeBias.LEFT
                    9 -> EdgeBias.RIGHT
                    else -> EdgeBias.NONE
                }
            )
        }

        // Exact iOS Row 2: 9 keys centered with 0.5-key horizontal inset on both sides
        val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").mapIndexed { idx, ch ->
            charKey(
                ch = ch,
                edgeBias = when (idx) {
                    0 -> EdgeBias.LEFT
                    8 -> EdgeBias.RIGHT
                    else -> EdgeBias.NONE
                },
                leadingSpacer = if (idx == 0) 0.5f else 0f,
                trailingSpacer = if (idx == 8) 0.5f else 0f
            )
        }

        // Exact iOS Row 3: Shift (1.28) + gap (0.22) + 7 keys + gap (0.22) + Backspace (1.28) = 10.0 units
        val row3 = buildList {
            add(
                KeySpec(
                    id = "shift",
                    label = "",
                    code = KeyCode.SHIFT,
                    type = KeyType.MODIFIER,
                    weight = 1.28f,
                    trailingSpacerWeight = 0.22f
                )
            )
            listOf("z", "x", "c", "v", "b", "n", "m").forEach { ch ->
                add(charKey(ch))
            }
            add(
                KeySpec(
                    id = "backspace",
                    label = "",
                    code = KeyCode.BACKSPACE,
                    type = KeyType.MODIFIER,
                    weight = 1.28f,
                    leadingSpacerWeight = 0.22f
                )
            )
        }

        val row4 = buildBottomRow(leftSwitchCode = KeyCode.SYMBOLS, leftSwitchLabel = "123", variant = variant)
        return listOf(row1, row2, row3, row4)
    }

    private fun buildSymbolRows(): List<List<KeySpec>> {
        val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").mapIndexed { idx, ch ->
            charKey(
                ch = ch,
                edgeBias = when (idx) {
                    0 -> EdgeBias.LEFT
                    9 -> EdgeBias.RIGHT
                    else -> EdgeBias.NONE
                }
            )
        }

        val row2 = listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"").mapIndexed { idx, ch ->
            charKey(
                ch = ch,
                edgeBias = when (idx) {
                    0 -> EdgeBias.LEFT
                    9 -> EdgeBias.RIGHT
                    else -> EdgeBias.NONE
                }
            )
        }

        val row3 = buildList {
            add(
                KeySpec(
                    id = "more_symbols",
                    label = "#+=",
                    code = KeyCode.MORE_SYMBOLS,
                    type = KeyType.MODIFIER,
                    weight = 1.28f,
                    trailingSpacerWeight = 0.22f
                )
            )
            listOf(".", ",", "?", "!", "'").forEach { ch ->
                add(charKey(ch, weight = 1.4f))
            }
            add(
                KeySpec(
                    id = "backspace",
                    label = "",
                    code = KeyCode.BACKSPACE,
                    type = KeyType.MODIFIER,
                    weight = 1.28f,
                    leadingSpacerWeight = 0.22f
                )
            )
        }

        val row4 = buildBottomRow(leftSwitchCode = KeyCode.ALPHA, leftSwitchLabel = "ABC", variant = InputVariant.NORMAL)
        return listOf(row1, row2, row3, row4)
    }

    private fun buildMoreSymbolRows(): List<List<KeySpec>> {
        val row1 = listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").mapIndexed { idx, ch ->
            charKey(
                ch = ch,
                edgeBias = when (idx) {
                    0 -> EdgeBias.LEFT
                    9 -> EdgeBias.RIGHT
                    else -> EdgeBias.NONE
                }
            )
        }

        val row2 = listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•").mapIndexed { idx, ch ->
            charKey(
                ch = ch,
                edgeBias = when (idx) {
                    0 -> EdgeBias.LEFT
                    9 -> EdgeBias.RIGHT
                    else -> EdgeBias.NONE
                }
            )
        }

        val row3 = buildList {
            add(
                KeySpec(
                    id = "symbols_back",
                    label = "123",
                    code = KeyCode.SYMBOLS,
                    type = KeyType.MODIFIER,
                    weight = 1.28f,
                    trailingSpacerWeight = 0.22f
                )
            )
            listOf(".", ",", "?", "!", "'").forEach { ch ->
                add(charKey(ch, weight = 1.4f))
            }
            add(
                KeySpec(
                    id = "backspace",
                    label = "",
                    code = KeyCode.BACKSPACE,
                    type = KeyType.MODIFIER,
                    weight = 1.28f,
                    leadingSpacerWeight = 0.22f
                )
            )
        }

        val row4 = buildBottomRow(leftSwitchCode = KeyCode.ALPHA, leftSwitchLabel = "ABC", variant = InputVariant.NORMAL)
        return listOf(row1, row2, row3, row4)
    }

    private fun buildBottomRow(
        leftSwitchCode: Int,
        leftSwitchLabel: String,
        variant: InputVariant
    ): List<KeySpec> {
        return when (variant) {
            InputVariant.EMAIL -> listOf(
                KeySpec(id = "mode_$leftSwitchLabel", label = leftSwitchLabel, code = leftSwitchCode, type = KeyType.MODIFIER, weight = 1.28f),
                KeySpec(id = "emoji", label = "", code = KeyCode.EMOJI, type = KeyType.MODIFIER, weight = 1.12f),
                KeySpec(id = "space", label = "space", code = KeyCode.SPACE, type = KeyType.SPACE, weight = 3.20f),
                charKey("@", weight = 1.0f),
                charKey(".", weight = 1.0f),
                KeySpec(id = "return", label = "return", code = KeyCode.RETURN, type = KeyType.ACTION, weight = 2.40f)
            )
            InputVariant.URI -> listOf(
                KeySpec(id = "mode_$leftSwitchLabel", label = leftSwitchLabel, code = leftSwitchCode, type = KeyType.MODIFIER, weight = 1.28f),
                KeySpec(id = "emoji", label = "", code = KeyCode.EMOJI, type = KeyType.MODIFIER, weight = 1.12f),
                KeySpec(id = "space", label = "space", code = KeyCode.SPACE, type = KeyType.SPACE, weight = 3.20f),
                charKey(".", weight = 1.0f),
                charKey("/", weight = 1.0f),
                KeySpec(id = "return", label = "return", code = KeyCode.RETURN, type = KeyType.ACTION, weight = 2.40f)
            )
            InputVariant.NORMAL -> listOf(
                // Exact symmetrical iOS proportions: 1.32 + 1.18 (=2.50) | Space (5.00) | Return (2.50)
                KeySpec(id = "mode_$leftSwitchLabel", label = leftSwitchLabel, code = leftSwitchCode, type = KeyType.MODIFIER, weight = 1.32f),
                KeySpec(id = "emoji", label = "", code = KeyCode.EMOJI, type = KeyType.MODIFIER, weight = 1.18f),
                KeySpec(id = "space", label = "space", code = KeyCode.SPACE, type = KeyType.SPACE, weight = 5.00f),
                KeySpec(id = "return", label = "return", code = KeyCode.RETURN, type = KeyType.ACTION, weight = 2.50f)
            )
        }
    }
}

/**
 * Computes exact pixel geometry and expanded touch hit-boxes for every key in the keyboard grid.
 * Pure Kotlin math — zero Android framework allocations.
 */
class KeyboardLayoutEngine {

    data class Metrics(
        val width: Float,
        val topOffset: Float,
        val rowHeight: Float,
        val horizontalPadding: Float,
        val keyHorizontalGap: Float,
        val rowVerticalGap: Float
    )

    fun computeKeys(rows: List<List<KeySpec>>, metrics: Metrics): List<ComputedKey> {
        if (rows.isEmpty() || metrics.width <= 0f || metrics.rowHeight <= 0f) return emptyList()

        val result = ArrayList<ComputedKey>(36)
        val usableWidth = (metrics.width - metrics.horizontalPadding * 2f).coerceAtLeast(1f)
        val halfGapX = metrics.keyHorizontalGap * 0.5f
        val halfGapY = metrics.rowVerticalGap * 0.5f
        // Slight protective bias so bottom-row letters (Z..M) win over Spacebar at the seam
        val spaceSeamBias = (metrics.rowVerticalGap * 0.14f).coerceAtMost(metrics.rowHeight * 0.08f)

        rows.forEachIndexed { rowIndex, row ->
            if (row.isEmpty()) return@forEachIndexed

            val totalUnits = row.sumOf {
                (it.leadingSpacerWeight + it.weight + it.trailingSpacerWeight).toDouble()
            }.toFloat().coerceAtLeast(1f)

            val rowTop = metrics.topOffset + rowIndex * (metrics.rowHeight + metrics.rowVerticalGap)
            val rowBottom = rowTop + metrics.rowHeight

            // Compute draw rects first
            val drawRects = ArrayList<KeyRect>(row.size)
            var cursorUnit = 0f

            row.forEach { spec ->
                cursorUnit += spec.leadingSpacerWeight
                val startFrac = cursorUnit / totalUnits
                cursorUnit += spec.weight
                val endFrac = cursorUnit / totalUnits
                cursorUnit += spec.trailingSpacerWeight

                val rawLeft = metrics.horizontalPadding + startFrac * usableWidth
                val rawRight = metrics.horizontalPadding + endFrac * usableWidth

                val left = rawLeft + halfGapX
                val right = rawRight - halfGapX
                drawRects.add(KeyRect(left, rowTop, right.coerceAtLeast(left + 1f), rowBottom))
            }

            // Compute expanded gap-free touch rects (midpoints between adjacent keys, full edge bleed)
            val touchTop = when (rowIndex) {
                0 -> 0f
                rows.lastIndex -> rowTop - halfGapY + spaceSeamBias
                else -> rowTop - halfGapY
            }
            val touchBottom = when (rowIndex) {
                rows.lastIndex - 1 -> rowBottom + halfGapY + spaceSeamBias
                rows.lastIndex -> rowBottom + halfGapY
                else -> rowBottom + halfGapY
            }

            row.forEachIndexed { colIndex, spec ->
                val draw = drawRects[colIndex]
                val touchLeft = if (colIndex == 0) {
                    0f
                } else {
                    (drawRects[colIndex - 1].right + draw.left) * 0.5f
                }
                val touchRight = if (colIndex == row.lastIndex) {
                    metrics.width
                } else {
                    (draw.right + drawRects[colIndex + 1].left) * 0.5f
                }

                result.add(
                    ComputedKey(
                        spec = spec,
                        drawRect = draw,
                        touchRect = KeyRect(touchLeft, touchTop, touchRight, touchBottom),
                        rowIndex = rowIndex,
                        colIndex = colIndex
                    )
                )
            }
        }

        return result
    }

    /**
     * Resolves a touch coordinate (x, y) to a ComputedKey with zero dead zones.
     * Direct hit in expanded touchRect wins immediately; otherwise nearest drawRect wins.
     */
    fun findKeyAt(keys: List<ComputedKey>, x: Float, y: Float): ComputedKey? {
        if (keys.isEmpty()) return null

        for (i in keys.indices) {
            val key = keys[i]
            if (key.touchRect.contains(x, y)) {
                return key
            }
        }

        // Fallback to closest key by Euclidean distance squared
        var bestKey: ComputedKey? = null
        var bestDistSq = Float.MAX_VALUE
        for (i in keys.indices) {
            val key = keys[i]
            val dSq = key.drawRect.distanceSquaredTo(x, y)
            if (dSq < bestDistSq) {
                bestDistSq = dSq
                bestKey = key
            }
        }
        return bestKey
    }
}
