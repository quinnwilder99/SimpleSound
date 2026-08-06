package com.simplesound.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.ui.LocalPlayer

/** Preset sleep timer durations, in minutes. */
private val SleepTimerPresets = listOf(5, 10, 15, 30, 45, 60, 90)

/** Format minutes as either "Xm" or "Xh Ym" for compact preset labels. */
private fun formatMinutesShort(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0 && m > 0) "${h}h ${m}m"
    else if (h > 0) "${h}h"
    else "${m}m"
}

/** Format remaining ms as H:MM:SS (or M:SS when under an hour). */
private fun formatRemaining(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

/**
 * Sleep timer sub-page of Settings. Lets the user choose when playback should
 * automatically pause. Supports presets plus a custom Hour + Minute duration,
 * and shows a live countdown clock while a timer is running. Only one timer is
 * active at a time; choosing a new value (or tapping "Cancel") replaces/cancels
 * the current one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepTimerScreen(onBack: () -> Unit) {
    val player = LocalPlayer.current
    val active by player.sleepTimerActive.collectAsStateWithLifecycle()
    val remainingMs by player.sleepTimerRemainingMs.collectAsStateWithLifecycle()

    // Total selected duration in minutes (presets set this directly; custom fields
    // contribute hours + minutes).
    var selectedMinutes by remember { mutableStateOf(15) }

    // Custom duration inputs (raw strings so empty fields are editable).
    var hoursText by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }
    val customHours = hoursText.toIntOrNull() ?: 0
    val customMinutes = minutesText.toIntOrNull() ?: 0
    val customTotal = customHours * 60 + customMinutes
    val customValid = customTotal in 1..720

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        "Back",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "Sleep timer",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Music will pause automatically after the selected time.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- Countdown clock (only when a timer is running) ----
            if (active && remainingMs > 0L) {
                Spacer(Modifier.size(20.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(vertical = 20.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Time remaining",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = formatRemaining(remainingMs),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.size(20.dp))

            Text(
                text = "Choose a duration",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.size(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SleepTimerPresets.forEach { minutes ->
                    val selected = minutes == selectedMinutes && !active
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable {
                                selectedMinutes = minutes
                                hoursText = ""
                                minutesText = ""
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = formatMinutesShort(minutes),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.size(24.dp))

            Text(
                text = "Or set a custom duration",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.size(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(2)
                        hoursText = digits
                        val h = digits.toIntOrNull() ?: 0
                        val m = minutesText.toIntOrNull() ?: 0
                        val total = h * 60 + m
                        if (total in 1..720) selectedMinutes = total
                    },
                    label = { Text("Hours") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp)
                )
                Text(
                    text = ":",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(2)
                        val bounded = digits.toIntOrNull()?.coerceAtMost(59)?.toString() ?: digits
                        minutesText = bounded
                        val h = hoursText.toIntOrNull() ?: 0
                        val m = bounded.toIntOrNull() ?: 0
                        val total = h * 60 + m
                        if (total in 1..720) selectedMinutes = total
                    },
                    label = { Text("Minutes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp)
                )
            }
            if ((hoursText.isNotEmpty() || minutesText.isNotEmpty()) && !customValid) {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "Enter a duration between 1 minute and 12 hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.size(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val minutes = if (hoursText.isNotEmpty() || minutesText.isNotEmpty()) {
                            if (customValid) customTotal else selectedMinutes
                        } else selectedMinutes
                        player.setSleepTimer(minutes)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    enabled = selectedMinutes > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Start timer", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { player.cancelSleepTimer() },
                    modifier = Modifier.weight(1f),
                    enabled = active
                ) {
                    Text(text = "Cancel", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}