package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.hardware.TfliteObjectDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun testAppTitle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AI Camera Guide", appName)
    }

    @Test
    fun testModelAndLabelsAssetsExist() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = context.assets.list("")?.toList() ?: emptyList()
        assertTrue("detect.tflite must exist in assets", assets.contains("detect.tflite"))
        assertTrue("labelmap.txt must exist in assets", assets.contains("labelmap.txt"))

        val fd = context.assets.openFd("detect.tflite")
        assertTrue("detect.tflite file size must be > 1MB", fd.length > 1_000_000L)
    }

    @Test
    fun testTfliteObjectDetector_LoadsLabels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = TfliteObjectDetector(context)

        val supportedLabels = detector.getSupportedLabels()
        assertTrue("Labels must not be empty", supportedLabels.isNotEmpty())

        // Verify key real COCO navigation classes exist directly in the model labelmap
        assertTrue("Model must contain person", supportedLabels.contains("person"))
        assertTrue("Model must contain bicycle", supportedLabels.contains("bicycle"))
        assertTrue("Model must contain car", supportedLabels.contains("car"))
        assertTrue("Model must contain motorcycle", supportedLabels.contains("motorcycle"))
        assertTrue("Model must contain bus", supportedLabels.contains("bus"))
        assertTrue("Model must contain truck", supportedLabels.contains("truck"))
        assertTrue("Model must contain chair", supportedLabels.contains("chair"))
        assertTrue("Model must contain couch", supportedLabels.contains("couch"))
        assertTrue("Model must contain dining table", supportedLabels.contains("dining table"))
        assertTrue("Model must contain bench", supportedLabels.contains("bench"))

        detector.close()
    }

    @Test
    fun testViewModel_VoiceCommandProcessing() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = MainViewModel(application)

        // 1. Unrecognized voice input -> "I didn't understand."
        viewModel.processVoiceTranscript("what is the weather")
        assertEquals("I didn't understand.", viewModel.guidanceState.value.sceneDescriptionSummary)

        // 2. Valid "Describe surroundings" with no objects -> "I couldn't identify enough of the surroundings."
        viewModel.processVoiceTranscript("describe surroundings")
        assertEquals("I couldn't identify enough of the surroundings.", viewModel.guidanceState.value.sceneDescriptionSummary)

        // 3. Valid "Find the chair" with no objects -> "I don't currently see a chair."
        viewModel.processVoiceTranscript("Where is the chair?")
        assertEquals("I don't currently see a chair.", viewModel.guidanceState.value.sceneDescriptionSummary)

        // 4. Unsupported "Find the door" -> "I can't reliably search for that object yet."
        viewModel.processVoiceTranscript("Find the door.")
        assertEquals("I can't reliably search for that object yet.", viewModel.guidanceState.value.sceneDescriptionSummary)

        // 5. Valid "Suggest path" / "Which way" with no objects -> "Limited visibility. I can't determine a preferred direction."
        viewModel.processVoiceTranscript("Which way should I go?")
        assertEquals("Limited visibility. I can't determine a preferred direction.", viewModel.guidanceState.value.sceneDescriptionSummary)
    }
}
