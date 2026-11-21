#!/usr/bin/env bash
# Downloads the offline voice model, the voice engine library and the OCR data.
# This runs only on GitHub's build machine. The finished app never goes online.
#
#   bash scripts/fetch-deps.sh download   -> downloads into .downloads/ (cached between builds)
#   bash scripts/fetch-deps.sh install    -> copies them into the app project
set -euo pipefail

SHERPA_VERSION="${SHERPA_VERSION:-1.13.7}"
KOKORO_MODEL="${KOKORO_MODEL:-kokoro-multi-lang-v1_0}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DL="$ROOT/.downloads"
ASSETS="$ROOT/app/src/main/assets"

download() {
  rm -rf "$DL"
  mkdir -p "$DL/kokoro" "$DL/tessdata"

  echo "==> Voice engine library (sherpa-onnx $SHERPA_VERSION)"
  curl -fL --retry 3 -o "$DL/sherpa-onnx.aar" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
  # Make sure the library really contains the text-to-speech classes.
  unzip -p "$DL/sherpa-onnx.aar" classes.jar > "$DL/classes.jar"
  if ! unzip -l "$DL/classes.jar" | grep -q "com/k2fsa/sherpa/onnx/OfflineTts.class"; then
    echo "::error::sherpa-onnx.aar does not contain OfflineTts"; exit 1
  fi
  rm -f "$DL/classes.jar"

  echo "==> Kokoro voice model ($KOKORO_MODEL)"
  curl -fL --retry 3 -o "$DL/kokoro.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${KOKORO_MODEL}.tar.bz2"
  mkdir -p "$DL/extract"
  tar -xjf "$DL/kokoro.tar.bz2" -C "$DL/extract"
  rm -f "$DL/kokoro.tar.bz2"
  SRC="$DL/extract/$KOKORO_MODEL"
  for f in model.onnx voices.bin tokens.txt lexicon-us-en.txt; do
    if [ ! -f "$SRC/$f" ]; then echo "::error::Missing $f in voice model"; ls -la "$SRC"; exit 1; fi
    mv "$SRC/$f" "$DL/kokoro/$f"
  done
  if [ ! -d "$SRC/espeak-ng-data" ]; then echo "::error::Missing espeak-ng-data"; exit 1; fi
  mv "$SRC/espeak-ng-data" "$DL/kokoro/espeak-ng-data"
  rm -rf "$DL/extract"

  echo "==> English OCR data (Tesseract)"
  curl -fL --retry 3 -o "$DL/tessdata/eng.traineddata" \
    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata" \
  || curl -fL --retry 3 -o "$DL/tessdata/eng.traineddata" \
    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/master/eng.traineddata"

  du -sh "$DL"/*
}

install() {
  if [ ! -f "$DL/sherpa-onnx.aar" ]; then echo "::error::Run download first"; exit 1; fi
  mkdir -p "$ROOT/app/libs"
  cp "$DL/sherpa-onnx.aar" "$ROOT/app/libs/sherpa-onnx.aar"
  rm -rf "$ASSETS/kokoro" "$ASSETS/tessdata"
  mkdir -p "$ASSETS"
  cp -r "$DL/kokoro" "$ASSETS/kokoro"
  cp -r "$DL/tessdata" "$ASSETS/tessdata"
  echo "Installed into the app:"
  du -sh "$ASSETS"/* "$ROOT/app/libs"/*
}

case "${1:-}" in
  download) download ;;
  install) install ;;
  *) echo "usage: $0 download|install"; exit 1 ;;
esac
