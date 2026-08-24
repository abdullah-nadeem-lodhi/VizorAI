import os
import shutil
import random
import glob

def split_dataset(raw_dir, splits_dir, train_ratio=0.7, val_ratio=0.15):
    """
    Splits the dataset into train/val/test.
    To prevent near-duplicate leakage (e.g., extracted video frames), 
    it groups files by a shared prefix if they contain frame numbers.
    Otherwise, it falls back to random image-level splitting.
    """
    img_dir = os.path.join(raw_dir, 'images')
    ann_dir = os.path.join(raw_dir, 'annotations_od')
    
    # Setup Output Dirs
    for split in ['train', 'val', 'test']:
        os.makedirs(os.path.join(splits_dir, split, 'images'), exist_ok=True)
        os.makedirs(os.path.join(splits_dir, split, 'annotations'), exist_ok=True)
        
    img_files = [f for f in os.listdir(img_dir) if f.lower().endswith(('.png', '.jpg', '.jpeg'))]
    
    # Group by base prefix to avoid leaking consecutive video frames
    # e.g., "corridor_walk_001.jpg" and "corridor_walk_002.jpg" -> "corridor_walk"
    groups = {}
    for img in img_files:
        base_name = os.path.splitext(img)[0]
        # Attempt to strip trailing numbers/underscores to find the video source
        prefix = base_name.rstrip('0123456789-_')
        if not prefix:
            prefix = base_name # Fallback
            
        if prefix not in groups:
            groups[prefix] = []
        groups[prefix].append(base_name)
        
    group_keys = list(groups.keys())
    random.shuffle(group_keys)
    
    total_groups = len(group_keys)
    train_idx = int(total_groups * train_ratio)
    val_idx = train_idx + int(total_groups * val_ratio)
    
    train_groups = group_keys[:train_idx]
    val_groups = group_keys[train_idx:val_idx]
    test_groups = group_keys[val_idx:]
    
    def copy_split(groups_list, split_name):
        count = 0
        for g in groups_list:
            for base_name in groups[g]:
                # Find the actual image extension
                img_src = None
                for ext in ['.jpg', '.jpeg', '.png']:
                    if os.path.exists(os.path.join(img_dir, base_name + ext)):
                        img_src = base_name + ext
                        break
                
                ann_src = base_name + '.xml'
                ann_path = os.path.join(ann_dir, ann_src)
                
                if img_src and os.path.exists(ann_path):
                    shutil.copy2(
                        os.path.join(img_dir, img_src), 
                        os.path.join(splits_dir, split_name, 'images', img_src)
                    )
                    shutil.copy2(
                        ann_path,
                        os.path.join(splits_dir, split_name, 'annotations', ann_src)
                    )
                    count += 1
        return count

    train_c = copy_split(train_groups, 'train')
    val_c = copy_split(val_groups, 'val')
    test_c = copy_split(test_groups, 'test')
    
    print(f"Dataset Split Complete:")
    print(f"  Train: {train_c} images")
    print(f"  Val:   {val_c} images")
    print(f"  Test:  {test_c} images")

if __name__ == "__main__":
    split_dataset(
        raw_dir=os.path.join(os.path.dirname(__file__), '../dataset/raw'),
        splits_dir=os.path.join(os.path.dirname(__file__), '../dataset/splits')
    )
