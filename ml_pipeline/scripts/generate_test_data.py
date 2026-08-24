import os
import shutil
from PIL import Image

def create_xml(filename, img_width, img_height, bboxes):
    xml_content = f"""<annotation>
    <filename>{filename}</filename>
    <size>
        <width>{img_width}</width>
        <height>{img_height}</height>
        <depth>3</depth>
    </size>
"""
    for bbox in bboxes:
        xml_content += f"""    <object>
        <name>{bbox['name']}</name>
        <bndbox>
            <xmin>{bbox['xmin']}</xmin>
            <ymin>{bbox['ymin']}</ymin>
            <xmax>{bbox['xmax']}</xmax>
            <ymax>{bbox['ymax']}</ymax>
        </bndbox>
    </object>
"""
    xml_content += "</annotation>"
    return xml_content

def main():
    base_dir = os.path.join(os.path.dirname(__file__), '../dataset/raw')
    img_dir = os.path.join(base_dir, 'images')
    ann_dir = os.path.join(base_dir, 'annotations_od')
    
    os.makedirs(img_dir, exist_ok=True)
    os.makedirs(ann_dir, exist_ok=True)
    
    # 1. Valid Images
    for i in range(1, 4):
        img_name = f"valid_img_{i}.jpg"
        Image.new('RGB', (300, 300), color = 'blue').save(os.path.join(img_dir, img_name))
        bboxes = [{'name': 'person', 'xmin': 10, 'ymin': 10, 'xmax': 50, 'ymax': 100}]
        with open(os.path.join(ann_dir, img_name.replace('.jpg', '.xml')), 'w') as f:
            f.write(create_xml(img_name, 300, 300, bboxes))

    # 2. Missing Annotation (no XML)
    Image.new('RGB', (300, 300), color = 'red').save(os.path.join(img_dir, 'missing_ann.jpg'))

    # 3. Invalid BBox (xmin > xmax)
    Image.new('RGB', (300, 300), color = 'green').save(os.path.join(img_dir, 'invalid_bbox.jpg'))
    bboxes = [{'name': 'chair', 'xmin': 100, 'ymin': 100, 'xmax': 50, 'ymax': 150}] # xmin > xmax
    with open(os.path.join(ann_dir, 'invalid_bbox.xml'), 'w') as f:
        f.write(create_xml('invalid_bbox.jpg', 300, 300, bboxes))

    # 4. Unknown Class
    Image.new('RGB', (300, 300), color = 'yellow').save(os.path.join(img_dir, 'unknown_cls.jpg'))
    bboxes = [{'name': 'spaceship', 'xmin': 10, 'ymin': 10, 'xmax': 50, 'ymax': 50}]
    with open(os.path.join(ann_dir, 'unknown_cls.xml'), 'w') as f:
        f.write(create_xml('unknown_cls.jpg', 300, 300, bboxes))

    # 5. Near-duplicate frame names (e.g. from a video)
    for i in range(1, 6):
        img_name = f"videoA_frame_{i}.jpg"
        Image.new('RGB', (300, 300), color = 'black').save(os.path.join(img_dir, img_name))
        bboxes = [{'name': 'door', 'xmin': 20, 'ymin': 20, 'xmax': 80, 'ymax': 200}]
        with open(os.path.join(ann_dir, img_name.replace('.jpg', '.xml')), 'w') as f:
            f.write(create_xml(img_name, 300, 300, bboxes))

    # Create more distinct prefixes to ensure train/val/test splits get populated
    for prefix in ['seqB', 'seqC', 'seqD', 'seqE', 'seqF', 'seqG', 'seqH', 'seqI', 'seqJ']:
        img_name = f"{prefix}_img_1.jpg"
        Image.new('RGB', (300, 300), color = 'white').save(os.path.join(img_dir, img_name))
        bboxes = [{'name': 'table', 'xmin': 10, 'ymin': 10, 'xmax': 100, 'ymax': 100}]
        with open(os.path.join(ann_dir, img_name.replace('.jpg', '.xml')), 'w') as f:
            f.write(create_xml(img_name, 300, 300, bboxes))

if __name__ == '__main__':
    main()
