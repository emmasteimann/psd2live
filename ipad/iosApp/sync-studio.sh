#!/bin/zsh
# Copies the studio's current build into the iPad app (iosApp/studio), with what its smart mask needs to run offline:
# the ONNX runtime (from the studio's node_modules) and SAM 2.1 Tiny's GPU model (downloaded once into ~/.cache).
# Run it after changing the studio.
set -e
STUDIO="$HOME/Downloads/neuro-goose/layerloom"
HERE="${0:A:h}"
MODEL="onnx-community/sam2.1-hiera-tiny-ONNX"
CACHE="$HOME/.cache/layerloom-ipad/$MODEL"
(cd "$STUDIO" && npx vite build >/dev/null)
rm -rf "$HERE/studio"
cp -R "$STUDIO/dist/client" "$HERE/studio"
# The bundler's copy of the runtime is not the one loaded; the app loads /ort/.
find "$HERE/studio/assets" -name 'ort-wasm*.wasm' -delete
mkdir -p "$HERE/studio/ort"
cp "$STUDIO"/node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.asyncify.{mjs,wasm} "$HERE/studio/ort/"
mkdir -p "$CACHE/onnx"
for f in config.json preprocessor_config.json processor_config.json \
         onnx/vision_encoder_fp16.onnx onnx/vision_encoder_fp16.onnx_data \
         onnx/prompt_encoder_mask_decoder.onnx onnx/prompt_encoder_mask_decoder.onnx_data; do
  [ -s "$CACHE/$f" ] || curl -sfL "https://huggingface.co/$MODEL/resolve/main/$f" -o "$CACHE/$f"
done
mkdir -p "$HERE/studio/models/$MODEL"
cp -R "$CACHE/" "$HERE/studio/models/$MODEL/"
echo "Studio copied into $HERE/studio ($(du -sh "$HERE/studio" | cut -f1))"
