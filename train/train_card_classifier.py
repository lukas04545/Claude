"""
train_card_classifier.py
========================
Trains a MobileNetV2-based card classifier on Clash Royale card screenshots
and exports a TensorFlow Lite model for on-device inference.

Usage
-----
1. Collect screenshots:
   Place card placement screenshots in:
     data/
       <card_id>/
         frame_001.png
         frame_002.png
         ...
   Each sub-directory name must match a card id in CardRegistry.

2. Install dependencies:
   pip install tensorflow pillow numpy tqdm

3. Run:
   python train_card_classifier.py --data data/ --output ../app/src/main/assets/models/

The script will produce:
  card_classifier.tflite   — quantised INT8 model for Android
  card_labels.txt          — one card_id per line, matching model output order
  card_histograms.json     — colour histogram references for the fallback detector
"""

import argparse
import json
import os
import pathlib
import numpy as np
from tqdm import tqdm

# ---------------------------------------------------------------------------
# Optional heavy imports — only needed for full training
# ---------------------------------------------------------------------------
try:
    import tensorflow as tf
    from tensorflow.keras import layers, Model
    from tensorflow.keras.applications import MobileNetV2
    from tensorflow.keras.preprocessing.image import ImageDataGenerator
    TF_AVAILABLE = True
except ImportError:
    TF_AVAILABLE = False

try:
    from PIL import Image
    PIL_AVAILABLE = True
except ImportError:
    PIL_AVAILABLE = False

# ---------------------------------------------------------------------------
IMG_SIZE   = 224
BATCH_SIZE = 32
EPOCHS     = 20


def build_model(num_classes: int) -> "tf.keras.Model":
    base = MobileNetV2(
        input_shape=(IMG_SIZE, IMG_SIZE, 3),
        include_top=False,
        weights="imagenet"
    )
    # Fine-tune last 30 layers
    for layer in base.layers[:-30]:
        layer.trainable = False

    x = base.output
    x = layers.GlobalAveragePooling2D()(x)
    x = layers.Dropout(0.3)(x)
    x = layers.Dense(256, activation="relu")(x)
    x = layers.Dropout(0.2)(x)
    outputs = layers.Dense(num_classes, activation="softmax")(x)
    return Model(base.input, outputs)


def export_tflite(model: "tf.keras.Model", output_dir: str, representative_data_gen):
    """Convert to INT8 quantised TFLite model."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_data_gen
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type  = tf.uint8
    converter.inference_output_type = tf.uint8
    tflite_model = converter.convert()
    out_path = os.path.join(output_dir, "card_classifier.tflite")
    with open(out_path, "wb") as f:
        f.write(tflite_model)
    print(f"[OK] TFLite model saved → {out_path}")


def build_histograms(data_dir: str, labels: list[str], bins: int = 64) -> dict:
    """
    Compute average HSV hue histograms for each card class.
    These are used as a fast fallback when the TFLite model is unavailable.
    """
    if not PIL_AVAILABLE:
        print("[WARN] Pillow not available — skipping histogram generation")
        return {}

    histograms = {}
    for card_id in tqdm(labels, desc="Building histograms"):
        card_dir = pathlib.Path(data_dir) / card_id
        if not card_dir.is_dir():
            continue
        hists = []
        for img_path in card_dir.glob("*.png"):
            try:
                img = Image.open(img_path).convert("HSV").resize((64, 64))
                arr = np.array(img)[:, :, 0].flatten()          # hue channel
                h, _ = np.histogram(arr, bins=bins, range=(0, 255))
                h = h.astype(float)
                norm = np.linalg.norm(h)
                if norm > 1e-6:
                    h /= norm
                hists.append(h.tolist())
            except Exception:
                pass
        if hists:
            mean_hist = np.mean(hists, axis=0).tolist()
            histograms[card_id] = mean_hist
    return histograms


def main():
    parser = argparse.ArgumentParser(description="Train Clash Royale card classifier")
    parser.add_argument("--data",   default="data/",    help="Root data directory")
    parser.add_argument("--output", default="../app/src/main/assets/models/", help="Output directory")
    parser.add_argument("--epochs", type=int, default=EPOCHS)
    parser.add_argument("--histograms-only", action="store_true",
                        help="Skip model training — only build histograms")
    args = parser.parse_args()

    os.makedirs(args.output, exist_ok=True)

    data_dir  = pathlib.Path(args.data)
    labels    = sorted([d.name for d in data_dir.iterdir() if d.is_dir()])
    num_classes = len(labels)
    print(f"Found {num_classes} classes: {labels[:5]} ...")

    # Save labels
    labels_path = os.path.join(args.output, "card_labels.txt")
    with open(labels_path, "w") as f:
        f.write("\n".join(labels))
    print(f"[OK] Labels saved → {labels_path}")

    # Build and save histograms
    histograms = build_histograms(args.data, labels)
    hist_path  = os.path.join(args.output, "card_histograms.json")
    with open(hist_path, "w") as f:
        json.dump(histograms, f)
    print(f"[OK] Histograms saved → {hist_path}")

    if args.histograms_only:
        return

    if not TF_AVAILABLE:
        print("[ERROR] TensorFlow not installed. Run: pip install tensorflow")
        return

    # Data generators
    datagen = ImageDataGenerator(
        rescale=1.0 / 255,
        validation_split=0.15,
        rotation_range=10,
        width_shift_range=0.1,
        height_shift_range=0.1,
        zoom_range=0.1,
        horizontal_flip=False   # cards should not be flipped
    )

    train_gen = datagen.flow_from_directory(
        args.data,
        target_size=(IMG_SIZE, IMG_SIZE),
        batch_size=BATCH_SIZE,
        class_mode="categorical",
        subset="training"
    )
    val_gen = datagen.flow_from_directory(
        args.data,
        target_size=(IMG_SIZE, IMG_SIZE),
        batch_size=BATCH_SIZE,
        class_mode="categorical",
        subset="validation"
    )

    model = build_model(num_classes)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(1e-4),
        loss="categorical_crossentropy",
        metrics=["accuracy"]
    )
    model.summary()

    callbacks = [
        tf.keras.callbacks.EarlyStopping(patience=5, restore_best_weights=True),
        tf.keras.callbacks.ReduceLROnPlateau(patience=3, factor=0.5)
    ]

    model.fit(train_gen, validation_data=val_gen,
              epochs=args.epochs, callbacks=callbacks)

    # Representative dataset for INT8 quantisation
    def representative_data_gen():
        for images, _ in train_gen.take(100):
            yield [images]

    export_tflite(model, args.output, representative_data_gen)


if __name__ == "__main__":
    main()
