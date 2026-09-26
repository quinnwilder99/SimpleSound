package com.simplesound.app.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.simplesound.app.ui.theme.SoundColors
import com.simplesound.ui.R

/**
 * Track / playlist artwork. With [embeddedSource] (a track's content URI) it shows
 * the picture embedded in the file, falling back to [uri] (album art), then to the
 * "no artwork" placeholder -- see [EmbeddedArt]. Without it, [uri] is loaded directly
 * (e.g. a playlist cover). All decoding, downsampling and caching is Coil's.
 */
@Composable
fun Artwork(
    uri: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    @Suppress("unused") iconSize: Dp = 28.dp,
    embeddedSource: String? = null,
) {
    val context = LocalContext.current
    val embedded = !embeddedSource.isNullOrBlank()
    val request =
        remember(uri, embeddedSource) {
            val data: Any =
                when {
                    embedded -> EmbeddedArt(embeddedSource!!, uri?.takeIf { it.isNotBlank() })
                    !uri.isNullOrBlank() -> runCatching { Uri.parse(uri) }.getOrNull() ?: uri
                    else -> R.drawable.no_artwork
                }
            ImageRequest.Builder(context).data(data).crossfade(false).build()
        }
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(corner))
                .background(SoundColors.SurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            // Embedded art loads blank rather than flashing the placeholder for the
            // (usually very short) time it takes to read a cover.
            placeholder = if (embedded) null else painterResource(R.drawable.no_artwork),
            error = painterResource(R.drawable.no_artwork),
        )
    }
}
