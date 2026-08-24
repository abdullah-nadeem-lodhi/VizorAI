import os
import tensorflow as tf
from tflite_model_maker.config import QuantizationConfig
from tflite_model_maker.config import ExportFormat
from tflite_model_maker import model_spec
from tflite_model_maker import object_detector

# ==============================================================================
# VizorAI Custom Object Detector Training Pipeline
# Architecture: EfficientDet-Lite0 (Optimized for Mobile/Android TFLite)
# ==============================================================================

def main():
    # 1. Define target classes based on VizorAI specification
    # These represent the reliable categories. Unreliable detections will be 
    # masked by our temporal tracking fallback on the Android side.
    classes = [
        'person', 'chair', 'table', 'desk', 'bench', 'sofa', 'bed', 
        'backpack', 'bag', 'bottle', 'cup', 'laptop', 'phone', 
        'bicycle', 'motorcycle', 'car', 'bus', 'traffic sign', 
        'plant', 'trash bin'
    ]
    print(f"Configured for {len(classes)} classes.")

    # 2. Select the lightweight model architecture
    # EfficientDet-Lite0 takes 320x320 input and has very low latency (~10ms on EdgeTPU/Hexagon)
    spec = model_spec.get('efficientdet_lite0')

    # 3. Load the Dataset
    # Expecting dataset in standard PASCAL VOC format or CSV format.
    # Replace 'dataset_path' with the actual path to your labeled images.
    dataset_path = './vizor_dataset'
    
    if not os.path.exists(dataset_path):
        print(f"ERROR: Dataset directory '{dataset_path}' not found.")
        print("Please prepare your dataset with images in diverse lighting, distances, and angles.")
        return

    print("Loading datasets...")
    train_data = object_detector.DataLoader.from_pascal_voc(
        f'{dataset_path}/train/images',
        f'{dataset_path}/train/annotations',
        label_map=classes
    )
    
    val_data = object_detector.DataLoader.from_pascal_voc(
        f'{dataset_path}/val/images',
        f'{dataset_path}/val/annotations',
        label_map=classes
    )
    
    test_data = object_detector.DataLoader.from_pascal_voc(
        f'{dataset_path}/test/images',
        f'{dataset_path}/test/annotations',
        label_map=classes
    )

    # 4. Train the Model (Transfer Learning)
    # We freeze the backbone initially and only train the head, then fine-tune.
    print("Starting Transfer Learning...")
    model = object_detector.create(
        train_data,
        model_spec=spec,
        batch_size=16,
        train_whole_model=True, # Fine-tunes the entire model for better accuracy on custom classes
        epochs=50,
        validation_data=val_data
    )

    # 5. Evaluate the Model (mAP, Precision, Recall)
    print("\n--- Evaluating Model ---")
    evaluation_result = model.evaluate(test_data)
    
    print("\nEvaluation Results:")
    for key, value in evaluation_result.items():
        print(f"{key}: {value:.4f}")

    # 6. Export to TFLite with Post-Training Quantization
    # INT8 Quantization drastically reduces size and improves latency on Android NNAPI
    print("\n--- Exporting TFLite Model ---")
    export_dir = './exported_model'
    quant_config = QuantizationConfig.for_int8()
    
    model.export(
        export_dir=export_dir,
        tflite_filename='vizor_detector_quant.tflite',
        quantization_config=quant_config,
        export_format=[ExportFormat.TFLITE, ExportFormat.LABEL]
    )
    
    print(f"Success! Model and labels exported to {export_dir}")
    print("Size:", os.path.getsize(f"{export_dir}/vizor_detector_quant.tflite") / (1024*1024), "MB")

if __name__ == '__main__':
    main()
