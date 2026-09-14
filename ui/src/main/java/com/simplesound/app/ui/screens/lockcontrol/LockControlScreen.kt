package com.simplesound.app.ui.screens.lockcontrol

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplesound.app.data.model.EdgeBarSide
import com.simplesound.app.playback.LockScreenPlaybackState
import com.simplesound.app.ui.components.Artwork
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The lock-screen "edge control bar" itself: a stand-in lock screen (blurred
 * album art, clock, now-playing card) with a slim Play/Next/Previous bar
 * docked to [side], shown by `LockScreenControlActivity` (:app) over the real
 * keyguard while it's locked and something is loaded in the player.
 *
 * This deliberately doesn't try to reproduce the real lock screen's actual
 * wallpaper, notifications, or emergency-call affordance -- rebuilding those
 * reliably across OEMs isn't realistic, and it's important a user is never
 * confused about how to get back to actually unlocking the phone. Tapping
 * anywhere outside the bar calls [onDismiss] (which finishes the activity,
 * revealing the real keyguard underneath for normal PIN/pattern/biometric
 * auth), and a hint to that effect is always on screen.
 */
@Composable
fun LockControlScreen(
    state: LockScreenPlaybackState,
    side: EdgeBarSide,
    onDismiss: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val isPlaying = state.isPlaying
    val trackTitle = state.trackTitle
    val trackArtist = state.trackArtist
    val embeddedArtSource = state.embeddedArtSource
    val albumArtUri = state.albumArtUri
    // A quick fade-in on first appearance -- landing straight on a fully-opaque
    // screen the instant the notification fires felt abrupt in testing.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "lockControlFadeIn",
    )

    var clockText by remember { mutableStateOf(formattedNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            clockText = formattedNow()
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = contentAlpha)
                .background(Color.Black)
                // No ripple/indication -- this should read as "tap the background to
                // dismiss", not as a pressable control in its own right.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
    ) {
        // Heavily blurred, darkened album art as the backdrop -- same artwork
        // priority (embedded picture, then album-level fallback) the rest of the
        // app uses; falls back to the app's own "no artwork" placeholder image
        // when neither resolves, never to a bare black rectangle.
        Artwork(
            uri = albumArtUri,
            embeddedSource = embeddedArtSource,
            corner = 0.dp,
            modifier = Modifier.fillMaxSize().blur(64.dp),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.4f to Color.Black.copy(alpha = 0.4f),
                            0.75f to Color.Black.copy(alpha = 0.5f),
                            1f to Color.Black.copy(alpha = 0.75f),
                        ),
                    ),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = clockText,
                color = Color.White,
                fontSize = 88.sp,
                fontWeight = FontWeight.Light,
                style = MaterialTheme.typography.displayLarge,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = formattedToday(),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
        }

        if (trackTitle.isNotBlank()) {
            NowPlayingCard(
                title = trackTitle,
                artist = trackArtist,
                embeddedArtSource = embeddedArtSource,
                albumArtUri = albumArtUri,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 132.dp)
                        .padding(horizontal = 28.dp)
                        .fillMaxWidth(),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp),
        ) {
            Icon(
                Icons.Rounded.KeyboardArrowUp,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Tap anywhere to unlock",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }

        // The bar itself: its buttons consume their own taps (standard Compose nested-
        // clickable behavior), so pressing one never falls through to onDismiss above.
        EdgeBar(
            isPlaying = isPlaying,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            modifier = Modifier.align(if (side == EdgeBarSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd),
        )
    }
}

@Composable
private fun NowPlayingCard(
    title: String,
    artist: String,
    embeddedArtSource: String?,
    albumArtUri: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            uri = albumArtUri,
            embeddedSource = embeddedArtSource,
            corner = 10.dp,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (artist.isNotBlank()) {
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EdgeBar(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier =
            modifier
                .padding(horizontal = 10.dp)
                .shadow(16.dp, shape, clip = false)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), shape)
                .padding(vertical = 14.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Play/Pause is the primary action -- filled with the accent color so it
        // reads as the one button that matters most, the other two secondary.
        Box(
            modifier =
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = "Play/Pause",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(26.dp),
            )
        }
        EdgeBarButton(Icons.Rounded.SkipNext, "Next", onNext)
        EdgeBarButton(Icons.Rounded.SkipPrevious, "Previous", onPrevious)
    }
}

@Composable
private fun EdgeBarButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

private fun formattedNow(): String = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())

private fun formattedToday(): String = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
