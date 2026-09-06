package com.simplesound.app.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplesound.app.util.CoverImageStore
import com.simplesound.app.util.CropRegion
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_ZOOM = 6f

/**
 * The user's framing of the photo: [scale] on top of the base fit that makes the
 * photo cover the square crop frame, and [offset] — the photo's translation from
 * centred, in screen pixels.
 */
private data class CropTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero)

/**
 * Full-screen editor shown after the user picks a photo for a playlist cover.
 * The photo can be pinch-zoomed and dragged behind a fixed square crop frame;
 * the area outside the frame is dimmed. Confirming bakes the framed region into
 * a saved cover file and reports its file URI through [onConfirm]. [onDismiss]
 * is called if the user backs out or the image can't be read.
 */
@Composable
fun CoverCropDialog(
    sourceUri: Uri,
    playlistId: String,
    previousCoverUri: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember(sourceUri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(sourceUri) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var region by remember(sourceUri) { mutableStateOf<CropRegion?>(null) }

    LaunchedEffect(sourceUri) {
        val loaded = CoverImageStore.loadForEditing(context, sourceUri)
        if (loaded == null) loadFailed = true else bitmap = loaded
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            CropTopBar(
                canConfirm = bitmap != null && !loadFailed && !saving && region != null,
                saving = saving,
                onCancel = onDismiss,
                onConfirm = {
                    val bmp = bitmap ?: return@CropTopBar
                    val r = region ?: return@CropTopBar
                    saving = true
                    scope.launch {
                        val uri =
                            runCatching {
                                CoverImageStore.saveCrop(context, playlistId, bmp, r, previousCoverUri)
                            }.getOrNull()
                        saving = false
                        if (uri != null) onConfirm(uri.toString()) else onDismiss()
                    }
                },
            )

            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val loaded = bitmap
                when {
                    loadFailed ->
                        Text("Couldn't open that image.", color = Color.White)

                    loaded == null ->
                        CircularProgressIndicator(color = Color.White)

                    else ->
                        CropStage(bitmap = loaded, onRegionChange = { region = it })
                }
            }

            Text(
                "Pinch to zoom · drag to reposition",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CropTopBar(
    canConfirm: Boolean,
    saving: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel, enabled = !saving) {
            Text("Cancel", color = Color.White)
        }
        Text("Position cover", color = Color.White, fontWeight = FontWeight.SemiBold)
        Button(
            onClick = onConfirm,
            enabled = canConfirm,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(if (saving) "Saving…" else "Use photo")
        }
    }
}

/**
 * The interactive crop area: the photo drawn under a centred square frame, with
 * everything outside the frame dimmed. Reports the pixel region of [bitmap] that
 * the frame currently covers through [onRegionChange] whenever it changes.
 */
@Composable
private fun CropStage(
    bitmap: Bitmap,
    onRegionChange: (CropRegion) -> Unit,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    var transform by remember(bitmap) { mutableStateOf(CropTransform()) }
    // detectTransformGestures runs in a coroutine keyed only by `fit`, so it
    // reads the latest transform through this rather than a stale capture.
    val live by rememberUpdatedState(transform)

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val stageW = constraints.maxWidth.toFloat()
        val stageH = constraints.maxHeight.toFloat()
        val frame = min(stageW, stageH)
        val fit = remember(bitmap, stageW, stageH) { CropFit(bitmap, frame, stageW, stageH) }
        val displayW = with(density) { fit.displayW.toDp() }
        val displayH = with(density) { fit.displayH.toDp() }

        val current = remember(fit, transform) { fit.region(transform) }
        LaunchedEffect(current) { onRegionChange(current) }

        // Gestures are handled on this full-stage box (never scaled/translated),
        // so `centroid` is in stage coordinates and `pan` is in screen pixels.
        Box(
            Modifier.fillMaxSize().pointerInput(fit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    transform = fit.next(live, centroid, pan, zoom)
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier =
                    Modifier
                        .size(displayW, displayH)
                        .graphicsLayer {
                            scaleX = transform.scale
                            scaleY = transform.scale
                            translationX = transform.offset.x
                            translationY = transform.offset.y
                        },
            )
        }
        CropMask(framePx = frame)
    }
}

/** Dims everything outside the centred [framePx]-sided square and outlines it. */
@Composable
private fun CropMask(framePx: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val scrim = Color.Black.copy(alpha = 0.55f)
        val left = (size.width - framePx) / 2f
        val top = (size.height - framePx) / 2f
        val right = left + framePx
        val bottom = top + framePx
        drawRect(scrim, Offset.Zero, Size(size.width, top))
        drawRect(scrim, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(scrim, Offset(0f, top), Size(left, framePx))
        drawRect(scrim, Offset(right, top), Size(size.width - right, framePx))
        drawRect(
            Color.White.copy(alpha = 0.9f),
            Offset(left, top),
            Size(framePx, framePx),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/**
 * The geometry of fitting [bmp] to a centred square crop frame of side [frame]
 * px inside a [stageW] x [stageH] stage: the photo is scaled by `baseScale` so
 * its shorter edge exactly covers the frame, drawn centred in the stage, then
 * the user's zoom/pan is applied on top.
 */
private class CropFit(
    private val bmp: Bitmap,
    private val frame: Float,
    stageW: Float,
    stageH: Float,
) {
    private val baseScale = max(frame / bmp.width, frame / bmp.height)
    private val stageCenter = Offset(stageW / 2f, stageH / 2f)

    val displayW get() = bmp.width * baseScale
    val displayH get() = bmp.height * baseScale

    /**
     * The next transform after one gesture: [zoom] clamped to [1, MAX_ZOOM] and
     * anchored on the pinch [centroid] (stage coords) so the point under the
     * fingers stays put, then [pan], then the offset clamped so the photo still
     * covers the crop frame.
     */
    fun next(
        prev: CropTransform,
        centroid: Offset,
        pan: Offset,
        zoom: Float,
    ): CropTransform {
        val scale = (prev.scale * zoom).coerceIn(1f, MAX_ZOOM)
        val k = scale / prev.scale
        val d = centroid - stageCenter
        val moved = d * (1f - k) + pan + prev.offset * k
        val maxX = max(0f, (displayW * scale - frame) / 2f)
        val maxY = max(0f, (displayH * scale - frame) / 2f)
        return CropTransform(
            scale,
            Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY)),
        )
    }

    /** The pixel region of [bmp] the crop frame covers under [t]. */
    fun region(t: CropTransform): CropRegion {
        val total = baseScale * t.scale
        val left = bmp.width / 2f - (frame / 2f + t.offset.x) / total
        val top = bmp.height / 2f - (frame / 2f + t.offset.y) / total
        return CropRegion(left.roundToInt(), top.roundToInt(), (frame / total).roundToInt())
    }
}
