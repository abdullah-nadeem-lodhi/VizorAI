import os
import glob
import xml.etree.ElementTree as ET
from PIL import Image
import yaml
import json
from collections import Counter
import hashlib

def load_classes(config_path):
    with open(config_path, 'r') as f:
        data = yaml.safe_load(f)
    return set(data.get('object_detection', []))

def get_file_hash(filepath):
    """Fallback exact file hash for duplicate detection."""
    hasher = hashlib.md5()
    with open(filepath, 'rb') as f:
        buf = f.read()
        hasher.update(buf)
    return hasher.hexdigest()

def validate_and_report(raw_dir, config_path):
    img_dir = os.path.join(raw_dir, 'images')
    ann_dir = os.path.join(raw_dir, 'annotations_od')
    
    valid_classes = load_classes(config_path)
    
    stats = {
        'total_images': 0,
        'total_annotations': 0,
        'corrupted_images': [],
        'missing_annotations': [],
        'missing_images': [],
        'invalid_bboxes': [],
        'unknown_classes': [],
        'exact_duplicates': [],
        'class_counts': Counter(),
        'resolutions': Counter()
    }

    img_files = set([f for f in os.listdir(img_dir) if f.lower().endswith(('.png', '.jpg', '.jpeg'))])
    ann_files = set([f for f in os.listdir(ann_dir) if f.lower().endswith('.xml')])
    
    # 1. Check Image Health & Duplicates
    seen_hashes = {}
    for img_name in img_files:
        img_path = os.path.join(img_dir, img_name)
        stats['total_images'] += 1
        
        try:
            with Image.open(img_path) as img:
                img.verify()
                stats['resolutions'][f"{img.width}x{img.height}"] += 1
            
            # Simple Exact Duplicate Check
            f_hash = get_file_hash(img_path)
            if f_hash in seen_hashes:
                stats['exact_duplicates'].append((img_name, seen_hashes[f_hash]))
            else:
                seen_hashes[f_hash] = img_name
                
        except Exception:
            stats['corrupted_images'].append(img_name)

    # 2. Check Annotations & Missing Links
    for img_name in img_files:
        base = os.path.splitext(img_name)[0]
        ann_name = base + '.xml'
        if ann_name not in ann_files:
            stats['missing_annotations'].append(img_name)
            
    for ann_name in ann_files:
        base = os.path.splitext(ann_name)[0]
        # Allow jpg, png, jpeg
        linked_img = None
        for ext in ['.jpg', '.jpeg', '.png']:
            if (base + ext) in img_files:
                linked_img = base + ext
                break
                
        if not linked_img:
            stats['missing_images'].append(ann_name)
            continue
            
        stats['total_annotations'] += 1
        
        # Parse XML
        ann_path = os.path.join(ann_dir, ann_name)
        try:
            tree = ET.parse(ann_path)
            root = tree.getroot()
            
            # Get Image size from XML if possible, otherwise rely on PIL
            size_node = root.find('size')
            if size_node is not None:
                width = int(size_node.find('width').text)
                height = int(size_node.find('height').text)
            else:
                with Image.open(os.path.join(img_dir, linked_img)) as img:
                    width, height = img.width, img.height
            
            for obj in root.findall('object'):
                cls_name = obj.find('name').text
                if cls_name not in valid_classes:
                    stats['unknown_classes'].append((ann_name, cls_name))
                else:
                    stats['class_counts'][cls_name] += 1
                
                bndbox = obj.find('bndbox')
                xmin = int(float(bndbox.find('xmin').text))
                ymin = int(float(bndbox.find('ymin').text))
                xmax = int(float(bndbox.find('xmax').text))
                ymax = int(float(bndbox.find('ymax').text))
                
                if xmin >= xmax or ymin >= ymax or xmin < 0 or ymin < 0 or xmax > width or ymax > height:
                    stats['invalid_bboxes'].append((ann_name, cls_name, [xmin, ymin, xmax, ymax]))
                    
        except Exception as e:
             stats['invalid_bboxes'].append((ann_name, "XML_PARSE_ERROR", str(e)))

    # Print Report
    print("========================================")
    print(" VIZOR AI DATASET VALIDATION REPORT")
    print("========================================")
    print(f"Total Images: {stats['total_images']}")
    print(f"Total Annotations: {stats['total_annotations']}")
    
    print("\n--- CLASS DISTRIBUTION ---")
    if not stats['class_counts']:
        print("No objects found.")
    for cls, count in stats['class_counts'].most_common():
        print(f"  - {cls}: {count}")
        
    print("\n--- RESOLUTIONS ---")
    for res, count in stats['resolutions'].most_common():
        print(f"  - {res}: {count}")
        
    print("\n--- ISSUES ---")
    print(f"Corrupted Images: {len(stats['corrupted_images'])}")
    print(f"Missing Annotations (Images w/o XML): {len(stats['missing_annotations'])}")
    print(f"Missing Images (XML w/o Image): {len(stats['missing_images'])}")
    print(f"Unknown Classes: {len(stats['unknown_classes'])}")
    print(f"Invalid Bounding Boxes: {len(stats['invalid_bboxes'])}")
    print(f"Exact Image Duplicates: {len(stats['exact_duplicates'])}")
    
    if len(stats['unknown_classes']) > 0:
        print("\nUnknown Class Details:")
        for ann, cls in stats['unknown_classes'][:10]:
            print(f"  {ann}: '{cls}'")
        if len(stats['unknown_classes']) > 10: print("  ...")
            
    if len(stats['invalid_bboxes']) > 0:
        print("\nInvalid BBox Details:")
        for ann, cls, box in stats['invalid_bboxes'][:10]:
            print(f"  {ann}: {cls} -> {box}")
        if len(stats['invalid_bboxes']) > 10: print("  ...")

    print("========================================")

if __name__ == "__main__":
    validate_and_report(
        raw_dir=os.path.join(os.path.dirname(__file__), '../dataset/raw'),
        config_path=os.path.join(os.path.dirname(__file__), '../config/classes.yaml')
    )
