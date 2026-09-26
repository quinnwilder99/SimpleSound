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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.simplesound.app.data.model.Track
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.LocalPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deletes tracks' audio files from the device, then drops them from the library,
 * every playlist, favorites and the play queue.
 *
 * Removing only the database rows (what "Delete" used to do) was undone by the very
 * next MediaStore scan: the file was still on disk, so the track came back on the
 * next launch -- minus every playlist and favorite it had been in.
 *
 * - Android 11+: [MediaStore.createDeleteRequest] shows the system's own "Allow
 *   SimpleSound to delete this audio?" confirmation, so no in-app dialog is shown.
 * - Android 8–10: no system prompt exists, so an in-app confirmation is shown and
 *   the files are deleted directly (needs WRITE_EXTERNAL_STORAGE, requested on
 *   first use; Android 10 relies on requestLegacyExternalStorage in the manifest).
 */
@Stable
class TrackDeleter internal constructor(
    private val confirming: State<Boolean>,
    private val onRequest: (List<Track>) -> Unit,
) {
    /** True while the in-app confirmation (Android 8–10 only) is on screen. */
    val isConfirming: Boolean get() = confirming.value

    fun request(tracks: List<Track>) {
        if (tracks.isNotEmpty()) onRequest(tracks)
    }
}

@Composable
fun rememberTrackDeleter(
    vm: AppViewModel,
    onDeleted: (Set<Long>) -> Unit = {},
): TrackDeleter {
    val context = LocalContext.current
    val player = LocalPlayer.current
    val scope = rememberCoroutineScope()
    val latestOnDeleted by rememberUpdatedState(onDeleted)

    // Tracks waiting on the system prompt / in-app confirmation / permission grant.
    var pending by remember { mutableStateOf<List<Track>>(emptyList()) }
    val confirmingState = remember { mutableStateOf(false) }
    var confirming by confirmingState

    fun finish(ids: Set<Long>) {
        if (ids.isEmpty()) return
        player.removeTracksFromQueue(ids)
        vm.deleteTracks(ids.toList())
        latestOnDeleted(ids)
    }

    val systemPrompt =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val tracks = pending
            pending = emptyList()
            // RESULT_OK means the system already deleted every file in the request.
            if (result.resultCode == Activity.RESULT_OK) finish(tracks.mapTo(HashSet()) { it.id })
        }

    fun deleteDirectly(tracks: List<Track>) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) { deleteFiles(context, tracks) }
            val failed = tracks.size - deleted.size
            if (failed > 0) {
                Toast.makeText(context, "Couldn't delete $failed of ${tracks.size} tracks", Toast.LENGTH_SHORT).show()
            }
            finish(deleted)
        }
    }

    val writePermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val tracks = pending
            pending = emptyList()
            if (granted) {
                deleteDirectly(tracks)
            } else {
                Toast.makeText(context, "Storage permission is needed to delete files", Toast.LENGTH_SHORT).show()
            }
        }

    val deleter =
        remember {
            TrackDeleter(confirmingState) { tracks ->
                pending = tracks
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val uris = tracks.map { Uri.parse(it.uri) }
                    val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    systemPrompt.launch(IntentSenderRequest.Builder(request.intentSender).build())
                } else {
                    confirming = true
                }
            }
        }

    if (confirming && pending.isNotEmpty()) {
        DeleteTracksDialog(
            count = pending.size,
            onConfirm = {
                confirming = false
                if (hasLegacyWritePermission(context)) {
                    val tracks = pending
                    pending = emptyList()
                    deleteDirectly(tracks)
                } else {
                    writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            },
            onDismiss = {
                confirming = false
                pending = emptyList()
            },
        )
    }
    return deleter
}

private fun hasLegacyWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED

/** Android 8–10 path. Returns the ids whose files are actually gone. */
private fun deleteFiles(
    context: Context,
    tracks: List<Track>,
): Set<Long> =
    tracks.mapNotNullTo(HashSet()) { track ->
        val removed =
            runCatching { context.contentResolver.delete(Uri.parse(track.uri), null, null) > 0 }
                .onFailure { Log.w("TrackDeleter", "Delete failed for ${track.uri}", it) }
                .getOrDefault(false)
        track.id.takeIf { removed }
    }
