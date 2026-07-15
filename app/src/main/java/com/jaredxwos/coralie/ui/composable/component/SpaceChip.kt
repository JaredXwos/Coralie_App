package com.jaredxwos.coralie.ui.composable.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** §4.4: "coloured pill (label + colour)." Caller supplies spaceColor(spaceId). */
@Composable
fun SpaceChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}