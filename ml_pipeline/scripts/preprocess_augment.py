import os
import cv2
import glob
import xml.etree.ElementTree as ET
import albumentations as A

# Note: Requires `pip install albumentations opencv-python-headless`

def get_android_mobility_augmentations():
    """
    Augmentation pipeline tailored for first-person mobility (chest/hand held).
    Simulates motion blur, varying lighting, slight perspective shifts, and camera noise.
    """
    return A.Compose([
        A.MotionBlur(p=0.3, blur_limit=7), # Frequent in walking footage
        A.RandomBrightnessContrast(p=0.4, brightness_limit=0.2, contrast_limit=0.2), # Overexposure / dark rooms
        A.ISONoise(p=0.2), # Simulates low-light sensor noise
        A.Affine(translate_percent=0.05, scale=(0.95, 1.05), rotate=(-5, 5), p=0.5), # Slight camera tilt
        A.HorizontalFlip(p=0.5) # Basic data multiplication
    ], bbox_params=A.BboxParams(format='pascal_voc', label_fields=['class_labels']))

def augment_dataset(split_dir, num_augments_per_image=2):
    """
    Reads images and Pascal VOC XMLs from the training split, applies albumentations,
    and saves the augmented images and new XMLs.
    """
    img_dir = os.path.join(split_dir, 'images')
    ann_dir = os.path.join(split_dir, 'annotations')
    
    transform = get_android_mobility_augmentations()
    img_files = glob.glob(os.path.join(img_dir, '*.jpg')) + glob.glob(os.path.join(img_dir, '*.png'))
    
    print(f"Starting Augmentation on {len(img_files)} images in {split_dir}...")
    
    for img_path in img_files:
        base_name = os.path.splitext(os.path.basename(img_path))[0]
        ext = os.path.splitext(img_path)[1]
        ann_path = os.path.join(ann_dir, base_name + '.xml')
        
        if not os.path.exists(ann_path):
            continue
            
        # Parse XML
        tree = ET.parse(ann_path)
        root = tree.getroot()
        
        bboxes = []
        class_labels = []
        for obj in root.findall('object'):
            name = obj.find('name').text
            bndbox = obj.find('bndbox')
            xmin = float(bndbox.find('xmin').text)
            ymin = float(bndbox.find('ymin').text)
            xmax = float(bndbox.find('xmax').text)
            ymax = float(bndbox.find('ymax').text)
            
            bboxes.append([xmin, ymin, xmax, ymax])
            class_labels.append(name)
            
        # Read Image
        image = cv2.imread(img_path)
        image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        
        for i in range(num_augments_per_image):
            try:
                transformed = transform(image=image, bboxes=bboxes, class_labels=class_labels)
                aug_img = transformed['image']
                aug_bboxes = transformed['bboxes']
                aug_labels = transformed['class_labels']
                
                # Save Image
                aug_base_name = f"{base_name}_aug_{i}"
                aug_img_path = os.path.join(img_dir, aug_base_name + ext)
                
                aug_img_bgr = cv2.cvtColor(aug_img, cv2.COLOR_RGB2BGR)
                cv2.imwrite(aug_img_path, aug_img_bgr)
                
                # Save XML
                aug_root = ET.Element("annotation")
                ET.SubElement(aug_root, "filename").text = aug_base_name + ext
                size = ET.SubElement(aug_root, "size")
                ET.SubElement(size, "width").text = str(aug_img.shape[1])
                ET.SubElement(size, "height").text = str(aug_img.shape[0])
                
                for bbox, label in zip(aug_bboxes, aug_labels):
                    obj = ET.SubElement(aug_root, "object")
                    ET.SubElement(obj, "name").text = label
                    bndbox = ET.SubElement(obj, "bndbox")
                    ET.SubElement(bndbox, "xmin").text = str(int(bbox[0]))
                    ET.SubElement(bndbox, "ymin").text = str(int(bbox[1]))
                    ET.SubElement(bndbox, "xmax").text = str(int(bbox[2]))
                    ET.SubElement(bndbox, "ymax").text = str(int(bbox[3]))
                    
                aug_tree = ET.ElementTree(aug_root)
                aug_tree.write(os.path.join(ann_dir, aug_base_name + '.xml'))
                
            except Exception as e:
                print(f"Error augmenting {base_name}: {e}")
                
    print("Augmentation Complete.")

if __name__ == "__main__":
    augment_dataset(os.path.join(os.path.dirname(__file__), '../dataset/splits/train'))
