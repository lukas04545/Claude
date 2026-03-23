# Clash Royale AI Detector

An Android overlay app that uses computer vision to detect which cards the enemy plays and estimates their current elixir in real-time.

## Features

| Feature | Details |
|---|---|
| **Card detection** | Detects enemy card placements using a MobileNetV2 TFLite classifier + HSV histogram fallback |
| **Elixir tracking** | Accounting model (refill rate × time − played cards) re-synced via visual bar read |
| **Double/Triple elixir** | Automatically detected at 2:00 and 3:00 game time |
| **Elixir advantage** | Shows ± difference between your elixir and the enemy's estimate |
| **Draggable overlay** | Semi-transparent floating window, drag to reposition |

---

## Architecture

```
MainActivity
    │
    ├─ Requests SYSTEM_ALERT_WINDOW + MediaProjection permissions
    │
    ├─► ScreenCaptureService  (foreground, mediaProjection type)
    │       │  ImageReader ← VirtualDisplay ← MediaProjection
    │       │
    │       ├─► CardDetector
    │       │       Stage 1: findPlacementFlashes() — brightness blob detection
    │       │       Stage 2: TFLite classifier  OR  HSV histogram matching
    │       │
    │       └─► ElixirTracker
    │               - Accounting model (refill + deductions)
    │               - Visual elixir bar reader (HSV colour scan)
    │               - Emits GameState every ~250 ms
    │
    └─► OverlayService  (foreground, specialUse type)
            Observes GameState flow → updates floating overlay View
```

---

## Building

### Prerequisites
- Android Studio Hedgehog or later
- Android SDK 34
- A device / emulator running Android 8.0+ (API 26)

### Steps

```bash
git clone <repo>
cd ClashRoyaleDetector
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Training Your Own Model (Optional)

The app ships with an **HSV histogram fallback** that works without any trained model.
For higher accuracy, train the TFLite classifier:

### 1. Collect training data

Record Clash Royale gameplay (your device or replays), then:

```bash
cd train/
pip install opencv-python pillow tqdm
python collect_training_data.py --videos raw_videos/ --output data/unknown/
```

Manually sort the extracted frames into labelled sub-directories:

```
data/
  knight/      ← placement frames showing Knight being played
  fireball/
  hog_rider/
  ...
```

### 2. Train

```bash
pip install tensorflow
python train_card_classifier.py \
    --data data/ \
    --output ../app/src/main/assets/models/ \
    --epochs 25
```

This produces:
- `card_classifier.tflite` — INT8 quantised model (~3 MB)
- `card_labels.txt` — class index → card id mapping
- `card_histograms.json` — fallback histogram references

Rebuild the APK after adding the model asset.

---

## How Card Detection Works

```
Screen frame (250 ms interval)
    │
    ▼
cropEnemySide()          ← top 50 % of screen = enemy arena
    │
    ▼
findPlacementFlashes()   ← brightness threshold → connected components
    │                       returns bounding boxes of "flash" blobs
    ▼
For each blob:
    classifyCard()
    ├─ [if model present] TFLite MobileNetV2 → softmax scores
    └─ [fallback]         HSV histogram cosine similarity
    │
    ▼
confidence ≥ 0.65?  → emit PlayedCard + deduct elixir
```

## How Elixir Tracking Works

```
ElixirTracker.tick() called every ~250 ms:

modelElixir += refillRate × Δt          (2.8 / 5.6 / 8.4 e/s)
modelElixir -= Σ(detected card costs)   (from CardDetector)
modelElixir  = clamp(0, 10)

Optional re-sync:
  readEnemyElixirBar(bitmap)             (HSV colour scan of top bar)
  modelElixir = visual × 0.8 + model × 0.2
```

---

## Permissions Required

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw the floating overlay on top of other apps |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Run screen capture in the background |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Run overlay in the background |

---

## Limitations & Notes

- The **enemy elixir bar is not directly visible** in the game UI — the tracker estimates it from card plays and timing.  Accuracy improves as more cards are detected.
- The classifier requires **training data** to reach high accuracy; the histogram fallback gives rough results.
- Works best on **1080p** devices; very low-res screens may reduce detection accuracy.
- This app is for **personal analysis / learning** purposes only. Use responsibly.
