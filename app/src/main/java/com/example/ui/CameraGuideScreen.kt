package com.example.ui

import android.Manifest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.DangerLevel
import com.example.domain.model.GuidanceState
import com.example.hardware.CameraVisionPipeline
import com.example.ui.components.CameraViewfinder
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.BorderFocused
import com.example.ui.theme.BorderStrong
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.DangerCautionBg
import com.example.ui.theme.DangerHazardBg
import com.example.ui.theme.DangerHazardText
import com.example.ui.theme.DangerInfoBg
import com.example.ui.theme.InverseBackground
import com.example.ui.theme.InverseText
import com.example.ui.theme.SurfaceBase
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraGuideScreen(
    state: GuidanceState,
    onToggleGuidance: () -> Unit,
    onToggleMute: () -> Unit,
    onEmergencyStop: () -> Unit,
    onDescribeSurroundings: () -> Unit,
    onVoiceCommand: () -> Unit = {},
    onPipelineResult: (List<com.example.domain.model.RawVisionDetection>, com.example.domain.model.PerformanceTelemetry) -> Unit,
    isCameraPermissionGrantedOverride: Boolean? = null
) {
    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)
    val isCameraGranted = isCameraPermissionGrantedOverride ?: cameraPermissionState.status.isGranted
    val context = androidx.compose.ui.platform.LocalContext.current

    val pipeline = remember(isCameraGranted) {
        if (isCameraGranted) {
            try {
                CameraVisionPipeline(context, onPipelineResult)
            } catch (e: Throwable) {
                null
            }
        } else null
    }

    DisposableEffect(pipeline) {
        onDispose {
            pipeline?.close()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(BgCanvas),
        color = BgCanvas
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Section: Title & Status Telemetry
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "AI CAMERA GUIDE",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            ),
                            color = TextPrimary
                        )
                        Text(
                            text = if (state.isGuidanceActive) "GUIDANCE ACTIVE" else "STANDBY",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp
                            ),
                            color = if (state.isGuidanceActive) TextPrimary else TextMuted
                        )
                    }

                    // Emergency Mute / Unmute Quick Control
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (state.isAudioMuted) SurfaceElevated else SurfaceBase)
                            .border(1.dp, if (state.isAudioMuted) BorderStrong else BorderSubtle, RoundedCornerShape(8.dp))
                            .clickable { onToggleMute() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("mute_toggle_button")
                            .semantics {
                                contentDescription = if (state.isAudioMuted) "Audio is muted. Tap to unmute." else "Audio is active. Tap to mute."
                            }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (state.isAudioMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                tint = if (state.isAudioMuted) TextMuted else TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (state.isAudioMuted) "MUTED" else "VOICE ON",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (state.isAudioMuted) TextMuted else TextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Live Semantic Safety Status Banner (Section 10 & 17)
                SafetyStateBanner(state)
            }

            // Middle Section: Camera Viewfinder or Permission Prompt
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isCameraGranted && pipeline != null) {
                    CameraViewfinder(
                        isGuidanceActive = state.isGuidanceActive,
                        trackedObjects = state.trackedObjects,
                        pipeline = pipeline,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Camera Permission Required",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Camera access is needed to detect immediate obstacles and guide safe navigation.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { cameraPermissionState.launchPermissionRequest() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = InverseBackground,
                                contentColor = InverseText
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("grant_permission_button")
                        ) {
                            Text("GRANT CAMERA ACCESS", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom Section: Core Accessible Large Actions (Section 14 & 15)
            Column(modifier = Modifier.fillMaxWidth()) {
                // Scene Description Result Card (if active)
                state.sceneDescriptionSummary?.let { summary ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(SurfaceElevated, RoundedCornerShape(8.dp))
                            .border(1.dp, BorderStrong, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = TextPrimary
                        )
                    }
                }

                // Secondary Action: "Describe Surroundings"
                OutlinedButton(
                    onClick = onDescribeSurroundings,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SurfaceBase,
                        contentColor = TextPrimary
                    ),
                    border = BorderStroke(1.dp, BorderStrong),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("describe_surroundings_button")
                        .semantics {
                            contentDescription = "Describe visible surroundings aloud"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.isDescribingScene) "ANALYZING SURROUNDINGS..." else "DESCRIBE SURROUNDINGS",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Primary Massive Accessible Action Button (Min 70dp height)
                Button(
                    onClick = onToggleGuidance,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isGuidanceActive) SurfaceElevated else InverseBackground,
                        contentColor = if (state.isGuidanceActive) TextPrimary else InverseText
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .border(
                            width = if (state.isGuidanceActive) 2.dp else 0.dp,
                            color = if (state.isGuidanceActive) BorderFocused else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .testTag("guidance_main_toggle_button")
                        .semantics {
                            contentDescription = if (state.isGuidanceActive) {
                                "Stop camera guidance. Currently active."
                            } else {
                                "Start camera guidance. Tap to begin real-time obstacle voice guidance."
                            }
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (state.isGuidanceActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (state.isGuidanceActive) "STOP CAMERA GUIDANCE" else "START CAMERA GUIDANCE",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                }

                // Safety Disclaimer Footer (Spec Section 3)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Experimental prototype. Not a white-cane replacement. When uncertain, exercise caution.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    ),
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun SafetyStateBanner(state: GuidanceState) {
    val dangerLevel = state.currentDangerLevel
    val latestAlert = state.latestAlert

    val (bgColor, textColor, borderColor, icon) = when (dangerLevel) {
        DangerLevel.IMMEDIATE_HAZARD -> Quad(
            DangerHazardBg,
            DangerHazardText,
            BorderFocused,
            Icons.Default.Warning
        )
        DangerLevel.CAUTION -> Quad(
            DangerCautionBg,
            TextPrimary,
            BorderStrong,
            Icons.Default.Warning
        )
        DangerLevel.INFORMATION -> Quad(
            DangerInfoBg,
            TextSecondary,
            BorderSubtle,
            Icons.Default.Info
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics {
                contentDescription = "Safety Status: ${dangerLevel.title}. ${latestAlert?.spokenText ?: "No nearby obstacles detected."}"
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "LEVEL ${dangerLevel.levelCode} — ${dangerLevel.title}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    ),
                    color = textColor
                )
                Text(
                    text = latestAlert?.shortDisplay ?: if (state.isGuidanceActive) "No nearby obstacles detected." else "Standby. Tap Start Guidance.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor,
                    maxLines = 1
                )
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
