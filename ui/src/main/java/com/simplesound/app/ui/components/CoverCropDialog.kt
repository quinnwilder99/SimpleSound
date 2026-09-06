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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplesound.app.util.CoverImageStore
import com.simplesound.app.util.CropRegion
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_ZOOM = 6f

/**
 * The user's framing of the photo inside the square viewport: [scale] on top of
 * the base center-crop fit, and [offset] (the image's translation from centred,
 * in on-screen pixels).
 */
private data class CropTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero)

/**
 * Full-screen editor shown after the user picks a photo for a playlist cover.
 * The photo can be pinch-zoomed and dragged behind a fixed square viewport;
 * confirming bakes the framed region into a saved cover file and reports its
 * file URI through [onConfirm]. [onDismiss] is called if the user backs out or
 * the image can't be read.
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

    // Latest framing reported by the viewport, plus the square viewport's side
    // in px (captured from layout) — together they define the crop on confirm.
    var transform by remember(sourceUri) { mutableStateOf(CropTransform()) }
    var viewportPx by remember { mutableStateOf(0) }

    LaunchedEffect(sourceUri) {
        val loaded = CoverImageStore.loadForEditing(context, sourceUri)
        if (loaded == null) loadFailed = true else bitmap = loaded
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.97f))
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CropHeader()

            CropViewport(
                bitmap = bitmap,
                loadFailed = loadFailed,
                onTransformChange = { transform = it },
                onViewportSize = { viewportPx = it },
            )

            CropActions(
                canConfirm = bitmap != null && !loadFailed && !saving && viewportPx > 0,
                saving = saving,
                onCancel = onDismiss,
                onConfirm = {
                    val bmp = bitmap ?: return@CropActions
                    val region = cropRegion(bmp, viewportPx.toFloat(), transform.scale, transform.offset)
                    saving = true
                    scope.launch {
                        val uri =
                            runCatching {
                                CoverImageStore.saveCrop(context, playlistId, bmp, region, previousCoverUri)
                            }.getOrNull()
                        saving = false
                        if (uri != null) onConfirm(uri.toString()) else onDismiss()
                    }
                },
            )
        }
    }
}

@Composable
private fun CropHeader() {
    Text(
        "Position cover",
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
        "Pinch to zoom, drag to choose what sits in the centre.",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.padding(bottom = 16.dp),
    )
}

@Composable
private fun CropViewport(
    bitmap: Bitmap?,
    loadFailed: Boolean,
    onTransformChange: (CropTransform) -> Unit,
    onViewportSize: (Int) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(0xFF101010))
            .clipToBounds()
            .onSizeChanged { onViewportSize(it.width) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadFailed ->
                Text("Couldn't open that image.", color = Color.White, style = MaterialTheme.typography.bodyMedium)

            bitmap == null ->
                CircularProgressIndicator(color = Color.White)

            else -> {
                CropImage(bitmap = bitmap, onTransformChange = onTransformChange)
                // A thin frame so the edge of what will be kept is obvious.
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.85f),
                        topLeft = Offset.Zero,
                        size = size,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
    }
}

/**
 * Draws [bitmap] at its full [baseScale] size (so the smaller edge exactly
 * covers the square viewport and the other edge overflows), centred, with the
 * user's zoom/pan applied on top via [graphicsLayer]. The parent clips to the
 * square, so panning reveals the overflow rather than a pre-cropped square.
 */
@Composable
private fun CropImage(
    bitmap: Bitmap,
    onTransformChange: (CropTransform) -> Unit,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    var transform by remember(bitmap) { mutableStateOf(CropTransform()) }
    // detectTransformGestures runs in a long-lived coroutine keyed only by
    // `bitmap`, so it must read the latest framing through this rather than
    // close over a stale value.
    val live by rememberUpdatedState(transform)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val frame = constraints.maxWidth.toFloat()
        val baseScale = max(frame / bitmap.width, frame / bitmap.height)
        val displayW = with(LocalDensity.current) { (bitmap.width * baseScale).toDp() }
        val displayH = with(LocalDensity.current) { (bitmap.height * baseScale).toDp() }
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
                    }
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (live.scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val maxX = max(0f, (bitmap.width * baseScale * next - frame) / 2f)
                            val maxY = max(0f, (bitmap.height * baseScale * next - frame) / 2f)
                            val moved = live.offset + pan
                            transform =
                                CropTransform(
                                    next,
                                    Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY)),
                                )
                            onTransformChange(transform)
                        }
                    },
        )
    }
}

@Composable
private fun CropActions(
    canConfirm: Boolean,
    saving: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onCancel, enabled = !saving) {
            Text("Cancel", color = Color.White)
        }
        TextButton(enabled = canConfirm, onClick = onConfirm) {
            Text(
                if (saving) "Saving…" else "Use photo",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Maps the square viewport (side [frame] px) back to pixel coordinates in [bmp],
 * given the user's [scale] and [offset]. The image is rendered centred in the
 * viewport with a base center-crop fit, then scaled by [scale] and translated by
 * [offset] about its centre — this inverts that transform for the viewport's
 * bounding box. [CoverImageStore.saveCrop] re-clamps the result into bounds.
 */
private fun cropRegion(
    bmp: Bitmap,
    frame: Float,
    scale: Float,
    offset: Offset,
): CropRegion {
    val baseScale = max(frame / bmp.width, frame / bmp.height)
    val total = baseScale * scale
    val left = bmp.width / 2f - (frame / 2f + offset.x) / total
    val top = bmp.height / 2f - (frame / 2f + offset.y) / total
    val size = frame / total
    return CropRegion(left.roundToInt(), top.roundToInt(), size.roundToInt())
}
