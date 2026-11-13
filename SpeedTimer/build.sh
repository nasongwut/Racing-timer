#!/bin/bash
# Build Speed Timer from the command line (no Android Studio needed).
# Usage: ./build.sh            -> build only
#        ./build.sh install    -> build and install on every connected adb device
#        ./build.sh run        -> build, uninstall old, install, launch, save crash-<id>.txt / users-<id>.txt
set -e
cd "$(dirname "$0")"

APP_ID="com.tracking.speedtimer"
APK_NAME="SpeedTimer.apk"

# 1) Toolchain + env (run ./install-dev.sh once for first-time setup)
if [ ! -d "$(brew --prefix 2>/dev/null)/share/android-commandlinetools/platforms/android-35" ]; then
  ./install-dev.sh --no-build
fi
source ./install-dev.sh --env
[ -f gradlew ] || gradle wrapper --gradle-version 8.9 -q
chmod +x gradlew

# 2) Build
echo ">> Building APK"
./gradlew assembleDebug -q
cp app/build/outputs/apk/debug/app-debug.apk "./$APK_NAME"
echo ">> Done: $(pwd)/$APK_NAME"

# adb: prefer the one on PATH, fall back to the SDK copy
ADB="$(command -v adb || echo "$ANDROID_HOME/platform-tools/adb")"
DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')

if [ "$1" = "install" ] || [ "$1" = "run" ]; then
  [ -n "$DEVICES" ] || { echo "!! No device found - connect via USB and enable USB debugging"; exit 1; }
fi

# 3) Install via adb (--user 0 = main user, not Secure Folder / Dual App)
if [ "$1" = "install" ]; then
  for d in $DEVICES; do
    echo ">> Installing on $d"
    "$ADB" -s "$d" install --user 0 -r "$APK_NAME"
  done
fi

# 4) Clean install + launch + collect logs
if [ "$1" = "run" ]; then
  for d in $DEVICES; do
    echo ">> [$d] Uninstalling old build"
    "$ADB" -s "$d" uninstall "$APP_ID" >/dev/null 2>&1 || true
    echo ">> [$d] Installing"
    "$ADB" -s "$d" install --user 0 -r "$APK_NAME"
    "$ADB" -s "$d" logcat -c
    echo ">> [$d] Launching"
    "$ADB" -s "$d" shell am start -n "$APP_ID/.MainActivity"
    sleep 5
    "$ADB" -s "$d" logcat -d -b crash > "crash-$d.txt"
    "$ADB" -s "$d" shell pm list users > "users-$d.txt"
    if [ -s "crash-$d.txt" ]; then
      echo "!! [$d] App crashed - see crash-$d.txt"
    else
      echo ">> [$d] Launched OK, no crash"
    fi
  done
fi
