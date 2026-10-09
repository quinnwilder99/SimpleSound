package com.simplesound.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.simplesound.app.data.model.Track
import com.simplesound.app.data.tags.TagEdits
import com.simplesound.app.data.tags.TrackTagWriter
import com.simplesound.app.data.tags.UnsupportedTagException
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edits a track's title/artist/album *inside the audio file* (its ID3 tag), then
 * updates the library and the live queue to match.
 *
 * - Android 11+: [MediaStore.createWriteRequest] shows the system's "Allow
 *   SimpleSound to modify this audio file?" prompt before anything is written.
 * - Android 8–10: needs WRITE_EXTERNAL_STORAGE, requested on first use (Android 10
 *   relies on requestLegacyExternalStorage, same as [TrackDeleter]).
 */
@Stable
class TrackTagEditor internal constructor(
    private val editing: State<Track?>,
    private val onOpen: (Track) -> Unit,
) {
    /** True while the edit dialog is on screen. */
    val isOpen: Boolean get() = editing.value != null

    fun open(track: Track) = onOpen(track)

    companion object {
        fun canEdit(track: Track): Boolean = TrackTagWriter.supports(track)
    }
}

@Composable
fun rememberTrackTagEditor(vm: AppViewModel): TrackTagEditor {
    val context = LocalContext.current
    val player = LocalPlayer.current
    val scope = rememberCoroutineScope()

    val editingState = remember { mutableStateOf<Track?>(null) }
    var editing by editingState

    val save =
        rememberWriteAccess { track, edits ->
            scope.launch {
                if (writeTags(context, track, edits)) {
                    val t = track.withEdits(edits)
                    vm.updateTrackTags(t.id, t.title, t.artist, t.album)?.let(player::refreshTrackMetadata)
                }
            }
        }

    editing?.let { track ->
        EditTagsDialog(
            track = track,
            onSave = { edits ->
                editing = null
                if (!edits.isEmpty) save(track, edits)
            },
            onDismiss = { editing = null },
        )
    }

    return remember { TrackTagEditor(editingState) { editing = it } }
}

/**
 * Returns a function that obtains write access to a track's file (system prompt on
 * Android 11+, WRITE_EXTERNAL_STORAGE below) and then calls [onGranted].
 */
@Composable
private fun rememberWriteAccess(onGranted: (Track, TagEdits) -> Unit): (Track, TagEdits) -> Unit {
    val context = LocalContext.current
    val latestOnGranted by rememberUpdatedState(onGranted)
    // The edit waiting on the system prompt / permission grant.
    var pending by remember { mutableStateOf<Pair<Track, TagEdits>?>(null) }

    fun finish(granted: Boolean) {
        val (track, edits) = pending ?: return
        pending = null
        if (granted) latestOnGranted(track, edits)
    }

    val systemPrompt =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            finish(result.resultCode == Activity.RESULT_OK)
        }
    val writePermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    context,
                    "Storage permission is needed to edit files",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            finish(granted)
        }

    return { track, edits ->
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                pending = track to edits
                val request = MediaStore.createWriteRequest(context.contentResolver, listOf(Uri.parse(track.uri)))
                systemPrompt.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }
            hasLegacyWritePermission(context) -> latestOnGranted(track, edits)
            else -> {
                pending = track to edits
                writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}

private fun Track.withEdits(edits: TagEdits): Track =
    copy(
        title = edits.title?.trim() ?: title,
        artist = edits.artist?.trim() ?: artist,
        album = edits.album?.trim() ?: album,
    )

/** Writes [edits] into the file off the main thread and toasts the outcome; true on success. */
private suspend fun writeTags(
    context: Context,
    track: Track,
    edits: TagEdits,
): Boolean {
    val error =
        withContext(Dispatchers.IO) {
            runCatching { TrackTagWriter.write(context, track, edits) }
                .onFailure { Log.w("TrackTagEditor", "Tag write failed for ${track.path}", it) }
                .exceptionOrNull()
        }
    if (error == null) {
        Toast.makeText(context, "Tags saved", Toast.LENGTH_SHORT).show()
    } else {
        val reason = if (error is UnsupportedTagException) error.message else "the file couldn't be written"
        Toast.makeText(context, "Couldn't save tags: $reason", Toast.LENGTH_LONG).show()
    }
    return error == null
}

private fun hasLegacyWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun EditTagsDialog(
    track: Track,
    onSave: (TagEdits) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(track.id) { mutableStateOf(track.title) }
    var artist by rememberSaveable(track.id) { mutableStateOf(track.artist) }
    var album by rememberSaveable(track.id) { mutableStateOf(track.album) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    // Only fields that actually changed are written, so an untouched
                    // frame in the file stays byte-for-byte as it was.
                    fun changed(
                        new: String,
                        old: String,
                    ) = new.trim().takeIf { it != old }
                    onSave(
                        TagEdits(
                            title = changed(title, track.title),
                            artist = changed(artist, track.artist),
                            album = changed(album, track.album),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
