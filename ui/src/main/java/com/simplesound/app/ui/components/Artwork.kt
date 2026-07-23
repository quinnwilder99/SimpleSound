package com.simplesound.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.simplesound.app.ui.theme.SoundColors
import com.simplesound.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Artwork(
    uri: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    @Suppress("unused") iconSize: Dp = 28.dp,
    embeddedSource: String? = null
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(SoundColors.SurfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        key(embeddedSource ?: uri) {
            val embedded = rememberEmbeddedArt(embeddedSource)
            when (embedded) {
                EmbeddedState.Loading -> Box(Modifier.fillMaxSize())
                is EmbeddedState.Art -> Image(
                    bitmap = embedded.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                EmbeddedState.None -> Unit
            }
            if (embedded is EmbeddedState.None && !uri.isNullOrBlank()) {
                val parsed = runCatching { Uri.parse(uri) }.getOrNull()
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(parsed ?: uri)
                        .memoryCacheKey(uri)
                        .diskCacheKey(uri)
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    error = painterResource(R.drawable.no_artwork),
                    placeholder = painterResource(R.drawable.no_artwork)
                )
            } else if (embedded is EmbeddedState.None) {
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
}

private sealed interface EmbeddedState {
    data object Loading : EmbeddedState
    data object None : EmbeddedState
    class Art(val bitmap: Bitmap) : EmbeddedState
}

@Composable
private fun rememberEmbeddedArt(source: String?): EmbeddedState {
    if (source.isNullOrBlank()) return EmbeddedState.None
    val context = LocalContext.current
    var state by remember(source) {
        mutableStateOf<EmbeddedState>(LruEmbeddedArt[source] ?: EmbeddedState.Loading)
    }
    if (state is EmbeddedState.Loading) {
        LaunchedEffect(source) {
            val decoded: EmbeddedState = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                val bitmap: Bitmap? = try {
                    retriever.setDataSource(context, Uri.parse(source))
                    val bytes = retriever.embeddedPicture
                    if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
                } catch (e: Throwable) {
                    null
                } finally {
                    runCatching { retriever.release() }
                }
                bitmap?.let { EmbeddedState.Art(it) } ?: EmbeddedState.None
            }
            LruEmbeddedArt[source] = decoded
            state = decoded
        }
    }
    return state
}

private object LruEmbeddedArt {
    private const val MAX = 256
    private val cache = object : LinkedHashMap<String, EmbeddedState>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, EmbeddedState>): Boolean = size > MAX
    }
    @Synchronized
    operator fun get(key: String): EmbeddedState? = cache[key]
    @Synchronized
    operator fun set(key: String, value: EmbeddedState) {
        cache[key] = value
    }
}