package com.simplesound.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.data.model.EdgeBarSide
import com.simplesound.app.playback.LockScreenControlLauncher
import com.simplesound.app.ui.AppViewModel

/**
 * Settings > Edge control bar. Lets the user replace the default Android
 * lock-screen media notification with a stand-in lock screen showing a slim
 * vertical Play/Next/Previous bar docked to a screen edge instead (see
 * [com.simplesound.app.playback.LockScreenControlLauncher]), or turn it off to
 * keep using the default one.
 *
 * Showing custom UI over a secure lock screen means a real `Activity`, which
 * fully occupies the screen while it's up (see `LockScreenControlActivity` /
 * `LockControlScreen`), so the copy below is upfront about that trade-off rather
 * than calling it just an "edge bar". The Activity is launched by a full-screen
 * notification intent, which on Android 14+ Play installs needs the user to allow
 * "full screen notifications" by hand; the row below asks for it while it's
 * missing and the bar stays inactive until then.
 */
@Composable
fun EdgeControlBarScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val enabled by vm.edgeControlBarEnabled.collectAsStateWithLifecycle()
    val side by vm.edgeControlBarSide.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Re-checked on every resume: the grant happens on a system Settings page.
    var fullScreenAllowed by remember { mutableStateOf(LockScreenControlLauncher.canUseFullScreenIntent(context)) }
    LifecycleResumeEffect(Unit) {
        fullScreenAllowed = LockScreenControlLauncher.canUseFullScreenIntent(context)
        onPauseOrDispose {}
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        "Back",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "Edge control bar",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    ) { inner ->
        Column(
            modifier =
                Modifier
                    .padding(inner)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 160.dp),
        ) {
            Text(
                text =
                    "While music is loaded and your screen is locked, show a stand-in lock " +
                        "screen with Play, Next and Previous on a screen edge, instead of the " +
                        "default Android media player. Tap anywhere else on it to get to your " +
                        "normal lock screen (PIN/pattern/fingerprint) as usual.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(20.dp))

            // ---- Enable/disable ----
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Edge control bar",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (enabled) "On" else "Off — using the default Android lock screen",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { vm.setEdgeControlBarEnabled(it) },
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                )
            }

            if (enabled && !fullScreenAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Spacer(Modifier.size(12.dp))
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = "Allow full screen notifications",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text =
                            "Android needs this to show the control bar over the lock screen. " +
                                "Until it's allowed, the normal media controls are used.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.size(28.dp))

            Text(
                text = "Position",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SidePill(
                    label = "Left edge",
                    selected = side == EdgeBarSide.LEFT,
                    enabled = enabled,
                    onClick = { vm.setEdgeControlBarSide(EdgeBarSide.LEFT) },
                    modifier = Modifier.weight(1f),
                )
                SidePill(
                    label = "Right edge",
                    selected = side == EdgeBarSide.RIGHT,
                    enabled = enabled,
                    onClick = { vm.setEdgeControlBarSide(EdgeBarSide.RIGHT) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SidePill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
                    },
                )
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = alpha)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
