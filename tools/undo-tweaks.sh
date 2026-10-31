#!/bin/bash
# Puts the animation speed back to how this TV was before the tweaks.
set -u; . "$(dirname "$0")/common.sh"
tv settings put global window_animation_scale 1.0
tv settings put global transition_animation_scale 1.0
tv settings put global animator_duration_scale 1.0
echo "Animation speed restored."
