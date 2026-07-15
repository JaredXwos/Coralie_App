package com.jaredxwos.coralie.ui.theme

import androidx.compose.ui.graphics.Color

// Palette (§4.5 of the architecture guide): mint background, purple primary,
// teal + pink accents. These are the only "designed" values — everything
// else in ColorScheme.kt is a derived on/container tone.
val InkOnMint = Color(0xFF1B2A22)

val DarkTextPurple = Color(0xFF531C7F)
val DarkTextTeal = Color(0xFF42958B)
val DarkTextPink = Color(0xFFC9418B)

val ContainerBorderPurple = Color(0xFF8728E8)
val ContainerBorderTeal = Color(0xFF258479)
val ContainerBorderPink = Color(0xFFDD2D8D)

val VividButtonShadePurple = Color(0xFF892AEA)
val VividButtonShadeTeal = Color(0xFF17D4BD)
val VividButtonShadePink = Color(0xFFEB85BD)
val ButtonShadeTeal = Color(0xFF93CEC7)
val BackgroundAqua = Color(0xFFD4F8F3)
val LightTextAqua = Color(0xFF96CFC8)
val GradientBrushPurple = Color(0xFFC07BFD)
val GradientBrushMint = Color(0xFF40ECD7)

// Distinct from Pink above — this is the coral/danger red seen on the
// Warning + Deleting File dialogs, "Yes, Delete", and the edit-mode ✕.
// Estimated by eye from the mocks, not pixel-sampled — nudge if it's off.
val Danger = Color(0xFFE5484D)
val DangerContainer = Color(0xFFFFDAD6)
val OnDangerContainer = Color(0xFF410E0B)