package com.jaredxwos.coralie.ui.composable.component.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.jaredxwos.coralie.R

/**
 * §4.4 — the one dialog composable. Fully self-contained: owns title,
 * message, icon, and an optional row of action buttons built from
 * [buttons]. Replaces the old shell+content-slot design — every popup in
 * the app is a direct call to this, not a separate wrapper composable.
 *
 * - [buttons] empty  -> message-only, dismissed via the X (e.g. "Warning!").
 * - [buttons] 1+      -> confirm-style, X still dismisses too (e.g. "Deleting File").
 * - [isWarning] (dialog-level) picks ic_warning + red bold title vs
 *   ic_info + regular-colour title. Independent of each button's own
 *   [ButtonConfig.isWarning], which only affects that button's fill/border.
 */
@Composable
fun AppDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isWarning: Boolean = true,
    buttons: List<ButtonConfig> = emptyList(),
) {
    Dialog(onDismissRequest = onDismiss) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setDimAmount(0f)
        }
        val titleColor = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        Column(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(if (isWarning) R.drawable.ic_warning else R.drawable.ic_info),
                        contentDescription = null,
                        tint = titleColor,
                    )
                    Text(
                        text = title,
                        color = titleColor,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isWarning) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.extraSmall)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.primary, thickness = 2.dp)
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = message, style = MaterialTheme.typography.bodyLarge)
                if (buttons.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        buttons.forEach { button -> DialogButton(button, modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(config: ButtonConfig, modifier: Modifier = Modifier) {
    if (config.isWarning) {
        Button(
            onClick = config.effect,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.error),
        ) {
            Text(config.text)
        }
    } else {
        OutlinedButton(
            onClick = config.effect,
            modifier = modifier,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(config.text)
        }
    }
}