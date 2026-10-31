#!/usr/bin/env bash
#
# 编译并安装 DiPlay legacy Debug APK（Android 4.2.2 车机，armeabi-v7a）
# 用法：./build_debug.sh
#
set -euo pipefail

# 切换到脚本所在目录（DiPlay 根目录）
cd "$(dirname "$0")"

# 1. Gradle 9.5 需要 JDK 17+，仓库文档要求 JDK 25；可用 DIPLAY_JDK 覆盖
JDK_HOME="${DIPLAY_JDK:-$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || true)}"
if [ -z "${JDK_HOME:-}" ]; then
    JDK_HOME="${JAVA_HOME:-}"
fi
if [ -z "${JDK_HOME:-}" ] || [ ! -d "$JDK_HOME" ]; then
    echo "错误：未找到可用的 JDK（Gradle 9.5 需要 JDK 17+）" >&2
    exit 1
fi
export JAVA_HOME="$JDK_HOME"

# 2. Android SDK：优先使用 ANDROID_HOME，未设置则从 local.properties 读取 sdk.dir
if [ -z "${ANDROID_HOME:-}" ] && [ -f local.properties ]; then
    SDK_DIR=$(sed -n 's/^[[:space:]]*sdk\.dir=//p' local.properties | head -1 | tr -d '\r')
    if [ -n "$SDK_DIR" ]; then
        export ANDROID_HOME="$SDK_DIR"
        echo "ANDROID_HOME 未设置，已从 local.properties 读取：$ANDROID_HOME"
    fi
fi

# 3. 编译 legacy debug 包
echo "使用 JDK: $JAVA_HOME"
./gradlew :legacy:assembleDebug

DEBUG_APK="legacy/build/outputs/apk/debug/legacy-debug.apk"
if [ ! -f "$DEBUG_APK" ]; then
    echo "错误：未找到 debug APK：$DEBUG_APK" >&2
    exit 1
fi

# 4. 重签：车机上已安装的是 release 签名版本，debug 签名会因签名不一致无法覆盖安装；
#    这里用与 sh_build_apk.sh 相同的 release 证书重签 debug 包，
#    包内容仍是 debuggable 的 debug 版本，但可以覆盖安装并保留应用数据。
KEYSTORE_PATH="${ANDROID_KEYSTORE_PATH:-$PWD/cert/mgrelease.mgkeystore}"
KEYSTORE_PASSWORD="${ANDROID_KEYSTORE_PASSWORD:-automanagernaigai666}"
KEY_ALIAS="${ANDROID_KEY_ALIAS:-mgcarplay}"
KEY_PASSWORD="${ANDROID_KEY_PASSWORD:-automanagernaigai666}"

if [ ! -f "$KEYSTORE_PATH" ]; then
    echo "错误：未找到签名证书：$KEYSTORE_PATH" >&2
    exit 1
fi
BUILD_TOOLS_DIR=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
if [ ! -x "$APKSIGNER" ]; then
    echo "错误：未找到 apksigner（$ANDROID_HOME/build-tools）" >&2
    exit 1
fi

SIGNED_APK="legacy/build/outputs/apk/debug/legacy-debug-release-signed.apk"
echo ""
echo "==> 使用 release 证书重签 debug 包"
"$APKSIGNER" sign --ks "$KEYSTORE_PATH" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass pass:"$KEYSTORE_PASSWORD" --key-pass pass:"$KEY_PASSWORD" \
    --out "$SIGNED_APK" "$DEBUG_APK"
"$APKSIGNER" verify "$SIGNED_APK"

# 5. 定位 adb（优先 PATH，其次 SDK 目录）
ADB="${ADB:-$(command -v adb || true)}"
if [ -z "$ADB" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
fi
if [ -z "$ADB" ]; then
    echo ""
    echo "未找到 adb，跳过安装。"
    exit 0
fi

DEVICE="${ANDROID_SERIAL:-$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')}"
if [ -z "$DEVICE" ]; then
    echo ""
    echo "未检测到已连接的设备，跳过安装。"
    exit 0
fi

# 6. 安装
echo ""
echo "=== 构建完成，APK 产物 ==="
ls -1 legacy/build/outputs/apk/debug/*.apk
echo ""
echo "设备 ${DEVICE}"
echo "安装包：$SIGNED_APK"
if ! "$ADB" -s "$DEVICE" install -r "$SIGNED_APK"; then
    echo "错误：安装失败。若设备上已安装 debug 签名版本，请先执行：$ADB -s $DEVICE uninstall com.shilapi.mgxcertplay" >&2
    exit 1
fi
echo "安装完成。"
