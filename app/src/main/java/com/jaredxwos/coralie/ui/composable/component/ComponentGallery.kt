package com.jaredxwos.coralie.ui.composable.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaredxwos.coralie.R
import com.jaredxwos.coralie.ui.composable.component.dialogs.AppDialog
import com.jaredxwos.coralie.ui.composable.component.dialogs.ButtonConfig
import com.jaredxwos.coralie.ui.composable.component.fileRow.FileRow
import com.jaredxwos.coralie.ui.composable.component.fileRow.FileRowConfig
import com.jaredxwos.coralie.ui.theme.HtmlHosterTheme

/**
 * Not app-facing — a scratch gallery so the Phase 2 leaves can be judged
 * together, in context, against the mocks, rather than one at a time.
 */
@Composable
fun ComponentGallery(modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SquareIconButton(onClick = {}) {
                    Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                SquareIconButton(onClick = {}) {
                    Icon(painter = painterResource(R.drawable.ic_settings), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                GradientButton(
                    onClick = {},
                    modifier = Modifier.height(48.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text("EDIT", modifier = Modifier.padding(horizontal = 20.dp), color = Color.White)
                }
            }

            SectionLabel("Name")
            AppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Type the file name…",
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("HTML file")
            FilePickerField(label = null, onClick = {}, modifier = Modifier.fillMaxWidth())

            SectionLabel("Space")
            listOf(
                FileRowConfig(1L, 2L, "-w-", "Work"),
                FileRowConfig(2L, 1L, "owo", "Personal"),
                FileRowConfig(3L, 3L, "uwu", "uwu stuff"),
            ).forEach { FileRow(
                file = it, onTap = {}, onSwipeDelete = {}, onLongPress = {}
            ) }
        }
    }
}

@Preview(showBackground = true, heightDp = 760)
@Composable
private fun ComponentGalleryPreview() {
    HtmlHosterTheme { ComponentGallery() }
}

@Preview(showBackground = true)
@Composable
private fun MessageDialogPreview() {
    HtmlHosterTheme {
        AppDialog(
            title = "Warning!",
            message = "Something went wrong.",
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConfirmDeleteDialogPreview() {
    HtmlHosterTheme {
        AppDialog(
            title = "Deleting File",
            message = "Are you sure you want to delete \"owo\"?",
            onDismiss = {},
            buttons = listOf(
                ButtonConfig(isWarning = false, text = "No", effect = {}),
                ButtonConfig(isWarning = true, text = "Yes, Delete", effect = {}),
            ),
        )
    }
}