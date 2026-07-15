package com.jaredxwos.coralie.ui.composable.screens

import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jaredxwos.coralie.R
import com.jaredxwos.coralie.ui.composable.component.AppTextField
import com.jaredxwos.coralie.ui.composable.component.FilePickerField
import com.jaredxwos.coralie.ui.composable.component.GradientButton
import com.jaredxwos.coralie.ui.composable.component.SectionLabel
import com.jaredxwos.coralie.ui.composable.component.SquareIconButton
import com.jaredxwos.coralie.ui.composable.component.dialogs.AppDialog
import com.jaredxwos.coralie.ui.composable.component.dialogs.ButtonConfig
import com.jaredxwos.coralie.ui.composable.component.spaceRow.SpaceRow
import com.jaredxwos.coralie.ui.composable.component.spaceRow.SpaceRowConfig
import com.jaredxwos.coralie.viewModel.storage.StorageViewModel
import kotlinx.coroutines.launch


@Composable
fun AddFileScreen(
    viewModel: StorageViewModel,
    onBack: () -> Unit,
    onFileAdded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    var spaces by remember { mutableStateOf<List<SpaceRowConfig>>(emptyList()) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var spaceSelection by remember { mutableStateOf<SpaceSelection>(SpaceSelection.None) }

    val loadSpacesError = stringResource(R.string.error_load_spaces_failed)
    val saveFileError = stringResource(R.string.error_save_file_failed)
    val missingNameError = stringResource(R.string.error_missing_file_name)
    val missingFileError = stringResource(R.string.error_missing_file)
    val missingSpaceError = stringResource(R.string.error_missing_space)

    LaunchedEffect(Unit) {
        viewModel.retrieveAllSpaceConfig()
            .onSuccess { spaces = it }
            .onFailure { errorMessage = it.message ?: loadSpacesError }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) pickedUri = uri }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SquareIconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.cd_back),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(R.string.add_file_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Name
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.label_name))
            AppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.placeholder_file_name),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // HTML file
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.label_html_file))
            FilePickerField(
                label = pickedUri?.lastPathSegment,
                onClick = { filePicker.launch(arrayOf("text/html")) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Space
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.label_space))
            val focusManager = LocalFocusManager.current

            val newSpaceText = (spaceSelection as? SpaceSelection.New)?.name.orEmpty()

            AppTextField(
                value = newSpaceText,
                onValueChange = { text ->
                    spaceSelection = if (text.isBlank()) SpaceSelection.None else SpaceSelection.New(text)
                },
                placeholder = stringResource(R.string.placeholder_new_space),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused && spaceSelection is SpaceSelection.Existing) {
                            spaceSelection = SpaceSelection.New(newSpaceText)
                        }
                    },
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                spaces.forEach { space ->
                    SpaceRow(
                        space = space,
                        isSelected = (spaceSelection as? SpaceSelection.Existing)?.spaceId == space.spaceId,
                        onTap = {
                            focusManager.clearFocus()
                            spaceSelection = SpaceSelection.Existing(space.spaceId)
                        },
                    )
                }
            }
        }

        GradientButton(
            onClick = {
                val uri = pickedUri
                val trimmedName = name.trim()

                val validationError = when {
                    trimmedName.isBlank() -> missingNameError
                    uri == null -> missingFileError
                    spaceSelection is SpaceSelection.None -> missingSpaceError
                    else -> null
                }

                if (validationError != null) {
                    errorMessage = validationError
                    return@GradientButton
                }

                if (isSaving) return@GradientButton
                isSaving = true

                scope.launch {
                    val result = when (val selection = spaceSelection) {
                        is SpaceSelection.Existing -> viewModel.saveNewFileToExistingSpace(
                            spaceId = selection.spaceId,
                            name = trimmedName,
                            uri = uri!!,
                        )

                        is SpaceSelection.New -> viewModel.saveNewFileToNewSpace(
                            spaceName = selection.name.trim(),
                            name = trimmedName,
                            uri = uri!!,
                        )

                        SpaceSelection.None -> return@launch // unreachable, guarded above
                    }

                    isSaving = false
                    result
                        .onSuccess { onFileAdded() }
                        .onFailure { errorMessage = it.message ?: saveFileError }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                text = stringResource(R.string.button_confirm),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        errorMessage?.let { message ->
            AppDialog(
                title = stringResource(R.string.error_dialog_title),
                message = message,
                onDismiss = { errorMessage = null },
                isWarning = true,
                buttons = listOf(
                    ButtonConfig(
                        text = stringResource(R.string.button_ok),
                        isWarning = true,
                        effect = { errorMessage = null },
                    )
                ),
            )
        }
    }
}
sealed interface SpaceSelection {
    data object None : SpaceSelection
    data class Existing(val spaceId: Long) : SpaceSelection
    data class New(val name: String) : SpaceSelection
}