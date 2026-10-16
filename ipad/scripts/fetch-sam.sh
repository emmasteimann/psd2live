#!/bin/bash
# Fetches Apple's Core ML SAM 2.1 Tiny (the smart mask, ~76 MB) into iosApp/models/ - too big for git, so it's
# downloaded once (Hugging Face apple/coreml-sam2.1-tiny). deploy-ipad.sh runs this when the models are missing.
set -euo pipefail
cd "$(dirname "$0")/../iosApp/models"
BASE="https://huggingface.co/apple/coreml-sam2.1-tiny/resolve/main"
for m in ImageEncoder PromptEncoder MaskDecoder; do
  P="SAM2_1Tiny${m}FLOAT16.mlpackage"
  [ -f "$P/Manifest.json" ] && continue
  echo "Downloading $P…"
  for f in Manifest.json Data/com.apple.CoreML/model.mlmodel Data/com.apple.CoreML/weights/weight.bin; do
    mkdir -p "$P/$(dirname "$f")"
    curl -fsSL "$BASE/$P/$f" -o "$P/$f"
  done
done
