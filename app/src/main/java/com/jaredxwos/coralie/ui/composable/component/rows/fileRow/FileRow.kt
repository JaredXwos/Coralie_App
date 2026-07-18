package com.jaredxwos.coralie.ui.composable.component.rows.fileRow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaredxwos.coralie.R
import com.jaredxwos.coralie.ui.composable.component.SpaceChip
import com.jaredxwos.coralie.ui.theme.HtmlHosterTheme
import com.jaredxwos.coralie.ui.theme.spaceColor
import kotlinx.coroutines.launch

/**
 * §4.4, redesigned — no Browse/Edit mode. The row is always tappable (opens
 * the viewer); swiping either direction past the threshold fires
 * [onSwipeDelete] (the caller shows the ConfirmDelete dialog from there) and
 * then snaps back — the swipe itself never removes the row. Deletion only
 * actually happens if the user confirms in that dialog.
 */
@Composable
fun FileRow(
    file: FileRowConfig,
    onTap: () -> Unit,
    onSwipeDelete: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val dismissState = rememberSwipeToDismissBoxState()

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = { SwipeDeleteBackground(dismissState.dismissDirection) },
        onDismiss = {
            onSwipeDelete()
            scope.launch { dismissState.reset() }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                .border(2.dp, MaterialTheme.colorScheme.secondary, MaterialTheme.shapes.small)
                .combinedClickable(          // ← was .clickable(onClick = onTap)
                    onClick = onTap,
                    onLongClick = onLongPress,
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SpaceChip(file.spaceName, spaceColor(file.spaceId))
        }
    }
}

@Composable
private fun SwipeDeleteBackground(direction: SwipeToDismissBoxValue) {
    val alignment = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        SwipeToDismissBoxValue.Settled -> Alignment.Center
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_delete),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FileRowPreview() {
    val rows = listOf(
        FileRowConfig(1L, 2L, "-w-", "Work"),
        FileRowConfig(2L, 1L, "owo", "Personal"),
        FileRowConfig(3L, 3L, "uwu", "uwu stuff"),
    )
    HtmlHosterTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rows.forEach { FileRow(file = it, onTap = {}, onSwipeDelete = {}, onLongPress = {}) }
        }
    }
}