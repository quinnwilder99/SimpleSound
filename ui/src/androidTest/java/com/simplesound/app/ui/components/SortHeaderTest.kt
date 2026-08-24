package com.simplesound.app.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.ui.theme.SimpleSoundTheme
import org.junit.Rule
import org.junit.Test

/**
 * [SortHeader] takes plain lambdas and no ViewModel/CompositionLocal, making it the
 * simplest, most isolated screen-level composable to test end to end.
 */
class SortHeaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingTheSortLabelOpensTheMenuWithEveryOption() {
        composeRule.setContent {
            SimpleSoundTheme {
                SortHeader(
                    current = SortOption.DATE_ADDED,
                    onSort = {},
                    onShuffle = {},
                    onPlayAll = {},
                )
            }
        }

        composeRule.onNodeWithText(SortOption.DATE_ADDED.label).performClick()

        // current == DATE_ADDED, so once the menu is open its label appears twice
        // (the always-visible header label, plus its own dropdown item) -- assert
        // "at least one" rather than the single-match onNodeWithText default.
        SortOption.entries.forEach { option ->
            composeRule.onAllNodesWithText(option.label).onFirst().assertExists()
        }
    }

    @Test
    fun tappingAMenuItemReportsThatOptionAndClosesTheMenu() {
        var selected: SortOption? = null
        composeRule.setContent {
            SimpleSoundTheme {
                SortHeader(
                    current = SortOption.DATE_ADDED,
                    onSort = { selected = it },
                    onShuffle = {},
                    onPlayAll = {},
                )
            }
        }

        composeRule.onNodeWithText(SortOption.DATE_ADDED.label).performClick()
        composeRule.onNodeWithText(SortOption.NAME.label).performClick()

        assert(selected == SortOption.NAME) { "expected NAME, got $selected" }
        // The menu is dismissed, so only the always-visible current-sort label
        // remains — the other options' menu items should be gone from the tree.
        composeRule.onNodeWithText(SortOption.ARTIST.label).assertDoesNotExist()
    }

    @Test
    fun shuffleAndPlayAllButtonsFireTheirOwnCallbacks() {
        var shuffled = false
        var playedAll = false
        composeRule.setContent {
            SimpleSoundTheme {
                SortHeader(
                    current = SortOption.DATE_ADDED,
                    onSort = {},
                    onShuffle = { shuffled = true },
                    onPlayAll = { playedAll = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Shuffle").performClick()
        assert(shuffled) { "expected onShuffle to have fired" }

        composeRule.onNodeWithContentDescription("Play all").performClick()
        assert(playedAll) { "expected onPlayAll to have fired" }
    }

    @Test
    fun restrictedOptionsListHidesCustomOrder() {
        composeRule.setContent {
            SimpleSoundTheme {
                SortHeader(
                    current = SortOption.DATE_ADDED,
                    onSort = {},
                    onShuffle = {},
                    onPlayAll = {},
                    options = SortOption.entries.filterNot { it == SortOption.CUSTOM_ORDER },
                )
            }
        }

        composeRule.onNodeWithText(SortOption.DATE_ADDED.label).performClick()
        composeRule.onNodeWithText(SortOption.CUSTOM_ORDER.label).assertDoesNotExist()
    }
}
