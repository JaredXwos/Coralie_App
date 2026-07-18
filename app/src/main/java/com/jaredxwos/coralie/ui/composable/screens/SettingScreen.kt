package com.jaredxwos.coralie.ui.composable.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.jaredxwos.coralie.R
import com.jaredxwos.coralie.ui.composable.component.rows.DomainRow
import com.jaredxwos.coralie.ui.composable.component.SectionLabel
import com.jaredxwos.coralie.ui.composable.component.SquareIconButton
import com.jaredxwos.coralie.ui.composable.component.dialogs.AppDialog
import com.jaredxwos.coralie.ui.composable.component.dialogs.ButtonConfig
import com.jaredxwos.coralie.ui.composable.component.rows.spaceUsageRow.SpaceUsage
import com.jaredxwos.coralie.ui.composable.component.rows.spaceUsageRow.SpaceUsageRow
import com.jaredxwos.coralie.viewModel.storage.StorageViewModel




/**
 * §Settings — spaces + allowed-domain management.
 *
 * Reuses [HomeScreen]'s load-on-refreshKey pattern and [AppDialog] for every
 * popup. Spaces are long-pressed to Clear (drop their HTML but keep the space)
 * or Delete (cascading removal of the space + all its HTML). Allowed domains
 * use the same swipe-to-delete + confirm flow as the file list on Home.
 */
@Composable
fun SettingsScreen(
    viewModel: StorageViewModel,
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var spaces by remember { mutableStateOf<List<SpaceUsage>>(emptyList()) }
    var domains by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingSpace by remember { mutableStateOf<SpaceUsage?>(null) }
    var pendingDomain by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val loadSpacesError = stringResource(R.string.error_load_spaces_failed)
    val loadDomainsError = stringResource(R.string.error_load_domains_failed)
    val clearSpaceError = stringResource(R.string.error_clear_space_failed)
    val deleteSpaceError = stringResource(R.string.error_delete_space_failed)
    val removeDomainError = stringResource(R.string.error_remove_domain_failed)

    LaunchedEffect(refreshKey) {
        viewModel.retrieveAllSpaceUsage()
            .onSuccess { spaces = it }
            .onFailure { errorMessage = it.message ?: loadSpacesError }
        viewModel.retrieveAllAllowedDomains()
            .onSuccess { domains = it }
            .onFailure { errorMessage = it.message ?: loadDomainsError }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Back + title.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SquareIconButton(onClick = onBackClicked) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.cd_back),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.primary, thickness = 2.dp)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // SPACES.
            item {
                SectionLabel(
                    stringResource(R.string.header_space),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (spaces.isEmpty()) {
                item { EmptyHint(stringResource(R.string.empty_files_title)) }
            } else {
                items(spaces, key = { "space-${it.spaceId}" }) { space ->
                    SpaceUsageRow(space = space, onLongPress = { pendingSpace = space })
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            // ALLOWED DOMAINS.
            item {
                SectionLabel(
                    stringResource(R.string.header_allowed_domains),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (domains.isEmpty()) {
                item { EmptyHint(stringResource(R.string.settings_no_domains)) }
            } else {
                items(domains, key = { "domain-$it" }) { domain ->
                    DomainRow(domain = domain, onSwipeDelete = { pendingDomain = domain })
                }
            }
        }
    }

    // Long-press a space -> information dialog offering Clear or Delete.
    pendingSpace?.let { space ->
        AppDialog(
            title = stringResource(R.string.settings_manage_space_title),
            message = stringResource(
                R.string.settings_manage_space_message,
                space.name,
                pluralStringResource(R.plurals.space_html_count, space.htmlCount, space.htmlCount),
            ),
            onDismiss = { pendingSpace = null },
            isWarning = false, // information dialog: ic_info + regular-colour title
            buttons = listOf(
                ButtonConfig(
                    isWarning = false,
                    text = stringResource(R.string.button_clear),
                    effect = {
                        scope.launch {
                            viewModel.clearSpace(space.spaceId)
                                .onSuccess {
                                    pendingSpace = null
                                    refreshKey++
                                }
                                .onFailure {
                                    pendingSpace = null
                                    errorMessage = it.message ?: clearSpaceError
                                }
                        }
                    },
                ),
                ButtonConfig(
                    isWarning = true, // delete rendered in warning colours
                    text = stringResource(R.string.button_delete),
                    effect = {
                        scope.launch {
                            viewModel.deleteSpace(space.spaceId)
                                .onSuccess {
                                    pendingSpace = null
                                    refreshKey++
                                }
                                .onFailure {
                                    pendingSpace = null
                                    errorMessage = it.message ?: deleteSpaceError
                                }
                        }
                    },
                ),
            ),
        )
    }

    // Swipe a domain -> same confirm-then-delete flow as the file list.
    pendingDomain?.let { domain ->
        AppDialog(
            title = stringResource(R.string.delete_domain_title),
            message = stringResource(R.string.delete_domain_message, domain),
            onDismiss = { pendingDomain = null },
            isWarning = true,
            buttons = listOf(
                ButtonConfig(
                    isWarning = false,
                    text = stringResource(R.string.button_cancel),
                    effect = { pendingDomain = null },
                ),
                ButtonConfig(
                    isWarning = true,
                    text = stringResource(R.string.button_delete),
                    effect = {
                        scope.launch {
                            viewModel.disallowDomain(domain)
                                .onSuccess {
                                    pendingDomain = null
                                    refreshKey++
                                }
                                .onFailure {
                                    pendingDomain = null
                                    errorMessage = it.message ?: removeDomainError
                                }
                        }
                    },
                ),
            ),
        )
    }

    errorMessage?.let { message ->
        AppDialog(
            title = stringResource(R.string.warning_dialog_title),
            message = message,
            onDismiss = { errorMessage = null },
            isWarning = true,
        )
    }
}

@Composable
fun EmptyHint(x0: String) {
    TODO("Not yet implemented")
}



