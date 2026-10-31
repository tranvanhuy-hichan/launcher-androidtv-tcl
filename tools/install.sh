#!/bin/bash
# Installs the release build on the TV and re-applies what an update resets.
# Usage: TV=192.168.1.30 ./tools/install.sh        (build first: ./gradlew assembleRelease)
# Updating the app switches off its accessibility service (the Home-button redirect), so this turns it back on.
set -u; . "$(dirname "$0")/common.sh"
PK=io.github.qbh21.vitrine
APK="$(dirname "$0")/../app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "Build first: ./gradlew assembleRelease"; exit 1; }
adb -s "$TV" install -r "$APK"
tv pm grant $PK android.permission.ACCESS_FINE_LOCATION      # lets the launcher read the Wi-Fi name
tv settings put secure enabled_accessibility_services $PK/$PK.HomeRedirectService
tv settings put secure accessibility_enabled 1
echo "Installed. Home button -> Launcher TCL. Undo: ./tools/undo-home.sh"
