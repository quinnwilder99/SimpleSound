package com.simplesound.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import com.simplesound.ui.R
import com.simplesound.app.ui.theme.SoundColors

/**
 * Square artwork with a graceful fallback when no cover is available.
 *
 * The fallback is a static drawable (no_artwork.png) bundled in the ui module's
 * resources, loaded directly as a resource id via [painterResource]. This avoids
 * reliance on the `android.resource://` URI scheme, which Coil can fail to resolve
 * in APK builds on real devices. When a track has a real [uri] (MediaStore
 * album-art content URI), Coil loads it; otherwise the bundled PNG is shown.
 */
@Composable
fun Artwork(
    uri: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    @Suppress("unused") iconSize: Dp = 28.dp
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(SoundColors.SurfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (!uri.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uri)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = painterResource(R.drawable.no_artwork),
                placeholder = painterResource(R.drawable.no_artwork)
            )
        } else {
            // No album-art URI: show the bundled fallback drawable directly.
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(R.drawable.no_artwork)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
