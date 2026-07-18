package com.jaredxwos.coralie.ui.composable.component.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.jaredxwos.coralie.R

/** A domain row with left-swipe-to-delete. The swipe only *arms* deletion; the
 *  confirmation dialog does the actual removal, so the box snaps back until then. */
@Composable
fun DomainRow(
    domain: String,
    onSwipeDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        initialValue = SwipeToDismissBoxValue.Settled,
    )

    // Swiping past the threshold sets currentValue — treat that as "armed",
    // hand off to the confirm dialog, then reset the row back to Settled so
    // it doesn't stay visually dismissed while the dialog is up.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onSwipeDelete()
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.fillMaxWidth(),
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.medium)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                // Only show the delete icon while actually swiping toward
                // delete — read the direction off dismissState rather than a
                // lambda parameter, since backgroundContent has none.
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .border(2.dp, MaterialTheme.colorScheme.secondary, MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = domain,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}