package com.arshad.ayn.ui.tv.common

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.arshad.ayn.ui.tv.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A channel card (Home's Your channels) for a subscription without a picture shows its monogram;
 *  an icon card (the kids Find a channel tile, no picture either) keeps its icon. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class ChannelAvatarCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aChannelWithoutAPictureShowsItsMonogram() {
        compose.setContent {
            ChannelAvatarCard(name = "Veritasium Channel", avatarUrl = null, meta = null, focusKey = "channel:v", onClick = {})
        }

        compose.onNodeWithText("VC", useUnmergedTree = true).assertExists()
    }

    @Test
    fun anIconCardWithoutAPictureKeepsItsIcon() {
        compose.setContent {
            ChannelAvatarCard(
                name = "Find a channel",
                avatarUrl = null,
                meta = null,
                focusKey = "find",
                onClick = {},
                icon = R.drawable.ic_nav_search,
            )
        }

        compose.onNodeWithText("FA", useUnmergedTree = true).assertDoesNotExist()
    }
}
