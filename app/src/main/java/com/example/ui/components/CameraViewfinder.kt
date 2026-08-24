package com.example.ui.components

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.domain.model.DangerLevel
import com.example.domain.model.TrackedDetection
import com.example.hardware.CameraVisionPipeline
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.BorderStrong
import com.example.ui.theme.BorderSubtle
import java.util.concurrent.Executors

@Composable
fun CameraViewfinder(
    isGuidanceActive: Boolean,
    trackedObjects: List<TrackedDetection>,
    pipeline: CameraVisionPipeline,
    isFlashEnabled: Boolean = false,
    onFlashSupportChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraControl: androidx.camera.core.CameraControl? by remember { mutableStateOf(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(
        modifier = modifier
            .background(BgCanvas)
            .border(1.dp, BorderSubtle)
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor, pipeline)
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                        val hasFlash = camera.cameraInfo.hasFlashUnit()
                        onFlashSupportChanged(hasFlash)
                        cameraControl = camera.cameraControl
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            update = { _ -> cameraControl?.enableTorch(isFlashEnabled) },
            modifier = Modifier.fillMaxSize()
        )

        // Monochromatic High-Contrast Spatial Guidance Overlay
        if (isGuidanceActive) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Draw Walking Corridor Center Lines (Dotted subtle guide)
                val corridorLeft = w * 0.32f
                val corridorRight = w * 0.68f
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)

                drawLine(
                    color = Color.White.copy(alpha = 0.25f),
                    start = Offset(corridorLeft, h * 0.40f),
                    end = Offset(corridorLeft, h),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = dashEffect
                )

                drawLine(
                    color = Color.White.copy(alpha = 0.25f),
                    start = Offset(corridorRight, h * 0.40f),
                    end = Offset(corridorRight, h),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = dashEffect
                )

                // Draw Tracked Bounding Boxes with High-Contrast Monochromatic Semantics
                for (obj in trackedObjects) {
                    val rect = obj.rect
                    val leftPx = rect.left * w
                    val topPx = rect.top * h
                    val widthPx = rect.width * w
                    val heightPx = rect.height * h

                    val (boxColor, strokeW) = when (obj.dangerLevel) {
                        DangerLevel.IMMEDIATE_HAZARD -> Pair(Color.White, 4.dp.toPx())
                        DangerLevel.CAUTION -> Pair(Color(0xFFE0E0E0), 2.5.dp.toPx())
                        DangerLevel.INFORMATION -> Pair(Color(0xFFA0A0A0), 1.5.dp.toPx())
                    }

                    drawRect(
                        color = boxColor,
                        topLeft = Offset(leftPx, topPx),
                        size = Size(widthPx, heightPx),
                        style = Stroke(width = strokeW)
                    )
                }
            }
        }
    }
}
