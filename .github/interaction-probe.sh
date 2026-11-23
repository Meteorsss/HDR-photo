#!/usr/bin/env bash
set -euo pipefail
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell settings put global animator_duration_scale 1
adb shell input keyevent 82
result=$(adb shell am instrument -w com.meteorsss.hdrphoto.test/com.meteorsss.hdrphoto.InteractionProbe)
printf '%s\n' "$result"
grep -q 'PASS: refresh' <<< "$result"
