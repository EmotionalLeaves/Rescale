#!/bin/sh
# Clones the pinned ncnn release (with glslang) for a from-source build:
#   scripts/fetch-ncnn-source.sh && ./gradlew assembleRelease -PncnnSource=engine/native/third_party/ncnn-src
set -eu
VERSION=20261020
DEST="$(dirname "$0")/../engine/native/third_party/ncnn-src"
if [ -d "$DEST/.git" ]; then echo "already at $DEST"; exit 0; fi
git clone --depth 1 --branch "$VERSION" --recurse-submodules --shallow-submodules \
  https://github.com/Tencent/ncnn "$DEST"
