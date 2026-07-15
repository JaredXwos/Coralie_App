package com.jaredxwos.coralie.ui.composable.component.dialogs

/**
 * One action button in AppDialog's button row.
 *
 * [isWarning] picks the button's own visual treatment — danger-red filled
 * ("Yes, Delete") when true, neutral outlined ("No") when false. This is
 * separate from AppDialog's own [isWarning] param, which controls the
 * dialog's icon/title, not any individual button.
 */
data class ButtonConfig(
    val isWarning: Boolean,
    val text: String,
    val effect: () -> Unit,
)
