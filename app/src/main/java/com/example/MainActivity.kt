package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.CameraGuideScreen
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BgCanvas
                ) {
                    val state by viewModel.guidanceState.collectAsStateWithLifecycle()
                    CameraGuideScreen(
                        state = state,
                        onToggleGuidance = { viewModel.toggleGuidance() },
                        onToggleMute = { viewModel.toggleMute() },
                        onToggleFlash = { viewModel.toggleFlash() },
                        onFlashSupportChanged = { viewModel.setFlashSupported(it) },
                        onEmergencyStop = { viewModel.emergencyStopOrSilence() },
                        onDescribeSurroundings = { viewModel.describeSurroundings() },
                        onVoiceCommand = { viewModel.startVoiceListening() },
                        onPipelineResult = { detections, telemetry ->
                            viewModel.processFrameDetections(detections, telemetry)
                        }
                    )
                }
            }
        }
    }
}
