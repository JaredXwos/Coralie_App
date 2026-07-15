package com.jaredxwos.coralie.ui.theme

import androidx.compose.ui.graphics.Color

// §4.5: "the single source for chip colour." Personal-style spaces land on
// the vivid purple (same value as the gradient's purple end); Work-style
// spaces land on ButtonShadeTeal specifically — the softer teal, not the
// vivid gradient teal, which reads too bright as a solid chip fill.
private val SpacePalette = listOf(VividButtonShadePurple, VividButtonShadePink, VividButtonShadeTeal)

fun spaceColor(spaceId: Long): Color {
    val index = (spaceId % SpacePalette.size).let { if (it < 0) it + SpacePalette.size else it }
    return SpacePalette[index.toInt()]
}