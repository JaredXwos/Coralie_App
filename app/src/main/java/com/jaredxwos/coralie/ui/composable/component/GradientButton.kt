package com.jaredxwos.coralie.ui.composable.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import com.jaredxwos.coralie.ui.theme.GradientBrushMint
import com.jaredxwos.coralie.ui.theme.GradientBrushPurple

/**
 * §4.4: "the purple→teal pill behind Confirm, the + FAB, and EDIT. One base
 * with a content slot; the three differ only in size/shape/content."
 * Callers control size/shape/padding via [modifier]/[shape]; this only owns
 * the gradient fill and click handling.
 */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(colors = listOf(GradientBrushPurple, GradientBrushMint)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}