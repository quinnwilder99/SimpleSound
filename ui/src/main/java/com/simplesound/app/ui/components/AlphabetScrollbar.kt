package com.simplesound.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.simplesound.app.data.model.Track
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BAR_WIDTH = 24.dp
private val BUBBLE_SIZE = 64.dp
private val BUBBLE_GAP = 12.dp

/** Vertical room each visible letter (plus the dot after it) needs on the bar. */
private val LABEL_PITCH = 44.dp
private const val MAX_LABELS = 7

/**
 * A slim "A to Z" fast-scroll bar for a name-sorted track list. It shows only a
 * handful of letters with dots between them; touching or dragging it jumps the
 * list to that letter and pops a large letter bubble beside the finger.
 *
 * [tracks] must be in Name sort order. They are rendered in [listState]'s list
 * starting at item [headerItemCount] (for any non-track header items above them).
 *
 * Place it aligned to the end of a Box overlaying the list. Only the bar itself
 * takes touches; the bubble area beside it passes them through to the list.
 */
@Composable
fun AlphabetScrollbar(
    tracks: List<Track>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    headerItemCount: Int = 0,
) {
    val itemSlots = remember(tracks) { IntArray(tracks.size) { AlphabetIndex.slotOf(tracks[it].title) } }
    val slots = remember(itemSlots) { AlphabetIndex.barSlots(itemSlots) }

    // The gesture loop outlives recompositions (e.g. a library sync landing
    // mid-drag), so it reads the latest values through these.
    val currentTracks by rememberUpdatedState(tracks)
    val currentItemSlots by rememberUpdatedState(itemSlots)
    val currentSlots by rememberUpdatedState(slots)
    val currentHeaderCount by rememberUpdatedState(headerItemCount)
    val currentListState by rememberUpdatedState(listState)

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var dragging by remember { mutableStateOf(false) }
    var touchY by remember { mutableFloatStateOf(0f) }
    var barHeightPx by remember { mutableIntStateOf(0) }
    var bubbleLabel by remember { mutableStateOf("") }
    var lastTarget by remember { mutableIntStateOf(-1) }

    fun onTouch(y: Float) {
        val list = currentTracks
        val slotList = currentSlots
        if (list.isEmpty() || slotList.isEmpty() || barHeightPx <= 0) return
        touchY = y.coerceIn(0f, barHeightPx.toFloat())
        val slotIdx = (touchY / barHeightPx * slotList.size).toInt().coerceIn(0, slotList.lastIndex)
        val target = AlphabetIndex.targetIndex(currentItemSlots, slotList[slotIdx]).coerceIn(0, list.lastIndex)
        if (target != lastTarget) {
            lastTarget = target
            scope.launch { currentListState.scrollToItem(currentHeaderCount + target) }
        }
        val label = AlphabetIndex.labelOf(list[target].title)
        if (label != bubbleLabel) {
            bubbleLabel = label
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(modifier.fillMaxHeight().width(BUBBLE_SIZE + BUBBLE_GAP + BAR_WIDTH)) {
        LetterBubble(
            visible = dragging,
            label = bubbleLabel,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val size = BUBBLE_SIZE.roundToPx()
                        val maxY = (barHeightPx - size).coerceAtLeast(0)
                        IntOffset(0, (touchY - size / 2f).roundToInt().coerceIn(0, maxY))
                    },
        )

        val tint = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        BoxWithConstraints(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(BAR_WIDTH)
                // Keep gesture-nav's edge back swipe from stealing drags that start on the bar.
                .systemGestureExclusion()
                .onSizeChanged { barHeightPx = it.height }
                .scrubGesture(
                    onStart = {
                        dragging = true
                        lastTarget = -1
                        bubbleLabel = ""
                    },
                    onMove = ::onTouch,
                    onEnd = { dragging = false },
                )
                .liquidGlass(
                    corner = BAR_WIDTH / 2,
                    tint = tint,
                    bodyAlpha = if (dragging) 0.16f else 0.06f,
                    showGloss = false,
                    showRim = dragging,
                ),
        ) {
            val labelCount = (maxHeight / LABEL_PITCH).toInt().coerceIn(2, MAX_LABELS)
            BarMarks(slots = slots, labelCount = labelCount, color = tint)
        }
    }
}

/**
 * Claims every touch that starts here (so neither the list nor the
 * HorizontalPager around the Tracks tab reacts) and reports its y position
 * until the finger lifts or the gesture is cancelled.
 */
private fun Modifier.scrubGesture(
    onStart: () -> Unit,
    onMove: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            onStart()
            try {
                onMove(down.position.y)
                var pressed = true
                while (pressed) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    change?.consume()
                    pressed = change != null && change.pressed
                    if (change != null && pressed) onMove(change.position.y)
                }
            } finally {
                onEnd()
            }
        }
    }

/** The large letter that pops up beside the finger while scrubbing. */
@Composable
private fun LetterBubble(
    visible: Boolean,
    label: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.6f),
        exit = fadeOut() + scaleOut(targetScale = 0.6f),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(BUBBLE_SIZE)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
            )
        }
    }
}

/**
 * A few letters spread over the bar with a dot between each pair, every one
 * centred on the slot it stands for so the labels line up with what a touch
 * at that height actually jumps to.
 */
@Composable
private fun BarMarks(
    slots: List<Int>,
    labelCount: Int,
    color: Color,
) {
    val marks =
        remember(slots, labelCount) {
            val positions = AlphabetIndex.labelPositions(slots.size, labelCount)

            fun center(idx: Int) = (idx + 0.5f) / slots.size
            buildList {
                positions.forEachIndexed { i, idx ->
                    add(AlphabetIndex.slotLabel(slots[idx]) to center(idx))
                    positions.getOrNull(i + 1)?.let { next -> add("•" to (center(idx) + center(next)) / 2f) }
                }
            }
        }
    Layout(
        content = {
            marks.forEach { (text, _) ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    maxLines = 1,
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, p ->
                val cy = marks[i].second * constraints.maxHeight
                p.place((constraints.maxWidth - p.width) / 2, (cy - p.height / 2f).roundToInt())
            }
        }
    }
}
