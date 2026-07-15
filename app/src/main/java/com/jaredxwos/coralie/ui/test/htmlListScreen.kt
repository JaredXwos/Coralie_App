package com.jaredxwos.coralie.ui.test

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jaredxwos.coralie.storage.AppStorage

private data class HtmlRow(
    val assetId: Long,
    val htmlName: String,
    val spaceId: Long,
    val spaceName: String
)

@Composable
fun HtmlListScreen() {
    var rows by remember { mutableStateOf<List<HtmlRow>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<HtmlRow?>(null) }   // null = list; non-null = viewing
    var adding by remember { mutableStateOf(false) }          // true = showing CacheNewHtml
    var reloadKey by remember { mutableIntStateOf(0) }        // bump to force a reload

    // Viewing a specific html: show the renderer.
    open?.let { row ->
        PageRender(
            assetId = row.assetId,
            spaceId = row.spaceId,
            onBack = { open = null },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    // Registering a new html: show the add screen.
    if (adding) {
        CacheNewHtml(onSaved = {
            adding = false
            reloadKey++          // new html exists now — reload the list
        })
        return
    }

    LaunchedEffect(reloadKey) {
        val htmls  = AppStorage.retrieveAllHtml().getOrElse  { error = "Couldn't load html"; return@LaunchedEffect }
        val spaces = AppStorage.retrieveAllSpaces().getOrElse { error = "Couldn't load spaces"; return@LaunchedEffect }
        val nameById = spaces.associate { it.spaceId to it.name }
        rows = htmls.map { HtmlRow(it.assetId, it.name, it.spaceId, nameById[it.spaceId] ?: "(unknown space)") }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.assetId }) { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            Log.d("nav", "row clicked: assetId=${row.assetId}, spaceId=${row.spaceId}")
                            open = row
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(row.htmlName, style = MaterialTheme.typography.bodyLarge)
                    Text(row.spaceName, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Register New Html")
        }
    }
}