# VizorAI Mobility Dataset Pipeline

This directory contains the data collection, organization, and validation pipeline for the **VizorAI Vision Models**. As per the mobility-aware strategy, this pipeline strictly separates *discrete Object Detection labels* from *Geometry/Segmentation labels*.

## 1. Directory Structure

```text
ml_pipeline/
├── config/
│   └── classes.yaml                # Defines the active object detection vs segmentation classes
├── dataset/
│   ├── raw/                        # ⬅️ ADD YOUR NEW DATA HERE
│   │   ├── images/                 # Raw .jpg / .png files
│   │   ├── annotations_od/         # Pascal VOC XML bounding box files
│   │   └── annotations_seg/        # (Future) Segmentation masks or depth maps
│   └── splits/                     # Generated automatically by split_dataset.py
│       ├── train/
│       ├── val/
│       └── test/
├── scripts/
│   ├── validate_dataset.py         # Validates XMLs, images, and generates stats
│   ├── split_dataset.py            # Safely divides raw data into train/val/test
│   └── preprocess_augment.py       # Applies Albumentations for Android-specific mobility noise
├── requirements.dataset.txt        # Python dependencies for the scripts
└── DATASET_README.md               # This file
```

## 2. Supported Annotation Format
Currently, the pipeline supports **Pascal VOC XML** format for Object Detection (`annotations_od`). 
- Tools like **LabelImg** or **CVAT** can export directly to Pascal VOC XML.
- Ensure the bounding box names exactly match the `object_detection` list inside `config/classes.yaml`.
- Do **not** draw bounding boxes for geometry classes (e.g., `wall`, `stairs`). These belong in `annotations_seg` as polygon masks or depth maps, which will be handled by a separate future training pipeline.

## 3. How to Add Images and Labels
1. Collect first-person perspective images (chest-mounted or hand-held POV). Prioritize real-world environments with varying lighting, corridors, elevators, and sidewalks.
2. Place all new images into `ml_pipeline/dataset/raw/images/`.
3. Draw your bounding boxes (using LabelImg/CVAT) and export the XML files into `ml_pipeline/dataset/raw/annotations_od/`. The XML filename must exactly match the image filename (e.g., `frame_001.jpg` -> `frame_001.xml`).

## 4. How to Validate the Dataset
Before training, you must validate the dataset to catch corrupted files, missing XMLs, illegal bounding boxes, or typos in class names.
1. Install dependencies: `pip install -r requirements.dataset.txt`
2. Run the validator:
   ```bash
   python ml_pipeline/scripts/validate_dataset.py
   ```
3. Read the output report. It will list the total image counts, class imbalance, and any explicit errors that need fixing before the ML pipeline can consume the data.

## 5. Splitting & Augmentation
Once validated:
1. Safely split the dataset:
   ```bash
   python ml_pipeline/scripts/split_dataset.py
   ```
   *(Note: This script groups images with similar filename prefixes to prevent near-duplicate video frames from leaking across the train and test sets.)*

2. Augment the training split (adds motion blur, low-light noise, and perspective tilts typical of Android camera movement):
   ```bash
   python ml_pipeline/scripts/preprocess_augment.py
   ```
