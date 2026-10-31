#!/bin/bash
# Gives the Home button back to the stock launcher (the app stays installed).
set -u; . "$(dirname "$0")/common.sh"
tv settings put secure enabled_accessibility_services null
tv settings put secure accessibility_enabled 0
echo "Home button is the stock launcher again."
