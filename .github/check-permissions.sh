#!/usr/bin/env bash
# Hramota needs almost no permissions: anything else in the APK (internet above all) fails the build.
set -euo pipefail
APK="$1"
AAPT=$(ls "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
"$AAPT" dump permissions "$APK" > permissions.txt
cat permissions.txt
grep -q '^package:' permissions.txt || { echo "::error::Could not read permissions"; exit 1; }
# Android adds this one to every app that targets 33+; it only protects the app's own receivers.
# Reading aloud with the screen off (agreed with the owner): keep playing, nothing else
ALLOWED=" ua.hramota.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION android.permission.FOREGROUND_SERVICE android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK "
for p in $(grep -oE "name='[^']+'" permissions.txt | sed "s/name='//;s/'//"); do
  case "$ALLOWED" in
    *" $p "*) ;;
    *) echo "::error::Unexpected permission: $p"; exit 1 ;;
  esac
done
echo "Permissions OK"
