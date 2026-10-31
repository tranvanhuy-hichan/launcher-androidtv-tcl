#!/bin/bash
# Turns the TV's own launcher back on and gives it the Home button again.
# Usage: TV=192.168.1.30 ./tools/restore-stock-launcher.sh
# Safe to run any time: it only re-enables packages (nothing was ever uninstalled) and switches off the redirect.
set -u; . "$(dirname "$0")/common.sh"
for p in com.google.android.tvlauncher com.google.android.tvrecommendations; do
  tv pm enable "$p" | sed "s|^|$p: |"
done
tv settings put secure enabled_accessibility_services null
tv settings put secure accessibility_enabled 0
echo "Stock launcher is back. Home now opens it. Run ./tools/install.sh to use Launcher TCL again."
echo "Now serving Home: $(tv cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1)"
