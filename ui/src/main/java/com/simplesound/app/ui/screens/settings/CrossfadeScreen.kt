package com.simplesound.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.data.MAX_CROSSFADE_SECONDS
import com.simplesound.app.ui.AppViewModel

/** Preset crossfade durations offered as quick-pick chips, Samsung Music-style. */
private val CrossfadePresets = listOf(0, 2, 4, 6, 8, 10, 12)

/**
 * Crossfade sub-page of Settings. A single slider (0s = off, up to
 * [MAX_CROSSFADE_SECONDS]) plus preset chips for quick jumps, mirroring the
 * layout of [SleepTimerScreen]. Applies immediately as the value changes --
 * same pattern as [AccentColorScreen] -- since PlaybackService reads the
 * setting live and there's nothing to "save".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrossfadeScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val seconds by vm.crossfadeSeconds.collectAsStateWithLifecycle()

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
                    text = "Crossfade",
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
                    .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Smoothly fade out the current track while the next one fades in.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(20.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(vertical = 20.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Crossfade duration",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = if (seconds <= 0) "Off" else "${seconds}s",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.size(24.dp))

            Slider(
                value = seconds.toFloat(),
                onValueChange = { vm.setCrossfadeSeconds(it.toInt()) },
                valueRange = 0f..MAX_CROSSFADE_SECONDS.toFloat(),
                steps = MAX_CROSSFADE_SECONDS - 1,
                colors =
                    SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
            )

            Spacer(Modifier.size(20.dp))

            Text(
                text = "Or pick a duration",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CrossfadePresets.forEach { preset ->
                    val selected = preset == seconds
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .clickable { vm.setCrossfadeSeconds(preset) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = if (preset <= 0) "Off" else "${preset}s",
                            style = MaterialTheme.typography.titleMedium,
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
