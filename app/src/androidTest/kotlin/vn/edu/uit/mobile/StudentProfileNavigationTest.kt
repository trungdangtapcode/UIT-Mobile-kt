package vn.edu.uit.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the production account entry point without a student login in CI. */
@RunWith(AndroidJUnit4::class)
class StudentProfileNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun accountIconOpensProfileAndSettingsRemainReachable() {
        compose.onNodeWithContentDescription("Open student profile").performClick()
        compose.onNodeWithText("Student profile", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Connect your student account").assertIsDisplayed()
        compose.onNodeWithContentDescription("Manage accounts and appearance").performClick()
        compose.onNodeWithText("Accounts & appearance").assertIsDisplayed()
        compose.onAllNodesWithText("Sign in with UIT SSO").onLast().assertExists()
    }
}
