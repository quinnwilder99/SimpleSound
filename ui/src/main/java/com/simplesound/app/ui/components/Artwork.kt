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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.simplesound.app.ui.theme.SoundColors

/**
 * Square artwork with a graceful fallback when no cover is available.
 * The fallback is a static PNG (no_artwork.png) bundled in the ui
 * module's raw resources, referenced as a raw resource URI so Coil
 * decodes it consistently across emulators and devices.
 */
@Composable
fun Artwork(
    uri: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    @Suppress("unused") iconSize: Dp = 28.dp
) {
    val context = LocalContext.current
    val rawUri = "android.resource://${context.packageName}/raw/no_artwork"
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
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(rawUri)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
