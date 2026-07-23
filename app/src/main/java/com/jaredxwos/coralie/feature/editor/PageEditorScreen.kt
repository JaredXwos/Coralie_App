package com.jaredxwos.coralie.feature.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jaredxwos.coralie.R
import com.jaredxwos.coralie.data.library.model.PageCapability
import com.jaredxwos.coralie.data.library.model.SpaceSummary
import com.jaredxwos.coralie.ui.component.AppTextField
import com.jaredxwos.coralie.ui.component.FilePickerField
import com.jaredxwos.coralie.ui.component.GradientButton
import com.jaredxwos.coralie.ui.component.SectionLabel
import com.jaredxwos.coralie.ui.component.SquareIconButton
import com.jaredxwos.coralie.ui.component.dialogs.AppDialog
import com.jaredxwos.coralie.ui.component.dialogs.ButtonConfig
import com.jaredxwos.coralie.feature.editor.component.SpaceRow
import com.jaredxwos.coralie.feature.editor.component.SpaceRowConfig

@Composable
fun PageEditorScreen(
    viewModel: PageEditorViewModel,
    onBack: () -> Unit,
    onFileAdded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by
        viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.completed) {
        if (uiState.completed) {
            viewModel.consumeCompletion()
            onFileAdded()
        }
    }

    BackHandler(onBack = onBack)

    val filePicker =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .OpenDocument(),
        ) { uri: Uri? ->
            uri?.let(
                viewModel::setSourceUri,
            )
        }

    val focusManager =
        LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                MaterialTheme
                    .colorScheme
                    .background,
            )
            .verticalScroll(
                rememberScrollState(),
            )
            .padding(20.dp),
        verticalArrangement =
            Arrangement.spacedBy(20.dp),
    ) {
        Row(
            verticalAlignment =
                Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.spacedBy(16.dp),
        ) {
            SquareIconButton(
                onClick = onBack,
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

            Text(
                text =
                    stringResource(
                        if (uiState.isEditing) {
                            R.string
                                .edit_file_title
                        } else {
                            R.string
                                .add_file_title
                        },
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
                        .primary,
            )
        }

        Column(
            verticalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(
                stringResource(
                    R.string.label_name,
                ),
            )

            AppTextField(
                value = uiState.pageName,
                onValueChange =
                    viewModel::setPageName,
                placeholder =
                    stringResource(
                        R.string
                            .placeholder_file_name,
                    ),
                modifier =
                    Modifier.fillMaxWidth(),
            )
        }

        Column(
            verticalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(
                stringResource(
                    R.string.label_html_file,
                ),
            )

            FilePickerField(
                label =
                    uiState.selectedUri
                        ?.lastPathSegment,
                onClick = {
                    filePicker.launch(
                        arrayOf("text/html"),
                    )
                },
                modifier =
                    Modifier.fillMaxWidth(),
            )
        }

        Column(
            verticalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(
                stringResource(
                    R.string.label_space,
                ),
            )

            val newSpaceText =
                (
                    uiState.spaceSelection as?
                        EditorSpaceSelection.New
                    )
                    ?.name
                    .orEmpty()

            AppTextField(
                value = newSpaceText,
                onValueChange =
                    viewModel::setNewSpaceName,
                placeholder =
                    stringResource(
                        R.string
                            .placeholder_new_space,
                    ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (
                            focusState.isFocused &&
                            uiState.spaceSelection
                            is EditorSpaceSelection
                                .Existing
                        ) {
                            viewModel
                                .startNewSpaceSelection()
                        }
                    },
            )

            Column(
                verticalArrangement =
                    Arrangement.spacedBy(12.dp),
            ) {
                uiState.spaces.forEach { space ->
                    SpaceRow(
                        space =
                            space.toRowConfig(),
                        isSelected =
                            (
                                uiState
                                    .spaceSelection
                                as?
                                    EditorSpaceSelection
                                        .Existing
                                )
                                ?.spaceId ==
                                space.spaceId,
                        onTap = {
                            focusManager
                                .clearFocus()
                            viewModel.selectSpace(
                                space.spaceId,
                            )
                        },
                    )
                }
            }
        }

        Column(
            verticalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(
                stringResource(
                    R.string
                        .label_capabilities,
                ),
            )

            Text(
                text =
                    stringResource(
                        R.string
                            .capabilities_help,
                    ),
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,
            )

            PageCapability.entries.forEach { capability ->
                CapabilityToggleRow(
                    capability =
                        capability,
                    enabled =
                        uiState.capabilities
                            .allows(capability),
                    onEnabledChange = { enabled ->
                        viewModel
                            .setCapability(
                                capability =
                                    capability,
                                enabled =
                                    enabled,
                            )
                    },
                )
            }
        }

        GradientButton(
            onClick =
                viewModel::submit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                text =
                    stringResource(
                        R.string.button_confirm,
                    ),
                color = Color.White,
                style =
                    MaterialTheme
                        .typography
                        .titleMedium,
                fontWeight =
                    FontWeight.Bold,
            )
        }

        editorErrorMessage(
            uiState = uiState,
        )?.let { message ->
            AppDialog(
                title =
                    stringResource(
                        R.string
                            .error_dialog_title,
                    ),
                message = message,
                onDismiss =
                    viewModel::consumeError,
                isWarning = true,
                buttons = listOf(
                    ButtonConfig(
                        text =
                            stringResource(
                                R.string
                                    .button_ok,
                            ),
                        isWarning = true,
                        effect =
                            viewModel::
                                consumeError,
                    ),
                ),
            )
        }
    }
}

