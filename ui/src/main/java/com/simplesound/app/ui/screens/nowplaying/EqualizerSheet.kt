package com.simplesound.app.ui.screens.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplesound.app.data.model.EqPreset
import com.simplesound.app.data.model.EqualizerSettings
import com.simplesound.app.data.model.HeadphoneCorrection
import com.simplesound.app.playback.eq.EqualizerController
import kotlin.math.roundToInt

/**
 * Bottom sheet for the equalizer: on/off, an AutoEq headphone correction (searchable
 * list of the bundled models), and a 10-band graphic EQ with presets on top of it.
 * Every change goes straight to [EqualizerController], so it's heard while dragging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EqualizerSheet(
    controller: EqualizerController,
    onDismiss: () -> Unit,
) {
    val settings by controller.settings.collectAsStateWithLifecycle()
    var pickingHeadphone by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        if (pickingHeadphone) {
            HeadphonePicker(
                controller = controller,
                selectedId = settings.headphone?.id,
                onPick = { picked ->
                    controller.setHeadphone(picked)
                    pickingHeadphone = false
                },
                onBack = { pickingHeadphone = false },
            )
        } else {
            EqualizerControls(
                settings = settings,
                controller = controller,
                onChooseHeadphone = { pickingHeadphone = true },
            )
        }
    }
}

@Composable
private fun EqualizerControls(
    settings: EqualizerSettings,
    controller: EqualizerController,
    onChooseHeadphone: () -> Unit,
) {
    // Scrolls on short/landscape screens; the band sliders consume their own drags, so they don't fight it.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Equalizer",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = settings.enabled, onCheckedChange = controller::setEnabled)
        }
        // Dimmed rather than disabled while off: touching any control turns the EQ on.
        Column(modifier = Modifier.alpha(if (settings.enabled) 1f else 0.5f)) {
            Spacer(Modifier.height(16.dp))
            SectionLabel("Headphones")
            HeadphoneRow(
                headphone = settings.headphone,
                onClick = onChooseHeadphone,
                onClear = { controller.setHeadphone(null) },
            )
            Spacer(Modifier.height(20.dp))
            SoundSection(settings, controller)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Headphone corrections from AutoEq",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { controller.setBandGains(EqPreset.FLAT.gainsDb) }) {
                    Text("Reset bands")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundSection(
    settings: EqualizerSettings,
    controller: EqualizerController,
) {
    SectionLabel("Sound")
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val selected = settings.preset
        EqPreset.entries.forEach { preset ->
            PresetChip(
                label = preset.label,
                selected = preset == selected,
                onClick = { controller.setBandGains(preset.gainsDb) },
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    // Equal-width columns: sized by their labels instead, a "+10.5" readout would widen its
    // column mid-drag and shift every slider sideways under the finger.
    Row(modifier = Modifier.fillMaxWidth()) {
        EqualizerSettings.BAND_FREQUENCIES_HZ.forEachIndexed { band, hz ->
            BandSlider(
                gainDb = settings.bandGainsDb[band],
                label = bandLabel(hz),
                onChange = { controller.setBandGain(band, it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun HeadphoneRow(
    headphone: HeadphoneCorrection?,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Headphones,
                contentDescription = null,
                tint =
                    if (headphone != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headphone?.name ?: "Choose your headphones",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        if (headphone != null) {
                            "AutoEq · ${headphone.source}"
                        } else {
                            "Flattens your model's frequency response"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (headphone != null) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.Close, contentDescription = "Remove headphone correction")
                }
            } else {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors =
            AssistChipDefaults.assistChipColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                labelColor =
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
}

/**
 * One vertical graphic-EQ band: drag (or tap) anywhere on the track to set its gain,
 * +[EqualizerSettings.MAX_BAND_GAIN_DB] at the top, the same cut at the bottom, in
 * half-dB steps. The fill grows from the 0 dB centre line.
 */
