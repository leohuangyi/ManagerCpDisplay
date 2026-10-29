#!/usr/bin/env bash

export ANDROID_KEYSTORE_PATH="/Users/leo/Car/vincicar/vc3/DiPlay/cert/mgrelease.mgkeystore"
export ANDROID_KEYSTORE_PASSWORD="automanagernaigai666"
export ANDROID_KEY_ALIAS="mgcarplay"
export ANDROID_KEY_PASSWORD="automanagernaigai666"

set -euo pipefail

# 切换到脚本所在目录（DiPlay 根目录）
cd "$(dirname "$0")"

# 可选构建参数:
#   publish=true   构建完成后调用 sh_publish.sh 发布 APK;默认 false
PUBLISH=false
for arg in "$@"; do
    case "$arg" in
        publish=true) PUBLISH=true ;;
        publish=false) PUBLISH=false ;;
        *)
            printf "\033[31m😵 未知参数: %s (仅支持 publish=true|false)\033[0m\n" "$arg"
            exit 1
            ;;
    esac
done
echo "==> publish = $PUBLISH"

# 1. Android SDK：优先使用 ANDROID_HOME，未设置则从 local.properties 读取 sdk.dir
if [ -z "${ANDROID_HOME:-}" ] && [ -f "local.properties" ]; then
  SDK_DIR=$(sed -n 's/^[[:space:]]*sdk\.dir=//p' local.properties | head -1)
  if [ -n "$SDK_DIR" ]; then
    export ANDROID_HOME="$SDK_DIR"
    echo "ANDROID_HOME 未设置，已从 local.properties 读取：$ANDROID_HOME"
  fi
fi

# 2. release 签名依赖以下四个环境变量，缺失则直接报错退出
if [ -z "${ANDROID_KEYSTORE_PATH:-}" ] || \
   [ -z "${ANDROID_KEYSTORE_PASSWORD:-}" ] || \
   [ -z "${ANDROID_KEY_ALIAS:-}" ] || \
   [ -z "${ANDROID_KEY_PASSWORD:-}" ]; then
  echo "错误：release 构建需要签名环境变量，请先设置：" >&2
  echo "  ANDROID_KEYSTORE_PATH" >&2
  echo "  ANDROID_KEYSTORE_PASSWORD" >&2
  echo "  ANDROID_KEY_ALIAS" >&2
  echo "  ANDROID_KEY_PASSWORD" >&2
  exit 1
fi

# 3. 构建 legacy 模块的 release APK
./gradlew :legacy:assembleRelease

# 4. 构建成功后打开 APK 所在目录
LEGACY_DIR="legacy/build/outputs/apk/release"
LEGACY_APK="$LEGACY_DIR/legacy-release.apk"

echo "构建完成，正在打开 APK 所在目录 ..."
open "$LEGACY_DIR"

# 5. 可选发布
if [ "$PUBLISH" = "true" ]; then
    echo "==> 发布 APK: $LEGACY_APK"
    bash "$(dirname "$0")/sh_publish.sh" "$LEGACY_APK" || { printf "\033[31m😵 sh_publish.sh 发布失败\033[0m\n"; exit 1; }
fi