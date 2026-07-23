package com.jaredxwos.coralie.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jaredxwos.coralie.R
import com.jaredxwos.coralie.data.library.model.PageSummary
import com.jaredxwos.coralie.ui.component.GradientButton
import com.jaredxwos.coralie.ui.component.SectionLabel
import com.jaredxwos.coralie.ui.component.SquareIconButton
import com.jaredxwos.coralie.ui.component.dialogs.AppDialog
import com.jaredxwos.coralie.ui.component.dialogs.ButtonConfig
import com.jaredxwos.coralie.feature.home.component.FileRow
import com.jaredxwos.coralie.feature.home.component.FileRowConfig

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onAddFileClicked: () -> Unit,
    onOpenFileClicked: (Long) -> Unit,
    onEditFileClicked: (Long) -> Unit,
    onSettingsClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by
        viewModel.uiState.collectAsState()

    var pendingDelete by remember {
        mutableStateOf<PageSummary?>(null)
    }

    val lifecycleOwner =
        LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (
                    event ==
                    Lifecycle.Event.ON_RESUME
                ) {
                    viewModel.refresh()
                }
            }

        lifecycleOwner.lifecycle
            .addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle
                .removeObserver(observer)
        }
    }

    val loadFilesError =
        stringResource(
            R.string.error_load_files_failed,
        )
    val deleteFileError =
        stringResource(
            R.string.error_delete_file_failed,
        )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                MaterialTheme
                    .colorScheme
                    .background,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp,
                    vertical = 20.dp,
                ),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.Top,
        ) {
            Column {
                Text(
                    text =
                        stringResource(
                            R.string.app_name,
                        ),
                    style =
                        MaterialTheme
                            .typography
                            .headlineSmall,
                    fontWeight =
                        FontWeight.Bold,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onBackground,
                )

                Spacer(
                    Modifier.height(2.dp),
                )

                Text(
                    text =
                        pluralStringResource(
                            R.plurals
                                .files_saved_count,
                            uiState.pages.size,
                            uiState.pages.size,
                        ),
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,
                    color =
                        MaterialTheme
                            .colorScheme
                            .primary,
                )
            }

            SquareIconButton(
                onClick =
                    onSettingsClicked,
            ) {
                Icon(
                    painter =
                        painterResource(
                            R.drawable
                                .ic_settings,
                        ),
                    contentDescription =
                        stringResource(
                            R.string.cd_settings,
                        ),
                    tint =
                        MaterialTheme
                            .colorScheme
                            .primary,
                )
            }
        }

        HorizontalDivider(
            color =
                MaterialTheme
                    .colorScheme
                    .primary,
            thickness = 2.dp,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp,
                    vertical = 12.dp,
                ),
            horizontalArrangement =
                Arrangement.SpaceBetween,
        ) {
            SectionLabel(
                stringResource(
                    R.string.header_file,
                ),
                modifier =
                    Modifier.padding(
                        start = 16.dp,
                    ),
            )
            SectionLabel(
                stringResource(
                    R.string.header_space,
                ),
                modifier =
                    Modifier.padding(
                        end = 30.dp,
                    ),
            )
        }

        if (uiState.pages.isEmpty()) {
            EmptyFilesState(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding =
                    PaddingValues(
                        horizontal = 20.dp,
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        3.dp,
                    ),
            ) {
                items(
                    items = uiState.pages,
                    key = { page ->
                        page.assetId
                    },
                ) { page ->
                    FileRow(
                        file =
                            page.toRowConfig(),
                        onTap = {
                            onOpenFileClicked(
                                page.assetId,
                            )
                        },
                        onSwipeDelete = {
                            pendingDelete = page
                        },
                        onLongPress = {
                            onEditFileClicked(
                                page.assetId,
                            )
                        },
                    )
                }
            }
        }

        GradientButton(
            onClick = onAddFileClicked,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp,
                    vertical = 16.dp,
                )
                .height(56.dp),
        ) {
            Icon(
                painter =
                    painterResource(
                        R.drawable.ic_add,
                    ),
                contentDescription =
                    stringResource(
                        R.string.cd_add_file,
                    ),
                tint = Color.White,
            )
        }
    }

    pendingDelete?.let { page ->
        AppDialog(
            title =
                stringResource(
                    R.string
                        .delete_file_title,
                ),
            message =
                stringResource(
                    R.string
                        .delete_file_message,
                    page.name,
                ),
            onDismiss = {
                pendingDelete = null
            },
            isWarning = true,
            buttons = listOf(
                ButtonConfig(
                    isWarning = false,
                    text =
                        stringResource(
                            R.string
                                .button_cancel,
                        ),
                    effect = {
                        pendingDelete = null
                    },
                ),
                ButtonConfig(
                    isWarning = true,
                    text =
                        stringResource(
                            R.string
                                .button_delete,
                        ),
                    effect = {
                        pendingDelete = null
                        viewModel.deletePage(
                            page.assetId,
                        )
                    },
                ),
            ),
        )
    }

    uiState.error?.let { error ->
        val fallback =
            when (error.operation) {
                HomeOperation.LOAD ->
                    loadFilesError
                HomeOperation.DELETE ->
                    deleteFileError
            }

        AppDialog(
            title =
                stringResource(
                    R.string
                        .warning_dialog_title,
                ),
            message =
                error.cause.message
                    ?: fallback,
            onDismiss =
                viewModel::consumeError,
            isWarning = true,
        )
    }
}

private fun PageSummary.toRowConfig():
    FileRowConfig =
    FileRowConfig(
        assetId = assetId,
        spaceId = spaceId,
        name = name,
        spaceName =
            spaceName.ifBlank {
                "(unknown space)"
            },
        capabilityMask =
            capabilities.mask,
    )

@Composable
private fun EmptyFilesState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment =
            Alignment.CenterHorizontally,
        verticalArrangement =
            Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .dashedBorder(
                    color =
                        MaterialTheme
                            .colorScheme
                            .secondary,
                    cornerRadius = 20.dp,
                ),
            contentAlignment =
                Alignment.Center,
        ) {
            Icon(
                painter =
                    painterResource(
                        R.drawable
                            .ic_folder_off,
                    ),
                contentDescription = null,
                tint =
                    MaterialTheme
                        .colorScheme
                        .secondary,
                modifier =
                    Modifier.size(36.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text =
                stringResource(
                    R.string
                        .empty_files_title,
                ),
            style =
                MaterialTheme
                    .typography
                    .titleMedium,
            fontWeight =
                FontWeight.SemiBold,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurface,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text =
                stringResource(
                    R.string
                        .empty_files_subtitle,
                ),
            style =
                MaterialTheme
                    .typography
                    .bodyMedium,
            color =
                MaterialTheme
                    .colorScheme
                    .onSurfaceVariant,
        )
    }
}

private fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: Dp,
    strokeWidth: Dp = 2.dp,
    dashLength: Dp = 8.dp,
    gapLength: Dp = 6.dp,
): Modifier =
    drawWithContent {
        drawContent()
        drawRoundRect(
            color = color,
            cornerRadius =
                CornerRadius(
                    cornerRadius.toPx(),
                    cornerRadius.toPx(),
                ),
            style =
                Stroke(
                    width =
                        strokeWidth.toPx(),
                    pathEffect =
                        PathEffect
                            .dashPathEffect(
                                floatArrayOf(
                                    dashLength.toPx(),
                                    gapLength.toPx(),
                                ),
                                0f,
                            ),
                ),
        )
    }