@Composable
private fun BandSlider(
    gainDb: Float,
    label: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val max = EqualizerSettings.MAX_BAND_GAIN_DB
    val latestOnChange by rememberUpdatedState(onChange)
    val accent = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        GainReadout(gainDb)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier =
                Modifier
                    .height(168.dp)
                    .width(30.dp)
                    .pointerInput(Unit) {
                        fun gainAt(y: Float): Float {
                            val fraction = (y / size.height).coerceIn(0f, 1f)
                            return ((max - fraction * 2 * max) * 2).roundToInt() / 2f
                        }
                        awaitEachGesture {
                            // Consumed so the bottom sheet doesn't treat the drag as a dismiss.
                            val down = awaitFirstDown()
                            down.consume()
                            latestOnChange(gainAt(down.position.y))
                            drag(down.id) { change ->
                                change.consume()
                                latestOnChange(gainAt(change.position.y))
                            }
                        }
                    }
                    .semantics {
                        contentDescription = "$label hertz band"
                        stateDescription = "${formatGain(gainDb)} decibels"
                        progressBarRangeInfo = ProgressBarRangeInfo(gainDb, -max..max)
                        setProgress { target ->
                            latestOnChange(target.coerceIn(-max, max))
                            true
                        }
                    }
                    .drawBehind {
                        val x = size.width / 2
                        val stroke = 4.dp.toPx()
                        val centre = size.height / 2
                        val valueY = centre - (gainDb / max) * centre
                        drawLine(trackColor, Offset(x, 0f), Offset(x, size.height), stroke, StrokeCap.Round)
                        val tick = 6.dp.toPx()
                        drawLine(trackColor, Offset(x - tick, centre), Offset(x + tick, centre), 1.dp.toPx())
                        drawLine(accent, Offset(x, centre), Offset(x, valueY), stroke, StrokeCap.Round)
                        drawCircle(accent, radius = 8.dp.toPx(), center = Offset(x, valueY))
                    },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One line, allowed to spill past its narrow column: wrapping "+10.5" would push the slider down mid-drag. */
@Composable
private fun GainReadout(gainDb: Float) {
    Text(
        text = formatGain(gainDb),
        style = MaterialTheme.typography.labelSmall,
        color = if (gainDb != 0f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

@Composable
private fun HeadphonePicker(
    controller: EqualizerController,
    selectedId: String?,
    onPick: (HeadphoneCorrection?) -> Unit,
    onBack: () -> Unit,
) {
    val headphones by produceState<List<HeadphoneCorrection>?>(null) { value = controller.headphones() }
    var query by remember { mutableStateOf("") }
    // Letters and digits only, so "hd650" finds "Sennheiser HD 650".
    val keys = remember(headphones) { headphones.orEmpty().map { searchKey(it.name) } }
    val results =
        remember(headphones, query) {
            val tokens = query.split(' ').map(::searchKey).filter { it.isNotEmpty() }
            headphones.orEmpty().filterIndexed { i, _ -> tokens.all { it in keys[i] } }
        }
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(headphones?.let { "Search ${it.size} headphones" } ?: "Search headphones") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f).padding(end = 16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (headphones == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            HeadphoneList(results, query, selectedId, onPick)
        }
    }
}

@Composable
private fun HeadphoneList(
    results: List<HeadphoneCorrection>,
    query: String,
    selectedId: String?,
    onPick: (HeadphoneCorrection?) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        if (query.isBlank()) {
            item(key = "none") {
                PickerRow(
                    title = "None",
                    subtitle = "No headphone correction",
                    selected = selectedId == null,
                    onClick = { onPick(null) },
                )
            }
        }
        items(results, key = { it.id }) { h ->
            PickerRow(
                title = h.name,
                subtitle = "${h.type.replaceFirstChar { it.uppercase() }} · ${h.source}",
                selected = h.id == selectedId,
                onClick = { onPick(h) },
            )
        }
        if (results.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = if (query.isBlank()) "Headphone list unavailable" else "No headphones match \"$query\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun searchKey(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

private fun bandLabel(hz: Double): String = if (hz >= 1_000) "${(hz / 1_000).roundToInt()}k" else "${hz.roundToInt()}"

private fun formatGain(db: Float): String =
    when {
        db == 0f -> "0"
        db % 1f == 0f -> "%+d".format(db.toInt())
        else -> "%+.1f".format(db)
    }
