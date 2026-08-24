package com.example.hardware

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.domain.model.NormalizedRect
import com.example.domain.model.ObjectCategory
import com.example.domain.model.RawVisionDetection
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Real On-Device TensorFlow Lite Object Detector.
 * Executes offline MobileNet SSD (quantized) bundled in assets (`detect.tflite` & `labelmap.txt`).
 * Directly yields real physical bounding boxes, genuine COCO class labels, and real model confidence scores.
 */
class TfliteObjectDetector(private val context: Context) {

    companion object {
        private const val TAG = "TfliteObjectDetector"
    }

    private var interpreter: Interpreter? = null
    private val labels = mutableListOf<String>()

    private val inputSize = 300 // MobileNet SSD input 300x300
    private val maxDetections = 10
    private val confidenceThreshold = 0.38f

    // Input buffer: [1, 300, 300, 3] bytes for uint8 quantized model
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3).apply {
        order(ByteOrder.nativeOrder())
    }

    // Output buffers for MobileNet SSD
    // Output 0: Locations [1, 10, 4] -> [top, left, bottom, right]
    private val outputLocations = Array(1) { Array(maxDetections) { FloatArray(4) } }
    // Output 1: Classes [1, 10]
    private val outputClasses = Array(1) { FloatArray(maxDetections) }
    // Output 2: Scores [1, 10]
    private val outputScores = Array(1) { FloatArray(maxDetections) }
    // Output 3: Number of detections [1]
    private val numDetections = FloatArray(1)

    private val outputMap = HashMap<Int, Any>()

    init {
        loadModel()
        loadLabels()
        outputMap[0] = outputLocations
        outputMap[1] = outputClasses
        outputMap[2] = outputScores
        outputMap[3] = numDetections
    }

    private fun loadModel() {
        try {
            val fileDescriptor = context.assets.openFd("detect.tflite")
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val mappedByteBuffer: MappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(mappedByteBuffer, options)
            Log.d(TAG, "TFLite model loaded successfully from detect.tflite (size=${declaredLength}B)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize TFLite Interpreter from detect.tflite", t)
            interpreter = null
        }
    }

    private fun loadLabels() {
        try {
            context.assets.open("labelmap.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    labels.add(line.trim())
                }
            }
            Log.d(TAG, "Loaded ${labels.size} labels from labelmap.txt")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load labelmap.txt", t)
        }
    }

    fun isModelLoaded(): Boolean = interpreter != null && labels.isNotEmpty()

    fun getSupportedLabels(): List<String> = labels.filter { it != "???" }

    /**
     * Executes real inference on an ImageProxy frame from CameraX.
     */
    @Synchronized
    fun detect(imageProxy: ImageProxy): List<RawVisionDetection> {
        val currentInterpreter = interpreter ?: run {
            Log.w(TAG, "Interpreter not initialized, skipping detection")
            return emptyList()
        }
        val timestamp = System.currentTimeMillis()

        val bitmap = imageProxyToBitmap(imageProxy) ?: return emptyList()
        
        // Handle camera orientation: physical sensors are rotated 90/270 deg in portrait
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val orientedBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val resizedBitmap = if (orientedBitmap.width == inputSize && orientedBitmap.height == inputSize) {
            orientedBitmap
        } else {
            Bitmap.createScaledBitmap(orientedBitmap, inputSize, inputSize, true)
        }

        // Fill input buffer with RGB bytes (quantized [0..255])
        inputBuffer.rewind()
        val pixels = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF).toByte()
            val g = (pixel shr 8 and 0xFF).toByte()
            val b = (pixel and 0xFF).toByte()
            inputBuffer.put(r)
            inputBuffer.put(g)
            inputBuffer.put(b)
        }

        val inputArray = arrayOf<Any>(inputBuffer)
        currentInterpreter.runForMultipleInputsOutputs(inputArray, outputMap)

        val count = numDetections[0].toInt().coerceIn(0, maxDetections)
        val detections = mutableListOf<RawVisionDetection>()

        for (i in 0 until count) {
            val score = outputScores[0][i]
            if (score < confidenceThreshold) continue

            val top = outputLocations[0][i][0].coerceIn(0f, 1f)
            val left = outputLocations[0][i][1].coerceIn(0f, 1f)
            val bottom = outputLocations[0][i][2].coerceIn(0f, 1f)
            val right = outputLocations[0][i][3].coerceIn(0f, 1f)

            if (right <= left || bottom <= top) continue

            val classIndex = outputClasses[0][i].toInt()
            // COCO MobileNet SSD label indexing: classIndex + 1 corresponds to labelmap
            val rawLabel = when {
                classIndex + 1 in labels.indices -> labels[classIndex + 1]
                classIndex in labels.indices -> labels[classIndex]
                else -> "Obstacle"
            }

            if (rawLabel == "???") continue

            val (category, formattedLabel) = mapCocoLabel(rawLabel)

            detections.add(
                RawVisionDetection(
                    category = category,
                    label = formattedLabel,
                    confidence = score,
                    rect = NormalizedRect(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom
                    ),
                    timestamp = timestamp
                )
            )
        }

        if (detections.isNotEmpty()) {
            Log.d(TAG, "Detections: ${detections.size} objects (top: ${detections.first().label} @ ${"%.2f".format(detections.first().confidence)})")
        }

        return detections
    }

    /**
     * Direct mapping of real COCO model labels to domain ObjectCategory without heuristic fabricating.
     */
    private fun mapCocoLabel(rawLabel: String): Pair<ObjectCategory, String> {
        val lower = rawLabel.lowercase().trim()
        val capitalized = lower.replaceFirstChar { it.uppercase() }

        return when (lower) {
            "person" -> Pair(ObjectCategory.PERSON, "Person")
            "car" -> Pair(ObjectCategory.VEHICLE, "Car")
            "bus" -> Pair(ObjectCategory.VEHICLE, "Bus")
            "truck" -> Pair(ObjectCategory.VEHICLE, "Truck")
            "motorcycle" -> Pair(ObjectCategory.MOTORCYCLE, "Motorcycle")
            "bicycle" -> Pair(ObjectCategory.BICYCLE, "Bicycle")
            "chair" -> Pair(ObjectCategory.CHAIR, "Chair")
            "couch" -> Pair(ObjectCategory.CHAIR, "Couch")
            "dining table" -> Pair(ObjectCategory.TABLE, "Dining Table")
            "bench" -> Pair(ObjectCategory.OBSTACLE, "Bench")
            "traffic light" -> Pair(ObjectCategory.OBSTACLE, "Traffic Light")
            "stop sign" -> Pair(ObjectCategory.OBSTACLE, "Stop Sign")
            "fire hydrant" -> Pair(ObjectCategory.OBSTACLE, "Fire Hydrant")
            "backpack" -> Pair(ObjectCategory.OBSTACLE, "Backpack")
            "umbrella" -> Pair(ObjectCategory.OBSTACLE, "Umbrella")
            "suitcase" -> Pair(ObjectCategory.OBSTACLE, "Suitcase")
            "bottle" -> Pair(ObjectCategory.OBSTACLE, "Bottle")
            "potted plant" -> Pair(ObjectCategory.OBSTACLE, "Potted Plant")
            else -> Pair(ObjectCategory.OBSTACLE, capitalized)
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            image.toBitmap()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed converting ImageProxy to Bitmap", e)
            null
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
