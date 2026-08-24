package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.domain.model.DangerLevel
import com.example.domain.model.GuidanceAlert
import com.example.domain.model.GuidanceState
import com.example.ui.CameraGuideScreen
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun camera_guide_screen_screenshot() {
        val testState = GuidanceState(
            isGuidanceActive = true,
            isAudioMuted = false,
            currentDangerLevel = DangerLevel.INFORMATION,
            latestAlert = GuidanceAlert(
                id = "1",
                level = DangerLevel.INFORMATION,
                spokenText = "Door approximately three meters ahead.",
                shortDisplay = "Door ahead (~3m)"
            )
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                CameraGuideScreen(
                    state = testState,
                    onToggleGuidance = {},
                    onToggleMute = {},
                        onToggleFlash = {},
                        onFlashSupportChanged = {},
                    onEmergencyStop = {},
                    onDescribeSurroundings = {},
                    onPipelineResult = { _, _ -> },
                    isCameraPermissionGrantedOverride = false
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
    }
}
