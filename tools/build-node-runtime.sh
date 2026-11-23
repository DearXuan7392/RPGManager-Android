#!/usr/bin/env bash
# 预编译内嵌 Node 运行时所需的 native 库，产物直接放进 app/src/main/jniLibs。
#
# 需要三样东西：
#   1. NDK（默认 E:/Environment/Android/android-ndk-r27d，可用 NDK_HOME 覆盖）
#   2. nodejs-mobile 的 Android 预编译包解压目录（libnode.so + include/node/*.h）
#      下载：https://github.com/nodejs-mobile/nodejs-mobile/releases  （nodejs-mobile-v18.20.x-android.zip）
#      解压后结构：bin/<abi>/libnode.so、include/node/...
#   3. 本工程 app/src/main/cpp/node-jni.cpp
#
# 产物：
#   jniLibs/<abi>/libnode.so         Node 运行时本体（62MB，可 strip 到 30MB 左右）
#   jniLibs/<abi>/libnodejni.so      上面 cpp 编译出的 JNI 桥
#   jniLibs/<abi>/libc++_shared.so   libnode 依赖的 C++ 运行时（必须一起打包）
#
# 用法：NODEMOBILE_DIR=/path/to/nodejs-mobile ./tools/build-node-runtime.sh [abi...]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK_HOME="${NDK_HOME:-E:/Environment/Android/android-ndk-r27d}"
NODEMOBILE_DIR="${NODEMOBILE_DIR:-/e/Environment/cache/nodemobile/nodejs-mobile}"

HOST_TAG="windows-x86_64"
case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux) HOST_TAG="linux-x86_64" ;;
esac
TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
BIN="$TOOLCHAIN/bin"
STRIP="$BIN/llvm-strip.exe"
[ -x "$STRIP" ] || STRIP="$BIN/llvm-strip"

ABIS=("$@")
if [ ${#ABIS[@]} -eq 0 ]; then
  ABIS=(arm64-v8a)
fi

api_level() {
  case "$1" in
    arm64-v8a) echo 24 ;;
    armeabi-v7a) echo 24 ;;
    x86_64) echo 24 ;;
  esac
}

triple() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    armeabi-v7a) echo armv7a-linux-androideabi ;;
    x86_64) echo x86_64-linux-android ;;
  esac
}

for ABI in "${ABIS[@]}"; do
  echo "==> 构建 $ABI"
  OUT="$ROOT/app/src/main/jniLibs/$ABI"
  mkdir -p "$OUT"

  cp "$NODEMOBILE_DIR/bin/$ABI/libnode.so" "$OUT/libnode.so"
  "$STRIP" --strip-unneeded "$OUT/libnode.so" || echo "  (strip 失败，保留原文件)"

  cp "$TOOLCHAIN/sysroot/usr/lib/$(triple "$ABI")/libc++_shared.so" "$OUT/libc++_shared.so"

  CC="$BIN/$(triple "$ABI")$(api_level "$ABI")-clang++"
  [ -x "$CC" ] || CC="$CC.exe"
  "$CC" -shared -fPIC -O2 -fvisibility=hidden \
    -I "$NODEMOBILE_DIR/include/node" \
    -o "$OUT/libnodejni.so" \
    "$ROOT/app/src/main/cpp/node-jni.cpp" \
    -L "$OUT" -lnode -llog -lc++_shared \
    -Wl,--no-undefined -Wl,-z,max-page-size=16384

  ls -lh "$OUT"
done

echo "完成。"