@Composable
private fun editorErrorMessage(
    uiState: PageEditorUiState,
): String? =
    when (uiState.validationError) {
        PageEditorValidationError
            .MISSING_NAME ->
            stringResource(
                R.string
                    .error_missing_file_name,
            )

        PageEditorValidationError
            .MISSING_FILE ->
            stringResource(
                R.string.error_missing_file,
            )

        PageEditorValidationError
            .MISSING_SPACE ->
            stringResource(
                R.string.error_missing_space,
            )

        null ->
            uiState.operationError
                ?.let { error ->
                    error.cause.message
                        ?: stringResource(
                            when (error.operation) {
                                PageEditorOperation.LOAD ->
                                    R.string
                                        .error_load_spaces_failed
                                PageEditorOperation.SAVE ->
                                    R.string
                                        .error_save_file_failed
                            },
                        )
                }
    }

private fun SpaceSummary.toRowConfig():
    SpaceRowConfig =
    SpaceRowConfig(
        spaceId = spaceId,
        name = name,
    )

@Composable
private fun CapabilityToggleRow(
    capability: PageCapability,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val title =
        when (capability) {
            PageCapability.MESH ->
                stringResource(
                    R.string
                        .capability_mesh_title,
                )

            PageCapability.STORAGE ->
                stringResource(
                    R.string
                        .capability_storage_title,
                )

            PageCapability.HTTP ->
                stringResource(
                    R.string
                        .capability_http_title,
                )

            PageCapability.TIMERS ->
                stringResource(
                    R.string
                        .capability_timers_title,
                )
        }

    val description =
        when (capability) {
            PageCapability.MESH ->
                stringResource(
                    R.string
                        .capability_mesh_description,
                )

            PageCapability.STORAGE ->
                stringResource(
                    R.string
                        .capability_storage_description,
                )

            PageCapability.HTTP ->
                stringResource(
                    R.string
                        .capability_http_description,
                )

            PageCapability.TIMERS ->
                stringResource(
                    R.string
                        .capability_timers_description,
                )
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = enabled,
                role = Role.Checkbox,
                onValueChange =
                    onEnabledChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment =
            Alignment.Top,
        horizontalArrangement =
            Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(
            checked = enabled,
            onCheckedChange = null,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement =
                Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style =
                    MaterialTheme
                        .typography
                        .bodyLarge,
                fontWeight =
                    FontWeight.SemiBold,
            )

            Text(
                text = description,
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant,
            )
        }
    }
}
