package com.jaredxwos.coralie.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jaredxwos.coralie.ui.theme.DarkTextTeal

/**
 * §4.4: "small-caps labels (NAME, HTML FILE, SPACE, FILE/SPACE headers)."
 * Reads DarkTextTeal directly, not colorScheme.secondary — secondary is now
 * the *border* teal (ContainerBorderTeal), a different, more saturated shade
 * than what these labels actually use in the mocks.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = DarkTextTeal,
        style = MaterialTheme.typography.labelLarge,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.SemiBold,
    )
}