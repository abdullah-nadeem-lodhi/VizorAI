# VizorAI Custom Model & Uncertainty Fallback Strategy

## 1. The Python Training Pipeline (`train_vizor_detector.py`)
Because VizorAI requires lightweight, real-time edge processing on Android hardware, the training pipeline is built on **TensorFlow Lite Model Maker** utilizing the **EfficientDet-Lite0** architecture.
- **Why EfficientDet-Lite0?** It accepts a 320x320 input, is highly optimized for Android CPU/NNAPI, and compiles down to a ~4MB INT8 quantized model.
- **Evaluation:** The script automatically evaluates mAP and per-class precision against your test set before exporting the `.tflite` file.

### How to run it:
You can run the contents of `ml_pipeline/` in any Google Colab or local GPU environment:
1. `pip install -r requirements.txt`
2. Prepare your dataset in PASCAL VOC format inside `./vizor_dataset/`
3. Run `python train_vizor_detector.py`

## 2. Unknown-Object Fallback Architecture (Kotlin)
As per your constraints, we will **not** integrate the model into the Android app yet. However, when you return with the exported `.tflite` model, we will implement the uncertainty fallback in the Android tracking engine (`MultiFrameTracker.kt`), not in the ML model itself.

### The Problem with Confident Misclassifications
Standard object detectors are forced to pick the closest label from their training classes, even if it's wrong (e.g., a ping-pong net becomes an "airplane").

### The Tracking-Layer Solution
We will add a Temporal Confidence & Label Volatility filter to `TrackedDetection`:

```kotlin
data class TrackedDetection(
    val trackId: Int,
    var primaryLabel: String, 
    val labelHistory: MutableList<String>, // Tracks last 10 frames of labels
    var isUnknownFallback: Boolean = false,
    ...
)
```

**The Rules we will implement:**
1. **Low Baseline Confidence:** If a bounding box is stable across frames but the raw prediction confidence is consistently below `0.55`, it is marked as `isUnknownFallback = true`.
2. **High Label Volatility:** If the tracker matches the same bounding box across 10 frames, but the network predicts 4 different labels (e.g., "laptop", "book", "tv", "book"), the label voting fails. It is marked as `isUnknownFallback = true`.
3. **Generic Resolution:** When the `NotificationPriorityEngine` or TTS system formats the spoken alert, it will check this flag. 
   - `if (isUnknownFallback) return "Something ${spatialZone.displayName}"`
   - `else return "${primaryLabel} ${spatialZone.displayName}"`

This guarantees that VizorAI will safely say *"Something ahead"* rather than inventing specific objects, while maintaining stable spatial tracking for physical safety.
