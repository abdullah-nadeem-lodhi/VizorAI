# VizorAI Mobility-Aware Vision Architecture

This document outlines the redesigned machine learning and vision strategy for VizorAI, transitioning from standard COCO object detection to a robust, safety-first mobility understanding system.

## 1 & 2. Taxonomy & Approach: Object Detection vs. Geometry

A single bounding-box object detector cannot solve mobility. Bounding boxes are excellent for discrete, countable items, but fail at understanding continuous surfaces (walls, floors) or negative space (drops). VizorAI must utilize a dual-representation strategy.

### A. Semantic Detection (Bounding Box Object Detection)
Best for discrete entities with clear boundaries.
*   **Dynamic Obstacles:** person, bicycle, motorcycle, car, bus
*   **Static Obstacles:** chair, table, desk, bench, sofa, backpack/bag, bottle, trash bin, pole, traffic sign
*   **Navigation Targets:** door, elevator door (bounding boxes are effective here as these act as distinct 'portals')

### B. Spatial / Geometric Understanding (Depth Estimation / Segmentation)
Best for surfaces, boundaries, and negative space. These cannot be safely modeled with bounding boxes.
*   **Boundaries:** wall, curb, railing
*   **Elevation Changes:** stairs (up), stairs (down) / possible drop, staircase landing
*   **Free Space:** Walkable floor/path
*   **Unstructured Hazard:** Generic physical obstacle blocking the path (regardless of semantic class)

*Technical Note:* Training a bounding box for a "wall" results in massive, overlapping boxes that provide zero actionable mobility context. Walls and stairs must be processed through Monocular Depth Estimation or Semantic Segmentation.

## 3. Mobility-Decision Architecture

The raw outputs from the Vision layer (OD + Depth) DO NOT go directly to the user. They pass through a **Conservative Reasoning Layer** (the DangerEngine).

### Inputs to Reasoning Layer
*   **Object Bounding Boxes** (with temporal confidence/volatility scores)
*   **Depth Map / Geometric Mask** (estimating distance and surface changes)
*   **Walking Corridor Projection** (what is physically in front of the user's path)
*   **Device IMU / Temporal Tracking** (to stabilize world-space movement)

### Safe Output States
VizorAI operates on a principle of "guilty until proven safe." It will never output "Path clear" or "Step forward."

*   `CONTINUE_WITH_CAUTION`: Default state when no hazards are detected within the immediate corridor. (Silent or low-frequency heartbeat).
*   `OBSTACLE_LEFT` / `OBSTACLE_RIGHT`: Obstacles detected outside the immediate corridor but nearby.
*   `OBSTACLE_CENTER`: Confirmed hazard in the path.
*   `SLOW_DOWN`: Imminent corridor obstruction or high environmental complexity.
*   `STOP_AND_ASSESS`: High-confidence immediate collision risk.
*   `POSSIBLE_STAIRS` / `POSSIBLE_DROP`: Elevation geometry detected. (e.g., "Possible stairs ahead. Stop and assess.")
*   `POSSIBLE_DOORWAY`: Navigation target detected.
*   `INSUFFICIENT_INFORMATION`: Camera is covered, motion is too blurry, or lighting is too poor. (e.g., "Camera vision unclear.")

## 4. Unknown Object Handling (Temporal Volatility)
As designed in the Android `MultiFrameTracker`:
If an object occupies physical space (high geometric/tracking confidence) but classification is unstable (low OD confidence or rapidly changing labels like "laptop" -> "book" -> "tv"), the label is stripped.
*   **Output:** `OBSTACLE_CENTER`
*   **Spoken:** "Something ahead."

## 5. Dataset Requirements
To achieve this, the dataset must reflect real-world Android usage by a visually impaired user, not scraped internet photos.
*   **Perspective:** Chest-height or hand-held POV (often angled slightly downwards).
*   **Environments:** Homes, offices, classrooms, narrow corridors, city streets, crosswalks, transit stations, elevators.
*   **Conditions:** Overexposed sunlight, low-light indoor, motion blur, partial occlusions (half a chair visible).
*   **Annotation Modalities:** 
    1.  Tight bounding boxes for the discrete classes.
    2.  Polygon masks or depth-maps for stairs, walls, and walkable paths.

## 6. Training Strategy
We must train two lightweight models, or a multi-task network.
*   **Model 1 (Object Detection):** Train on the discrete class list using hard-negative mining (feeding the network images of confusing textures to reduce false positives).
*   **Model 2 (Geometry/Depth):** Train a lightweight monocular depth estimator (like FastDepth or MiDaS-small) or a 3-class segmentation model (Walkable, Boundary, Hazard) using datasets like ScanNet or NYU Depth V2.

## 7. Evaluation Strategy
mAP (Mean Average Precision) is insufficient for safety. We will evaluate:
*   **False Positive Rate (FPR) / Over-alerting:** How often does it hallucinate a hazard? (Causes alarm fatigue).
*   **False Negative Rate (FNR) on Hazards:** How often does it miss a desk directly in front? (Causes physical injury).
*   **Temporal Jitter:** Variance of bounding box coordinates/depth estimates across 30 sequential frames of a static scene.
*   **Physical Latency:** End-to-end inference time on target hardware (e.g., Pixel 6, Samsung S22).

## 8. EfficientDet-Lite0 Suitability Assessment
*   **Suitability for Semantic OD:** *Marginal to Good.* EfficientDet-Lite0 is extremely fast and small, but its 320x320 input resolution struggles with distant hazards (e.g., a thin signpost or a curb 3 meters away). We may need to step up to **EfficientDet-Lite1 (384x384)** or evaluate **YOLOv8n (TFLite exported)** for better small-object recall.
*   **Suitability for Geometry:** *Fail.* EfficientDet is an object detector. It cannot natively output depth maps or segmentation masks for walls and stairs without significant architectural hacking.

## 9. Expected Android Inference Constraints
Running a dual-model pipeline (OD + Depth) on a mobile device introduces strict constraints:
*   **Thermal Throttling:** Running models at 30 FPS will overheat the phone in minutes. We must cap inference at ~10 FPS, using the `MultiFrameTracker` to interpolate positions between inferences.
*   **Compute Delegation:** We must route the OD model to the NNAPI/NPU, and potentially run the Depth model on the GPU via OpenGL/Vulkan delegates to prevent bottlenecking a single processor.
*   **Memory:** Total combined model size must stay under ~15MB (quantized INT8 or FP16) to ensure rapid loading and low RAM pressure.
