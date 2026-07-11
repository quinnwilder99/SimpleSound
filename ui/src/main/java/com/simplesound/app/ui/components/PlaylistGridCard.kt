package com.simplesound.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplesound.app.data.model.Playlist

/**
 * A large square playlist card as seen on the Favorites tab. Press-and-hold makes
 * it "shake" (a small continuous wobble) and fires [onLongPress] so the host can
 * present the Play / Add / Share / Remove options.
 */
@Composable
fun PlaylistGridCard(
    playlist: Playlist,
    shaking: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wobble = if (shaking) {
        val t = rememberInfiniteTransition(label = "shake")
        t.animateFloat(
            initialValue = -1.4f, targetValue = 1.4f,
            animationSpec = infiniteRepeatable(tween(110, easing = LinearEasing), RepeatMode.Reverse),
            label = "angle"
        ).value
    } else 0f

    Column(modifier = modifier.padding(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .rotate(wobble)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(playlist.id) {
                    detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() })
                }
        ) {
            Artwork(uri = playlist.coverUri, modifier = Modifier.fillMaxSize(), corner = 18.dp, iconSize = 48.dp)
            // Legibility scrim + labels overlaid, like the reference.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.45f)
                        )
                    )
            )
            Column(Modifier.align(Alignment.Center)) {
                Text(
                    "Playlists",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            if (playlist.favorited || playlist.kind != com.simplesound.app.data.model.PlaylistKind.USER) {
                Icon(
                    Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                )
            }
        }
        Text(
            text = com.simplesound.app.util.trackCountLabel(playlist.trackCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp)
        )
    }
}
