package com.jaredxwos.coralie.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jaredxwos.coralie.R
import com.jaredxwos.coralie.data.library.model.SpaceUsageSummary
import com.jaredxwos.coralie.ui.component.EmptyHint
import com.jaredxwos.coralie.ui.component.SectionLabel
import com.jaredxwos.coralie.ui.component.SquareIconButton
import com.jaredxwos.coralie.ui.component.dialogs.AppDialog
import com.jaredxwos.coralie.ui.component.dialogs.ButtonConfig
import com.jaredxwos.coralie.feature.settings.component.DomainRow
import com.jaredxwos.coralie.feature.settings.component.SpaceUsage
import com.jaredxwos.coralie.feature.settings.component.SpaceUsageRow

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by
        viewModel.uiState.collectAsState()

    var pendingSpace by remember {
        mutableStateOf<
            SpaceUsageSummary?
        >(null)
    }
    var pendingDomain by remember {
        mutableStateOf<String?>(null)
    }

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
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            SquareIconButton(
                onClick = onBackClicked,
            ) {
                Icon(
                    painter =
                        painterResource(
                            R.drawable
                                .ic_arrow_back,
                        ),
                    contentDescription =
                        stringResource(
                            R.string.cd_back,
                        ),
                    tint =
                        MaterialTheme
                            .colorScheme
                            .primary,
                )
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text =
                    stringResource(
                        R.string
                            .settings_title,
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
        }

        HorizontalDivider(
            color =
                MaterialTheme
                    .colorScheme
                    .primary,
            thickness = 2.dp,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding =
                PaddingValues(
                    horizontal = 20.dp,
                    vertical = 16.dp,
                ),
            verticalArrangement =
                Arrangement.spacedBy(3.dp),
        ) {
            item {
                SectionLabel(
                    stringResource(
                        R.string.header_space,
                    ),
                    modifier =
                        Modifier.padding(
                            bottom = 8.dp,
                        ),
                )
            }

            if (uiState.spaces.isEmpty()) {
                item {
                    EmptyHint(
                        stringResource(
                            R.string
                                .empty_files_title,
                        ),
                    )
                }
            } else {
                items(
                    items = uiState.spaces,
                    key = {
                        "space-${it.spaceId}"
                    },
                ) { space ->
                    SpaceUsageRow(
                        space =
                            space.toRowModel(),
                        onLongPress = {
                            pendingSpace = space
                        },
                    )
                }
            }

            item {
                Spacer(
                    Modifier.height(24.dp),
                )
            }

            item {
                SectionLabel(
                    stringResource(
                        R.string
                            .header_allowed_domains,
                    ),
                    modifier =
                        Modifier.padding(
                            bottom = 8.dp,
                        ),
                )
            }

            if (
                uiState.allowedDomains.isEmpty()
            ) {
                item {
                    EmptyHint(
                        stringResource(
                            R.string
                                .settings_no_domains,
                        ),
                    )
                }
            } else {
                items(
                    items =
                        uiState.allowedDomains,
                    key = {
                        "domain-$it"
                    },
                ) { domain ->
                    DomainRow(
                        domain = domain,
                        onSwipeDelete = {
                            pendingDomain =
                                domain
                        },
                    )
                }
            }
        }
    }

    pendingSpace?.let { space ->
        AppDialog(
            title =
                stringResource(
                    R.string
                        .settings_manage_space_title,
                    space.name,
                ),
            message =
                stringResource(
                    R.string
                        .settings_manage_space_message,
                    pluralStringResource(
                        R.plurals
                            .space_html_count,
                        space.pageCount,
                        space.pageCount,
                    ),
                ),
            onDismiss = {
                pendingSpace = null
            },
            isWarning = false,
            buttons = listOf(
                ButtonConfig(
                    isWarning = false,
                    text =
                        stringResource(
                            R.string
                                .button_clear,
                        ),
                    effect = {
                        pendingSpace = null
                        viewModel.clearSpace(
                            space.spaceId,
                        )
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
                        pendingSpace = null
                        viewModel.deleteSpace(
                            space.spaceId,
                        )
                    },
                ),
            ),
        )
    }

    pendingDomain?.let { domain ->
        AppDialog(
            title =
                stringResource(
                    R.string
                        .delete_domain_title,
                ),
            message =
                stringResource(
                    R.string
                        .delete_domain_message,
                    domain,
                ),
            onDismiss = {
                pendingDomain = null
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
                        pendingDomain = null
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
                        pendingDomain = null
                        viewModel.revokeDomain(
                            domain,
                        )
                    },
                ),
            ),
        )
    }

    uiState.error?.let { error ->
        AppDialog(
            title =
                stringResource(
                    R.string
                        .warning_dialog_title,
                ),
            message =
                error.cause.message
                    ?: settingsFallback(
                        error.operation,
                    ),
            onDismiss =
                viewModel::consumeError,
            isWarning = true,
        )
    }
}

@Composable
private fun settingsFallback(
    operation: SettingsOperation,
): String =
    when (operation) {
        SettingsOperation.LOAD_SPACES ->
            stringResource(
                R.string
                    .error_load_spaces_failed,
            )

        SettingsOperation.LOAD_DOMAINS ->
            stringResource(
                R.string
                    .error_load_domains_failed,
            )

        SettingsOperation.CLEAR_SPACE ->
            stringResource(
                R.string
                    .error_clear_space_failed,
            )

        SettingsOperation.DELETE_SPACE ->
            stringResource(
                R.string
                    .error_delete_space_failed,
            )

        SettingsOperation.REVOKE_DOMAIN ->
            stringResource(
                R.string
                    .error_remove_domain_failed,
            )
    }

private fun SpaceUsageSummary.toRowModel():
    SpaceUsage =
    SpaceUsage(
        spaceId = spaceId,
        name = name,
        htmlCount = pageCount,
    )
