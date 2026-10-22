#!/bin/bash
# Builds the bridge executable for arm64 (phones, handhelds, most TV boxes) and x86_64 (PC-based Android).
set -e
cd "$(dirname "$0")"
B=$HOME/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin
for abi in arm64-v8a:aarch64 x86_64:x86_64; do
  dir=${abi%%:*}; triple=${abi##*:}
  $B/${triple}-linux-android29-clang -O2 -Wall -fPIE -pie -o ../app/src/main/jniLibs/$dir/libwiibridge.so wiibridge.c -lm
  $B/llvm-strip ../app/src/main/jniLibs/$dir/libwiibridge.so
done
ls -la ../app/src/main/jniLibs/*/
