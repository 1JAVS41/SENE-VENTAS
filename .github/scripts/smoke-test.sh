#!/bin/sh
set -eu
mkdir -p diagnostics
test -n "${APK_PATH:-}" && test -s "$APK_PATH"
timeout 60s adb wait-for-device
BOOTED="$(adb shell getprop sys.boot_completed | tr -d '\r')"
if [ "$BOOTED" != "1" ]; then
  echo "El emulador no terminó de iniciar."
  adb logcat -d -v threadtime > diagnostics/logcat.txt 2>&1 || true
  adb shell dumpsys activity activities > diagnostics/activity.txt 2>&1 || true
  exit 1
fi
adb devices -l
if ! timeout 120s adb install -r "$APK_PATH"; then
  adb logcat -d -v threadtime > diagnostics/logcat.txt 2>&1 || true
  exit 1
fi
adb shell am force-stop com.sene.ventas
if ! timeout 45s adb shell monkey -p com.sene.ventas 1; then
  adb logcat -d -v threadtime > diagnostics/logcat.txt 2>&1 || true
  adb shell dumpsys activity activities > diagnostics/activity.txt 2>&1 || true
  exit 1
fi
attempt=0
while [ "$attempt" -lt 20 ]; do
  if adb shell pidof com.sene.ventas >/dev/null 2>&1; then
    adb shell pidof com.sene.ventas
    echo "Smoke test de arranque correcto." > diagnostics/result.txt
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 1
done
echo "La aplicación no mantuvo un proceso activo tras el lanzamiento."
adb logcat -d -v threadtime > diagnostics/logcat.txt 2>&1 || true
adb shell dumpsys activity activities > diagnostics/activity.txt 2>&1 || true
adb shell dumpsys package com.sene.ventas > diagnostics/package.txt 2>&1 || true
echo "Smoke test de arranque fallido." > diagnostics/result.txt
exit 1
