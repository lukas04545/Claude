"""
collect_training_data.py
========================
Helper script to automatically crop card-placement frames from Clash Royale
screen recordings and organise them into the labelled dataset structure
expected by train_card_classifier.py.

Usage
-----
1. Record your (or an enemy's) games with ADB screencap or a screen recorder.
2. Place video files in raw_videos/
3. Run:
     python collect_training_data.py --videos raw_videos/ --output data/

Dependencies:
  pip install opencv-python pillow tqdm

Manual labelling:
  After running, review data/<unknown>/ and move frames into the correct
  card id sub-directory (e.g. data/knight/, data/fireball/, …).
"""

import argparse
import os
import pathlib
import json
from tqdm import tqdm

try:
    import cv2
    CV2_AVAILABLE = True
except ImportError:
    CV2_AVAILABLE = False

try:
    from PIL import Image
    PIL_AVAILABLE = True
except ImportError:
    PIL_AVAILABLE = False


# Arena coordinates as fractions of frame size (portrait 9:19 aspect)
# Enemy side = top 45 % of screen
ENEMY_TOP    = 0.0
ENEMY_BOTTOM = 0.45

# A "placement flash" is detected by looking for frames that are significantly
# brighter in the enemy region than average — then we save a crop.
BRIGHTNESS_DELTA_THRESHOLD = 30   # pixel value increase vs rolling average


def extract_placement_frames(
    video_path: str,
    output_dir: str,
    sample_every_n: int = 3,
    brightness_threshold: float = BRIGHTNESS_DELTA_THRESHOLD
) -> int:
    """
    Extract frames that contain a placement event from [video_path].
    Returns number of frames saved.
    """
    if not CV2_AVAILABLE:
        print("[ERROR] OpenCV not installed. Run: pip install opencv-python")
        return 0

    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        print(f"[ERROR] Cannot open {video_path}")
        return 0

    os.makedirs(output_dir, exist_ok=True)
    video_name = pathlib.Path(video_path).stem

    frame_idx   = 0
    saved       = 0
    prev_bright = None

    fps    = cap.get(cv2.CAP_PROP_FPS) or 30
    total  = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

    with tqdm(total=total, desc=f"Processing {video_name}") as pbar:
        while True:
            ret, frame = cap.read()
            if not ret:
                break

            pbar.update(1)
            frame_idx += 1

            if frame_idx % sample_every_n != 0:
                continue

            h, w = frame.shape[:2]
            enemy_h = int(h * ENEMY_BOTTOM)
            enemy_region = frame[0:enemy_h, :]

            # Mean brightness of enemy region
            grey   = cv2.cvtColor(enemy_region, cv2.COLOR_BGR2GRAY)
            bright = float(grey.mean())

            # Detect sudden brightness spike (placement flash)
            if prev_bright is not None and (bright - prev_bright) > brightness_threshold:
                out_path = os.path.join(output_dir, f"{video_name}_f{frame_idx:06d}.png")
                cv2.imwrite(out_path, enemy_region)
                saved += 1

            prev_bright = bright

    cap.release()
    print(f"  → Saved {saved} placement frames from {video_name}")
    return saved


def main():
    parser = argparse.ArgumentParser(description="Collect Clash Royale training frames")
    parser.add_argument("--videos", default="raw_videos/", help="Directory of input videos")
    parser.add_argument("--output", default="data/unknown/", help="Output directory for frames")
    parser.add_argument("--sample-every", type=int, default=3,
                        help="Process every N-th frame (default: 3)")
    parser.add_argument("--threshold", type=float, default=BRIGHTNESS_DELTA_THRESHOLD,
                        help="Brightness spike threshold for placement detection")
    args = parser.parse_args()

    videos = list(pathlib.Path(args.videos).glob("*.mp4")) + \
             list(pathlib.Path(args.videos).glob("*.mkv")) + \
             list(pathlib.Path(args.videos).glob("*.avi"))

    if not videos:
        print(f"[WARN] No video files found in {args.videos}")
        return

    total_saved = 0
    for v in videos:
        total_saved += extract_placement_frames(
            str(v), args.output, args.sample_every, args.threshold
        )

    print(f"\nTotal frames saved: {total_saved}")
    print(f"Next step: manually sort frames in '{args.output}' into labelled sub-directories.")
    print("Example:\n  mv data/unknown/game1_f000123.png data/knight/")


if __name__ == "__main__":
    main()
