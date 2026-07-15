package com.jaredxwos.coralie

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jaredxwos.coralie.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 0 smoke test. Not a real feature test — HomeRoute is a placeholder
 * until Phase 5. This just pins down "app launches to an empty HomeRoute"
 * (composition root ran AppStorage.init without throwing, nav graph reached
 * Home) so a regression here surfaces immediately in later phases.
 */
@RunWith(AndroidJUnit4::class)
class Phase0SmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesToHome() {
        composeRule.onNodeWithText("Home").assertExists()
    }
}