package com.jaredxwos.coralie.ui.test

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jaredxwos.coralie.storage.AppStorage
import com.jaredxwos.coralie.storage.database.Space
import kotlinx.coroutines.launch

// Either create a brand-new space, or use an existing one.
private sealed interface SpaceChoice {
    object New : SpaceChoice
    data class Existing(val spaceId: Long) : SpaceChoice
}

@Composable
fun CacheNewHtml(onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()

    var spaces        by remember { mutableStateOf<List<Space>>(emptyList()) }
    var name          by rememberSaveable { mutableStateOf("") }
    var pickedUri     by rememberSaveable { mutableStateOf<Uri?>(null) }
    var newSpaceName  by rememberSaveable { mutableStateOf("") }
    var selection     by remember { mutableStateOf<SpaceChoice?>(null) }
    var saving        by remember { mutableStateOf(false) }
    var error         by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        AppStorage.retrieveAllSpaces()
            .onSuccess { spaces = it }
            .onFailure { error = "Couldn't load spaces" }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) pickedUri = uri }

    fun launchPicker() = filePicker.launch(arrayOf("text/html", "text/plain"))

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        // ── Name ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Name", Modifier.width(80.dp), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Address ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { launchPicker() }, modifier = Modifier.width(110.dp)) {
                Text("Address")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { launchPicker() }, modifier = Modifier.weight(1f)) {
                Text(
                    text = pickedUri?.toString() ?: "Choose a file",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Space selection: new space + existing spaces, one radio group ──
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {

            // New space option: radio + editable name field
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selection == SpaceChoice.New,
                    onClick = { selection = SpaceChoice.New }
                )
                OutlinedTextField(
                    value = newSpaceName,
                    onValueChange = {
                        newSpaceName = it
                        selection = SpaceChoice.New   // typing implies choosing "new"
                    },
                    placeholder = { Text("New space name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // Existing spaces
            spaces.forEach { space ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selection == SpaceChoice.Existing(space.spaceId),
                            onClick = { selection = SpaceChoice.Existing(space.spaceId) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selection == SpaceChoice.Existing(space.spaceId),
                        onClick = null   // handled by the row's selectable
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(space.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Register ──
        Button(
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val htmlName = name.trim()
                val uri = pickedUri
                val sel = selection
                // Do nothing if anything required is missing.
                if (htmlName.isEmpty() || uri == null || sel == null) return@Button
                if (sel is SpaceChoice.New && newSpaceName.isBlank()) return@Button

                saving = true
                error = null
                scope.launch {
                    val spaceId = when (sel) {
                        is SpaceChoice.Existing -> sel.spaceId
                        SpaceChoice.New -> AppStorage.createSpace(newSpaceName.trim())
                            .getOrElse {
                                saving = false
                                error = "Couldn't create space: ${it.message}"
                                return@launch
                            }
                    }
                    val result = AppStorage.addHtml(spaceId, htmlName, uri)
                        .mapCatching { assetId -> AppStorage.cache(assetId).getOrThrow() }
                    saving = false
                    result
                        .onSuccess { onSaved() }
                        .onFailure { error = "Save failed: ${it.message}" }
                }
            }
        ) {
            Text(if (saving) "Saving…" else "Register")
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
    }
}